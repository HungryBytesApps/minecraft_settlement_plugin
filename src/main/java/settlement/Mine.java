package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * A miner's mine: a 2-wide switchback staircase down from where it starts (the surface, or an elevator's lowest
 * floor), with a landing every 9 blocks down. A level is a landing with a hub room beside it: corridors to three
 * sides, side tunnels every 4 blocks (3 stone between), log frames (studs in the walls, a beam in the ceiling)
 * every 8 blocks with torches on the studs.
 *
 * Coordinates: t = along the stairs (direction dir), s = sideways (0..4: lane, wall, lane), y = feet height.
 * A level uses u (away from the stairs) and v (sideways) around its hub's center.
 */
final class Mine {
    static final int[][] DIRS = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}}; // south, west, north, east (x, z)

    final String key; // the workplace's key
    World world;
    int ox, oy, oz, dir;
    /** Feet height up top (the surface; with an elevator start: its top floor). */
    int surface;
    /** Approved by the founder: digging may start. */
    boolean approved;
    /** Started at an elevator's floor (its id), or null: from the surface. */
    String elevator;
    /** Stair cells dug so far (in plan order). */
    int stairsDone;
    /** Size: how far tunnels go from a hub. */
    final Map<Integer, Level> levels = new TreeMap<>();

    static final class Level {
        int done;
        boolean hub;
        final Set<String> blocked = new HashSet<>();
        transient List<Col> plan;
        transient int planSize;
    }

    /** One column of a tunnel: (u, v) at the level's feet height, h blocks high; seg names the tunnel it belongs to. */
    record Col(int u, int v, int h, String seg) {}

    /** One stair cell: (t, s) at feet y, 3 high; landing ≥ 0 for the landing's cells. */
    record Cell(int t, int s, int y, int landing) {}

    Mine(String key) {
        this.key = key;
    }

    int fx() { return DIRS[dir][0]; }
    int fz() { return DIRS[dir][1]; }
    int sx() { return -DIRS[dir][1]; }
    int sz() { return DIRS[dir][0]; }

    Block at(int t, int s, int y) {
        return world.getBlockAt(ox + fx() * t + sx() * s, y, oz + fz() * t + sz() * s);
    }

    Location loc(int t, int s, int y) {
        return at(t, s, y).getLocation().add(0.5, 0, 0.5);
    }

    // ---- Stairs ----

    /** The stairs down to (and with) landing k. */
    List<Cell> stairs(int k) {
        List<Cell> out = new ArrayList<>();
        int y = oy - 1;
        for (int i = 0; i <= k; i++) {
            boolean even = i % 2 == 0;
            int lane = even ? 0 : 3;
            for (int j = 0; j < 8; j++) {
                int t = even ? j : 7 - j;
                out.add(new Cell(t, lane, y, -1));
                out.add(new Cell(t, lane + 1, y, -1));
                y--;
            }
            int ta = even ? 8 : -1, tb = even ? 9 : -2;
            for (int s = 0; s <= 4; s++) out.add(new Cell(ta, s, y, i));
            for (int s = 0; s <= 4; s++) out.add(new Cell(tb, s, y, i));
            y--;
        }
        return out;
    }

    /** Feet height of landing k. */
    int landingY(int k) {
        return oy - 1 - 8 - 9 * k;
    }

    /** The far row of landing k (the one the hub opens from), and which way along t is "away". */
    int landingRow(int k) {
        return k % 2 == 0 ? 9 : -2;
    }

    int away(int k) {
        return k % 2 == 0 ? 1 : -1;
    }

    /** The middle of landing k (where to stand on it). */
    Location landing(int k) {
        int t = k % 2 == 0 ? 8 : -1;
        return loc(t, 2, landingY(k));
    }

    /** At the top of the stairs (outside). */
    Location entrance() {
        return loc(-1, 1, oy);
    }

    /** Is (t, s) part of the stairs' footprint (with its walls)? */
    static boolean inStairs(int t, int s) {
        return t >= -4 && t <= 11 && s >= -2 && s <= 6;
    }

    // ---- Levels ----

    /** Hub center of landing k in (t, s). */
    int hubT(int k) {
        return landingRow(k) + away(k) * 4;
    }

    Block levelAt(int k, int u, int v, int dy) {
        return at(hubT(k) + away(k) * u, 2 + v, landingY(k) + dy);
    }

    Location levelLoc(int k, int u, int v) {
        return levelAt(k, u, v, 0).getLocation().add(0.5, 0, 0.5);
    }

    Location hub(int k) {
        return levelLoc(k, 0, 0);
    }

    /** The (t, s) of a level spot, to check it against the stairs. */
    int[] ts(int k, int u, int v) {
        return new int[]{hubT(k) + away(k) * u, 2 + v};
    }

    /** All tunnel columns of a level, nearest first: ring by ring, corridors then side tunnels. */
    static List<Col> plan(int size) {
        List<Col> out = new ArrayList<>();
        int prev = 2;
        for (int r = 4; ; r += 4) {
            int to = Math.min(r, size);
            for (int u = Math.max(3, prev + 1); u <= to; u++) for (int v : new int[]{0, -1, 1}) out.add(new Col(u, v, 3, "A"));
            for (int v = Math.max(3, prev + 1); v <= to; v++) {
                for (int u : new int[]{0, -1, 1}) out.add(new Col(u, -v, 3, "L"));
                for (int u : new int[]{0, -1, 1}) out.add(new Col(u, v, 3, "R"));
            }
            for (int b = 4; b <= to; b += 4) {
                int from = b <= prev ? prev + 1 : 2;
                for (int sign : new int[]{-1, 1}) {
                    int v = sign * b;
                    for (int u = from; u <= to; u++) out.add(new Col(u, v, 2, "B" + v + "a"));
                    for (int u = from; u <= to; u++) out.add(new Col(-u, v, 2, "B" + v + "b"));
                }
            }
            prev = to;
            if (to >= size) break;
        }
        return out;
    }

    List<Col> plan(Level l, int size) {
        if (l.plan == null || l.planSize != size) {
            l.plan = plan(size);
            l.planSize = size;
        }
        return l.plan;
    }

    // ---- Finding the way ----

    /** Landings dug so far (whole ones). */
    int landingsDug() {
        int cells = 0;
        List<Cell> st = stairs(Math.max(0, stairsDone / 26 + 1));
        for (int i = 0; i < stairsDone && i < st.size(); i++) if (st.get(i).landing() >= 0) cells++;
        return cells / 10;
    }

    /** The landing nearest to a height (-1: above the first one, at the start). */
    int where(Location l) {
        int y = l.getBlockY();
        if (y >= oy - 2) return -1;
        int best = 0;
        for (int i = 1; i < 64 && landingY(i) >= y - 9; i++) if (Math.abs(landingY(i) - y) < Math.abs(landingY(best) - y)) best = i;
        return best;
    }

    /** (u, v) of a spot on level k. */
    int[] uv(int k, Location l) {
        int dx = l.getBlockX() - ox, dz = l.getBlockZ() - oz;
        int tt = dx * fx() + dz * fz();
        int s = dx * sx() + dz * sz();
        return new int[]{(tt - hubT(k)) * away(k), s - 2};
    }

    /** In a level's tunnels (not on the stairs): which level; null: not. */
    Integer levelOf(Location l, int size) {
        if (l.getWorld() != world) return null;
        for (int k : levels.keySet()) {
            int y = landingY(k);
            if (Math.abs(l.getBlockY() - y) > 2) continue;
            int[] uv = uv(k, l);
            if (Math.abs(uv[0]) <= size + 2 && Math.abs(uv[1]) <= size + 2 && !inStairs(hubT(k) + away(k) * uv[0], 2 + uv[1])) return k;
        }
        return null;
    }

    /** Hops on level k from a spot to (U, V): back to the spine, along it, out (long stretches in pieces). */
    List<Location> levelHops(int k, Location from, int U, int V) {
        List<Location> out = new ArrayList<>();
        int[] a = uv(k, from);
        int u0 = a[0], v0 = a[1];
        List<int[]> pts = new ArrayList<>();
        boolean onBranch = Math.abs(v0) >= 4 && Math.abs(u0) >= 2;
        boolean sameLine = (V == v0 && Math.abs(v0) >= 4) || (Math.abs(V) <= 1 && Math.abs(v0) <= 1 && u0 >= 2 && U >= 2);
        if (!sameLine) {
            if (onBranch) pts.add(new int[]{0, v0});
            else if (Math.abs(v0) <= 1 && u0 >= 3) pts.add(new int[]{0, 0});
            pts.add(new int[]{0, V != 0 || U < 2 ? V : 0});
        }
        pts.add(new int[]{U, V});
        int[] prev = {u0, v0};
        for (int[] p : pts) {
            int du = p[0] - prev[0], dv = p[1] - prev[1];
            int len = Math.max(Math.abs(du), Math.abs(dv));
            for (int step = 32; step < len; step += 32) out.add(levelLoc(k, prev[0] + du * step / len, prev[1] + dv * step / len));
            out.add(levelLoc(k, p[0], p[1]));
            prev = p;
        }
        return out;
    }

    /** Walking hops from a spot in the mine up to the start (entrance). */
    List<Location> upHops(Location from, int size) {
        List<Location> out = new ArrayList<>();
        int at = where(from);
        Integer lvl = levelOf(from, size);
        if (lvl != null) {
            out.addAll(levelHops(lvl, from, 0, 0));
            at = lvl;
        }
        for (int i = Math.min(at, landingsDug() - 1); i >= 0; i--) out.add(landing(i));
        out.add(entrance());
        return out;
    }

    /** Walking hops from the start (entrance) down to a spot in the mine. */
    List<Location> downHops(Location to, int size) {
        List<Location> out = new ArrayList<>();
        out.add(entrance());
        Integer lvl = levelOf(to, size);
        int at = lvl != null ? lvl : where(to);
        for (int i = 0; i <= Math.min(at, landingsDug() - 1); i++) out.add(landing(i));
        if (lvl != null) {
            int[] uv = uv(lvl, to);
            out.addAll(levelHops(lvl, hub(lvl), uv[0], uv[1]));
        }
        out.add(to);
        return out;
    }

    // ---- Saving (mines.yml) ----

    private static final Map<String, Mine> all = new HashMap<>();
    private static final Map<String, Map<String, Object>> unloaded = new HashMap<>();
    private static File file;
    private static boolean dirty;

    static Mine get(Town.Workplace wp) {
        return all.get(Keys.ser(wp.loc));
    }

    static Mine create(Town.Workplace wp) {
        Mine m = new Mine(Keys.ser(wp.loc));
        all.put(m.key, m);
        return m;
    }

    static void remove(Town.Workplace wp) {
        if (all.remove(Keys.ser(wp.loc)) != null) changed();
    }

    static Collection<Mine> all() {
        return all.values();
    }

    static void changed() {
        dirty = true;
    }

    static void load(SettlementPlugin plugin) {
        file = new File(plugin.getDataFolder(), "mines.yml");
        all.clear();
        unloaded.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) { // that world isn't loaded (yet): keep it as it is
                unloaded.put(id, s.getValues(true));
                continue;
            }
            Mine m = new Mine(s.getString("workplace", ""));
            m.world = w;
            m.ox = s.getInt("x");
            m.oy = s.getInt("y");
            m.oz = s.getInt("z");
            m.dir = Math.floorMod(s.getInt("dir"), 4);
            m.surface = s.getInt("surface", m.oy);
            m.approved = s.getBoolean("approved");
            m.elevator = s.getString("elevator");
            m.stairsDone = s.getInt("stairs");
            ConfigurationSection ls = s.getConfigurationSection("levels");
            if (ls != null) {
                for (String k : ls.getKeys(false)) {
                    ConfigurationSection l = ls.getConfigurationSection(k);
                    if (l == null) continue;
                    Level lv = new Level();
                    lv.done = l.getInt("done");
                    lv.hub = l.getBoolean("hub");
                    lv.blocked.addAll(l.getStringList("blocked"));
                    try {
                        m.levels.put(Integer.parseInt(k), lv);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            all.put(m.key, m);
        }
    }

    static void save() {
        if (file == null || !dirty) return;
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Map<String, Object>> e : unloaded.entrySet()) {
            for (Map.Entry<String, Object> v : e.getValue().entrySet()) if (!(v.getValue() instanceof ConfigurationSection)) y.set(e.getKey() + "." + v.getKey(), v.getValue());
        }
        int i = 0;
        for (Mine m : all.values()) {
            if (m.world == null) continue;
            while (unloaded.containsKey("m" + i)) i++;
            ConfigurationSection s = y.createSection("m" + (i++));
            s.set("workplace", m.key);
            s.set("world", m.world.getName());
            s.set("x", m.ox);
            s.set("y", m.oy);
            s.set("z", m.oz);
            s.set("dir", m.dir);
            s.set("surface", m.surface);
            s.set("approved", m.approved);
            if (m.elevator != null) s.set("elevator", m.elevator);
            s.set("stairs", m.stairsDone);
            for (Map.Entry<Integer, Level> e : m.levels.entrySet()) {
                ConfigurationSection l = s.createSection("levels." + e.getKey());
                l.set("done", e.getValue().done);
                l.set("hub", e.getValue().hub);
                l.set("blocked", new ArrayList<>(e.getValue().blocked));
            }
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            Bukkit.getLogger().warning("[Settlement] Could not save mines.yml: " + ex.getMessage());
        }
    }
}
