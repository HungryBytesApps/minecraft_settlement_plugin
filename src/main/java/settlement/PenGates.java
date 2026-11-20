package settlement;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.List;

/**
 * Gates of animal pens (the pens the herder looks after: fenced in, with farm animals) are for herders only (and
 * guards in a fight): other settlers walk around them, so no animals get out. Other gates anyone may use.
 * Uses the settlement's pens (worked out every 10 seconds anyway), so a check costs next to nothing.
 */
final class PenGates {
    private PenGates() {}

    /** The pens to keep out of, while a path is searched for a settler that must (null: none). */
    static List<HerderJob.Pen> avoid;

    /** May this settler go through pen gates? Herders, and guards while fighting. */
    static boolean mayUse(Worker w) {
        if (w.job() == Worker.Job.HERDER) return true;
        return w.activity instanceof Guard g && g.fighting();
    }

    /** The pens of the settler's settlement, or null when it may use their gates (or there are none). */
    static List<HerderJob.Pen> pensFor(Worker w) {
        if (mayUse(w)) return null;
        Town t = w.town();
        if (t == null) return null;
        List<HerderJob.Pen> pens = HerderJob.townPens(t, w.plugin.settings().townRadius());
        return pens.isEmpty() ? null : pens;
    }

    /** Is this gate part of one of the pens being avoided? */
    static boolean isPenGate(Block gate) {
        List<HerderJob.Pen> pens = avoid;
        if (pens == null) return false;
        BlockFace f = gate.getBlockData() instanceof org.bukkit.block.data.type.Gate g ? g.getFacing() : BlockFace.NORTH;
        for (BlockFace face : new BlockFace[]{f, f.getOppositeFace()}) {
            Block side = gate.getRelative(face);
            if (HerderJob.onPenFloor(pens, side.getX(), side.getY(), side.getZ())) return true;
        }
        return false;
    }

    /** Is this spot inside one of these pens? */
    static boolean inPen(List<HerderJob.Pen> pens, Location l) {
        return pens != null && HerderJob.onPenFloor(pens, l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
