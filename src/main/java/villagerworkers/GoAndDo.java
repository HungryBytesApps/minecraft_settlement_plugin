package villagerworkers;

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
                w.skip(target, 1200); // can't get there: leave it for a minute
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
                if (workTicks > 0) {
                    phase = 3;
                    return false;
                }
                action.accept(target);
                phase = 2;
                wait = 8;
            } else if (w.mover.failed() || ++time > 600) {
                w.mover.stop();
                w.skip(target, 1200);
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

    /** Walk to an open spot right next to the chest (a side you can stand at). Null if none can be reached. */
    private Location goNextTo(Block chest) {
        java.util.List<Location> spots = new java.util.ArrayList<>();
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : sides) {
            int x = chest.getX() + d[0], z = chest.getZ() + d[1];
            for (int dy : new int[]{0, -1, 1}) {
                int y = chest.getY() + dy;
                if (PathFinder.walkable(chest.getWorld(), x, y, z)) {
                    spots.add(new Location(chest.getWorld(), x + 0.5, y, z + 0.5));
                    break;
                }
            }
        }
        Location me = w.body.getLocation();
        spots.sort(java.util.Comparator.comparingDouble(l -> l.distanceSquared(me)));
        for (Location l : spots) {
            if (w.mover.go(l, 0.3, w.plugin.settings().workSpeed())) return l;
        }
        return null;
    }

    private boolean near(Location l, double r) {
        Location me = w.body.getLocation();
        double dx = me.getX() - l.getX(), dz = me.getZ() - l.getZ();
        return dx * dx + dz * dz <= r * r && Math.abs(me.getY() - l.getY()) <= 1.0;
    }

    /** Chest lid opens for a moment, like when a player uses it. */
    private void openLid(Block b) {
        if (!(b.getState(false) instanceof org.bukkit.block.Lidded lid)) return;
        try {
            lid.open();
            org.bukkit.Bukkit.getScheduler().runTaskLater(w.plugin, () -> {
                if (b.getState(false) instanceof org.bukkit.block.Lidded l) l.close();
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
}
