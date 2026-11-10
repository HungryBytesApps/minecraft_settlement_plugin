package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
 * Walks a worker along a path in small steps each tick (the client shows them walking),
 * up and down steps, opening and closing doors, and getting unstuck.
 */
final class Mover {
    private final Worker w;
    private List<int[]> path;
    private int idx;
    private Location target;
    private double reach, speed;
    private int stuckTicks, repaths;
    private double fall, stepDist;
    private Location lastPos;
    private boolean arrived, failed;
    private final Map<Block, Integer> openedDoors = new HashMap<>();
    private final java.util.Set<Block> passingGate = new java.util.HashSet<>();

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

    String debug() {
        return "moving " + moving() + ", node " + idx + "/" + (path == null ? 0 : path.size()) + ", arrived " + arrived
                + ", failed " + failed + (target == null ? "" : String.format(", to %.0f %.0f %.0f", target.getX(), target.getY(), target.getZ()));
    }
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
        // Step by step (Mannequins don't walk by themselves); the client smooths it into a walk.
        double nx = loc.getX(), nz = loc.getZ(), ny = loc.getY();
        float yaw = loc.getYaw();
        double ty = floorY(v.getWorld(), n); // the real surface: half a block lower on a slab
        boolean climbing = ty > ny + 0.01 && dist < 1.3;
        if (dist > 0.01 && !(climbing && dist < 0.95)) { // up a step: first up, then on (no walking into the block)
            double sp = Math.min(speed, dist);
            nx += dx / dist * sp;
            nz += dz / dist * sp;
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        if (ty > ny + 0.01) {
            if (dist < 1.3) ny = Math.min(ty, ny + 0.25); // up a step, close to it
            fall = 0;
        } else {
            // Down: stay on the edge until the body is past it, then drop (faster and faster, like falling).
            double support = Math.max(ty, supportY(v.getWorld(), nx, ny, nz));
            if (ny > support + 0.001) {
                fall = Math.min(0.6, fall + 0.08);
                ny = Math.max(support, ny - fall);
            } else {
                fall = 0;
            }
        }
        stepDist += Math.abs(nx - loc.getX()) + Math.abs(nz - loc.getZ());
        if (stepDist > 1.6 && fall == 0) {
            stepDist = 0;
            Voice.step(w);
        }
        v.setVelocity(new Vector());
        v.teleport(new Location(v.getWorld(), nx, ny, nz, yaw, 0),
                io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);

        // Stuck? Try a new path twice, then hop to the next spot.
        if (++stuckTicks >= 30) {
            if (lastPos.getWorld() == loc.getWorld() && lastPos.distanceSquared(loc) < 0.1) {
                if (repaths++ < 2) {
                    repath();
                } else {
                    v.teleport(new Location(v.getWorld(), n[0] + 0.5, n[1], n[2] + 0.5, loc.getYaw(), 0),
                            io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
                    repaths = 0;
                }
            }
            stuckTicks = 0;
            lastPos = v.getLocation();
        }
    }

    /** Where the feet go on a path spot: on top of the block below it (a slab: half a block lower). */
    private static double floorY(World w, int[] n) {
        Block below = w.getBlockAt(n[0], n[1] - 1, n[2]);
        if (below.isPassable()) return n[1];
        double top = below.getBoundingBox().getMaxY();
        return top < n[1] && top > n[1] - 1 ? top : n[1];
    }

    /** Top of whatever is under the body (its four corners), up to 3 blocks down. */
    private static double supportY(World w, double x, double y, double z) {
        double best = y - 3;
        for (double ox : new double[]{-0.29, 0.29}) {
            for (double oz : new double[]{-0.29, 0.29}) {
                int bx = (int) Math.floor(x + ox), bz = (int) Math.floor(z + oz);
                for (int by = (int) Math.floor(y + 0.5); by >= (int) Math.floor(y) - 3; by--) {
                    Block b = w.getBlockAt(bx, by, bz);
                    if (b.isPassable()) continue;
                    double top = b.getBoundingBox().getMaxY();
                    if (top <= y + 0.51) best = Math.max(best, top);
                    break;
                }
            }
        }
        return best;
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

    /** A door you open by hand (wooden or copper, not iron). */
    static boolean handDoor(Material m) {
        return Tag.DOORS.isTagged(m) && m != Material.IRON_DOOR;
    }

    private void openDoor(World world, int[] n) {
        Block b = world.getBlockAt(n[0], n[1], n[2]);
        boolean door = Mover.handDoor(b.getType());
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
            Block b = e.getKey();
            if (!(b.getBlockData() instanceof Openable o) || !o.isOpen()) {
                it.remove();
                continue;
            }
            // Gates: shut right behind it, so no animal slips out (an animal in the way gets pushed back).
            boolean gate = Tag.FENCE_GATES.isTagged(b.getType());
            Location me = w.body.getLocation();
            double gx = b.getX() + 0.5 - me.getX(), gz = b.getZ() + 0.5 - me.getZ();
            double d2 = me.getWorld() != b.getWorld() ? 1e9 : gx * gx + gz * gz;
            if (d2 < 0.7 * 0.7) passingGate.add(b);           // it's in the gate now
            boolean through = passingGate.contains(b) && d2 > 1.3 * 1.3; // ...and out the other side
            boolean shut = now || (gate && through);
            if (!shut && tick < e.getValue()) continue;
            boolean blocked = !b.getWorld().getNearbyEntities(BoundingBox.of(b), en -> en instanceof LivingEntity
                    && (!gate || en.equals(w.body) || en instanceof org.bukkit.entity.Player)).isEmpty();
            if (blocked && !shut) {
                e.setValue(tick + 20);
                continue;
            }
            passingGate.remove(b);
            o.setOpen(false);
            b.setBlockData(o, true);
            boolean door = Mover.handDoor(b.getType());
            b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5),
                    door ? "minecraft:block.wooden_door.close" : "minecraft:block.fence_gate.close", SoundCategory.BLOCKS, 0.8f, 1f);
            it.remove();
        }
    }
}
