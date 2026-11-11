package settlement;

import org.bukkit.Location;
import org.bukkit.block.Block;

/**
 * The shape of settlements: the main square's radius, expansion squares, and the rules that keep
 * settlements apart and keep workstations and expansion bells inside.
 */
final class Area {
    private Area() {}

    /** The biggest radius a square at (x, z) can have without touching another settlement (Integer.MAX_VALUE: no neighbours). */
    static int maxFit(SettlementPlugin plugin, Town self, Location at) {
        int best = Integer.MAX_VALUE;
        for (Town o : plugin.towns().all()) {
            if (o == self || o.world() != at.getWorld()) continue;
            for (int[] q : o.squares()) {
                int d = Math.max(Math.abs(at.getBlockX() - q[0]), Math.abs(at.getBlockZ() - q[1]));
                best = Math.min(best, d - q[2] - 1);
            }
        }
        return best;
    }

    /**
     * Can this square (main: e == null) get this radius? Null = yes, else the reason why not.
     * Growing: not into another settlement. Shrinking: workstations with workers and expansion bells stay inside.
     */
    static String check(SettlementPlugin plugin, Town t, Town.Expansion e, int newRadius) {
        int min = e == null ? Town.MAIN_MIN : Town.EXP_MIN, max = e == null ? Town.MAIN_MAX : Town.EXP_MAX;
        if (newRadius < min || newRadius > max) return "The radius must be " + min + " to " + max + ".";
        Location centre = e == null ? t.bell : e.bell;
        int fit = maxFit(plugin, t, centre);
        if (newRadius > fit) return "That would overlap another settlement (" + Math.max(0, fit) + " fits).";
        int old = e == null ? t.radius : e.radius;
        if (e == null) t.radius = newRadius;
        else e.radius = newRadius;
        try {
            return stillInside(t, null);
        } finally {
            if (e == null) t.radius = old;
            else e.radius = old;
        }
    }

    /** Can this expansion be removed? Null = yes. */
    static String checkRemove(Town t, Town.Expansion e) {
        t.expansions.remove(e);
        try {
            return stillInside(t, e);
        } finally {
            t.expansions.add(e);
        }
    }

    /** Workstations with workers and expansion bells (each held by the other squares) must be inside. */
    private static String stillInside(Town t, Town.Expansion removed) {
        for (Town.Workplace wp : t.workplaces.values()) {
            if (wp.workers.isEmpty()) continue;
            if (!t.inside(wp.loc.getX() + 0.5, wp.loc.getZ() + 0.5, 0))
                return "A workstation with workers would fall outside (" + wp.loc.getBlockX() + ", " + wp.loc.getBlockZ() + ").";
        }
        for (Town.Expansion x : new java.util.ArrayList<>(t.expansions)) {
            // The expansion's bell must stand in the rest of the settlement (so it stays one piece).
            t.expansions.remove(x);
            boolean ok;
            try {
                ok = t.inside(x.bell.getX() + 0.5, x.bell.getZ() + 0.5, 0);
            } finally {
                t.expansions.add(x);
            }
            if (!ok) return "An expansion bell would fall outside (" + x.bell.getBlockX() + ", " + x.bell.getBlockZ() + ").";
        }
        return null;
    }

    /** Radius for a new settlement at this bell (at most the config's), or -1 if not even the minimum fits. */
    static int newSettlementRadius(SettlementPlugin plugin, Block bell) {
        int r = Math.min(Town.BASE, maxFit(plugin, null, bell.getLocation()));
        r = Math.min(r, Town.MAIN_MAX);
        return r < Town.MAIN_MIN ? -1 : r;
    }

    /** Radius for a new expansion here, or -1 if not even the minimum fits. */
    static int newExpansionRadius(SettlementPlugin plugin, Town t, Block bell) {
        int r = Math.min(8, Math.min(Town.EXP_MAX, maxFit(plugin, t, bell.getLocation())));
        return r < Town.EXP_MIN ? -1 : r;
    }

    /** After a change: rescan fields, chests, chunks; save. */
    static void changed(SettlementPlugin plugin, Town t) {
        Fields.invalidate(t);
        t.invalidate();
        plugin.reloadChunks();
        plugin.towns().save();
    }
}
