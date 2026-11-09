package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Walks a worker along a path by giving it velocity each tick (so the client shows the normal
 * walking animation), jumping up steps, opening and closing doors, and getting unstuck.
 */
final class Mover {
    private final Worker w;
    private List<int[]> path;
    private int idx;
    private Location target;
    private double reach, speed;
    private int stuckTicks, repaths;
    private Location lastPos;
    private boolean arrived, failed;
    private final Map<Block, Integer> openedDoors = new HashMap<>();

    Mover(Worker w) {
        this.w = w;
    }

    /** Start walking to within {@code reach} blocks of {@code to}. False if there's no way there. */
    boolean go(Location to, double reach, double speed) {
        this.target = to.clone();
        this.reach = reach;
        this.speed = speed;
        this.repaths = 0;
        this.arrived = false;
        this.failed = false;
        return repath();
    }

    private boolean repath() {
        List<int[]> p = PathFinder.find(w.body.getLocation(), target, reach);
        if (p == null) {
            path = null;
            failed = true;
            halt();
            return false;
        }
        path = p;
        idx = 0;
        stuckTicks = 0;
        lastPos = w.body.getLocation();
        if (p.isEmpty()) {
            path = null;
            arrived = true;
        }
        return true;
    }

    boolean moving() { return path != null; }
    boolean arrived() { return arrived; }
    boolean failed() { return failed; }

    void stop() {
        if (path != null) halt();
        path = null;
    }

    private void halt() {
        Vector v = w.body.getVelocity();
        w.body.setVelocity(new Vector(0, Math.min(0, v.getY()), 0));
    }

    void tick() {
        closeDoors(false);
        if (path == null) return;
        LivingEntity v = w.body;
        if (v.isInsideVehicle() || v.isSleeping()) return;
        Location loc = v.getLocation();

        while (idx < path.size()) {
            int[] n = path.get(idx);
            double dx = n[0] + 0.5 - loc.getX(), dz = n[2] + 0.5 - loc.getZ();
            if (dx * dx + dz * dz < 0.09 && Math.abs(loc.getY() - n[1]) < 0.7) idx++;
            else break;
        }
        if (idx >= path.size()) {
            path = null;
            arrived = true;
            halt();
            return;
        }

        int[] n = path.get(idx);
        openDoor(v.getWorld(), n);
        double dx = n[0] + 0.5 - loc.getX(), dz = n[2] + 0.5 - loc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        Vector vel = v.getVelocity();
        if (dist > 0.01) {
            double sp = Math.min(speed, dist);
            vel.setX(dx / dist * sp);
            vel.setZ(dz / dist * sp);
            face(v, dx, dz);
        }
        if (n[1] > loc.getY() + 0.6 && v.isOnGround()) vel.setY(0.42); // jump up a step
        v.setVelocity(vel);

        // Stuck? Try a new path twice, then hop to the next spot.
        if (++stuckTicks >= 30) {
            if (lastPos.getWorld() == loc.getWorld() && lastPos.distanceSquared(loc) < 0.1) {
                if (repaths++ < 2) {
                    repath();
                } else {
                    v.teleport(new Location(v.getWorld(), n[0] + 0.5, n[1], n[2] + 0.5, loc.getYaw(), 0));
                    repaths = 0;
                }
            }
            stuckTicks = 0;
            lastPos = v.getLocation();
        }
    }

    static void face(LivingEntity v, double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        v.setRotation(yaw, 0);
    }

    static void lookAt(LivingEntity v, Location at) {
        Location l = v.getEyeLocation();
        double dx = at.getX() - l.getX(), dy = at.getY() - l.getY(), dz = at.getZ() - l.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        v.setRotation(yaw, Math.max(-60, Math.min(60, pitch)));
    }

    private void openDoor(World world, int[] n) {
        Block b = world.getBlockAt(n[0], n[1], n[2]);
        boolean door = Tag.WOODEN_DOORS.isTagged(b.getType());
        if (!door && !Tag.FENCE_GATES.isTagged(b.getType())) return;
        if (!(b.getBlockData() instanceof Openable o) || o.isOpen()) return;
        o.setOpen(true);
        b.setBlockData(o, true);
        world.playSound(b.getLocation().add(0.5, 0.5, 0.5),
                door ? "minecraft:block.wooden_door.open" : "minecraft:block.fence_gate.open", SoundCategory.BLOCKS, 0.8f, 1f);
        openedDoors.put(b, Bukkit.getCurrentTick() + 40);
    }

    /** Close doors this worker opened once nobody is standing in them. */
    void closeDoors(boolean now) {
        if (openedDoors.isEmpty()) return;
        int tick = Bukkit.getCurrentTick();
        Iterator<Map.Entry<Block, Integer>> it = openedDoors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, Integer> e = it.next();
            if (!now && tick < e.getValue()) continue;
            Block b = e.getKey();
            if (!(b.getBlockData() instanceof Openable o) || !o.isOpen()) {
                it.remove();
                continue;
            }
            boolean blocked = !b.getWorld().getNearbyEntities(BoundingBox.of(b), en -> en instanceof LivingEntity).isEmpty();
            if (blocked && !now) {
                e.setValue(tick + 20);
                continue;
            }
            o.setOpen(false);
            b.setBlockData(o, true);
            boolean door = Tag.WOODEN_DOORS.isTagged(b.getType());
            b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5),
                    door ? "minecraft:block.wooden_door.close" : "minecraft:block.fence_gate.close", SoundCategory.BLOCKS, 0.8f, 1f);
            it.remove();
        }
    }
}
