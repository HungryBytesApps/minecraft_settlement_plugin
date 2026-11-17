package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * A settlement around a bell: its chests (and which ones settlers may use), its workplaces
 * (job blocks like composters) and the town-wide target amounts.
 */
final class Town {
    /** A job block settlers work at. */
    static final class Workplace {
        final Location loc;
        int radius;
        boolean enabled = true;
        /** Everyone who works here (no limit). */
        final Set<UUID> workers = new LinkedHashSet<>();
        /** Herder: how many adults of each kind to keep. */
        int herd = 6;
        /** Herder: adults to keep per kind of animal (EntityType name, 2-20): breeds up to it, slaughters the extra. Off (checkbox): leave that kind alone. */
        final Map<String, Integer> herds = new HashMap<>();

        /** Adults to keep of this kind (2-20), or -1 when the herder leaves this kind alone. */
        int herdSize(org.bukkit.entity.EntityType type) {
            return herding(type) ? herdSetting(type) : -1;
        }

        /** The slider value (2-20), also while switched off. */
        int herdSetting(org.bukkit.entity.EntityType type) {
            int v = herds.getOrDefault(type.name(), herd);
            return Math.max(2, Math.min(20, v));
        }

        /** The checkbox: does the herder look after this kind at all? */
        boolean herding(org.bukkit.entity.EntityType type) {
            return options.getOrDefault("HERD_" + type.name(), true);
        }
        /** On/off settings (furnace categories): key → on. */
        final Map<String, Boolean> options = new HashMap<>();

        boolean isOn(String key) {
            Boolean v = options.get(key);
            if (v != null) return v;
            return !key.equals("BUILDING") && !key.equals("OTHER") && !key.equals("OUTSIDE") && !key.equals("BONEMEAL")
                    && !key.equals("COMPOST") && !key.equals("COMPOST_ALL"); // REPLANT: on by default // furnace: food and ores; lumberjack: inside; no bone meal
        }

        Workplace(Location loc, int radius) {
            this.loc = loc;
            this.radius = radius;
        }

        private Worker.Job jobSeen;
        private int jobSeenAt;

        /** The job this block stands for (looked at once a second: it's asked for all the time). */
        Worker.Job job() {
            int now = Bukkit.getCurrentTick();
            if (jobSeen == null || now - jobSeenAt >= 20 || now < jobSeenAt) {
                jobSeen = Worker.Job.of(loc.getBlock());
                jobSeenAt = now;
            }
            return jobSeen;
        }
    }

    final String id;
    UUID owner;
    /** Players who help run the settlement (everything except removing settlers, disbanding and members). */
    final Set<UUID> members = new LinkedHashSet<>();
    /** What went wrong lately (in the ledger). */
    final TownLog log = new TownLog();

    /** Owner, member or admin: may use the bell, ledger, workstations, settler menus. */
    boolean canManage(org.bukkit.entity.Player p) {
        return p.getUniqueId().equals(owner) || members.contains(p.getUniqueId()) || p.hasPermission("settlement.admin");
    }

    /** Only the founder (or an admin): dismiss settlers, disband, manage members. */
    boolean isOwner(org.bukkit.entity.Player p) {
        return p.getUniqueId().equals(owner) || p.hasPermission("settlement.admin");
    }

    /** Owner and members. */
    List<UUID> people() {
        List<UUID> l = new ArrayList<>();
        l.add(owner);
        l.addAll(members);
        return l;
    }
    String name;
    Location bell;
    boolean chestDefault;
    /** Per-chest exceptions to the default: {store, take}. Key = Keys.ser(location). */
    final Map<String, boolean[]> chestRules = new HashMap<>();
    final Map<Material, Integer> targets = new LinkedHashMap<>();
    final Map<String, Workplace> workplaces = new LinkedHashMap<>();
    /** What non-guards do when hostile mobs get in. */
    enum Reaction {
        TAKE_COVER("Take cover"), CALL_TO_ARMS("Call to arms");

        final String label;

        Reaction(String label) {
            this.label = label;
        }
    }

    Reaction reaction = Reaction.TAKE_COVER;
    /** What settlers may take from storage to heal. */
    enum Consumption {
        OFF("Off"), FOOD("Food only"), ALL("Food & Potions");

        final String label;

        Consumption(String label) {
            this.label = label;
        }

        Consumption next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    Consumption consumption = Consumption.FOOD;
    /** Free settlers take empty workplaces by themselves. */
    boolean autoJobs = false;

    boolean mayEat() {
        return consumption != Consumption.OFF;
    }

    boolean guardPotions() {
        return consumption == Consumption.ALL;
    }

    // Runtime (not saved): the alarm.
    boolean alarm;
    final List<org.bukkit.entity.LivingEntity> hostiles = new ArrayList<>();
    /** Monsters nobody can get to (in a pit, deep water): they don't count as an attack (monster → until tick). */
    final Map<UUID, Integer> unreachable = new HashMap<>();
    /** Where each monster was first seen standing (to notice ones that are stuck and can't get anywhere). */
    final Map<UUID, Location> stuckSpot = new HashMap<>();
    final Map<UUID, Integer> stuckSince = new HashMap<>();
    int quietTicks;

    /** All settlers of this town (also the ones in unloaded chunks): id → name. */
    final Map<UUID, String> settlers = new LinkedHashMap<>();
    /** Where each settler was last seen (for settlers that are far away, in unloaded land). */
    final Map<UUID, Location> lastSeen = new HashMap<>();

    private List<Block> containers = List.of();
    private int containersAt = -10000;
    private Map<Material, Integer> counts = Map.of();
    private int countsAt = -10000;

    Town(String id, UUID owner, String name, Location bell, boolean chestDefault) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.bell = bell;
        this.chestDefault = chestDefault;
    }

    World world() {
        return bell.getWorld();
    }

    // ---- Shape: the main square around the bell plus expansion squares ----

    /** The config's settlement-radius. Callers pass it (± a margin) to contains(); only the margin counts. */
    static int BASE = 48;

    /** An expansion: an extra square around its own bell. */
    static final class Expansion {
        final Location bell;
        int radius;

        Expansion(Location bell, int radius) {
            this.bell = bell;
            this.radius = radius;
        }
    }

    static int MAIN_MIN = 16, MAIN_MAX = 64, EXP_MIN = 4, EXP_MAX = 24;

    /** Half-width of the main square. */
    int radius = BASE;
    final List<Expansion> expansions = new ArrayList<>();

    /** All squares: {centre x, centre z, radius}. */
    List<int[]> squares() {
        List<int[]> l = new ArrayList<>();
        l.add(new int[]{bell.getBlockX(), bell.getBlockZ(), radius});
        for (Expansion e : expansions) l.add(new int[]{e.bell.getBlockX(), e.bell.getBlockZ(), e.radius});
        return l;
    }

    /** Inside the settlement (or within {@code margin} blocks of it; negative = that far inside). */
    boolean inside(double x, double z, double margin) {
        for (int[] q : squares()) {
            if (Math.abs(x - (q[0] + 0.5)) <= q[2] + 0.5 + margin && Math.abs(z - (q[1] + 0.5)) <= q[2] + 0.5 + margin) return true;
        }
        return false;
    }

    /** {@code radius} is the config radius ± a margin, as everywhere in the plugin. */
    boolean contains(Location l, int radius) {
        if (l == null || l.getWorld() != bell.getWorld() || Math.abs(l.getY() - bell.getY()) > 64) return false;
        return inside(l.getX(), l.getZ(), radius - BASE);
    }

    /** Within {@code dist} blocks of the main bell (to every side) and in the settlement: where free time happens. */
    boolean nearBell(Location l, double dist) {
        if (l == null || l.getWorld() != bell.getWorld()) return false;
        return Math.abs(l.getX() - (bell.getBlockX() + 0.5)) <= dist + 0.5 && Math.abs(l.getZ() - (bell.getBlockZ() + 0.5)) <= dist + 0.5
                && inside(l.getX(), l.getZ(), 2);
    }

    /** Bounding box of the whole settlement plus a margin: {minX, maxX, minZ, maxZ} (block coordinates). */
    int[] box(int margin) {
        int[] b = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int[] q : squares()) {
            b[0] = Math.min(b[0], q[0] - q[2] - margin);
            b[1] = Math.max(b[1], q[0] + q[2] + margin);
            b[2] = Math.min(b[2], q[1] - q[2] - margin);
            b[3] = Math.max(b[3], q[1] + q[2] + margin);
        }
        return b;
    }

    /** Does this chunk overlap the settlement? */
    boolean touchesChunk(int cx, int cz) {
        int x0 = cx << 4, z0 = cz << 4;
        for (int[] q : squares()) {
            if (x0 + 15 >= q[0] - q[2] && x0 <= q[0] + q[2] && z0 + 15 >= q[1] - q[2] && z0 <= q[1] + q[2]) return true;
        }
        return false;
    }

    /** The expansion whose bell this is. */
    Expansion expansionAt(Block b) {
        for (Expansion e : expansions) {
            if (e.bell.getWorld() == b.getWorld() && e.bell.getBlockX() == b.getX() && e.bell.getBlockY() == b.getY() && e.bell.getBlockZ() == b.getZ()) return e;
        }
        return null;
    }

    // ---- Chest rules ----

    static boolean isStorage(Block b) {
        BlockState s = b.getState(false);
        return s instanceof Chest || s instanceof Barrel;
    }

    boolean[] rule(Block b) {
        if (PrivateChests.owner(b) != null) return new boolean[]{false, false}; // private: settlers never touch it
        if (fishersBarrel(b)) return new boolean[]{false, true}; // the catch can be taken, nothing put in
        boolean[] r = chestRules.get(Keys.ser(b.getLocation()));
        return r != null ? r : new boolean[]{chestDefault, chestDefault};
    }

    /** A barrel a fisher works at: only for the fisher. (A barrel by water without a fisher is a normal barrel.) */
    boolean fishersBarrel(Block b) {
        if (b.getType() != Material.BARREL || workplaces.isEmpty()) return false;
        Workplace wp = workplaces.get(Keys.ser(b.getLocation()));
        return wp != null && !wp.workers.isEmpty();
    }

    // ---- Chest filters: which items belong in a chest ----

    enum FilterMode {
        OFF("Off"), PREFER("Prefer"), ONLY("Only");

        final String label;

        FilterMode(String label) {
            this.label = label;
        }

        FilterMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** A chest's filter: its items go here first (Prefer) or only these may go in (Only). */
    static final class Filter {
        FilterMode mode = FilterMode.OFF;
        final Set<Material> items = EnumSet.noneOf(Material.class);
    }

    /** Brewer: potion targets, "FORM:TYPE" → how many to keep (none = don't brew). */
    final Map<String, Integer> potionTargets = new HashMap<>();

    /** Settlers asking each other for things (not saved: a restart forgets them). */
    final List<Requests.Request> requests = new ArrayList<>();

    /** Per chest (both halves of a double chest share one). */
    final Map<String, Filter> chestFilters = new HashMap<>();

    Filter filter(Block b) {
        if (chestFilters.isEmpty()) return new Filter();
        for (Block part : halves(b)) {
            Filter f = chestFilters.get(Keys.ser(part.getLocation()));
            if (f != null) return f;
        }
        return new Filter();
    }

    /** The chest's filter to change (created and shared by both halves). */
    Filter editFilter(Block b) {
        Filter f = null;
        for (Block part : halves(b)) if (f == null) f = chestFilters.get(Keys.ser(part.getLocation()));
        if (f == null) f = new Filter();
        for (Block part : halves(b)) chestFilters.put(Keys.ser(part.getLocation()), f); // one shared filter
        return f;
    }

    boolean hasOwnRule(Block b) {
        return chestRules.containsKey(Keys.ser(b.getLocation()));
    }

    /** Set (or with null: clear) the rule for a chest, and for the other half of a double chest. */
    void setRule(Block b, boolean[] rule) {
        for (Block part : halves(b)) {
            if (rule == null) chestRules.remove(Keys.ser(part.getLocation()));
            else chestRules.put(Keys.ser(part.getLocation()), rule.clone());
        }
        invalidate();
    }

    /** Both halves of a double chest (this one first), or just this block. Read from the block itself: quick. */
    static List<Block> halves(Block b) {
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Chest data
                && data.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
            // A LEFT half has its other half clockwise of the way it faces, a RIGHT half the other way.
            org.bukkit.block.BlockFace side = switch (data.getFacing()) {
                case NORTH -> org.bukkit.block.BlockFace.EAST;
                case EAST -> org.bukkit.block.BlockFace.SOUTH;
                case SOUTH -> org.bukkit.block.BlockFace.WEST;
                default -> org.bukkit.block.BlockFace.NORTH;
            };
            if (data.getType() == org.bukkit.block.data.type.Chest.Type.RIGHT) side = side.getOppositeFace();
            Block other = b.getRelative(side);
            if (other.getType() == b.getType()) return List.of(b, other);
        }
        return List.of(b);
    }

    void allChests(boolean on) {
        chestDefault = on;
        chestRules.clear();
        invalidate();
    }

    void invalidate() {
        containersAt = -10000;
        countsAt = -10000;
    }

    // ---- Storage ----

    /** The inventory of just this block (one half of a double chest). */
    static Inventory inventory(Block b) {
        BlockState s = b.getState(false);
        if (s instanceof Chest c) return c.getBlockInventory();
        if (s instanceof Barrel br) return br.getInventory();
        return null;
    }

    /** All chests and barrels in town (cached for 10 seconds). */
    List<Block> containers(int radius) {
        int now = Bukkit.getCurrentTick();
        if (now - containersAt < 200) return containers;
        containersAt = now;
        List<Block> out = new ArrayList<>();
        World w = world();
        if (w != null) {
            int[] bx = box(radius - BASE);
            for (int x = bx[0] >> 4; x <= bx[1] >> 4; x++) {
                for (int z = bx[2] >> 4; z <= bx[3] >> 4; z++) {
                    if (!w.isChunkLoaded(x, z)) continue;
                    Chunk chunk = w.getChunkAt(x, z);
                    for (BlockState s : chunk.getTileEntities()) {
                        if ((s instanceof Chest || s instanceof Barrel) && contains(s.getLocation(), radius)) out.add(s.getBlock());
                    }
                }
            }
        }
        containers = out;
        return out;
    }

    /** How many of each item are in the chests settlers may use (cached for 2 seconds). */
    /** Everything in storage (a copy). */
    Map<Material, Integer> stock(int radius) {
        count(Material.AIR, radius); // refresh if old
        return new HashMap<>(counts);
    }

    int count(Material m, int radius) {
        int now = Bukkit.getCurrentTick();
        if (now - countsAt >= 40) {
            countsAt = now;
            Map<Material, Integer> c = new HashMap<>();
            for (Block b : containers(radius)) {
                boolean[] r = rule(b);
                if (!r[0] && !r[1]) continue;
                Inventory inv = inventory(b);
                if (inv == null) continue;
                for (ItemStack it : inv.getContents()) {
                    if (it != null && !it.getType().isAir()) c.merge(it.getType(), it.getAmount(), Integer::sum);
                }
            }
            counts = c;
        }
        return counts.getOrDefault(m, 0);
    }

    void countsChanged() {
        countsAt = -10000;
    }

    /** Below the town's target for this item (or no target set)? */
    boolean wants(Material item, int radius) {
        Integer target = targets.get(item); // none set = no limit; 0 = don't want any
        return target == null || count(item, radius) < target;
    }

    /** Where to put this item: an allowed chest that already has it, else the nearest allowed one with space. */
    /**
     * Where to put this item: first a chest whose filter lists it, then a chest that already holds it, then any.
     * A chest with filter "Only" takes nothing else.
     */
    Block storeChest(Material m, Location near, int radius) {
        Block filtered = null, withItem = null, any = null;
        double dFil = Double.MAX_VALUE, dWith = Double.MAX_VALUE, dAny = Double.MAX_VALUE;
        for (Block b : containers(radius)) {
            if (!rule(b)[0]) continue;
            Filter f = filter(b);
            boolean listed = f.mode != FilterMode.OFF && f.items.contains(m);
            if (f.mode == FilterMode.ONLY && !listed) continue;
            Inventory inv = inventory(b);
            if (inv == null || !hasSpace(inv, m)) continue;
            double d = b.getLocation().distanceSquared(near);
            if (listed) {
                if (d < dFil) {
                    dFil = d;
                    filtered = b;
                }
            } else if (inv.contains(m)) {
                if (d < dWith) {
                    dWith = d;
                    withItem = b;
                }
            } else if (d < dAny) {
                dAny = d;
                any = b;
            }
        }
        return filtered != null ? filtered : withItem != null ? withItem : any;
    }

    /** Nearest allowed chest to take this item from. */
    Block takeChest(Material m, Location near, int radius) {
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (Block b : containers(radius)) {
            if (!rule(b)[1]) continue;
            Inventory inv = inventory(b);
            if (inv == null || !inv.contains(m)) continue;
            double d = b.getLocation().distanceSquared(near);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    static boolean hasSpace(Inventory inv, Material m) {
        if (inv.firstEmpty() != -1) return true;
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.getType() == m && it.getAmount() < it.getMaxStackSize()) return true;
        }
        return false;
    }

    // ---- Workplaces ----

    Workplace workplace(Location l) {
        return workplaces.get(Keys.ser(l));
    }

    Workplace addWorkplace(Location l, int radius) {
        return workplaces.computeIfAbsent(Keys.ser(l), k -> new Workplace(l.getBlock().getLocation(), radius));
    }

    /** The enabled workplace with the fewest workers (nearest first). */
    Workplace freeWorkplace(Location near) {
        Workplace best = null;
        double bestD = Double.MAX_VALUE;
        for (Workplace wp : workplaces.values()) {
            if (!wp.enabled || wp.job() == Worker.Job.NONE) continue;
            if (wp.loc.getWorld() != near.getWorld()) continue;
            double d = wp.workers.size() * 1_000_000.0 + wp.loc.distanceSquared(near);
            if (d < bestD) {
                bestD = d;
                best = wp;
            }
        }
        return best;
    }
}
