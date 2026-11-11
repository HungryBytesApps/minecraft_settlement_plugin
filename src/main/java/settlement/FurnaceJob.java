package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmokingRecipe;

import java.util.*;
import java.util.function.Predicate;

/**
 * Cook (smoker), smelter (blast furnace) and furnace worker (furnace, with categories you choose):
 * fetches things to cook or smelt and fuel from storage, keeps the furnace going, collects the
 * results and brings them to storage. Only makes what the settlement still wants (storage targets).
 */
final class FurnaceJob {
    private FurnaceJob() {}

    private static Map<Material, Material> smoking, blasting, smelting; // input → result
    /** What only a plain furnace does (not what a smoker or blast furnace can do): the furnace worker's work. */
    private static Map<Material, Material> furnaceOnly;
    private static Set<Material> furnaceProducts;

    private static void loadRecipes() {
        if (smoking != null) return;
        smoking = new HashMap<>();
        blasting = new HashMap<>();
        smelting = new HashMap<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (!(r instanceof CookingRecipe<?> cr)) continue;
            Map<Material, Material> map = r instanceof SmokingRecipe ? smoking : r instanceof BlastingRecipe ? blasting
                    : r instanceof FurnaceRecipe ? smelting : null;
            if (map == null) continue;
            if (!(cr.getInputChoice() instanceof RecipeChoice.MaterialChoice mc)) continue;
            for (Material in : mc.getChoices()) {
                if (in.getMaxStackSize() == 1) continue; // no melting down tools and armor
                map.putIfAbsent(in, cr.getResult().getType());
            }
        }
        furnaceOnly = new HashMap<>();
        Set<Material> elsewhere = new java.util.HashSet<>(smoking.values());
        elsewhere.addAll(blasting.values());
        for (Map.Entry<Material, Material> e : smelting.entrySet()) {
            if (smoking.containsKey(e.getKey()) || blasting.containsKey(e.getKey()) || elsewhere.contains(e.getValue())) continue;
            furnaceOnly.put(e.getKey(), e.getValue());
        }
        furnaceProducts = new java.util.HashSet<>(furnaceOnly.values());
    }

    /** Made by the furnace worker: made to order (only with a storage target above 0). */
    static boolean furnaceProduct(Material m) {
        loadRecipes();
        return furnaceProducts.contains(m);
    }

    /** What this workplace may cook or smelt: input → result. */
    static Map<Material, Material> recipes(Town.Workplace wp) {
        loadRecipes();
        return switch (wp.job()) {
            case COOK -> smoking;
            case SMELTER -> blasting;
            case FURNACE_WORKER -> furnaceOnly;
            default -> Map.of();
        };
    }

    /** All results a job can make (for storage targets). */
    static Set<Material> products(Worker.Job job) {
        loadRecipes();
        Map<Material, Material> map = switch (job) {
            case COOK -> smoking;
            case SMELTER -> blasting;
            case FURNACE_WORKER -> furnaceOnly;
            default -> Map.of();
        };
        return new TreeSet<>(map.values());
    }

    private static final List<Material> GOOD_FUEL = List.of(Material.COAL, Material.CHARCOAL, Material.COAL_BLOCK,
            Material.DRIED_KELP_BLOCK, Material.BLAZE_ROD);

    static boolean isFuel(Material m) {
        return GOOD_FUEL.contains(m) || Tag.PLANKS.isTagged(m) || Tag.LOGS.isTagged(m) || m == Material.STICK;
    }

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        Block fb = wp.loc.getBlock();
        if (!(fb.getState(false) instanceof Furnace furnace)) return null;
        FurnaceInventory fi = furnace.getInventory();
        Map<Material, Material> recipes = recipes(wp);
        int radius = w.plugin.settings().townRadius();
        boolean toOrder = wp.job() == Worker.Job.FURNACE_WORKER; // only what has a storage target
        Predicate<Material> wantedInput = m -> {
            Material o = recipes.get(m);
            if (o == null) return false;
            if (!toOrder) return t.wants(o, radius);
            Integer target = t.targets.get(o);
            return target != null && target > 0 && t.count(o, radius) < target;
        };
        Predicate<Material> keep = m -> recipes.containsKey(m) || isFuel(m) || Work.gear(m);

        ItemStack in = fi.getSmelting(), fuel = fi.getFuel(), out = fi.getResult();
        boolean hasInputHere = in != null && !in.getType().isAir();
        boolean fuelHere = fuel != null && !fuel.getType().isAir();
        boolean resultReady = out != null && !out.getType().isAir() && out.getAmount() >= 8;
        boolean carryInput = carries(w, wantedInput);
        boolean carryFuel = carries(w, FurnaceJob::isFuel);

        if (Work.needsDeposit(w, keep, false)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        // Go to the furnace: take out the results, put in what it carries.
        boolean roomForInput = !hasInputHere || in.getAmount() < in.getMaxStackSize();
        if (resultReady || (carryInput && roomForInput) || (carryFuel && (!fuelHere || fuel.getAmount() < 8) && (hasInputHere || carryInput))) {
            w.workStatus = "Tending the " + Work.nice(fb.getType());
            return new GoAndDo(w, fb, 2.0, "Tending the " + Work.nice(fb.getType()), b -> b.getState(false) instanceof Furnace, b -> tend(w, b, wantedInput));
        }
        // Get things to cook/smelt (while the furnace runs low), and fuel.
        if (!carryInput && (!hasInputHere || in.getAmount() < 16)) {
            Activity a = Work.fetch(w, t, wantedInput, 64, "Getting things to " + verb(wp));
            if (a != null) return a;
        }
        if (!carryFuel && (!fuelHere || fuel.getAmount() < 4) && (hasInputHere || carryInput)) {
            Activity a = Work.fetch(w, t, m -> GOOD_FUEL.contains(m), 16, "Getting fuel");
            if (a == null) a = Work.fetch(w, t, FurnaceJob::isFuel, 16, "Getting fuel");
            if (a != null) return a;
            w.workStatus = "Needs fuel (coal, charcoal, wood) in storage";
            return null;
        }
        if (out != null && !out.getType().isAir()) {
            // A few results waiting and nothing else to do: collect them anyway.
            return new GoAndDo(w, fb, 2.0, "Tending the " + Work.nice(fb.getType()), b -> b.getState(false) instanceof Furnace, b -> tend(w, b, wantedInput));
        }
        if (Work.needsDeposit(w, keep, true)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        if (hasInputHere) w.workStatus = capital(verb(wp)) + "ing";
        else if (recipes.isEmpty()) w.workStatus = "Nothing allowed to make here";
        else if (toOrder && recipes.values().stream().noneMatch(o -> t.targets.getOrDefault(o, 0) > 0)) w.workStatus = "Nothing to make (set targets in Storage targets)";
        else w.workStatus = "Nothing to " + verb(wp) + " in storage";
        return null;
    }

    /** At the furnace: take out the results, put in input and fuel. */
    private static void tend(Worker w, Block b, Predicate<Material> wantedInput) {
        if (!(b.getState(false) instanceof Furnace f)) return;
        FurnaceInventory fi = f.getInventory();
        ItemStack out = fi.getResult();
        if (out != null && !out.getType().isAir()) {
            Map<Integer, ItemStack> left = w.pockets.addItem(out.clone());
            fi.setResult(left.isEmpty() ? null : left.values().iterator().next());
        }
        fill(w, fi, true, wantedInput);
        fill(w, fi, false, FurnaceJob::isFuel);
        w.savePockets();
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), "minecraft:block.fire.ambient", SoundCategory.BLOCKS, 0.6f, 1.2f);
    }

    private static void fill(Worker w, FurnaceInventory fi, boolean input, Predicate<Material> test) {
        ItemStack cur = input ? fi.getSmelting() : fi.getFuel();
        for (int i = 0; i < w.pockets.getSize(); i++) {
            ItemStack it = w.pockets.getItem(i);
            if (it == null || !test.test(it.getType())) continue;
            if (cur != null && !cur.getType().isAir() && !cur.isSimilar(it)) continue;
            int have = cur == null || cur.getType().isAir() ? 0 : cur.getAmount();
            int n = Math.min(it.getAmount(), it.getMaxStackSize() - have);
            if (n <= 0) break;
            ItemStack put = it.clone();
            put.setAmount(have + n);
            if (input) fi.setSmelting(put);
            else fi.setFuel(put);
            cur = put;
            it.setAmount(it.getAmount() - n);
            w.pockets.setItem(i, it.getAmount() > 0 ? it : null);
        }
    }

    private static boolean carries(Worker w, Predicate<Material> test) {
        for (ItemStack it : w.pockets.getContents()) if (it != null && test.test(it.getType())) return true;
        return false;
    }

    private static String verb(Town.Workplace wp) {
        return wp.job() == Worker.Job.COOK ? "cook" : "smelt";
    }

    private static String capital(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
