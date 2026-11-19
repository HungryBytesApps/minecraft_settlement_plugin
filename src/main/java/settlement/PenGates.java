package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Gates of animal pens (a fenced-in area with animals in it) are for herders only (and guards in a fight):
 * other settlers walk around them, so no animals get out. Other gates anyone may use.
 */
final class PenGates {
    private PenGates() {}

    /** While a path is searched for a settler that must keep out of pens. */
    static boolean avoid;

    private record Seen(boolean pen, int at) {}

    private static final Map<Long, Seen> gates = new HashMap<>();
    private static final Map<Long, Seen> areas = new HashMap<>();
    private static boolean busy;

    /** May this settler go through pen gates? Herders, and guards while fighting. */
    static boolean mayUse(Worker w) {
        if (w.job() == Worker.Job.HERDER) return true;
        return w.activity instanceof Guard g && g.fighting();
    }

    /** Is this gate part of a pen with animals in it? (Worked out again every 10 seconds.) */
    static boolean isPenGate(Block gate) {
        if (busy) return false;
        long k = key(gate.getX(), gate.getY(), gate.getZ());
        int now = Bukkit.getCurrentTick();
        Seen s = gates.get(k);
        if (s != null && now - s.at() < 200 && now >= s.at()) return s.pen();
        boolean pen = false;
        busy = true;
        try {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Block side = gate.getRelative(d[0], 0, d[1]);
                if (!Tag.FENCE_GATES.isTagged(side.getType()) && penWithAnimals(side.getWorld(), side.getX(), side.getY(), side.getZ())) {
                    pen = true;
                    break;
                }
            }
        } finally {
            busy = false;
        }
        if (gates.size() > 5000) gates.clear();
        gates.put(k, new Seen(pen, now));
        return pen;
    }

    /** Is this spot inside a pen with animals (a settler there may use the gate to get out)? */
    static boolean inPen(Location l) {
        long k = key(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        int now = Bukkit.getCurrentTick();
        Seen s = areas.get(k);
        if (s != null && now - s.at() < 100 && now >= s.at()) return s.pen();
        boolean pen;
        busy = true;
        try {
            pen = penWithAnimals(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        } finally {
            busy = false;
        }
        if (areas.size() > 5000) areas.clear();
        areas.put(k, new Seen(pen, now));
        return pen;
    }

    /** The area from this spot is closed in (fences, walls, gates; up to about 40x40) and has animals in it. */
    private static boolean penWithAnimals(World world, int sx, int sy, int sz) {
        int y0 = -1;
        for (int dy = 0; dy >= -1; dy--) if (PathFinder.walkable(world, sx, sy + dy, sz)) { y0 = sy + dy; break; }
        if (y0 == -1 && PathFinder.walkable(world, sx, sy + 1, sz)) y0 = sy + 1;
        if (y0 == -1) return false;
        Set<Long> seen = new HashSet<>();
        Deque<int[]> todo = new ArrayDeque<>();
        todo.add(new int[]{sx, y0, sz});
        seen.add(key(sx, y0, sz));
        int minX = sx, maxX = sx, minZ = sz, maxZ = sz, minY = y0, maxY = y0;
        while (!todo.isEmpty()) {
            if (seen.size() > 1600) return false; // open land, not a pen
            int[] c = todo.poll();
            minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]);
            minZ = Math.min(minZ, c[2]); maxZ = Math.max(maxZ, c[2]);
            minY = Math.min(minY, c[1]); maxY = Math.max(maxY, c[1]);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = c[0] + d[0], y = c[1] + dy, z = c[2] + d[1];
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) return false; // unknown: not treated as a pen
                    if (!PathFinder.walkable(world, x, y, z)) continue;
                    Material m = world.getBlockAt(x, y, z).getType(), below = world.getBlockAt(x, y - 1, z).getType();
                    if (side(m) || side(below)) continue; // the way out (or over)
                    if (seen.add(key(x, y, z))) todo.add(new int[]{x, y, z});
                    break;
                }
            }
        }
        // Animals inside?
        Location centre = new Location(world, (minX + maxX + 1) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ + 1) / 2.0);
        for (Animals a : world.getNearbyEntitiesByType(Animals.class, centre, (maxX - minX) / 2.0 + 1, (maxY - minY) / 2.0 + 2, (maxZ - minZ) / 2.0 + 1)) {
            Location al = a.getLocation();
            if (seen.contains(key(al.getBlockX(), al.getBlockY(), al.getBlockZ())) || seen.contains(key(al.getBlockX(), al.getBlockY() + 1, al.getBlockZ()))) return true;
        }
        return false;
    }

    private static boolean side(Material m) {
        return Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || m.name().endsWith("_CARPET");
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }
}
