package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Farmer (composter): works all farmland and plants in the settlement. First harvests what's ripe
 * (only while the settlement wants more of it), then plants the empty farmland with the same crop as
 * before. Also melons and pumpkins (leaves the stem), sugar cane, cactus and bamboo (leaves the bottom
 * piece), sweet berries and cocoa. Turns melon slices into melon blocks when you want blocks, uses bone
 * meal if allowed, and can compost its surplus harvest into bone meal at the composter.
 */
final class FarmerJob {
    private FarmerJob() {}

    /** Crop block on farmland → what you plant it with. */
    static final Map<Material, Material> SEED = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS);

    /** Seed item → the crop block it grows (the farmer plants these by itself). */
    static final Map<Material, Material> CROP = Map.of(
            Material.WHEAT_SEEDS, Material.WHEAT,
            Material.CARROT, Material.CARROTS,
            Material.POTATO, Material.POTATOES,
            Material.BEETROOT_SEEDS, Material.BEETROOTS);

    /** Plant block → the item that counts towards the storage target. */
    static final Map<Material, Material> PRODUCE = Map.ofEntries(
            Map.entry(Material.WHEAT, Material.WHEAT),
            Map.entry(Material.CARROTS, Material.CARROT),
            Map.entry(Material.POTATOES, Material.POTATO),
            Map.entry(Material.BEETROOTS, Material.BEETROOT),
            Map.entry(Material.PUMPKIN, Material.PUMPKIN),
            Map.entry(Material.MELON, Material.MELON_SLICE),
            Map.entry(Material.SUGAR_CANE, Material.SUGAR_CANE),
            Map.entry(Material.CACTUS, Material.CACTUS),
            Map.entry(Material.BAMBOO, Material.BAMBOO),
            Map.entry(Material.SWEET_BERRY_BUSH, Material.SWEET_BERRIES),
            Map.entry(Material.COCOA, Material.COCOA_BEANS));

    /** The items you can set storage targets for (in menu order). */
    static final List<Material> TARGET_ITEMS = List.of(Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO,
            Material.BEETROOT, Material.BEETROOT_SEEDS, Material.MELON_SLICE, Material.MELON, Material.PUMPKIN, Material.SUGAR_CANE,
            Material.CACTUS, Material.BAMBOO, Material.SWEET_BERRIES, Material.COCOA_BEANS, Material.BONE_MEAL);

    /** How likely one item raises the composter a level (like vanilla). */
    static final Map<Material, Double> COMPOST = Map.ofEntries(
            Map.entry(Material.WHEAT, 0.65), Map.entry(Material.WHEAT_SEEDS, 0.3), Map.entry(Material.CARROT, 0.65),
            Map.entry(Material.POTATO, 0.65), Map.entry(Material.BEETROOT, 0.65), Map.entry(Material.BEETROOT_SEEDS, 0.3),
            Map.entry(Material.MELON_SLICE, 0.5), Map.entry(Material.MELON, 0.65), Map.entry(Material.PUMPKIN, 0.65),
            Map.entry(Material.SUGAR_CANE, 0.5), Map.entry(Material.CACTUS, 0.5), Map.entry(Material.BAMBOO, 0.5),
            Map.entry(Material.SWEET_BERRIES, 0.3), Map.entry(Material.COCOA_BEANS, 0.65));

    /** Make compost: Off, On (harvest on to make bone meal up to its target), Overflow only (just the surplus). */
    enum Compost {
        OFF("Off"), ON("On"), OVERFLOW("Overflow only");

        final String label;

        Compost(String label) {
            this.label = label;
        }

        Compost next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    static Compost compost(Town.Workplace wp) {
        if (!wp.isOn("COMPOST")) return Compost.OFF;
        return wp.isOn("COMPOST_ALL") ? Compost.ON : Compost.OVERFLOW;
    }

    static void setCompost(Town.Workplace wp, Compost c) {
        wp.options.put("COMPOST", c != Compost.OFF);
        wp.options.put("COMPOST_ALL", c == Compost.ON);
    }

    /** What the spot grew last time, so it gets the same seed again. */
    private static final Map<Block, Material> lastSeed = new HashMap<>();

    static boolean wanted(Material m) {
        return TARGET_ITEMS.contains(m) || m == Material.POISONOUS_POTATO;
    }

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        int radius = w.plugin.settings().townRadius();
        boolean storageFull = Bukkit.getCurrentTick() < w.storageFullUntil;
        craftMelons(w, t, radius);
        Compost compost = compost(wp);
        boolean makeMeal = compost != Compost.OFF && t.wants(Material.BONE_MEAL, radius);

        // Surplus harvest to the composter (a good load), and a full load to storage.
        if (makeMeal && compostLoad(w, t, radius) >= 16) return compostTrip(w, t, wp);
        if (!storageFull && needsDeposit(w, t, wp, false)) {
            Activity d = deposit(w, t, wp);
            if (d != null) return d;
        }

        // Work in rounds: harvest a batch, then plant the empty farmland, then harvest again. (A big field
        // always has something ripening; without rounds the planting would never come.)
        boolean harvestForCompost = makeMeal && compost == Compost.ON;
        List<Block> land = Fields.farmland(t, radius);
        Block empty = Work.nearestOf(w, land, f -> f.getType() == Material.FARMLAND && f.getRelative(BlockFace.UP).getType() == Material.AIR
                && !w.isSkipped(f.getRelative(BlockFace.UP)));
        Block spot = empty == null ? null : empty.getRelative(BlockFace.UP);
        if (w.planting && spot != null) {
            Activity p = plantOrFetch(w, t, empty, spot);
            if (p != null) return p;
        }
        w.planting = false;
        if (!storageFull || harvestForCompost) {
            List<Block> candidates = new ArrayList<>();
            for (Block f : land) candidates.add(f.getRelative(BlockFace.UP));
            candidates.addAll(Fields.plants(t, radius));
            Block ripe = w.harvestRound >= 24 ? null : Work.nearestOf(w, candidates, b -> harvestable(b) && wantsHarvest(t, b, radius, harvestForCompost));
            if (ripe != null) {
                w.harvestRound++;
                Work.claim(w, ripe);
                return harvest(w, ripe);
            }
        }
        // Batch harvested (or nothing ripe): now plant.
        w.harvestRound = 0;
        if (spot != null) {
            w.planting = true;
            Activity p = plantOrFetch(w, t, empty, spot);
            if (p != null) return p;
            w.planting = false;
        }

        // Use bone meal (if switched on): only on crops the settlement still wants.
        if (!storageFull && wp.isOn("BONEMEAL")) {
            Block growing = Work.nearestOf(w, land, f -> {
                Block c = f.getRelative(BlockFace.UP);
                return SEED.containsKey(c.getType()) && !ripe(c) && t.wants(PRODUCE.get(c.getType()), radius);
            });
            if (growing != null) {
                if (w.count(Material.BONE_MEAL) > 0) {
                    Work.claim(w, growing);
                    Block crop = growing.getRelative(BlockFace.UP);
                    w.workStatus = "Using bone meal";
                    return new GoAndDo(w, crop, 1.8, "Using bone meal", b -> SEED.containsKey(b.getType()) && !ripe(b), b -> {
                        if (!w.take(Material.BONE_MEAL, 1)) return;
                        b.applyBoneMeal(BlockFace.UP);
                        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), "minecraft:item.bone_meal.use", SoundCategory.BLOCKS, 0.8f, 1f);
                    });
                }
                Activity getBoneMeal = Work.fetch(w, t, m -> m == Material.BONE_MEAL, 16, "Getting bone meal");
                if (getBoneMeal != null) return getBoneMeal;
            }
        }

        // Nothing else: compost what's left over, bring the rest to storage.
        if (makeMeal && compostLoad(w, t, radius) > 0) return compostTrip(w, t, wp);
        if (!storageFull && needsDeposit(w, t, wp, true)) {
            Activity d = deposit(w, t, wp);
            if (d != null) return d;
        }

        if (storageFull) w.workStatus = "No room in storage";
        else if (spot != null) w.workStatus = "Needs seeds (none in storage)";
        else w.workStatus = "Waiting for crops to grow";
        return null;
    }

    // ---- What's ripe ----

    /** Ripe crop, a melon/pumpkin grown from a stem, sugar cane/cactus/bamboo 2+ high, ripe berries or cocoa. */
    static boolean harvestable(Block b) {
        Material m = b.getType();
        if (SEED.containsKey(m)) return ripe(b);
        switch (m) {
            case MELON, PUMPKIN -> {
                for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                    Block s = b.getRelative(f);
                    if ((s.getType() == Material.ATTACHED_MELON_STEM || s.getType() == Material.ATTACHED_PUMPKIN_STEM)
                            && s.getBlockData() instanceof Directional d && d.getFacing() == f.getOppositeFace()) return true;
                }
                return false;
            }
            case SUGAR_CANE, CACTUS, BAMBOO -> {
                return b.getRelative(BlockFace.DOWN).getType() != m && b.getRelative(BlockFace.UP).getType() == m;
            }
            case SWEET_BERRY_BUSH -> {
                return b.getBlockData() instanceof Ageable a && a.getAge() >= 2;
            }
            case COCOA -> {
                return b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
            }
            default -> {
                return false;
            }
        }
    }

    static boolean ripe(Block b) {
        return SEED.containsKey(b.getType()) && b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    /** Does the settlement want what this plant gives (or does it go into compost)? */
    private static boolean wantsHarvest(Town t, Block b, int radius, boolean forCompost) {
        Material product = PRODUCE.get(b.getType());
        if (product == null) return false;
        if (forCompost && COMPOST.containsKey(product)) return true;
        if (b.getType() == Material.MELON) return t.wants(Material.MELON_SLICE, radius) || t.wants(Material.MELON, radius);
        return t.wants(product, radius);
    }

    private static Activity harvest(Worker w, Block plant) {
        Material kind = plant.getType();
        w.workStatus = "Harvesting";
        return new GoAndDo(w, plant, 1.8, "Harvesting " + nice(PRODUCE.getOrDefault(kind, kind)), b -> b.getType() == kind && harvestable(b), b -> {
            World world = b.getWorld();
            Location c = b.getLocation().add(0.5, 0.3, 0.5);
            List<ItemStack> drops = new ArrayList<>();
            switch (kind) {
                case SUGAR_CANE, CACTUS, BAMBOO -> {
                    // Everything above the bottom piece, top first.
                    Block top = b;
                    while (top.getRelative(BlockFace.UP).getType() == kind) top = top.getRelative(BlockFace.UP);
                    for (Block x = top; x.getY() > b.getY(); x = x.getRelative(BlockFace.DOWN)) {
                        drops.add(new ItemStack(kind == Material.BAMBOO ? Material.BAMBOO : kind));
                        world.spawnParticle(Particle.BLOCK, x.getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, x.getBlockData());
                        x.setType(Material.AIR, false);
                    }
                    world.playSound(c, kind == Material.BAMBOO ? "minecraft:block.bamboo.break" : "minecraft:block.grass.break", SoundCategory.BLOCKS, 0.8f, 1f);
                }
                case SWEET_BERRY_BUSH -> {
                    Ageable a = (Ageable) b.getBlockData();
                    int n = a.getAge() >= 3 ? 2 + w.random.nextInt(2) : 1 + w.random.nextInt(2);
                    drops.add(new ItemStack(Material.SWEET_BERRIES, n));
                    a.setAge(1);
                    b.setBlockData(a);
                    world.playSound(c, "minecraft:block.sweet_berry_bush.pick_berries", SoundCategory.BLOCKS, 0.8f, 1f);
                }
                case COCOA -> {
                    drops.addAll(b.getDrops());
                    Ageable a = (Ageable) b.getBlockData();
                    a.setAge(0); // it grows again
                    b.setBlockData(a);
                    world.playSound(c, "minecraft:block.wood.hit", SoundCategory.BLOCKS, 0.8f, 1f);
                }
                default -> {
                    drops.addAll(b.getDrops());
                    world.spawnParticle(Particle.BLOCK, c, 12, 0.25, 0.2, 0.25, b.getBlockData());
                    world.playSound(c, kind == Material.MELON || kind == Material.PUMPKIN ? "minecraft:block.wood.break" : "minecraft:block.crop.break",
                            SoundCategory.BLOCKS, 0.8f, 1f);
                    Material seed = SEED.get(kind);
                    if (seed != null) lastSeed.put(b, seed); // replant the same later
                    b.setType(Material.AIR);
                }
            }
            for (ItemStack d : drops) w.harvested.merge(d.getType(), d.getAmount(), Integer::sum);
            w.give(drops, c);
        });
    }

    // ---- Melons: slices into blocks ----

    /** While the settlement wants melon blocks: every 9 slices in the pockets become a melon. */
    private static void craftMelons(Worker w, Town t, int radius) {
        while (t.wants(Material.MELON, radius) && w.count(Material.MELON_SLICE) >= 9) {
            if (!w.take(Material.MELON_SLICE, 9)) return;
            w.pockets.addItem(new ItemStack(Material.MELON));
            w.harvested.merge(Material.MELON, 1, Integer::sum);
            w.harvested.computeIfPresent(Material.MELON_SLICE, (k, v) -> Math.max(0, v - 9));
        }
    }

    // ---- Compost ----

    /**
     * How many of this item in the pockets are surplus: harvested by the farmer itself (never taken from
     * storage) and more than the settlement wants.
     */
    private static int surplus(Worker w, Town t, int radius, Material m) {
        if (!COMPOST.containsKey(m)) return 0;
        int have = Math.min(w.count(m), w.harvested.getOrDefault(m, 0));
        if (have <= 0) return 0;
        if (m == Material.MELON_SLICE && t.wants(Material.MELON, radius)) return 0; // kept for melon blocks
        if (CROP.containsKey(m)) { // seeds: keep enough to plant
            if (t.wants(m, radius)) return 0;
            return Math.max(0, Math.min(have, w.count(m) - w.plugin.settings().seedsToKeep()));
        }
        return t.wants(m, radius) ? 0 : have;
    }

    private static int compostLoad(Worker w, Town t, int radius) {
        int n = 0;
        for (Material m : COMPOST.keySet()) n += surplus(w, t, radius, m);
        return n;
    }

    /** To the composter: put in the surplus, take out the bone meal. */
    private static Activity compostTrip(Worker w, Town t, Town.Workplace wp) {
        Block composter = wp.loc.getBlock();
        if (composter.getType() != Material.COMPOSTER) return null;
        w.workStatus = "Making compost";
        return new GoAndDo(w, composter, 2.0, "Making compost", b -> b.getType() == Material.COMPOSTER, b -> {
            int radius = w.plugin.settings().townRadius();
            if (!(b.getBlockData() instanceof Levelled lv)) return;
            int level = Math.min(lv.getLevel(), 7), meal = 0, used = 0;
            if (lv.getLevel() >= 8) {
                meal++;
                level = 0;
            }
            for (Material m : COMPOST.keySet()) {
                int n = surplus(w, t, radius, m);
                for (int i = 0; i < n && t.wants(Material.BONE_MEAL, radius); i++) {
                    if (!w.take(m, 1)) break;
                    w.harvested.computeIfPresent(m, (k, v) -> Math.max(0, v - 1));
                    used++;
                    if (w.random.nextDouble() < COMPOST.get(m) && ++level >= 7) {
                        meal++;
                        level = 0;
                    }
                }
            }
            lv.setLevel(level);
            b.setBlockData(lv);
            Location c = b.getLocation().add(0.5, 0.8, 0.5);
            if (used > 0) {
                b.getWorld().playSound(c, "minecraft:block.composter.fill_success", SoundCategory.BLOCKS, 0.8f, 1f);
                b.getWorld().spawnParticle(Particle.COMPOSTER, c, 10, 0.25, 0.1, 0.25);
            }
            if (meal > 0) {
                w.pockets.addItem(new ItemStack(Material.BONE_MEAL, meal));
                b.getWorld().playSound(c, "minecraft:block.composter.empty", SoundCategory.BLOCKS, 0.8f, 1f);
            }
            w.savePockets();
        }).working(30, "minecraft:block.composter.fill");
    }

    // ---- Planting ----

    private static Activity plantOrFetch(Worker w, Town t, Block farmland, Block spot) {
        Work.claim(w, farmland);
        if (hasAnySeed(w)) return plant(w, spot);
        return fetchSeeds(w, t, lastSeed.get(spot));
    }

    private static boolean hasAnySeed(Worker w) {
        for (Material seed : CROP.keySet()) if (w.count(seed) > 0) return true;
        return false;
    }

    private static Activity plant(Worker w, Block spot) {
        w.workStatus = "Planting";
        return new GoAndDo(w, spot, 1.5, "Planting seeds",
                b -> b.getType() == Material.AIR && b.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND,
                b -> {
                    Material seed = pickSeed(w, b);
                    if (seed == null || !w.take(seed, 1)) return;
                    b.setType(CROP.get(seed));
                    lastSeed.remove(b);
                    b.getWorld().playSound(b.getLocation().add(0.5, 0.2, 0.5), "minecraft:item.crop.plant", SoundCategory.BLOCKS, 0.8f, 1f);
                });
    }

    /** The same crop as last time; else what grows next to it; else what it has most of. */
    private static Material pickSeed(Worker w, Block spot) {
        Material last = lastSeed.get(spot);
        if (last != null && w.count(last) > 0) return last;
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

    /** Get seeds from storage: the kind that grew there before if there is any, else the kind there's most of. */
    private static Activity fetchSeeds(Worker w, Town t, Material preferred) {
        int radius = w.plugin.settings().townRadius();
        Material best = null;
        if (preferred != null && t.takeChest(preferred, w.body.getLocation(), radius) != null) best = preferred;
        int most = 0;
        if (best == null) {
            for (Material seed : CROP.keySet()) {
                int n = t.count(seed, radius);
                if (n > most && t.takeChest(seed, w.body.getLocation(), radius) != null) {
                    most = n;
                    best = seed;
                }
            }
        }
        if (best == null) return null;
        Material seed = best;
        int want = Math.max(1, w.plugin.settings().seedsToKeep() - w.count(seed));
        return Work.fetch(w, t, m -> m == seed, want, "Getting seeds");
    }

    // ---- Bringing things to storage ----

    /** What goes to storage: everything except seeds to plant, bone meal it uses, slices for melons, compost. */
    private static Map<Material, Integer> excess(Worker w, Town t, Town.Workplace wp) {
        int radius = w.plugin.settings().townRadius();
        Map<Material, Integer> totals = new HashMap<>();
        for (ItemStack it : w.pockets.getContents()) {
            if (it != null && !it.getType().isAir() && !Work.gear(it.getType())) totals.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        int keep = w.plugin.settings().seedsToKeep();
        totals.replaceAll((m, n) -> CROP.containsKey(m) ? Math.max(0, n - keep) : n);
        if (wp.isOn("BONEMEAL")) totals.computeIfPresent(Material.BONE_MEAL, (k, n) -> Math.max(0, n - 16));
        if (t.wants(Material.MELON, radius)) totals.computeIfPresent(Material.MELON_SLICE, (k, n) -> n % 9); // the rest becomes melons
        if (compost(wp) != Compost.OFF && t.wants(Material.BONE_MEAL, radius)) {
            for (Material m : COMPOST.keySet()) {
                int s = surplus(w, t, radius, m);
                if (s > 0) totals.computeIfPresent(m, (k, n) -> Math.max(0, n - s)); // goes into the composter
            }
        }
        totals.values().removeIf(n -> n <= 0);
        return totals;
    }

    private static boolean needsDeposit(Worker w, Town t, Town.Workplace wp, boolean anything) {
        Map<Material, Integer> ex = excess(w, t, wp);
        if (ex.isEmpty()) return false;
        if (anything) return true;
        return ex.values().stream().mapToInt(Integer::intValue).sum() >= 48 || w.pockets.firstEmpty() == -1;
    }

    private static Activity deposit(Worker w, Town t, Town.Workplace wp) {
        if (Bukkit.getCurrentTick() < w.storageFullUntil) return null;
        int radius = w.plugin.settings().townRadius();
        Map<Material, Integer> ex = excess(w, t, wp);
        Material first = ex.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (first == null) return null;
        Block chest = t.storeChest(first, w.body.getLocation(), radius);
        if (chest == null) {
            w.storageFullUntil = Bukkit.getCurrentTick() + 400;
            w.warnStorageFull(first);
            return null;
        }
        w.workStatus = "Bringing the harvest to storage";
        return new GoAndDo(w, chest, 2.0, "Bringing the harvest to storage", b -> Town.isStorage(b) && t.rule(b)[0], b -> {
            Inventory inv = Town.inventory(b);
            if (inv == null) return;
            for (Map.Entry<Material, Integer> e : excess(w, t, wp).entrySet()) {
                Material m = e.getKey();
                if (!b.equals(t.storeChest(m, b.getLocation(), radius))) continue; // belongs elsewhere
                ItemStack stack = new ItemStack(m, e.getValue());
                w.pockets.removeItem(stack.clone());
                for (ItemStack left : inv.addItem(stack).values()) w.pockets.addItem(left);
                w.harvested.computeIfPresent(m, (k, v) -> Math.max(0, v - e.getValue()));
            }
            t.countsChanged();
        });
    }

    static String nice(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
