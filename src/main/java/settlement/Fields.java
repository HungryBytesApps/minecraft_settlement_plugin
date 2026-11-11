package settlement;

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

    private record Scan(List<Block> farmland, List<Block> trees, List<Block> plants, List<Block> jukeboxes, int at) {}

    private static final Map<String, Scan> inside = new HashMap<>(), outside = new HashMap<>();
    private static final int OUTSIDE_WIDTH = 24; // how far past the border lumberjacks look
    private static final int MAX_AGE = 600;

    /** All farmland in the settlement. */
    static List<Block> farmland(Town t, int radius) {
        return get(t, radius, false).farmland();
    }

    /** Jukeboxes in the settlement. */
    static List<Block> jukeboxes(Town t, int radius) {
        return get(t, radius, false).jukeboxes();
    }

    /** Melons, pumpkins, the bottom of sugar cane / cactus / bamboo, berry bushes and cocoa in the settlement. */
    static List<Block> plants(Town t, int radius) {
        return get(t, radius, false).plants();
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
        List<Block> farmland = new ArrayList<>(), trees = new ArrayList<>(), plants = new ArrayList<>(), jukeboxes = new ArrayList<>();
        if (w == null) return new Scan(farmland, trees, plants, jukeboxes, now);
        int cy = t.bell.getBlockY();
        int margin = radius - Town.BASE, outerMargin = ring ? margin + OUTSIDE_WIDTH : margin;
        int[] box = t.box(outerMargin);
        int minY = Math.max(w.getMinHeight() + 1, cy - (ring ? 24 : 12)), maxY = Math.min(w.getMaxHeight() - 1, cy + (ring ? 32 : 16));
        for (int chx = box[0] >> 4; chx <= box[1] >> 4; chx++) {
            for (int chz = box[2] >> 4; chz <= box[3] >> 4; chz++) {
                if (!w.isChunkLoaded(chx, chz)) continue;
                ChunkSnapshot snap = w.getChunkAt(chx, chz).getChunkSnapshot(false, false, false);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int x = (chx << 4) + lx, z = (chz << 4) + lz;
                        boolean in = t.inside(x + 0.5, z + 0.5, margin);
                        if (ring ? (in || !t.inside(x + 0.5, z + 0.5, outerMargin)) : !in) continue;
                        Material below = snap.getBlockType(lx, minY - 1, lz);
                        for (int y = minY; y <= maxY; y++) {
                            Material m = snap.getBlockType(lx, y, lz);
                            if (!ring && m == Material.FARMLAND) farmland.add(w.getBlockAt(x, y, z));
                            else if (!ring && m == Material.JUKEBOX) jukeboxes.add(w.getBlockAt(x, y, z));
                            else if (!ring && (m == Material.MELON || m == Material.PUMPKIN || m == Material.SWEET_BERRY_BUSH || m == Material.COCOA
                                    || ((m == Material.SUGAR_CANE || m == Material.CACTUS || m == Material.BAMBOO) && below != m))) plants.add(w.getBlockAt(x, y, z));
                            else if (Tag.LOGS.isTagged(m) && !m.name().startsWith("STRIPPED_")
                                    && (Tag.DIRT.isTagged(below) || below == Material.GRASS_BLOCK)) trees.add(w.getBlockAt(x, y, z));
                            below = m;
                        }
                    }
                }
            }
        }
        return new Scan(farmland, trees, plants, jukeboxes, now);
    }
}
