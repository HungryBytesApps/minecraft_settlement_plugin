package villagerworkers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;

/**
 * A* over the block grid. A node is the block a villager's feet are in: solid floor below,
 * two passable blocks (feet + head). Walks, steps up 1 block, drops up to 3, goes through
 * wooden doors and fence gates, avoids water, lava, fire, berry bushes, etc.
 */
final class PathFinder {
    private PathFinder() {}

    private static final int MAX_NODES = 6000; // the whole settlement can be a workplace now
    private static final int MAX_RANGE = 112;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node {
        final int x, y, z;
        double g, f;
        Node parent;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Path from the villager's position to within {@code reach} blocks (horizontally, and at most
     * 1.5 blocks up/down) of the target point. Returns the nodes to walk through (empty = already
     * there), or null if there's no way.
     */
    static List<int[]> find(Location from, Location to, double reach) {
        World w = from.getWorld();
        if (w == null || !w.equals(to.getWorld())) return null;
        Node start = startNode(w, from);
        if (start == null) return null;

        double tx = to.getX(), ty = to.getY(), tz = to.getZ();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> seen = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Map<Long, Boolean> walkCache = new HashMap<>();
        start.f = dist(start, tx, ty, tz);
        open.add(start);
        seen.put(key(start.x, start.y, start.z), start);

        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node n = open.poll();
            if (!closed.add(key(n.x, n.y, n.z))) continue;
            if (atGoal(n, tx, ty, tz, reach)) return build(n);

            for (int[] d : DIRS) {
                int nx = n.x + d[0], nz = n.z + d[1];
                if (Math.abs(nx - start.x) > MAX_RANGE || Math.abs(nz - start.z) > MAX_RANGE) continue;
                boolean diag = d[0] != 0 && d[1] != 0;

                if (walkable(w, nx, n.y, nz, walkCache)) {
                    if (diag && !(walkable(w, n.x + d[0], n.y, n.z, walkCache) && walkable(w, n.x, n.y, n.z + d[1], walkCache))) continue;
                    add(open, seen, closed, n, nx, n.y, nz, diag ? 1.414 : 1.0, tx, ty, tz);
                    continue;
                }
                if (diag) continue;
                // Step up one block (needs headroom above the current spot to jump).
                if (walkable(w, nx, n.y + 1, nz, walkCache) && passable(w.getBlockAt(n.x, n.y + 2, n.z))) {
                    add(open, seen, closed, n, nx, n.y + 1, nz, 4.0, tx, ty, tz); // climbing: only when needed
                    continue;
                }
                // Drop down up to 3 blocks.
                if (passable(w.getBlockAt(nx, n.y, nz)) && passable(w.getBlockAt(nx, n.y + 1, nz))) {
                    for (int drop = 1; drop <= 3; drop++) {
                        if (walkable(w, nx, n.y - drop, nz, walkCache)) {
                            add(open, seen, closed, n, nx, n.y - drop, nz, 2.0 + drop * 1.5, tx, ty, tz);
                            break;
                        }
                        if (!passable(w.getBlockAt(nx, n.y - drop, nz))) break;
                    }
                }
            }
        }
        return null;
    }

    private static void add(PriorityQueue<Node> open, Map<Long, Node> seen, Set<Long> closed, Node from,
                            int x, int y, int z, double cost, double tx, double ty, double tz) {
        long k = key(x, y, z);
        if (closed.contains(k)) return;
        double g = from.g + cost;
        Node n = seen.get(k);
        if (n != null && n.g <= g) return;
        if (n == null) {
            n = new Node(x, y, z);
            seen.put(k, n);
        }
        n.g = g;
        n.f = g + dist(n, tx, ty, tz);
        n.parent = from;
        open.add(n);
    }

    private static boolean atGoal(Node n, double tx, double ty, double tz, double reach) {
        double dx = n.x + 0.5 - tx, dz = n.z + 0.5 - tz;
        return dx * dx + dz * dz <= reach * reach && Math.abs(n.y - ty) <= 1.5;
    }

    private static List<int[]> build(Node end) {
        LinkedList<int[]> out = new LinkedList<>();
        for (Node n = end; n.parent != null; n = n.parent) out.addFirst(new int[]{n.x, n.y, n.z});
        return out;
    }

    private static double dist(Node n, double tx, double ty, double tz) {
        double dx = n.x + 0.5 - tx, dy = n.y - ty, dz = n.z + 0.5 - tz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Node startNode(World w, Location l) {
        int x = l.getBlockX(), z = l.getBlockZ();
        int y = (int) Math.floor(l.getY() + 0.1);
        for (int dy : new int[]{0, 1, -1}) {
            if (walkable(w, x, y + dy, z)) return new Node(x, y + dy, z);
        }
        // Standing on an odd spot (edge of a block): try the neighbours.
        for (int[] d : DIRS) {
            if (walkable(w, x + d[0], y, z + d[1])) return new Node(x + d[0], y, z + d[1]);
        }
        return null;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static boolean walkable(World w, int x, int y, int z, Map<Long, Boolean> cache) {
        return cache.computeIfAbsent(key(x, y, z), k -> walkable(w, x, y, z));
    }

    /** Can a villager stand with its feet in this block? */
    static boolean walkable(World w, int x, int y, int z) {
        if (y - 1 < w.getMinHeight() || y + 1 >= w.getMaxHeight()) return false;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
        // A carpet: stand on it (on a floor, or on top of a fence or wall: the way over a fence).
        Block feet = w.getBlockAt(x, y, z);
        if (feet.getType().name().endsWith("_CARPET")) {
            return !w.getBlockAt(x, y - 1, z).isPassable() && passable(w.getBlockAt(x, y + 1, z));
        }
        return standable(w.getBlockAt(x, y - 1, z))
                && passable(w.getBlockAt(x, y, z))
                && passable(w.getBlockAt(x, y + 1, z));
    }

    static boolean standable(Block b) {
        Material m = b.getType();
        if (!m.isSolid()) return false;
        if (Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.DOORS.isTagged(m)) return false;
        // No walking over furniture: chests, workstations, the bell, anvils, beds...
        if (m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.ENDER_CHEST || m == Material.BARREL
                || m == Material.BELL || m == Material.CRAFTING_TABLE || Tag.ANVIL.isTagged(m) || Tag.BEDS.isTagged(m)
                || Tag.SHULKER_BOXES.isTagged(m) || Worker.Job.forBlock(m) != Worker.Job.NONE || m == Material.LECTERN
                || m == Material.CAULDRON || m == Material.BREWING_STAND || m == Material.ENCHANTING_TABLE) return false;
        return m != Material.MAGMA_BLOCK && m != Material.CACTUS && m != Material.CAMPFIRE && m != Material.SOUL_CAMPFIRE;
    }

    static boolean passable(Block b) {
        Material m = b.getType();
        if (Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) return true; // opened on the way
        if (b.isLiquid()) return false;
        if (m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.SWEET_BERRY_BUSH || m == Material.COBWEB
                || m == Material.POWDER_SNOW || m == Material.WITHER_ROSE || m == Material.LAVA) return false;
        return b.isPassable();
    }
}
