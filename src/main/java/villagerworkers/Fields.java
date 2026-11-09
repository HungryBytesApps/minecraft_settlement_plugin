package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;

/**
 * Finds farmland and trees in (or around) a settlement in one quick pass over chunk snapshots,
 * and remembers the result for 30 seconds, so many workers can search the whole settlement cheaply.
 */
final class Fields {
    private Fields() {}

    private record Scan(List<Block> farmland, List<Block> trees, int at) {}

    private static final Map<String, Scan> inside = new HashMap<>(), outside = new HashMap<>();
    private static final int OUTSIDE_WIDTH = 24; // how far past the border lumberjacks look
    private static final int MAX_AGE = 600;

    /** All farmland in the settlement. */
    static List<Block> farmland(Town t, int radius) {
        return get(t, radius, false).farmland();
    }

    /** Tree bases (a log on dirt) inside the settlement, or in a ring just outside it. */
    static List<Block> trees(Town t, int radius, boolean outsideRing) {
        return get(t, radius, outsideRing).trees();
    }

    /** Forget a settlement's scan (e.g. after a big change). */
    static void invalidate(Town t) {
        inside.remove(t.id);
        outside.remove(t.id);
    }

    private static Scan get(Town t, int radius, boolean ring) {
        Map<String, Scan> cache = ring ? outside : inside;
        int now = Bukkit.getCurrentTick();
        Scan s = cache.get(t.id);
        if (s != null && now - s.at() < MAX_AGE) return s;
        s = scan(t, radius, ring, now);
        cache.put(t.id, s);
        return s;
    }

    private static Scan scan(Town t, int radius, boolean ring, int now) {
        World w = t.world();
        List<Block> farmland = new ArrayList<>(), trees = new ArrayList<>();
        if (w == null) return new Scan(farmland, trees, now);
        int cx = t.bell.getBlockX(), cz = t.bell.getBlockZ(), cy = t.bell.getBlockY();
        int outer = ring ? radius + OUTSIDE_WIDTH : radius;
        int minY = Math.max(w.getMinHeight() + 1, cy - (ring ? 24 : 12)), maxY = Math.min(w.getMaxHeight() - 1, cy + (ring ? 32 : 16));
        long inner2 = (long) radius * radius, outer2 = (long) outer * outer;
        for (int chx = (cx - outer) >> 4; chx <= (cx + outer) >> 4; chx++) {
            for (int chz = (cz - outer) >> 4; chz <= (cz + outer) >> 4; chz++) {
                if (!w.isChunkLoaded(chx, chz)) continue;
                ChunkSnapshot snap = w.getChunkAt(chx, chz).getChunkSnapshot(false, false, false);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int x = (chx << 4) + lx, z = (chz << 4) + lz;
                        long d2 = (long) (x - cx) * (x - cx) + (long) (z - cz) * (z - cz);
                        if (ring ? (d2 <= inner2 || d2 > outer2) : d2 > inner2) continue;
                        Material below = snap.getBlockType(lx, minY - 1, lz);
                        for (int y = minY; y <= maxY; y++) {
                            Material m = snap.getBlockType(lx, y, lz);
                            if (!ring && m == Material.FARMLAND) farmland.add(w.getBlockAt(x, y, z));
                            else if (Tag.LOGS.isTagged(m) && !m.name().startsWith("STRIPPED_")
                                    && (Tag.DIRT.isTagged(below) || below == Material.GRASS_BLOCK)) trees.add(w.getBlockAt(x, y, z));
                            below = m;
                        }
                    }
                }
            }
        }
        return new Scan(farmland, trees, now);
    }
}
