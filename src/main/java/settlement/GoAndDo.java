package settlement;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Walk to a block, then do something with it (harvest, plant, put things in a chest). */
final class GoAndDo implements Activity {
    private final Worker w;
    private final Block target;
    private final double reach;
    private final String label;
    private final Predicate<Block> stillValid;
    private final Consumer<Block> action;
    private int phase, time, wait;
    private Location access;
    private int workTicks;
    private String workSound;

    private Consumer<Block> onArrive;

    /** Something to do as soon as it gets there (before working). */
    GoAndDo onArrive(Consumer<Block> a) {
        this.onArrive = a;
        return this;
    }

    /** Work at the spot for a while (swinging, sounds) before the action happens. */
    GoAndDo working(int ticks, String sound) {
        this.workTicks = ticks;
        this.workSound = sound;
        return this;
    } // for chests: the open spot right next to it (no reaching through walls)

    GoAndDo(Worker w, Block target, double reach, String label, Predicate<Block> stillValid, Consumer<Block> action) {
        this.w = w;
        this.target = target;
        this.reach = reach;
        this.label = label;
        this.stillValid = stillValid;
        this.action = action;
    }

    @Override
    public boolean tick() {
        Location center = target.getLocation().add(0.5, 0, 0.5);
        if (phase == 0) {
            if (!stillValid.test(target)) return true;
            boolean ok;
            if (target.getState(false) instanceof org.bukkit.block.Container) {
                access = goNextTo(target);
                ok = access != null;
            } else {
                ok = w.mover.go(center, reach, w.plugin.settings().workSpeed());
            }
            if (!ok) {
                unreachable(true); // no way there at all: leave it for a minute
                return true;
            }
            phase = 1;
        }
        if (phase == 1) {
            if (!stillValid.test(target)) {
                w.mover.stop();
                return true;
            }
            if (access != null ? (w.mover.arrived() || near(access, 0.7)) : (w.mover.arrived() || closeEnough(center))) {
                w.mover.stop();
                openLid(target);
                w.anim.look(target.getLocation().add(0.5, 0.5, 0.5));
                if (!Town.isStorage(target) && target.getY() <= w.body.getLocation().getY() + 0.2) w.anim.crouch(16); // bend down to the ground
                w.body.swingMainHand();
                if (onArrive != null) onArrive.accept(target);
                if (workTicks > 0) {
                    phase = 3;
                    return false;
                }
                action.accept(target);
                phase = 2;
                wait = 8;
            } else if (w.mover.failed() || ++time > 600) {
                w.mover.stop();
                unreachable(false); // got stuck on the way (other settlers, a door...): just try again later
                return true;
            }
        }
        if (phase == 3) {
            if (!stillValid.test(target)) return true;
            if (workTicks % 12 == 0) w.body.swingMainHand();
            if (workSound != null && workTicks % 20 == 0)
                w.body.getWorld().playSound(target.getLocation().add(0.5, 0.5, 0.5), workSound, org.bukkit.SoundCategory.BLOCKS, 0.7f, 1f);
            if (--workTicks <= 0) {
                action.accept(target);
                phase = 2;
                wait = 8;
            }
            return false;
        }
        if (phase == 2) return --wait <= 0;
        return false;
    }

    /** Walk to a spot it can reach the chest from (like a player: within reach, nothing in between). Null if none. */
    private Location goNextTo(Block chest) {
        java.util.List<Location> spots = new java.util.ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                // The floor near the chest's height first; a chest up high is reached from below (up to 3 down).
                for (int dy : new int[]{0, -1, 1, -2, -3}) {
                    int x = chest.getX() + dx, y = chest.getY() + dy, z = chest.getZ() + dz;
                    if (!PathFinder.walkable(chest.getWorld(), x, y, z)) continue;
                    Location l = new Location(chest.getWorld(), x + 0.5, y, z + 0.5);
                    if (seesChest(l, chest)) {
                        spots.add(l);
                        break;
                    }
                }
            }
        }
        Location me = w.body.getLocation();
        Location c = chest.getLocation().add(0.5, 0, 0.5);
        spots.sort(java.util.Comparator.comparingDouble(l -> Math.abs(l.getY() - chest.getY()) * 3 + l.distanceSquared(c) * 2 + l.distanceSquared(me) * 0.01));
        int tries = 0;
        for (Location l : spots) {
            if (w.mover.go(l, 0.3, w.plugin.settings().workSpeed())) return l;
            if (++tries >= 3) break; // the nearest few: if none of those can be reached, the rest won't either
        }
        return null;
    }

    /**
     * Like a player: from this spot (eye height) one open side of the chest (the block next to it there is not
     * solid) faces it, is within reach, and nothing solid is between the eyes and that open side. So never through a
     * wall, but a chest up high, low, or under a counter is fine.
     */
    static boolean seesChest(Location feet, Block chest) {
        Location eye = feet.clone().add(0, 1.62, 0);
        java.util.List<Block> mine = Town.halves(chest);
        for (Block part : mine) {
            Location c = part.getLocation().add(0.5, 0.5, 0.5);
            for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
                    org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                Block n = part.getRelative(f);
                // That side is closed by a full block (a wall, another chest); a carpet, slab, trapdoor, lantern... leaves it open.
                if (mine.contains(n) || n.getType().isOccluding() || Town.isStorage(n) || fullBlock(n)) continue;
                // The eyes are on that side of the chest.
                double side = (eye.getX() - c.getX()) * f.getModX() + (eye.getY() - c.getY()) * f.getModY() + (eye.getZ() - c.getZ()) * f.getModZ();
                if (side <= 0.4) continue;
                // A point just outside that side; nothing solid on the way there.
                Location at = c.clone().add(f.getModX() * 0.6, f.getModY() * 0.6, f.getModZ() * 0.6);
                org.bukkit.util.Vector to = at.toVector().subtract(eye.toVector());
                double dist = to.length();
                if (dist > 4.5) continue;
                if (dist < 0.2) return true;
                org.bukkit.util.RayTraceResult r = chest.getWorld().rayTraceBlocks(eye, to.normalize(), dist,
                        org.bukkit.FluidCollisionMode.NEVER, true);
                if (r == null || r.getHitBlock() == null || mine.contains(r.getHitBlock())) return true;
                // A low thing on that side (carpet, slab, trapdoor) is fine; a pane, door or fence in between is not.
                if (r.getHitBlock().equals(n) && r.getHitBlockFace() == org.bukkit.block.BlockFace.UP && n.getBoundingBox().getMaxY() - n.getY() <= 0.55) return true;
            }
        }
        return false;
    }

    private static boolean fullBlock(Block b) {
        if (!b.getType().isSolid()) return false;
        org.bukkit.util.BoundingBox box = b.getBoundingBox();
        return box.getHeight() >= 0.99 && box.getWidthX() >= 0.99 && box.getWidthZ() >= 0.99;
    }

    private boolean near(Location l, double r) {
        Location me = w.body.getLocation();
        double dx = me.getX() - l.getX(), dz = me.getZ() - l.getZ();
        return dx * dx + dz * dz <= r * r && Math.abs(me.getY() - l.getY()) <= 1.0;
    }

    /** Chest lid opens for a moment, like when a player uses it. */
    private void openLid(Block b) {
        if (!(b.getState(false) instanceof org.bukkit.block.Lidded lid)) return;
        boolean barrel = b.getType() == org.bukkit.Material.BARREL;
        org.bukkit.Location at = b.getLocation().add(0.5, 0.5, 0.5);
        try {
            lid.open();
            b.getWorld().playSound(at, barrel ? "minecraft:block.barrel.open" : "minecraft:block.chest.open", org.bukkit.SoundCategory.BLOCKS, 0.5f, 1f);
            org.bukkit.Bukkit.getScheduler().runTaskLater(w.plugin, () -> {
                if (b.getState(false) instanceof org.bukkit.block.Lidded l) {
                    l.close();
                    b.getWorld().playSound(at, barrel ? "minecraft:block.barrel.close" : "minecraft:block.chest.close", org.bukkit.SoundCategory.BLOCKS, 0.5f, 1f);
                }
            }, 14L);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean closeEnough(Location center) {
        Location l = w.body.getLocation();
        double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ();
        return dx * dx + dz * dz <= reach * reach && Math.abs(l.getY() - center.getY()) <= 1.5;
    }

    @Override
    public void stop() {
        w.mover.stop();
    }

    @Override
    public String label() {
        return label;
    }

    /** Block → how often in a row no way was found to it, and since when. */
    private static final java.util.Map<Block, int[]> noWay = new java.util.HashMap<>();

    /**
     * Can't get there: leave it for a minute. A chest or workstation with no way to it three times within 10
     * minutes goes in the problem log (once could be a settler in the way, a door...).
     */
    /** No way at all was found to this block lately (not just a hold-up on the way). */
    static boolean noPathLately(Block b) {
        int[] f = noWay.get(b);
        int now = org.bukkit.Bukkit.getCurrentTick();
        return f != null && now - f[1] <= 12000 && now >= f[1];
    }

    private void unreachable(boolean noPath) {
        w.skip(target, 1200);
        if (!noPath) return;
        int now = org.bukkit.Bukkit.getCurrentTick();
        int[] f = noWay.get(target);
        if (f == null || now - f[1] > 12000 || now < f[1]) f = new int[]{0, now};
        f[0]++;
        noWay.put(target, f);
        if (noWay.size() > 500) noWay.values().removeIf(v -> now - v[1] > 12000 || now < v[1]);
        if (f[0] < 3) return;
        f[0] = 0; // (still known as "no way there": it's not waited for)
        Town t = w.town();
        if (t != null && (Town.isStorage(target) || t.workplace(target.getLocation()) != null)) {
            TownLog.settler(w, "can't reach the " + Work.nice(target.getType()) + " at " + target.getX() + ", " + target.getY() + ", " + target.getZ(),
                    net.kyori.adventure.text.format.NamedTextColor.GOLD);
        }
    }
}
