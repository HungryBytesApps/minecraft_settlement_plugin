package villagerworkers;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;

import java.util.*;

/** Free-time behaviour: walks, chats, looking around, sleeping at night, walking to town. */
final class Social {
    private Social() {}

    private static final Map<Block, UUID> claimedBeds = new HashMap<>();

    static Activity idle(Worker w) {
        int r = w.random.nextInt(100);
        if (r < 30) {
            Worker partner = findPartner(w);
            if (partner != null) return Chat.start(w, partner);
        }
        if (r < 85) {
            Activity walk = stroll(w);
            if (walk != null) return walk;
        }
        return new LookAround(w, 60 + w.random.nextInt(100));
    }

    // ---- Walk ----

    static Activity stroll(Worker w) {
        Location c = w.center();
        World world = c.getWorld();
        if (world == null || world != w.villager.getWorld()) return null;
        int r = w.plugin.settings().socialRadius();
        for (int tries = 0; tries < 12; tries++) {
            int x = c.getBlockX() + w.random.nextInt(r * 2 + 1) - r;
            int z = c.getBlockZ() + w.random.nextInt(r * 2 + 1) - r;
            for (int y = c.getBlockY() + 4; y >= c.getBlockY() - 6; y--) {
                if (PathFinder.walkable(world, x, y, z)) {
                    return new Stroll(w, new Location(world, x + 0.5, y, z + 0.5));
                }
            }
        }
        return null;
    }

    private static final class Stroll implements Activity {
        private final Worker w;
        private final Location to;
        private boolean started;
        private int time, pause = -1;

        Stroll(Worker w, Location to) {
            this.w = w;
            this.to = to;
        }

        @Override
        public boolean tick() {
            if (!started) {
                started = true;
                if (!w.mover.go(to, 0.8, w.plugin.settings().walkSpeed())) return true;
            }
            if (pause >= 0) {
                if (pause % 30 == 0) LookAround.glance(w);
                return --pause <= 0;
            }
            if (w.mover.arrived()) pause = 40 + w.random.nextInt(80);
            return w.mover.failed() || ++time > 600;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return "Taking a walk";
        }

        @Override
        public boolean interruptible() {
            return true;
        }
    }

    // ---- Walking to town ----

    /** Walk to the town bell; if it's too far to find a path, follow the owner; otherwise wait. */
    static final class GoHome implements Activity {
        private final Worker w;
        private final Town t;
        private int time, repathIn;
        private String label = "Walking to the settlement";

        GoHome(Worker w, Town t) {
            this.w = w;
            this.t = t;
        }

        @Override
        public boolean tick() {
            Location me = w.villager.getLocation();
            if (t.contains(me, w.plugin.settings().socialRadius() + 4)) return true;
            if (--repathIn <= 0) {
                repathIn = 40;
                Player owner = org.bukkit.Bukkit.getPlayer(w.owner);
                boolean sameWorld = t.bell.getWorld() == me.getWorld();
                double dx = sameWorld ? t.bell.getX() - me.getX() : 1e9, dz = sameWorld ? t.bell.getZ() - me.getZ() : 1e9;
                Location bell = t.bell.clone().add(0.5, -1, 0.5);
                if (dx * dx + dz * dz <= 46 * 46 && w.mover.go(bell, 4, w.plugin.settings().walkSpeed())) {
                    label = "Walking to the settlement";
                } else if (owner != null && owner.getWorld() == me.getWorld() && owner.getLocation().distanceSquared(me) <= 24 * 24) {
                    w.mover.go(owner.getLocation(), 2.5, w.plugin.settings().workSpeed());
                    label = "Following " + owner.getName();
                } else {
                    w.mover.stop();
                    label = "Waiting to be shown the way";
                    if (w.random.nextInt(8) == 0) Bubbles.say(w.plugin, w.villager, "Which way to the settlement?", 50);
                }
            }
            return ++time > 2400;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return label;
        }
    }

    // ---- Look around ----

    static final class LookAround implements Activity {
        private final Worker w;
        private int left;

        LookAround(Worker w, int ticks) {
            this.w = w;
            this.left = ticks;
        }

        static void glance(Worker w) {
            float yaw = w.villager.getLocation().getYaw() + w.random.nextInt(120) - 60;
            w.villager.setRotation(yaw, w.random.nextInt(30) - 15);
        }

        @Override
        public boolean tick() {
            if (left % 30 == 0 && w.random.nextBoolean()) glance(w);
            return --left <= 0;
        }

        @Override
        public String label() {
            return "Looking around";
        }

        @Override
        public boolean interruptible() {
            return true;
        }
    }

    // ---- Chat ----

    private static Worker findPartner(Worker w) {
        List<Worker> options = new ArrayList<>();
        Location me = w.villager.getLocation();
        for (Worker o : w.plugin.workers().all()) {
            if (o == w || o.villager.getWorld() != me.getWorld()) continue;
            if (o.villager.getLocation().distanceSquared(me) > 20 * 20) continue;
            if (o.villager.isSleeping() || o.villager.isInsideVehicle()) continue;
            if (o.activity != null && !o.activity.interruptible()) continue;
            options.add(o);
        }
        return options.isEmpty() ? null : options.get(w.random.nextInt(options.size()));
    }

    // ---- Sleep ----

    static Activity sleep(Worker w) {
        Block bed = findBed(w);
        if (bed == null) return null;
        return new Sleep(w, bed);
    }

    private static Block findBed(Worker w) {
        UUID me = w.villager.getUniqueId();
        if (w.bed != null && w.bed.getWorld() == w.villager.getWorld()) {
            Block b = w.bed.getBlock();
            if (isFreeBedHead(b, me)) {
                claimedBeds.put(b, me);
                return b;
            }
            claimedBeds.remove(b);
            w.bed = null;
        }
        Location c = w.center();
        World world = c.getWorld();
        if (world == null || world != w.villager.getWorld()) return null;
        int r = Math.min(24, w.plugin.settings().socialRadius() + 8);
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = c.getBlockX() - r; x <= c.getBlockX() + r; x++) {
            for (int z = c.getBlockZ() - r; z <= c.getBlockZ() + r; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = c.getBlockY() - 4; y <= c.getBlockY() + 4; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!Tag.BEDS.isTagged(b.getType()) || !isFreeBedHead(b, me) || w.isSkipped(b)) continue;
                    double d = b.getLocation().distanceSquared(c);
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
        }
        if (best != null) {
            claimedBeds.put(best, me);
            w.bed = best.getLocation();
            w.save();
        }
        return best;
    }

    private static boolean isFreeBedHead(Block b, UUID me) {
        if (!(b.getBlockData() instanceof Bed bed) || bed.getPart() != Bed.Part.HEAD) return false;
        UUID owner = claimedBeds.get(b);
        if (owner != null && !owner.equals(me)) return false;
        return !bed.isOccupied();
    }

    static void releaseBed(Worker w) {
        claimedBeds.values().removeIf(id -> id.equals(w.villager.getUniqueId()));
    }

    private static final class Sleep implements Activity {
        private final Worker w;
        private final Block bed;
        private int phase, time;

        Sleep(Worker w, Block bed) {
            this.w = w;
            this.bed = bed;
        }

        @Override
        public boolean tick() {
            if (phase == 0) {
                phase = 1;
                if (!w.mover.go(bed.getLocation().add(0.5, 0.5, 0.5), 2.0, w.plugin.settings().walkSpeed())) {
                    w.skip(bed, 2400);
                    return true;
                }
            }
            if (phase == 1) {
                if (!w.isNight()) return true;
                if (w.mover.failed() || ++time > 900) {
                    w.skip(bed, 2400);
                    return true;
                }
                if (!w.mover.arrived()) return false;
                if (!w.villager.sleep(bed.getLocation())) {
                    w.skip(bed, 2400);
                    return true;
                }
                phase = 2;
            }
            if (phase == 2) {
                if (!w.isNight() || !w.villager.isSleeping()) return true;
                if (++time % 200 == 0 && w.random.nextInt(4) == 0) {
                    w.villager.getWorld().playSound(w.villager.getLocation(), "minecraft:entity.villager.ambient",
                            SoundCategory.NEUTRAL, 0.3f, 0.6f); // snoring...
                }
            }
            return false;
        }

        @Override
        public void stop() {
            w.mover.stop();
            if (w.villager.isSleeping()) w.villager.wakeup();
        }

        @Override
        public String label() {
            return phase == 2 ? "Sleeping" : "Going to bed";
        }
    }

    static float yaw(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

}
