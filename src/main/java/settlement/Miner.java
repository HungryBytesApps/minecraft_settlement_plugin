package settlement;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Predicate;

/**
 * Miner (stonecutter). Digs a mine (see {@link Mine}) for what storage targets ask for: the level of each ore,
 * a hub there with corridors and side tunnels, log frames with torches, walls sealed against caves, lava and water.
 * Uses the cheapest pickaxe that will do, keeps what's wanted (plus some cobblestone to seal with), throws the
 * rest away. Fights with its pickaxe; with too many monsters or low health it runs out and asks for guards.
 * Only works while it's work time, and keeps the land around it loaded while it's down there.
 */
final class Miner {
    private Miner() {}

    /** Products (storage targets) and the height where they're best found. */
    static final Map<Material, Integer> BEST_Y = new LinkedHashMap<>();

    static {
        BEST_Y.put(Material.COBBLESTONE, 48);
        BEST_Y.put(Material.COAL, 48);
        BEST_Y.put(Material.RAW_COPPER, 48);
        BEST_Y.put(Material.GRANITE, 48);
        BEST_Y.put(Material.DIORITE, 48);
        BEST_Y.put(Material.ANDESITE, 48);
        BEST_Y.put(Material.DIRT, 48);
        BEST_Y.put(Material.GRAVEL, 48);
        BEST_Y.put(Material.FLINT, 48);
        BEST_Y.put(Material.RAW_IRON, 16);
        BEST_Y.put(Material.EMERALD, 16);
        BEST_Y.put(Material.LAPIS_LAZULI, 0);
        BEST_Y.put(Material.TUFF, -16);
        BEST_Y.put(Material.RAW_GOLD, -16);
        BEST_Y.put(Material.COBBLED_DEEPSLATE, -16);
        BEST_Y.put(Material.REDSTONE, -53);
        BEST_Y.put(Material.DIAMOND, -53);
    }

    static final List<Material> PRODUCTS = List.copyOf(BEST_Y.keySet());

    /** Pickaxe level a product's ore needs (0 wood, 1 stone, 2 iron, 3 diamond). */
    private static int needFor(Material product) {
        return switch (product) {
            case RAW_COPPER, RAW_IRON, LAPIS_LAZULI -> 1;
            case RAW_GOLD, REDSTONE, DIAMOND, EMERALD -> 2;
            default -> 0;
        };
    }

    static final Set<Material> FILLER = Set.of(Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.ANDESITE, Material.DIORITE,
            Material.GRANITE, Material.TUFF, Material.DIRT, Material.STONE, Material.DEEPSLATE);

    // ---- Pickaxes ----

    /** Cheapest first. */
    private static final List<String> PICKS = List.of("WOODEN", "STONE", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE");

    static boolean isPick(Material m) {
        return m.name().endsWith("_PICKAXE");
    }

    static int level(Material pick) {
        String n = pick.name();
        if (n.startsWith("NETHERITE") || n.startsWith("DIAMOND")) return 3;
        if (n.startsWith("IRON")) return 2;
        if (n.startsWith("STONE") || n.startsWith("COPPER")) return 1;
        return 0;
    }

    private static int cost(Material pick) {
        int i = PICKS.indexOf(pick.name().substring(0, pick.name().indexOf('_')));
        return i < 0 ? 99 : i;
    }

    private static double speed(Material pick) {
        String n = pick.name();
        if (n.startsWith("GOLDEN")) return 12;
        if (n.startsWith("NETHERITE")) return 9;
        if (n.startsWith("DIAMOND")) return 8;
        if (n.startsWith("IRON")) return 6;
        if (n.startsWith("COPPER")) return 5;
        if (n.startsWith("STONE")) return 4;
        return 2;
    }

    /** The pickaxe level a block needs to drop anything (and to be dug at a normal speed). */
    static int need(Material m) {
        if (Tag.NEEDS_DIAMOND_TOOL.isTagged(m)) return 3;
        if (Tag.NEEDS_IRON_TOOL.isTagged(m)) return 2;
        if (Tag.NEEDS_STONE_TOOL.isTagged(m)) return 1;
        return 0;
    }

    /** The cheapest pickaxe it carries that's good enough (null: none). */
    static ItemStack pick(Worker w, int need) {
        ItemStack best = null;
        for (ItemStack it : w.pockets.getContents()) {
            if (it == null || !isPick(it.getType()) || level(it.getType()) < need) continue;
            if (best == null || cost(it.getType()) < cost(best.getType())) best = it;
        }
        return best;
    }

    static int bestLevel(Worker w) {
        int l = -1;
        for (ItemStack it : w.pockets.getContents()) if (it != null && isPick(it.getType())) l = Math.max(l, level(it.getType()));
        return l;
    }

    // ---- What blocks are ----

    private static final Set<Material> NATURAL = EnumSet.noneOf(Material.class);

    static {
        for (Material m : new Material[]{Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.DEEPSLATE, Material.TUFF,
                Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM, Material.GRAVEL,
                Material.SAND, Material.RED_SAND, Material.SANDSTONE, Material.RED_SANDSTONE, Material.CLAY, Material.TERRACOTTA, Material.SNOW_BLOCK,
                Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.MOSS_BLOCK, Material.DRIPSTONE_BLOCK, Material.POINTED_DRIPSTONE,
                Material.MUD, Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.OBSIDIAN, Material.MAGMA_BLOCK, Material.RAW_IRON_BLOCK,
                Material.RAW_COPPER_BLOCK, Material.SMOOTH_BASALT, Material.SNOW, Material.POWDER_SNOW}) NATURAL.add(m);
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isBlock()) continue;
            String n = m.name();
            if (n.endsWith("_ORE") || n.startsWith("INFESTED_") || (n.endsWith("_TERRACOTTA") && !n.contains("GLAZED"))) NATURAL.add(m);
        }
    }

    static boolean natural(Material m) {
        return NATURAL.contains(m);
    }

    static boolean ore(Material m) {
        return m.name().endsWith("_ORE") || m == Material.RAW_IRON_BLOCK || m == Material.RAW_COPPER_BLOCK;
    }

    /** Ours or harmless in the mine: frames, torches, rails, redstone bits. */
    private static boolean ours(Material m) {
        return m == Material.TORCH || m == Material.WALL_TORCH || Tag.RAILS.isTagged(m) || m == Material.REDSTONE_TORCH
                || m == Material.REDSTONE_WALL_TORCH || Tag.BUTTONS.isTagged(m) || m == Material.LEVER || m == Material.LADDER;
    }

    /** Something the game made down there (a dungeon, an old mineshaft...), or null. */
    static String structure(Material m) {
        String n = m.name();
        if (m == Material.SPAWNER) return "a monster spawner";
        if (m == Material.TRIAL_SPAWNER || m == Material.VAULT || n.startsWith("TUFF_") || n.startsWith("POLISHED_TUFF") || n.startsWith("CHISELED_TUFF")
                || (n.contains("COPPER") && !n.endsWith("_ORE") && !n.startsWith("RAW_"))) return "a trial chamber";
        if (m == Material.MOSSY_COBBLESTONE) return "a dungeon";
        if (m == Material.COBWEB || n.endsWith("_FENCE")) return "an old mineshaft";
        if (n.startsWith("SCULK") || m == Material.REINFORCED_DEEPSLATE || n.startsWith("DEEPSLATE_TILE") || n.startsWith("DEEPSLATE_BRICK")
                || n.startsWith("POLISHED_DEEPSLATE") || m == Material.CHISELED_DEEPSLATE || m == Material.SOUL_LANTERN) return "an ancient city (the deep dark: careful, the Warden)";
        if (m == Material.AMETHYST_BLOCK || m == Material.BUDDING_AMETHYST || m == Material.CALCITE || n.contains("AMETHYST_BUD")
                || m == Material.AMETHYST_CLUSTER) return "a geode";
        if (n.contains("STONE_BRICK") || m == Material.END_PORTAL_FRAME || m == Material.IRON_BARS) return "a stronghold";
        if (m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL) return "a chest";
        return null;
    }

    // ---- Wanted ----

    static int radius(Worker w) {
        return w.plugin.settings().townRadius();
    }

    /** A storage target asks for more of it (made to order: no target = don't mine for it). */
    static boolean wanted(Worker w, Town t, Material m) {
        Integer target = t.targets.get(m);
        return target != null && target > 0 && t.count(m, radius(w)) + w.count(m) < target;
    }

    private static double shortfall(Worker w, Town t, Material m) {
        Integer target = t.targets.get(m);
        if (target == null || target <= 0) return 0;
        int have = t.count(m, radius(w)) + w.count(m);
        return have >= target ? 0 : (target - have) / (double) target;
    }

    static final int[] SIZES = {16, 32, 48, 64, 96, 128};

    /** How far the tunnels go from a hub (stonecutter menu). */
    static int size(Town.Workplace wp) {
        int r = wp.herds.getOrDefault("MINE_SIZE", 32);
        return Math.max(16, Math.min(128, r));
    }

    /** Is this spot in (or near) the mine (below the surface)? */
    static boolean isIn(Mine m, Town.Workplace wp, Location l) {
        if (m == null || m.world != l.getWorld() || !m.approved || l.getY() >= m.surface - 2) return false;
        if (m.floors()) { // an elevator mine: its levels, or at the elevator below the top
            if (m.levelOf(l, size(wp)) != null) return true;
            int[] sh = m.shaft();
            return sh != null && l.getBlockX() >= sh[0] - 8 && l.getBlockX() <= sh[2] + 8 && l.getBlockZ() >= sh[1] - 8 && l.getBlockZ() <= sh[3] + 8;
        }
        int dx = l.getBlockX() - m.ox, dz = l.getBlockZ() - m.oz;
        int tt = dx * m.fx() + dz * m.fz(), s = dx * m.sx() + dz * m.sz();
        if (tt >= -6 && tt <= 13 && s >= -4 && s <= 8) return true; // the stairs (and the start, the elevator next to it)
        return m.levelOf(l, size(wp)) != null;                       // a level's hub and tunnels
    }

    /** The landing (level) for a height: the first landing at or below it, but at least 8 below the start. */
    static int landingFor(Mine m, int y) {
        int k = 0;
        while (m.landingY(k) > y && m.landingY(k + 1) >= m.world.getMinHeight() + 8) k++;
        return k;
    }

    /** Where each product can be found: {lowest, highest} feet height. */
    private static int[] range(Material p) {
        return switch (p) {
            case RAW_IRON -> new int[]{-24, 56};
            case EMERALD -> new int[]{-16, 120};
            case LAPIS_LAZULI -> new int[]{-32, 32};
            case TUFF -> new int[]{-60, 0};
            case RAW_GOLD -> new int[]{-55, 0};
            case COBBLED_DEEPSLATE -> new int[]{-60, -8};
            case REDSTONE, DIAMOND -> new int[]{-60, -40};
            case RAW_COPPER -> new int[]{0, 96};
            default -> new int[]{0, 120}; // stone, coal, dirt, gravel...
        };
    }

    /** Elevator mine: the floor (level key) for a product, nearest to where it's best; -1: no floor there. */
    static int floorFor(Mine m, Material p) {
        int[] r = range(p);
        int best = -1, bestD = Integer.MAX_VALUE;
        for (int f : Lift.floors(Lift.resolve(m))) {
            int feet = f + 1;
            if (feet < r[0] || feet > r[1] || feet > m.surface - 8) continue;
            int d = Math.abs(feet - BEST_Y.get(p));
            if (d < bestD) {
                bestD = d;
                best = f + Mine.FLOOR;
            }
        }
        return best;
    }

    /** -3: waiting for an elevator floor. */
    static final int WAIT_FLOOR = -3;

    /** Elevator mine: the floor for the product furthest below its target; a missing floor is asked for. */
    private static int pickFloor(Worker w, Town t, Mine m) {
        List<Material> wanted = new ArrayList<>();
        for (Material p : PRODUCTS) if (shortfall(w, t, p) > 0) wanted.add(p);
        if (wanted.isEmpty() || w.workplace() == null) return -1;
        wanted.sort(Comparator.comparingDouble((Material p) -> -shortfall(w, t, p)));
        boolean missing = false, full = false;
        for (Material p : wanted) {
            int k = floorFor(m, p);
            if (k < 0) {
                missing = true;
                askFloor(w, t, m, p);
                continue;
            }
            Mine.Level l = m.levels.get(k);
            if (l != null && l.hub && l.done >= m.plan(l, size(w.workplace())).size()) {
                full = true;
                continue;
            }
            return k;
        }
        return missing ? WAIT_FLOOR : full ? -2 : -1;
    }

    /** Ask the founder for an elevator floor at the height of a product (once; no = never again). */
    private static void askFloor(Worker w, Town t, Mine m, Material p) {
        int y = BEST_Y.get(p);
        String data = "y" + y;
        if (Asks.declined(t, "floor:" + data) || Asks.declined(t, "floorok:" + data)) return;
        StringBuilder what = new StringBuilder();
        for (Material o : PRODUCTS) if (BEST_Y.get(o) == y && wanted(w, t, o)) what.append(what.length() == 0 ? "" : ", ").append(FarmerJob.nice(o).toLowerCase(Locale.ROOT));
        Asks.add(t, Asks.Kind.FLOOR, "Could the elevator get a floor at about y " + y + "? That's where " + what + " is found. "
                + "I'll use it as soon as it's there.", m.entrance(), w, data);
    }

    /** Which level to work: the one for the product that's furthest below its target (-1: nothing wanted). */
    static int pickLevel(Worker w, Town t, Mine m) {
        if (m.floors()) return pickFloor(w, t, m);
        Material best = null;
        double most = 0;
        for (Material p : PRODUCTS) {
            double s = shortfall(w, t, p);
            if (s > most) {
                most = s;
                best = p;
            }
        }
        if (best == null || w.workplace() == null) return -1;
        int k = landingFor(m, BEST_Y.get(best));
        Mine.Level l = m.levels.get(k);
        // That level is dug out: the next one down.
        while (l != null && l.done >= m.plan(l, size(w.workplace())).size()) {
            if (m.landingY(k + 1) < m.world.getMinHeight() + 8) return -2; // all the way down, all dug out
            k++;
            l = m.levels.get(k);
        }
        return k;
    }

    /** Pickaxe level needed for the wanted products at a level. */
    static int needAt(Worker w, Town t, Mine m, int k) {
        int need = 0;
        for (Material p : PRODUCTS) {
            if (!wanted(w, t, p)) continue;
            int at = m.floors() ? floorFor(m, p) : landingFor(m, BEST_Y.get(p));
            if (at == k) need = Math.max(need, needFor(p));
        }
        return need;
    }

    /** Elevator mine: where to step off at floor level k and which way the level goes (rock ahead). False: no room. */
    static boolean frameFloor(Mine m, int k) {
        Mine.Level l = m.levels.computeIfAbsent(k, x -> new Mine.Level());
        if (l.framed) return true;
        String id = Lift.resolve(m);
        int[] p = id == null ? null : Lift.platform(id);
        if (p == null) return false;
        int floor = k - Mine.FLOOR, y = floor + 1;
        double cx = (p[0] + p[2] + 1) / 2.0, cz = (p[1] + p[3] + 1) / 2.0;
        int bestScore = -1;
        for (int x = p[0] - 1; x <= p[2] + 1; x++) {
            for (int z = p[1] - 1; z <= p[3] + 1; z++) {
                boolean inside = x >= p[0] && x <= p[2] && z >= p[1] && z <= p[3];
                if (inside || !PathFinder.walkable(m.world, x, y, z)) continue;
                double ax = x + 0.5 - cx, az = z + 0.5 - cz;
                int dir = Math.abs(ax) > Math.abs(az) ? (ax > 0 ? 3 : 1) : (az > 0 ? 0 : 2);
                int fx = Mine.DIRS[dir][0], fz = Mine.DIRS[dir][1];
                int score = 0;
                for (int t2 = 2; t2 <= 9; t2++) {
                    for (int sv = -3; sv <= 3; sv++) {
                        Block b = m.world.getBlockAt(x + fx * t2 - fz * sv, y, z + fz * t2 + fx * sv);
                        if (natural(b.getType()) && b.getType().isSolid()) score++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    l.ex = x;
                    l.ez = z;
                    l.edir = dir;
                }
            }
        }
        if (bestScore < 20) { // no rock next to that floor (a cave, built stuff)
            m.levels.remove(k);
            return false;
        }
        l.framed = true;
        Mine.changed();
        return true;
    }

    // ---- In the mine? ----

    static Mine mine(Worker w) {
        Town.Workplace wp = w.workplace();
        return wp == null || wp.job() != Worker.Job.MINER ? null : Mine.get(wp);
    }

    static boolean underground(Worker w) {
        Mine m = mine(w);
        return m != null && isIn(m, w.workplace(), w.body.getLocation());
    }

    // ---- The job ----

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        Activity a = next0(w, t, wp);
        if (a == null && underground(w)) a = new Shift(w, t, wp, Mine.get(wp), -1); // never idle down there
        return a;
    }

    private static Activity next0(Worker w, Town t, Town.Workplace wp) {
        // Something to tell the founder (in person): when it's up here and they're around.
        if (!underground(w)) {
            Asks.Ask ask = Asks.toTell(w);
            Player f = ask == null ? null : Asks.founderNear(t, radius(w) + 8);
            if (f != null) return new Asks.Tell(w, f, ask);
        }
        Mine m = Mine.get(wp);
        if (m == null || !m.approved) return site(w, t, wp, m);
        int k = pickLevel(w, t, m);
        Integer blocked = blockedUntil.get(Keys.ser(wp.loc));
        if (blocked != null && Bukkit.getCurrentTick() < blocked) {
            w.workStatus = "The mine can't go on (see Ledger → Requests)";
            return null;
        }
        if (k < 0) {
            w.workStatus = k == -2 ? "The mine is dug out (make it bigger, or start a new mine)"
                    : k == WAIT_FLOOR ? "Waiting for an elevator floor (Ledger → Requests)" : "Nothing to mine (set storage targets)";
            return underground(w) ? new Shift(w, t, wp, m, -1) : null;
        }
        int now = Bukkit.getCurrentTick();
        Integer danger = dangerUntil.get(w.body.getUniqueId());
        if (danger != null && now < danger) {
            w.workStatus = "Waiting: monsters in the mine";
            if (underground(w)) return new Shift(w, t, wp, m, -1);
            Activity g = Escort.ask(w, t, m, k);
            return g;
        }
        if (!underground(w)) {
            // Up here: bring things to storage, get what it needs.
            // Keeps its tools and supplies (few, so its 9 pockets have room for what it mines).
            Predicate<Material> keep = m2 -> isPick(m2) || Combat.foodValue(m2) > 0 || m2 == Material.TORCH || Tag.LOGS.isTagged(m2)
                    || m2 == Material.COBBLESTONE || m2 == Material.COBBLED_DEEPSLATE || m2 == Material.MINECART || Work.gear(m2);
            java.util.function.ToIntFunction<Material> limit = m2 -> m2 == Material.COBBLESTONE || m2 == Material.COBBLED_DEEPSLATE ? 32
                    : Combat.foodValue(m2) > 0 ? 8 : Tag.LOGS.isTagged(m2) ? 32 : m2 == Material.TORCH ? 64 : m2 == Material.MINECART ? 1 : Integer.MAX_VALUE;
            if (Work.needsDeposit(w, keep, limit, true)) {
                Activity d = Work.deposit(w, t, keep, limit);
                if (d != null) return d;
            }
            Activity s = supplies(w, t, wp, m, k);
            if (s != null) return s;
            if (s == null && !w.workStatus.isEmpty() && w.workStatus.startsWith("Needs")) return null;
        }
        return new Shift(w, t, wp, m, k);
    }

    /** Fetch what it needs down there; null: it has it all (or a status says what's missing). */
    private static Activity supplies(Worker w, Town t, Town.Workplace wp, Mine m, int k) {
        int need = Math.max(needAt(w, t, m, k), 0);
        w.workStatus = "";
        if (pick(w, need) == null) {
            for (String tier : PICKS) {
                Material p = Material.matchMaterial(tier + "_PICKAXE");
                if (p == null || level(p) < need) continue;
                Activity a = Work.fetch(w, t, x -> x == p, 1, "Getting a pickaxe");
                if (a != null) return a;
            }
            String which = need >= 2 ? "an iron (or better) pickaxe" : need == 1 ? "a stone (or better) pickaxe" : "a pickaxe";
            w.warnMissingTool(which);
            w.workStatus = "Needs " + which;
            return null;
        }
        if (w.count(Material.TORCH) < 8) {
            Activity a = Work.fetch(w, t, x -> x == Material.TORCH, 32, "Getting torches");
            if (a != null) return a;
            if (w.count(Material.TORCH) < 2) {
                w.workStatus = "Needs torches (none in storage)";
                return null;
            }
        }
        int logs = 0;
        for (ItemStack it : w.pockets.getContents()) if (it != null && Tag.LOGS.isTagged(it.getType())) logs += it.getAmount();
        if (logs < 13) {
            Activity a = Work.fetch(w, t, Tag.LOGS::isTagged, 32, "Getting logs for the beams");
            if (a != null) return a;
            if (logs < 2) {
                w.workStatus = "Needs logs for the beams (none in storage)";
                return null;
            }
        }
        int food = 0;
        for (ItemStack it : w.pockets.getContents()) if (it != null && Combat.foodValue(it.getType()) > 0) food += it.getAmount();
        if (food < 4 && t.mayEat()) {
            Activity a = Work.fetch(w, t, x -> Combat.foodValue(x) > 0, 8, "Getting food for down there");
            if (a != null) return a;
        }
        if (Rails.on(wp) && w.count(Material.MINECART) == 0) {
            Activity a = Work.fetch(w, t, x -> x == Material.MINECART, 1, "Getting a minecart");
            if (a != null) return a;
        }
        return null;
    }

    /** Leave the mine (end of the day): walk (or ride) up. */
    static Activity leave(Worker w) {
        Mine m = mine(w);
        Town t = w.town();
        if (m == null || t == null) return null;
        return new Shift(w, t, w.workplace(), m, -1);
    }

    static final Map<UUID, Integer> dangerUntil = new HashMap<>();
    /** The mine's stairs or hub can't go on (bedrock, something old or built): wait (workplace key → tick). */
    static final Map<String, Integer> blockedUntil = new HashMap<>();

    /** Forget what ran out (called now and then). */
    static void cleanup() {
        int now = Bukkit.getCurrentTick();
        dangerUntil.values().removeIf(u -> u < now);
        blockedUntil.values().removeIf(u -> u < now);
        Escort.foes.keySet().removeIf(id -> !dangerUntil.containsKey(id));
        Lift.cleanup();
        Rails.cleanup();
    }

    // ---- Finding a spot ----

    private static final Map<String, Search> searches = new HashMap<>();

    /** Start over (stonecutter: Start a new mine). */
    static void reset(Town.Workplace wp) {
        searches.remove(Keys.ser(wp.loc));
        blockedUntil.remove(Keys.ser(wp.loc));
        Mine.remove(wp);
        Mine.save();
    }

    private static Activity site(Worker w, Town t, Town.Workplace wp, Mine m) {
        Asks.Ask open = Asks.open(wp, Asks.Kind.MINE_SITE);
        if (open != null) {
            w.workStatus = "Waiting for the founder: OK to start a mine here? (Ledger → Requests)";
            return null;
        }
        Search s = searches.get(Keys.ser(wp.loc));
        if (s != null && s.done && Bukkit.getCurrentTick() < s.retryAt) {
            w.workStatus = "No good spot for a mine in the settlement";
            return null;
        }
        if (s == null || s.done) {
            s = new Search(w, t, wp);
            searches.put(Keys.ser(wp.loc), s);
        }
        return s;
    }

    /** The founder said yes (start digging) or no (look for another spot). */
    static void answer(SettlementPlugin plugin, Town t, Town.Workplace wp, String data, boolean yes) {
        searches.remove(Keys.ser(wp.loc));
        blockedUntil.remove(Keys.ser(wp.loc));
        if (!yes) return;
        String[] p = data.split(",");
        Mine m = Mine.get(wp);
        if (m == null) m = Mine.create(wp);
        m.world = t.bell.getWorld();
        if (p[0].equals("E")) { // an elevator mine: E, id, x, y, z (where to stand up top)
            m.elevator = p[1].replace(';', ',');
            m.ox = Integer.parseInt(p[2]);
            m.oy = Integer.parseInt(p[3]);
            m.oz = Integer.parseInt(p[4]);
            m.surface = m.oy;
            m.dir = 0;
            m.approved = true;
            m.stairsDone = 0;
            m.levels.clear();
            Mine.changed();
            Mine.save();
            return;
        }
        m.ox = Integer.parseInt(p[0]);
        m.oy = Integer.parseInt(p[1]);
        m.oz = Integer.parseInt(p[2]);
        m.dir = Integer.parseInt(p[3]);
        m.elevator = p.length > 4 && !p[4].isEmpty() ? p[4].replace(';', ',') : null;
        m.surface = p.length > 5 ? Integer.parseInt(p[5]) : m.oy;
        m.approved = true;
        m.stairsDone = 0;
        m.levels.clear();
        Mine.changed();
        Mine.save();
    }

    /** Looks over the settlement for a spot (a few candidates per tick), then asks the founder. */
    static final class Search implements Activity {
        private final Worker w;
        private final Town t;
        private final Town.Workplace wp;
        private final List<int[]> todo = new ArrayList<>();
        boolean done;
        int retryAt;
        private int idx, groundY;
        private boolean elevatorChecked;

        Search(Worker w, Town t, Town.Workplace wp) {
            this.w = w;
            this.t = t;
            this.wp = wp;
            // The whole settlement: the main area and every expansion.
            Set<Long> seenXZ = new HashSet<>();
            for (int[] q : t.squares()) {
                int r = q[2] - 4;
                for (int x = q[0] - r; x <= q[0] + r; x += 3) {
                    for (int z = q[1] - r; z <= q[1] + r; z += 3) {
                        if (!seenXZ.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                        for (int d = 0; d < 4; d++) todo.add(new int[]{x, z, d});
                    }
                }
            }
            Location s = wp.loc;
            todo.sort(Comparator.comparingDouble(a -> {
                double dx = a[0] - s.getX(), dz = a[1] - s.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                return d < 10 ? 1000 - d : d; // near the stonecutter, but not right on top of it
            }));
        }

        @Override
        public boolean tick() {
            w.workStatus = "Looking for a spot for a mine";
            if (!elevatorChecked) {
                elevatorChecked = true;
                String[] e = Lift.mineStart(w, t);
                if (e != null) return propose(e, "using the elevator: I'll dig at its floors underground, and ask you for a floor where one is missing");
            }
            for (int n = 0; n < 2 && idx < todo.size(); n++) {
                int[] c = todo.get(idx++);
                int[] ok = check(c[0], c[1], c[2]);
                if (ok != null) return propose(new String[]{ok[0] + "," + ok[1] + "," + ok[2] + "," + ok[3]}, null);
            }
            if (idx >= todo.size()) {
                done = true;
                retryAt = Bukkit.getCurrentTick() + 6000;
                Asks.add(t, Asks.Kind.BLOCKED, "I can't find a good spot for a mine: open natural ground with nothing built close by, "
                        + "and no caves, water or old structures under it.", null, w, "nosite");
                return true;
            }
            return false;
        }

        private boolean propose(String[] data, String where) {
            done = true;
            retryAt = Integer.MAX_VALUE;
            String[] p = data[0].split(",");
            boolean lift = p[0].equals("E");
            Location at = lift ? new Location(t.bell.getWorld(), Integer.parseInt(p[2]) + 0.5, Integer.parseInt(p[3]), Integer.parseInt(p[4]) + 0.5)
                    : new Location(t.bell.getWorld(), Integer.parseInt(p[0]) + 0.5, Integer.parseInt(p[1]), Integer.parseInt(p[2]) + 0.5);
            Asks.add(t, Asks.Kind.MINE_SITE, lift ? "May I start a mine " + where + "?"
                    : "May I start a mine here? I've marked the spot (it glows).", at, w, data[0]);
            Bubbles.say(w.plugin, w.body, "Found a spot for the mine!", 60);
            return true;
        }

        /** A spot (x, z, dir): {ox, oy, oz, dir} when it's fine, else null. */
        private int[] check(int x, int z, int dir) {
            World world = t.bell.getWorld();
            if (Asks.declined(t, "site:" + x + "," + (world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1) + "," + z + "," + dir)) return null;
            Mine m = new Mine("check");
            m.world = world;
            m.ox = x;
            m.oz = z;
            m.dir = dir;
            int r = radius(w);
            // The whole footprint inside the settlement, ground flat and natural, nothing on top.
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int tt = -3; tt <= 11; tt++) {
                for (int s = -2; s <= 6; s++) {
                    m.oy = 0;
                    Block col = m.at(tt, s, 0);
                    Location colAt = col.getLocation();
                    colAt.setY(t.bell.getY()); // only across: the height doesn't matter here
                    if (!t.contains(colAt, r - 2)) return null;
                    if (!world.isChunkLoaded(col.getX() >> 4, col.getZ() >> 4)) return null;
                    int gy = world.getHighestBlockYAt(col.getX(), col.getZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    Material ground = world.getBlockAt(col.getX(), gy, col.getZ()).getType();
                    if (!natural(ground)) return null;
                    for (int dy = 1; dy <= 5; dy++) {
                        Block above = world.getBlockAt(col.getX(), gy + dy, col.getZ());
                        Material a = above.getType();
                        if (!a.isAir() && !Tag.REPLACEABLE.isTagged(a) && !Tag.FLOWERS.isTagged(a) && !Tag.LEAVES.isTagged(a)) return null;
                    }
                    min = Math.min(min, gy);
                    max = Math.max(max, gy);
                }
            }
            if (max - min > 2) return null;
            groundY = world.getHighestBlockYAt(m.at(0, 0, 0).getX(), m.at(0, 0, 0).getZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
            m.oy = groundY + 1;
            Location center = m.loc(4, 2, m.oy);
            // Away from what's built: workstations, chests, beds, the bell.
            if (center.distanceSquared(t.bell) < 14 * 14) return null;
            for (Town.Workplace o : t.workplaces.values()) if (o.loc.getWorld() == world && o.loc.distanceSquared(center) < 12 * 12) return null;
            for (Block b : t.containers(r)) if (b.getLocation().distanceSquared(center) < 12 * 12) return null;
            // Under it: natural down to the deepest level, few caves, no water or lava lakes, nothing old.
            int deepest = m.landingY(landingFor(m, -53)) - 1;
            int air = 0, fluid = 0;
            for (int tt = -2; tt <= 10; tt++) {
                for (int s = -1; s <= 5; s++) {
                    for (int y = m.oy - 2; y >= deepest; y--) {
                        Block b = m.at(tt, s, y);
                        Material mt = b.getType();
                        if (mt.isAir()) {
                            air++;
                            continue;
                        }
                        if (b.isLiquid()) {
                            fluid++;
                            continue;
                        }
                        if (mt == Material.BEDROCK) break;
                        if (!natural(mt) && !(!mt.isSolid() && !mt.isAir())) return null; // something built or made by the game
                    }
                }
            }
            if (air > 400 || fluid > 80) return null;
            return new int[]{m.ox, m.oy, m.oz, dir};
        }

        @Override
        public String label() {
            return "Looking for a mine spot";
        }
    }

    // ---- Keeping the land loaded ----

    static void ticket(Worker w, boolean on) {
        String who = "miner:" + w.body.getUniqueId();
        Tickets.releaseAll(who);
        if (!on || !w.plugin.getConfig().getBoolean("miner-keep-loaded", true)) return;
        World world = w.body.getWorld();
        int cx = w.body.getLocation().getBlockX() >> 4, cz = w.body.getLocation().getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) Tickets.hold(world, cx + dx, cz + dz, who);
    }

    static void releaseAll(SettlementPlugin plugin) {
        Tickets.releasePrefix("miner:");
    }

    // ---- A shift down the mine ----

    /**
     * Goes down (stairs, elevator or rails), digs (stairs, hub, tunnels) until the day ends, its pockets are full,
     * supplies run out or it's in danger; then goes back up. k = -1: just go up.
     */
    static final class Shift implements Activity {
        private final Worker w;
        private final Town t;
        private final Town.Workplace wp;
        private final Mine m;
        private int k;
        private final Deque<Location> hops = new ArrayDeque<>();
        private Activity ride; // an elevator or minecart ride
        private boolean leaving, up, retreat;
        private String why = "";
        private final Deque<Block> dig = new ArrayDeque<>();
        private Block cur;
        private ItemStack curPick;
        private int need, prog, hopFails, colFails, check, ticketAt, eatAt, leaveTries;
        private Runnable afterDig;
        private LivingEntity foe;
        private boolean scouted;
        private int clearSince = -1;

        Shift(Worker w, Town t, Town.Workplace wp, Mine m, int k) {
            this.w = w;
            this.t = t;
            this.wp = wp;
            this.m = m;
            this.k = k;
            if (k < 0) leave("");
        }

        private void leave(String reason) {
            if (leaving) return;
            leaving = true;
            why = reason;
            hops.clear();
            dig.clear();
            sendDamage(cur, -1);
            cur = null;
            afterDig = null; // a half-dug spot doesn't count as dug
            Escort.release(w);
            if (underground(w)) route(null);
        }

        @Override
        public boolean tick() {
            int now = Bukkit.getCurrentTick();
            if (Mine.get(wp) != m) { // the founder started a new mine: this one's forgotten
                ticket(w, false);
                return true;
            }
            if (ride == null && !w.body.isInsideVehicle() && now % 40 == 0 && fellOut()) return false;
            if (now >= ticketAt) {
                ticketAt = now + 40;
                ticket(w, underground(w));
            }
            if (ride != null) {
                if (ride.tick()) {
                    ride.stop();
                    ride = null;
                }
                return false;
            }
            if (++check % 5 == 0 && safety()) return false;
            if (foe != null) return fight();
            if (now >= eatAt && w.hurtBelow(w.plugin.settings().eatBelowPercent())) {
                eatAt = now + 80;
                if (!Combat.eat(w) && w.hurtBelow(40) && !leaving) leave("hungry and hurt");
            }
            if (!hops.isEmpty()) return walk();
            if (cur != null) return mineBlock();
            if (!dig.isEmpty()) {
                startBlock(dig.poll());
                return false;
            }
            if (afterDig != null) {
                Runnable r = afterDig;
                afterDig = null;
                r.run();
                return false;
            }
            if (leaving) {
                if (underground(w)) {
                    if (++leaveTries > 6 || !route(null)) {
                        ticket(w, false);
                        // No way up (the elevator gone?): back to the bell.
                        Location b = t.bell;
                        Block top = b.getWorld().getHighestBlockAt(b.getBlockX() + 1, b.getBlockZ() + 1);
                        w.body.getWorld().spawnParticle(Particle.POOF, w.body.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
                        w.body.teleport(top.getLocation().add(0.5, 1, 0.5));
                        TownLog.settler(w, "couldn't find the way up out of the mine and went back to the bell", NamedTextColor.YELLOW);
                        return true;
                    }
                    return false;
                }
                ticket(w, false);
                if (!why.isEmpty()) w.workStatus = why;
                return true;
            }
            plan();
            return false;
        }

        @Override
        public void stop() {
            ticket(w, false);
            Escort.release(w);
            if (ride != null) ride.stop();
            w.mover.stop();
            sendDamage(cur, -1);
        }

        @Override
        public String label() {
            if (ride != null) return ride.label();
            if (leaving) return "Going up from the mine";
            if (cur != null) return "Mining";
            if (!hops.isEmpty()) return "Going down the mine";
            return "Mining";
        }

        /** Fell out of the mine (into a cave below, say) and can't get back: back to the start of the mine. */
        private boolean fellOut() {
            Location l = w.body.getLocation();
            if (l.getWorld() != m.world || l.getY() >= m.surface - 4 || underground(w)) return false;
            double dx = l.getX() - m.ox, dz = l.getZ() - m.oz;
            int reach = size(wp) + 40;
            if (dx * dx + dz * dz > reach * reach) return false;
            hops.clear();
            dig.clear();
            sendDamage(cur, -1);
            cur = null;
            afterDig = null;
            w.mover.stop();
            w.body.getWorld().spawnParticle(Particle.POOF, l.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
            w.body.teleport(m.entrance());
            TownLog.settler(w, "fell out of the mine and climbed back to its start", NamedTextColor.YELLOW);
            return true;
        }

        // -- Danger --

        /** Monsters close: fight one, or run (too many, or badly hurt). True: busy with it. */
        private boolean safety() {
            if (!underground(w)) return false;
            List<LivingEntity> near = new ArrayList<>();
            for (Entity e : w.body.getNearbyEntities(10, 5, 10)) if (Combat.hostile(e) && w.body.hasLineOfSight(e)) near.add((LivingEntity) e);
            int now = Bukkit.getCurrentTick();
            boolean guarded = Escort.escorted(w);
            if (near.isEmpty()) {
                if (clearSince < 0) clearSince = now;
                if (guarded && scouted && now - clearSince > 200) Escort.release(w);
                return false;
            }
            clearSince = -1;
            near.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(w.body.getLocation())));
            if (guarded) { // the guards deal with them: wait, only hit back up close
                LivingEntity e = near.get(0);
                if (e.getLocation().distance(w.body.getLocation()) < 2.2 && !(e instanceof org.bukkit.entity.Creeper)) {
                    foe = e;
                    return true;
                }
                w.mover.stop();
                if (cur != null) {
                    sendDamage(cur, -1);
                    dig.addFirst(cur); // start this block again afterwards
                    cur = null;
                }
                return true;
            }
            if (!retreat && (near.size() >= 3 || w.hurtBelow(40))) {
                retreat = true;
                dangerUntil.put(w.body.getUniqueId(), now + 2400);
                Escort.foes.put(w.body.getUniqueId(), near.size());
                Bubbles.say(w.plugin, w.body, near.size() >= 3 ? "Too many of them! Out!" : "I need to get out of here!", 60);
                Asks.add(t, Asks.Kind.GUARDS, w.name + " fled from the mine: " + near.size() + " monster" + (near.size() == 1 ? "" : "s")
                        + " at y " + w.body.getLocation().getBlockY() + ". Guards will be asked to clear it.", w.body.getLocation(), w, "guards");
                Notify.send(t, t.owner, Notify.Kind.ATTACK, "⛏ " + w.name + " fled from the mine (monsters). Guards are needed down there.", NamedTextColor.GOLD);
                cur = null;
                dig.clear();
                leave("Fled from the mine: monsters");
                return false;
            }
            if (retreat) return false; // running: don't stop to fight
            near.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(w.body.getLocation())));
            LivingEntity e = near.get(0);
            if (e instanceof org.bukkit.entity.Creeper c && c.getLocation().distance(w.body.getLocation()) < 5) {
                // Back off along the tunnel toward the hub.
                Location back = k >= 0 ? m.hub(k) : m.entrance();
                w.mover.go(back, 1.5, w.plugin.settings().workSpeed() * 1.4);
                return true;
            }
            if (e.getLocation().distance(w.body.getLocation()) < 4) {
                foe = e;
                sendDamage(cur, -1);
                return true;
            }
            return false;
        }

        private boolean fight() {
            if (foe == null || foe.isDead() || !foe.isValid() || foe.getLocation().distance(w.body.getLocation()) > 8 || !w.body.hasLineOfSight(foe)) {
                foe = null;
                w.holdTool();
                return false;
            }
            ItemStack weapon = Combat.bestMelee(w);
            ItemStack p = pick(w, 0);
            if (weapon == null || (p != null && Combat.damage(p.getType()) > Combat.damage(weapon.getType()))) weapon = p;
            if (weapon == null) {
                foe = null;
                leave("No pickaxe to fight with");
                return false;
            }
            Combat.melee(w, foe, weapon);
            return false;
        }

        // -- Walking --

        private boolean walk() {
            Location to = hops.peek();
            if (to == null) return false;
            if (to.getWorld() != w.body.getWorld()) {
                hops.clear();
                return false;
            }
            if (w.body.getLocation().distanceSquared(to) < 1.6 * 1.6) {
                hops.poll();
                hopFails = 0;
                w.mover.stop();
                return false;
            }
            if (w.mover.moving()) return false;
            if (w.mover.arrived() && w.body.getLocation().distanceSquared(to) < 2.5 * 2.5) {
                hops.poll();
                return false;
            }
            if (!w.mover.go(to, 1.2, w.plugin.settings().workSpeed())) {
                if (++hopFails > 3) {
                    hops.poll(); // try the next one
                    hopFails = 0;
                    if (++colFails > 10) {
                        hops.clear();
                        leave("Can't find the way in the mine");
                    }
                }
            }
            return false;
        }

        /** Up and out: elevator from a level, rails, or the stairs (and the elevator from the start). */
        private boolean route(Location target) {
            hops.clear();
            Location here = w.body.getLocation();
            if (m.floors()) { // back to the elevator on this level, then up
                hops.addAll(m.upHops(here, size(wp)));
                Activity lift = Lift.toTop(w, m, retreat);
                if (lift == null) return !hops.isEmpty();
                if (hops.isEmpty()) ride = lift;
                else afterDig = () -> ride = lift;
                return true;
            }
            int at = m.where(here);
            Integer lvl = m.levelOf(here, size(wp));
            if (lvl != null) at = lvl;
            Activity r = Rails.up(w, t, wp, m, at, here);
            if (r != null) {
                ride = r;
                return true;
            }
            Activity e = Lift.up(w, m, at, here, retreat);
            if (e != null) {
                ride = e;
                return true;
            }
            hops.addAll(m.upHops(here, size(wp)));
            return true;
        }

        private Integer levelOf(Location l) {
            return m.levelOf(l, size(wp));
        }

        private void addLevelHops(int lk, Location from, int U, int V) {
            hops.addAll(m.levelHops(lk, from, U, V));
        }

        /** Down to landing (or elevator floor) i (-1: just to the start) from wherever it is. True: there (no travel). */
        private boolean downTo(int i) {
            Location here = w.body.getLocation();
            if (m.floors()) {
                Integer lvl = levelOf(here);
                if (lvl != null && lvl == i) return true;
                if (i < 0) return true;
                if (lvl != null) { // first back to the elevator on this level
                    hops.addAll(m.upHops(here, size(wp)));
                    afterDig = () -> ride = Lift.toFloor(w, m, i, retreat);
                    return false;
                }
                Activity r = Lift.toFloor(w, m, i, retreat);
                if (r != null) {
                    ride = r;
                    return false;
                }
                if (!near(m.entry(i), 2.5)) {
                    hops.add(m.entry(i));
                    return false;
                }
                return true;
            }
            int at = m.where(here);
            Integer lvl = levelOf(here);
            if (lvl != null) {
                addLevelHops(lvl, here, 0, 0);
                at = lvl;
            }
            if (at < 0 && w.body.getLocation().getY() > m.oy + 2 || at < 0 && !near(m.entrance(), 4)) {
                // From up top: rails or an elevator, else walk to the start.
                Activity r = i >= 0 ? Rails.down(w, t, wp, m, i) : null;
                if (r != null) {
                    ride = r;
                    return false;
                }
                Activity e = Lift.down(w, m, i, retreat);
                if (e != null) {
                    ride = e;
                    return false;
                }
                hops.add(m.entrance());
            }
            int dug = m.landingsDug();
            int to = Math.min(i, dug - 1);
            if (at <= to) for (int j = Math.max(0, at); j <= to; j++) hops.add(m.landing(j));
            else for (int j = at; j >= Math.max(0, to); j--) hops.add(m.landing(j));
            return hops.isEmpty();
        }

        // -- What to do next --

        private void plan() {
            colFails = Math.max(0, colFails - 1);
            if (w.isNight() && !w.plugin.settings().workAtNight()) {
                leave("Done for the day");
                return;
            }
            if (w.pockets.firstEmpty() < 0) {
                leave("Bringing up what I mined");
                return;
            }
            if (Rails.trySendCart(w, t, wp, m, k)) return;
            if (w.count(Material.TORCH) < 2 || logs() < 2) {
                leave("Needs torches and logs");
                return;
            }
            if (pick(w, 0) == null) {
                leave("Needs a pickaxe");
                return;
            }
            int target = pickLevel(w, t, m);
            if (target == -2) {
                leave("The mine is dug out");
                return;
            }
            if (target == WAIT_FLOOR) {
                leave("Waiting for an elevator floor (Ledger → Requests)");
                return;
            }
            if (target < 0) {
                leave("Nothing to mine (set storage targets)");
                return;
            }
            if (target != k) {
                k = target;
                scouted = false;
            }
            // 1. The stairs down to landing k (not in an elevator mine: the elevator goes down).
            List<Mine.Cell> stairs = m.floors() ? List.of() : m.stairs(k);
            if (m.stairsDone < stairs.size()) {
                Mine.Cell c = stairs.get(m.stairsDone);
                Location stand = m.stairsDone < 2 ? m.entrance() : cellLoc(stairs.get(Math.max(0, m.stairsDone - 2)));
                if (!near(stand, 2.6)) {
                    downTo(m.landingsDug() - 1);
                    hops.add(stand);
                    return;
                }
                for (int dy = 0; dy < 3; dy++) dig.add(m.at(c.t(), c.s(), c.y() + dy));
                floor(m.at(c.t(), c.s(), c.y() - 1));
                int done = m.stairsDone;
                afterDig = () -> {
                    if (m.stairsDone == done) m.stairsDone++;
                    Mine.changed();
                    if (c.landing() >= 0 && done + 1 < stairs.size() && stairs.get(done + 1).landing() != c.landing()) landingFrame(c.landing());
                    else if (c.landing() >= 0 && done + 1 >= stairs.size()) landingFrame(c.landing());
                };
                w.workStatus = "Digging the stairs down";
                return;
            }
            if (m.floors() && !frameFloor(m, k)) {
                // No room to step off there, or no rock next to it: that floor can't be used.
                Mine.Level dead = m.levels.computeIfAbsent(k, x -> new Mine.Level());
                dead.hub = true;
                dead.done = Integer.MAX_VALUE / 2;
                Mine.changed();
                Asks.add(t, Asks.Kind.BLOCKED, "I can't mine at the elevator's floor at y " + (k - Mine.FLOOR + 1)
                        + ": no room to step off next to the platform, or no rock around it.", m.entrance(), w, "floor" + k);
                return;
            }
            Mine.Level lv = m.levels.computeIfAbsent(k, x -> new Mine.Level());
            // Down there: look around first (monsters?).
            Integer lvlHere = levelOf(w.body.getLocation());
            if (!scouted && !(lvlHere != null && lvlHere == k) && !near(m.entry(k), 3)) {
                if (downTo(k) && lv.hub) addLevelHops(k, m.entry(k), 0, 0);
                return;
            }
            if (!scouted) {
                scouted = true;
                int foes = 0;
                for (Entity e : w.body.getNearbyEntities(16, 6, 16)) if (Combat.hostile(e) && w.body.hasLineOfSight(e)) foes++;
                if (foes > 0 && Escort.escorted(w)) {
                    scouted = false; // wait here while the guards clear it
                    w.workStatus = "Waiting while the guards clear the level";
                    return;
                }
                if (foes > 0) {
                    Escort.foes.put(w.body.getUniqueId(), foes);
                    retreat = true;
                    dangerUntil.put(w.body.getUniqueId(), Bukkit.getCurrentTick() + 2400);
                    Bubbles.say(w.plugin, w.body, "Monsters down here. I'll get the guards.", 60);
                    leave("Monsters in the mine: getting guards");
                    return;
                }
            }
            // 2. The hub.
            if (!lv.hub) {
                List<int[]> hub = hubCells();
                Block next = null;
                int[] nextCell = null;
                for (int[] h : hub) {
                    for (int dy = 0; dy < h[2]; dy++) {
                        Block b = m.levelAt(k, h[0], h[1], dy);
                        if ((!b.isPassable() || b.isLiquid()) && !(ours(b.getType()) && b.isPassable())) {
                            next = b;
                            nextCell = h;
                            break;
                        }
                    }
                    if (next != null) break;
                }
                if (next == null) {
                    lv.hub = true;
                    Mine.changed();
                    hubFrame();
                    return;
                }
                Location stand = standFor(nextCell[0], nextCell[1], true);
                if (!near(stand, 2.6)) {
                    hops.add(stand);
                    return;
                }
                for (int dy = 0; dy < nextCell[2]; dy++) dig.add(m.levelAt(k, nextCell[0], nextCell[1], dy));
                floor(m.levelAt(k, nextCell[0], nextCell[1], -1));
                w.workStatus = "Digging the hub";
                return;
            }
            // 3. The tunnels.
            List<Mine.Col> plan = m.plan(lv, size(wp));
            while (lv.done < plan.size()) {
                Mine.Col c = plan.get(lv.done);
                if (lv.blocked.contains(c.seg()) || (c.seg().startsWith("B") && (m.reserved(k, c.u(), c.v()) || cutOff(lv, c.v())))) {
                    if (c.seg().startsWith("B")) lv.blocked.add(c.seg());
                    lv.done++;
                    continue;
                }
                break;
            }
            if (lv.done >= plan.size()) {
                Mine.changed();
                return; // next plan() picks another level
            }
            Mine.Col c = plan.get(lv.done);
            Location stand = standFor(c.u(), c.v(), false);
            if (!near(stand, 2.6)) {
                boolean inLevel = levelOf(w.body.getLocation()) != null;
                if (!inLevel) {
                    if (!downTo(k)) return;
                    hops.add(m.hub(k));
                }
                int[] s = prevOf(c);
                addLevelHops(k, levelOf(w.body.getLocation()) != null ? w.body.getLocation() : m.hub(k), s[0], s[1]);
                if (++colFails > 4) { // can't get there: give up on this tunnel
                    block(lv, c);
                    colFails = 0;
                }
                return;
            }
            for (int dy = 0; dy < c.h(); dy++) dig.add(m.levelAt(k, c.u(), c.v(), dy));
            floor(m.levelAt(k, c.u(), c.v(), -1));
            int idx = lv.done;
            afterDig = () -> {
                if (lv.done == idx) lv.done++;
                Mine.changed();
                ores(c);
                frame(c);
            };
            w.workStatus = "Mining at level " + m.levelY(k);
        }

        private Location cellLoc(Mine.Cell c) {
            return m.loc(c.t(), c.s(), c.y());
        }

        private boolean near(Location l, double r) {
            return l.getWorld() == w.body.getWorld() && w.body.getLocation().distanceSquared(l) < r * r;
        }

        private int logs() {
            int n = 0;
            for (ItemStack it : w.pockets.getContents()) if (it != null && Tag.LOGS.isTagged(it.getType())) n += it.getAmount();
            return n;
        }

        /** Hub cells: the door, then the room (u, v, height). */
        private List<int[]> hubCells() {
            List<int[]> out = new ArrayList<>();
            for (int v = -1; v <= 1; v++) out.add(new int[]{-3, v, 3});
            for (int u = -2; u <= 2; u++) for (int v : new int[]{0, -1, 1, -2, 2}) out.add(new int[]{u, v, 4});
            return out;
        }

        /** Where to stand to dig (u, v): the dug spot before it. */
        private Location standFor(int u, int v, boolean hub) {
            if (hub) {
                if (u <= -2) return m.entry(k);
                return m.levelLoc(k, u - 1, 0);
            }
            int[] p = prevOf(new Mine.Col(u, v, 0, ""));
            return m.levelLoc(k, p[0], p[1]);
        }

        private int[] prevOf(Mine.Col c) {
            int u = c.u(), v = c.v();
            if (Math.abs(v) <= 1 && u >= 3) return new int[]{u - 1, 0};             // A corridor
            if (Math.abs(u) <= 1 && Math.abs(v) >= 3) return new int[]{0, v - Integer.signum(v)}; // L / R
            return new int[]{u - Integer.signum(u), v};                              // side tunnel
        }

        // -- Digging one block --

        /** The corridor this side tunnel starts from ends before it (blocked there). */
        private boolean cutOff(Mine.Level lv, int v) {
            String side = v < 0 ? "L@" : "R@";
            for (String bl : lv.blocked) {
                if (!bl.startsWith(side)) continue;
                try {
                    if (Math.abs(v) >= Integer.parseInt(bl.substring(2)) - 1) return true;
                } catch (NumberFormatException ignored) {
                }
            }
            return false;
        }

        /** Give up on the tunnel of this column (a corridor: remember where, its side tunnels beyond can't be reached). */
        private void block(Mine.Level lv, Mine.Col c) {
            lv.blocked.add(c.seg());
            if (c.seg().equals("L") || c.seg().equals("R")) lv.blocked.add(c.seg() + "@" + Math.abs(c.v()));
            Mine.changed();
        }

        private void startBlock(Block b) {
            Material type = b.getType();
            if (type.isAir()) {
                seal(b);
                return;
            }
            String s = structure(type);
            if (s == null) s = nearStructure(b);
            if (s != null) {
                stopHere(b, "Found " + s + " in the mine. I stopped digging there.", s);
                return;
            }
            if (type == Material.BEDROCK || type.getHardness() < 0) {
                stopHere(b, null, null);
                return;
            }
            if (!natural(type) && !ours(type) && type.isSolid()) {
                stopHere(b, "Ran into something built (" + FarmerJob.nice(type) + ") in the mine. I stopped digging there.", "built");
                return;
            }
            if (ours(type)) return; // leave our own frames and torches
            seal(b);
            if (b.isLiquid() || type.isAir() || !type.isSolid() && b.isPassable()) {
                b.setType(Material.AIR); // water, lava, plants
                return;
            }
            int lvl = need(type);
            curPick = pick(w, lvl);
            boolean weak = false;
            if (curPick == null) {
                // Its pickaxes aren't good enough: like you, it can still break it (slowly), but gets nothing from it.
                if (bestLevel(w) < lvl && !oreHoles.contains(b)) w.warnMissingTool(lvl >= 3 ? "a diamond pickaxe" : lvl == 2 ? "an iron pickaxe" : "a stone pickaxe");
                if (oreHoles.contains(b)) return; // an ore in the wall: just leave it
                curPick = pick(w, 0);
                if (curPick == null) return;
                weak = true;
            }
            cur = b;
            double hardness = Math.max(0.05, type.getHardness());
            double sp = Tag.MINEABLE_PICKAXE.isTagged(type) ? speed(curPick.getType()) : 1;
            need = Math.max(3, (int) Math.ceil((weak ? 100 : 30) * hardness / sp));
            prog = 0;
            w.body.getEquipment().setItemInMainHand(curPick.clone());
        }

        private boolean mineBlock() {
            Block b = cur;
            if (b.getType().isAir()) {
                cur = null;
                return false;
            }
            if (prog % 10 == 0) w.anim.look(b.getLocation().add(0.5, 0.5, 0.5));
            if (prog % 5 == 0) {
                w.body.swingMainHand();
                try {
                    b.getWorld().playSound(b.getLocation(), b.getBlockData().getSoundGroup().getHitSound(), SoundCategory.BLOCKS, 0.5f, 0.8f);
                } catch (RuntimeException ignored) {
                }
            }
            sendDamage(b, ++prog / (float) need);
            if (prog < need) return false;
            sendDamage(b, -1);
            breakBlock(b, curPick);
            cur = null;
            return false;
        }

        private void breakBlock(Block b, ItemStack pick) {
            Material type = b.getType();
            Collection<ItemStack> drops = pick != null ? b.getDrops(pick, w.body) : b.getDrops();
            BlockData data = b.getBlockData();
            try {
                b.getWorld().playSound(b.getLocation(), data.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1f, 0.8f);
            } catch (RuntimeException ignored) {
            }
            b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, data);
            b.setType(Material.AIR);
            if (pick != null && Tag.MINEABLE_PICKAXE.isTagged(type)) Work.wearSimilar(w, pick);
            if (oreHoles.remove(b) && space(b.getRelative(BlockFace.UP))) fill(b); // a hole in the tunnel floor: filled
            List<ItemStack> keep = new ArrayList<>();
            for (ItemStack d : drops) {
                Material dm = d.getType();
                boolean filler = dm == Material.COBBLESTONE || dm == Material.COBBLED_DEEPSLATE;
                boolean want = wanted(w, t, dm) || (filler && fillerCount() < 64) || (!FILLER.contains(dm) && ore(type));
                if (want) keep.add(d);
            }
            w.give(keep, w.body.getLocation());
            t.countsChanged();
        }

        private int fillerCount() {
            int n = 0;
            for (ItemStack it : w.pockets.getContents()) if (it != null && FILLER.contains(it.getType())) n += it.getAmount();
            return n;
        }

        /** The cracks on the block, for everyone close by (-1: gone). */
        private void sendDamage(Block b, float p) {
            if (b == null) return;
            for (Player pl : b.getWorld().getPlayers()) {
                if (pl.getLocation().distanceSquared(b.getLocation()) > 48 * 48) continue;
                try {
                    pl.sendBlockDamage(b.getLocation(), p < 0 ? 0f : Math.min(1f, p), w.body.getEntityId());
                } catch (RuntimeException ignored) {
                }
            }
        }

        /** Something old next to the block (a spawner, an ancient city...)? */
        private String nearStructure(Block b) {
            for (BlockFace f : FACES) {
                String s = structure(b.getRelative(f).getType());
                if (s != null) return s;
            }
            return null;
        }

        /** Stop this tunnel here (and tell the founder what it found). */
        private void stopHere(Block b, String text, String what) {
            if (oreHoles.contains(b)) { // an ore in the wall next to something: leave that ore, go on
                if (text != null) Asks.add(t, what != null && !what.equals("built") ? Asks.Kind.FOUND : Asks.Kind.BLOCKED, text, b.getLocation(), w,
                        (what == null ? "x" : what) + "@" + (b.getX() >> 4) + "," + (b.getZ() >> 4));
                return;
            }
            dig.clear();
            afterDig = null;
            cur = null;
            if (text != null) Asks.add(t, what != null && !what.equals("built") ? Asks.Kind.FOUND : Asks.Kind.BLOCKED, text, b.getLocation(), w,
                    (what == null ? "x" : what) + "@" + (b.getX() >> 4) + "," + (b.getZ() >> 4));
            Integer lvl = levelOf(w.body.getLocation());
            Mine.Level lv = lvl == null ? null : m.levels.get(lvl);
            if (lv != null && lv.hub) {
                List<Mine.Col> plan = m.plan(lv, size(wp));
                if (lv.done < plan.size()) block(lv, plan.get(lv.done));
            } else {
                // The stairs or the hub can't go on: the mine waits for the founder.
                blockedUntil.put(Keys.ser(wp.loc), Bukkit.getCurrentTick() + 12000);
                if (text == null) Asks.add(t, Asks.Kind.BLOCKED, "The mine's stairs can't go deeper here (bedrock, or a block my pickaxes can't dig).",
                        b.getLocation(), w, "stairs");
                leave("The mine can't go on");
            }
            Mine.changed();
        }

        private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

        /** Before digging: close caves, water and lava next to it, and sand or gravel above. */
        private void seal(Block b) {
            for (BlockFace f : FACES) {
                Block n = b.getRelative(f);
                Material nt = n.getType();
                boolean gravity = f == BlockFace.UP && nt.hasGravity();
                boolean open = n.isLiquid() || nt.isAir();
                if (!(gravity || open) || space(n)) continue;
                if (nt.isAir() && playerRoute(n)) continue; // a player's way through (an elevator shaft, a tunnel with rails): leave it open
                fill(n);
            }
        }

        private List<int[]> shafts;

        /** Air that's part of something built: in an elevator shaft, or next to rails, ladders, chains, planks... */
        private boolean playerRoute(Block n) {
            if (shafts == null) {
                shafts = new ArrayList<>();
                for (String id : Lift.near(m.world, m.ox, m.oz, size(wp) + 40)) {
                    int[] p = Lift.platform(id);
                    if (p != null) shafts.add(new int[]{p[0] - 1, p[1] - 1, p[2] + 1, p[3] + 1});
                }
            }
            for (int[] s : shafts) if (n.getX() >= s[0] && n.getX() <= s[2] && n.getZ() >= s[1] && n.getZ() <= s[3]) return true;
            for (BlockFace f : FACES) {
                Material x = n.getRelative(f).getType();
                if (x.isAir() || n.getRelative(f).isLiquid()) continue;
                if (!natural(x) && !Tag.LOGS.isTagged(x) && x != Material.TORCH && x != Material.WALL_TORCH) return true;
            }
            return false;
        }

        /** The floor under a dug spot: something to stand on. */
        private void floor(Block below) {
            Material bt = below.getType();
            if (bt.isSolid() && !bt.hasGravity()) return;
            if (bt.hasGravity() && below.getRelative(BlockFace.DOWN).getType().isSolid()) return;
            if (space(below) || ours(bt)) return;
            fill(below);
        }

        private void fill(Block n) {
            for (int i = 0; i < w.pockets.getSize(); i++) {
                ItemStack it = w.pockets.getItem(i);
                if (it == null || !FILLER.contains(it.getType())) continue;
                n.setType(it.getType());
                it.setAmount(it.getAmount() - 1);
                w.pockets.setItem(i, it.getAmount() > 0 ? it : null);
                n.getWorld().playSound(n.getLocation(), "minecraft:block.stone.place", SoundCategory.BLOCKS, 0.6f, 1f);
                return;
            }
            // None to spare: still never let lava or water in, or leave a hole to fall through (costs nothing this once).
            n.setType(n.getY() < 0 ? Material.COBBLED_DEEPSLATE : Material.COBBLESTONE);
        }

        /** Part of the mine's own open space (stairs, hub, tunnels)? */
        private boolean space(Block b) {
            if (b.getWorld() != m.world) return false;
            int y = b.getY();
            if (!m.floors()) {
                int dx = b.getX() - m.ox, dz = b.getZ() - m.oz;
                int tt = dx * m.fx() + dz * m.fz(), s = dx * m.sx() + dz * m.sz();
                if (y >= m.oy - 1 && Mine.inStairs(tt, s)) return true; // the entrance
                if (tt >= -2 && tt <= 9 && s >= 0 && s <= 4) {
                    for (Mine.Cell c : m.stairs(Math.max(k, 0) + 1)) if (c.t() == tt && c.s() == s && y >= c.y() && y <= c.y() + 2) return true;
                }
            }
            int size = size(wp);
            for (int lk : m.levels.keySet()) {
                if (Mine.isFloor(lk) && !m.levels.get(lk).framed) continue;
                int fy = m.levelY(lk);
                if (y < fy || y > fy + 3) continue;
                int[] uvd = m.uv(lk, b);
                int u = uvd[0], v = uvd[1], dy = uvd[2];
                if (Math.abs(u) <= 2 && Math.abs(v) <= 2) return true;                               // hub
                if (u == -3 && Math.abs(v) <= 1 && dy <= 2) return true;                             // door
                if (Mine.isFloor(lk) && u == -4 && Math.abs(v) <= 1 && dy <= 2) return true;         // where it steps off
                if (u >= 3 && u <= size && Math.abs(v) <= 1 && dy <= 2) return true;                 // A
                if (Math.abs(u) <= 1 && Math.abs(v) >= 3 && Math.abs(v) <= size && dy <= 2) return true; // L / R
                if (v != 0 && v % 4 == 0 && Math.abs(u) >= 2 && Math.abs(u) <= size && dy <= 1) return true; // side tunnels
            }
            return false;
        }

        /** Ores in the walls around a dug column: mine the ones that are wanted (the vein, within reach). */
        private void ores(Mine.Col c) {
            Set<Block> seen = new HashSet<>();
            Deque<Block> todo = new ArrayDeque<>();
            for (int dy = -1; dy <= c.h(); dy++) {
                Block b = m.levelAt(k, c.u(), c.v(), dy);
                for (BlockFace f : FACES) todo.add(b.getRelative(f));
            }
            List<Block> found = new ArrayList<>();
            while (!todo.isEmpty() && found.size() < 16) {
                Block b = todo.poll();
                if (!seen.add(b) || !ore(b.getType())) continue;
                if (b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(w.body.getEyeLocation()) > 4.5 * 4.5) continue;
                ItemStack p = pick(w, need(b.getType()));
                if (p == null) continue;
                boolean want = false;
                for (ItemStack d : b.getDrops(p, w.body)) if (wanted(w, t, d.getType())) want = true;
                if (!want) continue;
                found.add(b);
                for (BlockFace f : FACES) todo.add(b.getRelative(f));
            }
            oreHoles.addAll(found);
            dig.addAll(found);
        }

        /** Ores dug out of the walls (not part of the tunnel). */
        private final Set<Block> oreHoles = new HashSet<>();

        // -- Frames and torches --

        /** World direction of a step in mine coordinates (t, s): for the stairs' landings. */
        private BlockFace face(int ddt, int dds) {
            return faceXZ(m.fx() * ddt + m.sx() * dds, m.fz() * ddt + m.sz() * dds);
        }

        /** World direction of a step in level coordinates (u, v). */
        private BlockFace faceUV(int du, int dv) {
            int[] f = m.frame(k);
            return faceXZ(f[2] * du + f[4] * dv, f[3] * du + f[5] * dv);
        }

        private static BlockFace faceXZ(int x, int z) {
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                if (f.getModX() == x && f.getModZ() == z) return f;
            }
            return BlockFace.NORTH;
        }

        private Axis axisS() {
            return m.sx() != 0 ? Axis.X : Axis.Z;
        }

        private Axis axisU() {
            return m.frame(k)[2] != 0 ? Axis.X : Axis.Z;
        }

        private Axis axisV() {
            return m.frame(k)[4] != 0 ? Axis.X : Axis.Z;
        }

        private Material logType() {
            for (ItemStack it : w.pockets.getContents()) if (it != null && Tag.LOGS.isTagged(it.getType())) return it.getType();
            return null;
        }

        private boolean log(Block b, Axis axis) {
            Material lt = logType();
            if (lt == null) return false;
            Material cur = b.getType();
            if (!(natural(cur) || cur.isAir() || b.isLiquid()) || cur == Material.BEDROCK) return false;
            if (!w.take(lt, 1)) return false;
            b.setType(lt, false);
            BlockData d = b.getBlockData();
            if (d instanceof Orientable o) {
                o.setAxis(axis);
                b.setBlockData(o, false);
            }
            return true;
        }

        private void torch(Block at, BlockFace facing) {
            if (!at.getType().isAir() || w.count(Material.TORCH) == 0) return;
            if (!w.take(Material.TORCH, 1)) return;
            at.setType(Material.WALL_TORCH, false);
            if (at.getBlockData() instanceof Directional d) {
                d.setFacing(facing);
                at.setBlockData(d, false);
            }
            at.getWorld().playSound(at.getLocation(), "minecraft:block.wood.place", SoundCategory.BLOCKS, 0.6f, 1f);
        }

        /** Studs in both side walls, a beam across the ceiling, a torch on each stud (stairs landings, in t/s). */
        private void frameAcross(int[] studA, int[] studB, int y, int height, Axis beamAxis, boolean alongS) {
            for (int dy = 0; dy < height; dy++) {
                log(m.at(studA[0], studA[1], y + dy), Axis.Y);
                log(m.at(studB[0], studB[1], y + dy), Axis.Y);
            }
            int from = alongS ? Math.min(studA[1], studB[1]) : Math.min(studA[0], studB[0]);
            int to = alongS ? Math.max(studA[1], studB[1]) : Math.max(studA[0], studB[0]);
            for (int i = from; i <= to; i++) {
                if (alongS) log(m.at(studA[0], i, y + height), beamAxis);
                else log(m.at(i, studA[1], y + height), beamAxis);
            }
            int dtA = alongS ? 0 : Integer.signum(studB[0] - studA[0]), dsA = alongS ? Integer.signum(studB[1] - studA[1]) : 0;
            Block ta = m.at(studA[0] + dtA, studA[1] + dsA, y + 1);
            Block tb = m.at(studB[0] - dtA, studB[1] - dsA, y + 1);
            if (Tag.LOGS.isTagged(m.at(studA[0], studA[1], y + 1).getType())) torch(ta, face(dtA, dsA));
            if (Tag.LOGS.isTagged(m.at(studB[0], studB[1], y + 1).getType())) torch(tb, face(-dtA, -dsA));
        }

        /** The same on a level, in u/v: studs at (ua, va) and (ub, vb), one row apart along u or v. */
        private void frameUV(int ua, int va, int ub, int vb, int height) {
            for (int dy = 0; dy < height; dy++) {
                log(m.levelAt(k, ua, va, dy), Axis.Y);
                log(m.levelAt(k, ub, vb, dy), Axis.Y);
            }
            boolean alongV = ua == ub;
            if (alongV) for (int v = Math.min(va, vb); v <= Math.max(va, vb); v++) log(m.levelAt(k, ua, v, height), axisV());
            else for (int u = Math.min(ua, ub); u <= Math.max(ua, ub); u++) log(m.levelAt(k, u, va, height), axisU());
            int du = alongV ? 0 : Integer.signum(ub - ua), dv = alongV ? Integer.signum(vb - va) : 0;
            if (Tag.LOGS.isTagged(m.levelAt(k, ua, va, 1).getType())) torch(m.levelAt(k, ua + du, va + dv, 1), faceUV(du, dv));
            if (Tag.LOGS.isTagged(m.levelAt(k, ub, vb, 1).getType())) torch(m.levelAt(k, ub - du, vb - dv, 1), faceUV(-du, -dv));
        }

        private void landingFrame(int lk) {
            int row = m.landingRow(lk), y = m.landingY(lk);
            frameAcross(new int[]{row, -1}, new int[]{row, 5}, y, 3, axisS(), true);
        }

        private void hubFrame() {
            // On the far wall of the hub (where the main corridor leaves): studs, a beam, torches facing back into the room.
            for (int v : new int[]{-2, 2}) for (int dy = 0; dy < 3; dy++) log(m.levelAt(k, 3, v, dy), Axis.Y);
            for (int v = -2; v <= 2; v++) log(m.levelAt(k, 3, v, 3), axisV());
            for (int v : new int[]{-2, 2}) torch(m.levelAt(k, 2, v, 1), faceUV(-1, 0));
            if (!Mine.isFloor(k)) landingFrame(k); // the landing side too
            else frameUV(-3, -2, -3, 2, 3);       // over the door from the elevator
        }

        /** A frame where the plan says so (every 8 blocks, between the side tunnels). */
        private void frame(Mine.Col c) {
            int u = c.u(), v = c.v();
            if (c.seg().equals("A") && v == 1 && u >= 10 && u % 8 == 2) {
                frameUV(u, -2, u, 2, 3);
            } else if ((c.seg().equals("L") || c.seg().equals("R")) && u == 1 && Math.abs(v) >= 10 && Math.abs(v) % 8 == 2) {
                frameUV(-2, v, 2, v, 3);
            } else if (c.seg().startsWith("B") && Math.abs(u) % 8 == 4) {
                // Side tunnel: one stud in the wall with a torch.
                if (log(m.levelAt(k, u, v + 1, 0), Axis.Y) & log(m.levelAt(k, u, v + 1, 1), Axis.Y) || Tag.LOGS.isTagged(m.levelAt(k, u, v + 1, 1).getType())) {
                    torch(m.levelAt(k, u, v, 1), faceUV(0, -1));
                }
            }
        }
    }
}
