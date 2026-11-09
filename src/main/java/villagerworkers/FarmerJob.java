package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.Ageable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Predicate;

/**
 * Farmer: works the field around its composter. Harvests ripe crops (while the town wants more
 * of that crop), replants, plants empty farmland (fetching seeds from town storage when needed)
 * and brings the harvest to the town's chests.
 */
final class FarmerJob {
    private FarmerJob() {}

    /** Crop block → what you plant it with. */
    static final Map<Material, Material> SEED = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS);

    /** Seed item → the crop block it grows. */
    static final Map<Material, Material> CROP = Map.of(
            Material.WHEAT_SEEDS, Material.WHEAT,
            Material.CARROT, Material.CARROTS,
            Material.POTATO, Material.POTATOES,
            Material.BEETROOT_SEEDS, Material.BEETROOTS);

    /** Crop block → harvest item that counts towards the town target. */
    static final Map<Material, Material> PRODUCE = Map.of(
            Material.WHEAT, Material.WHEAT,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT);

    /** The items you can set town targets for (in menu order). */
    static final List<Material> TARGET_ITEMS = List.of(Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT);

    static boolean wanted(Material m) {
        return PRODUCE.containsValue(m) || CROP.containsKey(m) || m == Material.POISONOUS_POTATO;
    }

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        int radius = w.plugin.settings().townRadius();
        boolean storageFull = Bukkit.getCurrentTick() < w.storageFullUntil;

        if (!storageFull && needsDeposit(w, false)) {
            Activity d = deposit(w, t);
            if (d != null) return d;
        }
        Block crop = nearest(w, wp, b -> ripe(b) && !w.isSkipped(b) && t.wants(PRODUCE.get(b.getType()), radius));
        if (crop != null && !storageFull) return harvest(w, crop);

        Block spot = nearest(w, wp, b -> b.getType() == Material.AIR
                && b.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND && !w.isSkipped(b));
        if (spot != null) {
            if (hasAnySeed(w)) return plant(w, spot);
            Activity fetch = fetchSeeds(w, t);
            if (fetch != null) return fetch;
        }
        if (!storageFull && needsDeposit(w, true)) {
            Activity d = deposit(w, t);
            if (d != null) return d;
        }

        if (storageFull) w.workStatus = "No room in storage";
        else if (spot != null) w.workStatus = "Needs seeds (none in storage)";
        else if (anyRipe(w, wp)) w.workStatus = "The settlement has enough for now";
        else w.workStatus = "Waiting for crops to grow";
        return null;
    }

    // ---- Activities ----

    private static Activity harvest(Worker w, Block crop) {
        w.workStatus = "Harvesting";
        return new GoAndDo(w, crop, 1.5, "Harvesting " + nice(crop.getType()), FarmerJob::ripe, b -> {
            Material type = b.getType();
            Collection<ItemStack> drops = b.getDrops();
            World world = b.getWorld();
            Location c = b.getLocation().add(0.5, 0.3, 0.5);
            world.spawnParticle(Particle.BLOCK, c, 12, 0.25, 0.2, 0.25, b.getBlockData());
            world.playSound(c, "minecraft:block.crop.break", SoundCategory.BLOCKS, 0.8f, 1f);
            b.setType(Material.AIR);
            w.give(drops, c);
            Material seed = SEED.get(type);
            if (seed != null && b.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND && w.take(seed, 1)) {
                b.setType(type);
                world.playSound(c, "minecraft:item.crop.plant", SoundCategory.BLOCKS, 0.8f, 1f);
            }
        });
    }

    private static Activity plant(Worker w, Block spot) {
        w.workStatus = "Planting";
        return new GoAndDo(w, spot, 1.5, "Planting seeds",
                b -> b.getType() == Material.AIR && b.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND,
                b -> {
                    Material seed = pickSeed(w, b);
                    if (seed == null || !w.take(seed, 1)) return;
                    b.setType(CROP.get(seed));
                    b.getWorld().playSound(b.getLocation().add(0.5, 0.2, 0.5), "minecraft:item.crop.plant", SoundCategory.BLOCKS, 0.8f, 1f);
                });
    }

    /** Walk to the best chest for the biggest load and put in everything that belongs there. */
    private static Activity deposit(Worker w, Town t) {
        int radius = w.plugin.settings().townRadius();
        Map<Material, Integer> ex = excess(w);
        Material first = ex.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (first == null) return null;
        Block chest = t.storeChest(first, w.villager.getLocation(), radius);
        if (chest == null) {
            w.storageFullUntil = Bukkit.getCurrentTick() + 400;
            return null;
        }
        w.workStatus = "Bringing the harvest to storage";
        return new GoAndDo(w, chest, 2.0, "Bringing the harvest to storage",
                b -> Town.isStorage(b) && t.rule(b)[0],
                b -> {
                    openLid(w, b);
                    Inventory inv = Town.inventory(b);
                    if (inv == null) return;
                    Inventory pockets = w.villager.getInventory();
                    for (Map.Entry<Material, Integer> e : excess(w).entrySet()) {
                        Material m = e.getKey();
                        if (!b.equals(t.storeChest(m, b.getLocation(), radius))) continue; // belongs elsewhere
                        ItemStack stack = new ItemStack(m, e.getValue());
                        pockets.removeItem(stack.clone());
                        for (ItemStack left : inv.addItem(stack).values()) pockets.addItem(left);
                    }
                    t.countsChanged();
                });
    }

    /** Get seeds from town storage (the kind it has most of). */
    private static Activity fetchSeeds(Worker w, Town t) {
        int radius = w.plugin.settings().townRadius();
        Material best = null;
        int most = 0;
        for (Material seed : CROP.keySet()) {
            int n = t.count(seed, radius);
            if (n > most && t.takeChest(seed, w.villager.getLocation(), radius) != null) {
                most = n;
                best = seed;
            }
        }
        if (best == null) return null;
        Material seed = best;
        Block chest = t.takeChest(seed, w.villager.getLocation(), radius);
        w.workStatus = "Getting seeds";
        return new GoAndDo(w, chest, 2.0, "Getting seeds from storage",
                b -> Town.isStorage(b) && t.rule(b)[1],
                b -> {
                    openLid(w, b);
                    Inventory inv = Town.inventory(b);
                    if (inv == null) return;
                    int want = w.plugin.settings().seedsToKeep() - w.count(seed);
                    for (ItemStack it : inv.getContents()) {
                        if (want <= 0) break;
                        if (it == null || it.getType() != seed) continue;
                        int n = Math.min(want, it.getAmount());
                        ItemStack taken = new ItemStack(seed, n);
                        if (!w.villager.getInventory().addItem(taken).isEmpty()) break;
                        inv.removeItem(new ItemStack(seed, n));
                        want -= n;
                    }
                    t.countsChanged();
                });
    }

    private static void openLid(Worker w, Block b) {
        if (b.getState() instanceof Lidded lid) {
            lid.open();
            w.plugin.getServer().getScheduler().runTaskLater(w.plugin, () -> {
                BlockState now = b.getState();
                if (now instanceof Lidded l) l.close();
            }, 20L);
        }
    }

    // ---- Helpers ----

    static boolean ripe(Block b) {
        return SEED.containsKey(b.getType()) && b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    private static boolean anyRipe(Worker w, Town.Workplace wp) {
        return nearest(w, wp, FarmerJob::ripe) != null;
    }

    /** Nearest block in the work area (around the composter) matching the test. */
    private static Block nearest(Worker w, Town.Workplace wp, Predicate<Block> test) {
        World world = wp.loc.getWorld();
        if (world == null || world != w.villager.getWorld()) return null;
        int cx = wp.loc.getBlockX(), cy = wp.loc.getBlockY(), cz = wp.loc.getBlockZ(), r = wp.radius;
        Location me = w.villager.getLocation();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = cy - 3; y <= cy + 3; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!test.test(b)) continue;
                    double dx = x + 0.5 - me.getX(), dy = y - me.getY(), dz = z + 0.5 - me.getZ();
                    double d = dx * dx + dy * dy * 4 + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    /** Items to bring to storage (everything except the seeds it keeps for planting). */
    private static Map<Material, Integer> excess(Worker w) {
        Map<Material, Integer> totals = new HashMap<>();
        for (ItemStack it : w.villager.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) totals.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        int keep = w.plugin.settings().seedsToKeep();
        totals.replaceAll((m, n) -> CROP.containsKey(m) ? Math.max(0, n - keep) : n);
        totals.values().removeIf(n -> n <= 0);
        return totals;
    }

    /** Pockets (nearly) full, or a good load to bring; {@code anything}: any harvest at all. */
    private static boolean needsDeposit(Worker w, boolean anything) {
        Map<Material, Integer> ex = excess(w);
        if (ex.isEmpty()) return false;
        if (anything) return true;
        int total = ex.values().stream().mapToInt(Integer::intValue).sum();
        return total >= 48 || w.villager.getInventory().firstEmpty() == -1;
    }

    private static boolean hasAnySeed(Worker w) {
        for (Material seed : CROP.keySet()) if (w.count(seed) > 0) return true;
        return false;
    }

    /** Plant what grows next to it if possible, otherwise the seed it has most of. */
    private static Material pickSeed(Worker w, Block spot) {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Material seed = SEED.get(spot.getRelative(f).getType());
            if (seed != null && w.count(seed) > 0) return seed;
        }
        Material best = null;
        int most = 0;
        for (Material seed : CROP.keySet()) {
            int n = w.count(seed);
            if (n > most) {
                most = n;
                best = seed;
            }
        }
        return best;
    }

    static String nice(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
