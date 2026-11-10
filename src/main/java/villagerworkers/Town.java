package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
            return Math.max(2, Math.min(20, v < 0 ? 6 : v));
        }

        /** The checkbox: does the herder look after this kind at all? */
        boolean herding(org.bukkit.entity.EntityType type) {
            return options.getOrDefault("HERD_" + type.name(), true) && herds.getOrDefault(type.name(), herd) >= 0;
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

        Worker.Job job() {
            return Worker.Job.forBlock(loc.getBlock().getType());
        }
    }

    final String id;
    UUID owner;
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

    boolean contains(Location l, int radius) {
        if (l == null || l.getWorld() != bell.getWorld()) return false;
        // A square around the bell: "radius" blocks to every side.
        double dx = Math.abs(l.getX() - (bell.getX() + 0.5)), dz = Math.abs(l.getZ() - (bell.getZ() + 0.5));
        return dx <= radius + 0.5 && dz <= radius + 0.5 && Math.abs(l.getY() - bell.getY()) <= 64;
    }

    // ---- Chest rules ----

    static boolean isStorage(Block b) {
        BlockState s = b.getState(false);
        return s instanceof Chest || s instanceof Barrel;
    }

    boolean[] rule(Block b) {
        boolean[] r = chestRules.get(Keys.ser(b.getLocation()));
        return r != null ? r : new boolean[]{chestDefault, chestDefault};
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

    private static List<Block> halves(Block b) {
        if (b.getState(false) instanceof Chest c) {
            InventoryHolder h = c.getInventory().getHolder();
            if (h instanceof DoubleChest dc && dc.getLeftSide() instanceof Chest l && dc.getRightSide() instanceof Chest r) {
                return List.of(l.getBlock(), r.getBlock());
            }
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
            int cx = bell.getBlockX() >> 4, cz = bell.getBlockZ() >> 4, cr = (radius >> 4) + 1;
            for (int x = cx - cr; x <= cx + cr; x++) {
                for (int z = cz - cr; z <= cz + cr; z++) {
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
    Block storeChest(Material m, Location near, int radius) {
        Block withItem = null, any = null;
        double dWith = Double.MAX_VALUE, dAny = Double.MAX_VALUE;
        for (Block b : containers(radius)) {
            if (!rule(b)[0]) continue;
            Inventory inv = inventory(b);
            if (inv == null || !hasSpace(inv, m)) continue;
            double d = b.getLocation().distanceSquared(near);
            if (inv.contains(m)) {
                if (d < dWith) {
                    dWith = d;
                    withItem = b;
                }
            } else if (d < dAny) {
                dAny = d;
                any = b;
            }
        }
        return withItem != null ? withItem : any;
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
