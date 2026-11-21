package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** Places to sit (stairs, bottom slabs, cushions) and the settlement's evening gathering spot. */
final class Seats {
    private Seats() {}

    private static final Set<Block> taken = new HashSet<>();
    private static final Map<String, Location> gatherSpot = new HashMap<>();
    private static final Map<String, Integer> gatherAt = new HashMap<>();

    /** Cushions are entities (they lie on the floor): which block each one is in. */
    private static final Map<Block, org.bukkit.entity.Cushion> cushions = new HashMap<>();

    /** The cushion in this block, or null. */
    static org.bukkit.entity.Cushion cushionAt(Block b) {
        org.bukkit.entity.Cushion c = cushions.get(b);
        if (c != null && (!c.isValid() || !c.getLocation().getBlock().equals(b))) {
            cushions.remove(b);
            c = null;
        }
        return c;
    }

    /** Remember the cushions around here (one look for entities, not one per block). */
    private static java.util.Collection<org.bukkit.entity.Cushion> findCushions(World world, Location near, double rx, double ry, double rz) {
        java.util.Collection<org.bukkit.entity.Cushion> found = world.getNearbyEntitiesByType(org.bukkit.entity.Cushion.class, near, rx, ry, rz);
        if (cushions.size() > 2000) cushions.clear();
        for (org.bukkit.entity.Cushion c : found) cushions.put(c.getLocation().getBlock(), c);
        return found;
    }

    static boolean isSeat(Block b) {
        if (cushionAt(b) != null) { // a cushion on the floor: room for someone sitting on it
            Block above = b.getRelative(BlockFace.UP);
            return PathFinder.passable(b) && PathFinder.passable(above);
        }
        Material m = b.getType();
        boolean ok;
        if (Tag.STAIRS.isTagged(m)) {
            ok = b.getBlockData() instanceof Stairs s && s.getHalf() == Bisected.Half.BOTTOM && s.getShape() == Stairs.Shape.STRAIGHT;
        } else if (Tag.SLABS.isTagged(m)) {
            ok = b.getBlockData() instanceof Slab s && s.getType() == Slab.Type.BOTTOM;
        } else {
            ok = Tag.WOOL_CARPETS.isTagged(m);
        }
        if (!ok) return false;
        if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) return false; // not a wet seat
        Block above = b.getRelative(BlockFace.UP), above2 = above.getRelative(BlockFace.UP);
        return PathFinder.passable(above) && PathFinder.passable(above2);
    }

    static boolean free(Block b) {
        return !taken.contains(b);
    }

    /** Top of the seat (where you sit). */
    static double surface(Block b) {
        org.bukkit.entity.Cushion c = cushionAt(b);
        if (c != null) return c.getBoundingBox().getMaxY(); // on the cushion
        if (b.getBlockData() instanceof Stairs) return b.getY() + 0.5; // on the step, not on top of its back
        double top = b.getBoundingBox().getMaxY();
        return top > b.getY() ? top : b.getY() + 0.5;
    }

    /** Facing direction when sitting: away from a stair's back, otherwise towards {@code lookAt}. */
    static float yaw(Block seat, Location lookAt) {
        if (seat.getBlockData() instanceof Stairs s) return Social.yaw(s.getFacing().getOppositeFace());
        if (lookAt == null) return 0;
        double dx = lookAt.getX() - (seat.getX() + 0.5), dz = lookAt.getZ() - (seat.getZ() + 0.5);
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Nearest free seat within {@code r} blocks of {@code near}. */
    static Block nearestFree(Location near, int r, Worker w) {
        World world = near.getWorld();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = near.getBlockX() - r; x <= near.getBlockX() + r; x++) {
            for (int z = near.getBlockZ() - r; z <= near.getBlockZ() + r; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = near.getBlockY() - 4; y <= near.getBlockY() + 4; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!free(b) || (w != null && w.isSkipped(b)) || !isSeat(b)) continue;
                    double d = b.getLocation().distanceSquared(near);
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
        }
        // Cushions (entities, so looked for once).
        for (org.bukkit.entity.Cushion c : findCushions(world, near, r, 4, r)) {
            Block b = c.getLocation().getBlock();
            if (!free(b) || (w != null && w.isSkipped(b)) || !isSeat(b)) continue;
            double d = b.getLocation().distanceSquared(near);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /**
     * The evening gathering spot: the place in the settlement with the most seats close together
     * (a lit campfire nearby counts extra). The bell if there are no seats. Cached for a minute.
     */
    static Location gatheringSpot(Town t, int radius) {
        int now = Bukkit.getCurrentTick();
        Integer at = gatherAt.get(t.id);
        if (at != null && now - at < 6000 && gatherSpot.containsKey(t.id)) return gatherSpot.get(t.id);
        World w = t.bell.getWorld();
        List<Block> seats = new ArrayList<>();
        List<Block> fires = new ArrayList<>();
        int by = t.bell.getBlockY();
        int[] box = t.box(radius - Town.BASE);
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = by - 10; y <= by + 10; y++) {
                    Material m = w.getType(x, y, z);
                    if (m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE) fires.add(w.getBlockAt(x, y, z));
                    else if (Tag.STAIRS.isTagged(m) || Tag.SLABS.isTagged(m)) {
                        Block b = w.getBlockAt(x, y, z);
                        if (t.contains(b.getLocation(), radius) && isSeat(b)) seats.add(b);
                    }
                }
            }
        }
        Location mid = new Location(w, (box[0] + box[1] + 1) / 2.0, by, (box[2] + box[3] + 1) / 2.0);
        for (org.bukkit.entity.Cushion c : findCushions(w, mid, (box[1] - box[0]) / 2.0 + 1, 10, (box[3] - box[2]) / 2.0 + 1)) {
            Block b = c.getLocation().getBlock();
            if (t.contains(b.getLocation(), radius) && isSeat(b)) seats.add(b);
        }
        Location best = t.bell.clone().add(0.5, 0, 0.5);
        int bestScore = 0;
        for (Block s : seats) {
            int score = 0;
            for (Block o : seats) if (o.getLocation().distanceSquared(s.getLocation()) <= 36) score++;
            for (Block f : fires) if (f.getLocation().distanceSquared(s.getLocation()) <= 36) score += 2;
            if (score > bestScore) {
                bestScore = score;
                best = s.getLocation().add(0.5, 1, 0.5);
            }
        }
        gatherSpot.put(t.id, best);
        gatherAt.put(t.id, now);
        return best;
    }

    /** Sit a settler down on a seat (rides an invisible marker). */
    static ArmorStand sit(Worker w, Block seat, Location lookAt) {
        if (!free(seat)) return null;
        taken.add(seat);
        float yaw = yaw(seat, lookAt);
        Location at = seat.getLocation().add(0.5, surface(seat) - seat.getY() + w.plugin.settings().sitHeight(), 0.5);
        if (seat.getBlockData() instanceof Stairs st) { // a little forward, off the back
            org.bukkit.block.BlockFace front = st.getFacing().getOppositeFace();
            at.add(front.getModX() * 0.15, 0, front.getModZ() * 0.15);
        }
        at.setYaw(yaw);
        ArmorStand stand = seat.getWorld().spawn(at, ArmorStand.class, a -> {
            a.setMarker(true);
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(true); // so the settler riding it is saved with it (it's removed again when loaded)
            Keys.markTemp(a);
        });
        w.mover.stop();
        w.body.setRotation(yaw, 0);
        stand.addPassenger(w.body);
        return stand;
    }

    /** Stand up again, on top of the seat. */
    static void standUp(Worker w, Block seat, ArmorStand stand) {
        taken.remove(seat);
        if (stand != null) {
            stand.eject();
            stand.remove();
        }
        Location up = seat.getLocation().add(0.5, 1, 0.5);
        up.setYaw(w.body.getLocation().getYaw());
        if (!w.plugin.isEnabled()) { // shutting down: no scheduler, stand up now
            if (w.body.isValid()) w.body.teleport(up);
            return;
        }
        w.plugin.getServer().getScheduler().runTask(w.plugin, () -> {
            if (w.body.isValid() && !w.body.isInsideVehicle()) w.body.teleport(up);
        });
    }

    static boolean isSeatEntity(Entity e) {
        return e instanceof ArmorStand && Keys.isTemp(e);
    }
}
