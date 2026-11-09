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
            if (!w.mover.go(center, reach, w.plugin.settings().workSpeed())) {
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
            if (w.mover.arrived() || closeEnough(center)) {
                w.mover.stop();
                Mover.lookAt(w.body, target.getLocation().add(0.5, 0.5, 0.5));
                w.body.swingMainHand();
                action.accept(target);
                phase = 2;
                wait = 8;
            } else if (w.mover.failed() || ++time > 600) {
                w.mover.stop();
                w.skip(target, 1200);
                return true;
            }
        }
        if (phase == 2) return --wait <= 0;
        return false;
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
