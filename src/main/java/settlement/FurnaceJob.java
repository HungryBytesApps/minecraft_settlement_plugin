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

    /** What an ore block gives when mined anyway (smelting the block only works with silk touch): not a product. */
    private static final Set<Material> ORE_DROPS = EnumSet.of(Material.COAL, Material.DIAMOND, Material.EMERALD, Material.REDSTONE,
            Material.QUARTZ, Material.LAPIS_LAZULI);

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
                if (ORE_DROPS.contains(cr.getResult().getType()) && in.name().endsWith("_ORE")) continue; // mined, not smelted
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
    /** What goes into this kind of furnace (raw food, ores, sand...). */
    static Set<Material> inputs(Worker.Job job) {
        loadRecipes();
        Map<Material, Material> map = switch (job) {
            case COOK -> smoking;
            case SMELTER -> blasting;
            case FURNACE_WORKER -> furnaceOnly;
            default -> Map.of();
        };
        return new TreeSet<>(map.keySet());
    }

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
        // Someone asked for something and it's ready: hand it over first.
        Activity handover = Requests.deliver(w, t);
        if (handover != null) return handover;
        FurnaceInventory fi = furnace.getInventory();
        Map<Material, Material> recipes = recipes(wp);
        int radius = w.plugin.settings().townRadius();
        boolean toOrder = wp.job() == Worker.Job.FURNACE_WORKER; // only what has a storage target
        Map<Material, Integer> asked = Requests.asked(t, w); // made for someone, whatever the targets say
        Predicate<Material> wantedInput = m -> {
            Material o = recipes.get(m);
            if (o == null) return false;
            if (asked.containsKey(o) && w.count(o) < asked.get(o)) return true;
            if (!toOrder) return t.wants(o, radius);
            Integer target = t.targets.get(o);
            return target != null && target > 0 && t.count(o, radius) < target;
        };
        Set<Material> cookKeeps = wp.job() == Worker.Job.COOK ? Cook.keep(t, radius) : Set.of();
        // Keeps only what's still wanted to smelt (not every input it could smelt), fuel and gear.
        Predicate<Material> keepAll = m -> wantedInput.test(m) || isFuel(m) || Work.gear(m) || asked.containsKey(m) || cookKeeps.contains(m);
        // Pockets full of things it keeps: only the good fuel stays, the rest (wood, extra stacks) goes back too.
        // Never piles up: fuel up to 32 of a kind (charcoal it made goes to storage too), the cook's ingredients up to 16.
        java.util.function.ToIntFunction<Material> limit = m -> wantedInput.test(m) || Work.gear(m) || asked.containsKey(m) ? Integer.MAX_VALUE
                : isFuel(m) ? 32 : 16;
        boolean full = w.pockets.firstEmpty() == -1 && Work.excess(w, keepAll, limit).isEmpty();
        Predicate<Material> keep = !full ? keepAll
                : m -> Work.gear(m) || asked.containsKey(m) || GOOD_FUEL.contains(m) || (wantedInput.test(m) && !isFuel(m)) || cookKeeps.contains(m);
        // Asked for something it can't make (nothing to smelt anywhere): say sorry.
        for (Material o : asked.keySet()) {
            if (w.count(o) >= asked.get(o)) continue;
            boolean source = false;
            for (Map.Entry<Material, Material> e : recipes.entrySet()) {
                if (e.getValue() != o) continue;
                Material in = e.getKey();
                ItemStack cur = fi.getSmelting(), res = fi.getResult();
                if (w.count(in) > 0 || (cur != null && cur.getType() == in) || (res != null && res.getType() == o)
                        || Work.inStorage(w, t, x -> x == in)) source = true;
            }
            if (!source) Requests.decline(w.plugin, t, w, o, "I have nothing to make " + Work.nice(o) + " from");
        }

        ItemStack in = fi.getSmelting(), fuel = fi.getFuel(), out = fi.getResult();
        boolean hasInputHere = in != null && !in.getType().isAir();
        boolean fuelHere = fuel != null && !fuel.getType().isAir();
        boolean resultReady = out != null && !out.getType().isAir()
                && (out.getAmount() >= 8 || (asked.containsKey(out.getType()) && out.getAmount() + w.count(out.getType()) >= asked.get(out.getType())));
        boolean carryInput = carries(w, wantedInput);
        boolean carryFuel = carries(w, FurnaceJob::isFuel);
        // Only worth a walk when what it carries can actually go in (the same item as already in there, or an empty slot).
        ItemStack curIn = in, curFuel = fuel;
        boolean inputFits = carries(w, m -> wantedInput.test(m) && fits(curIn, m));
        boolean fuelFits = carries(w, m -> isFuel(m) && fits(curFuel, m));
        boolean resultFits = out == null || out.getType().isAir() || w.pockets.firstEmpty() >= 0 || w.pockets.containsAtLeast(new ItemStack(out.getType()), 1);

        if (Work.needsDeposit(w, keep, limit, false)) {
            Activity d = Work.deposit(w, t, keep, limit);
            if (d != null) return d;
        }
        // Go to the furnace: take out the results, put in what it carries.
        boolean roomForInput = !hasInputHere || in.getAmount() < in.getMaxStackSize();
        // Fuel burns on whether there's anything left to smelt or not: a cold furnace only starts with a full
        // batch (8, what one coal does), unless fewer are wanted (a request, or a target that's almost there).
        boolean burning = furnace.getBurnTime() > 0;
        int batch = (hasInputHere ? in.getAmount() : 0) + countCarried(w, wantedInput);
        boolean batchOk = burning || batch >= 8 || fewerWanted(w, t, recipes, asked, hasInputHere ? in.getType() : null, batch, radius);
        if (w.isSkipped(fb)) { // just tended it and nothing could be done: not again right away
            w.workStatus = hasInputHere ? capital(verb(wp)) + "ing" : "Waiting";
            return null;
        }
        if ((resultReady && resultFits) || (inputFits && roomForInput && batchOk)
                || (fuelFits && (!fuelHere || fuel.getAmount() < 8) && (hasInputHere || inputFits) && batchOk)) {
            w.workStatus = "Tending the " + Work.nice(fb.getType());
            return new GoAndDo(w, fb, 2.0, "Tending the " + Work.nice(fb.getType()), b -> b.getState(false) instanceof Furnace, b -> tend(w, b, wantedInput));
        }
        // The cook's crafted food (bread, cake, stews...) by storage target.
        String foodNote = null; // what the cook misses for that food: shown when it has nothing else to do
        if (wp.job() == Worker.Job.COOK) {
            w.workStatus = "";
            Activity food = Cook.next(w, t, wp);
            if (food != null) return food;
            if (!w.workStatus.isEmpty()) foodNote = w.workStatus;
        }
        // Get things to cook/smelt (while the furnace runs low), and fuel.
        if ((!carryInput && (!hasInputHere || in.getAmount() < 16)) || (!batchOk && w.pockets.firstEmpty() >= 0)) {
            Activity a = Work.fetch(w, t, wantedInput, 64, "Getting things to " + verb(wp));
            if (a != null) return a;
        }
        if (!batchOk) {
            w.workStatus = "Waiting for enough to " + verb(wp) + " (8 for a coal)";
            return null;
        }
        if (!carryFuel && (!fuelHere || fuel.getAmount() < 4) && (hasInputHere || carryInput)) {
            Activity a = Work.fetch(w, t, m -> GOOD_FUEL.contains(m), 16, "Getting fuel");
            if (a == null) a = Work.fetch(w, t, FurnaceJob::isFuel, 16, "Getting fuel");
            if (a != null) return a;
            w.workStatus = "Needs fuel (coal, charcoal, wood) in storage";
            return null;
        }
        if (out != null && !out.getType().isAir() && !hasInputHere && resultFits) {
            // Done smelting, a few results left: collect them.
            w.workStatus = "Tending the " + Work.nice(fb.getType());
            return new GoAndDo(w, fb, 2.0, "Tending the " + Work.nice(fb.getType()), b -> b.getState(false) instanceof Furnace, b -> tend(w, b, wantedInput));
        }
        if (Work.needsDeposit(w, keep, limit, true)) {
            Activity d = Work.deposit(w, t, keep, limit);
            if (d != null) return d;
        }
        if (hasInputHere) w.workStatus = capital(verb(wp)) + "ing";
        else if (foodNote != null) w.workStatus = foodNote;
        else if (recipes.isEmpty()) w.workStatus = "Nothing allowed to make here";
        else if (toOrder && recipes.values().stream().noneMatch(o -> t.targets.getOrDefault(o, 0) > 0)) w.workStatus = "Nothing to make (set targets in Storage targets)";
        else w.workStatus = "Nothing to " + verb(wp) + " in storage";
        return null;
    }

    /** At the furnace: take out the results, put in input and fuel. */
    private static void tend(Worker w, Block b, Predicate<Material> wantedInput) {
        if (!(b.getState(false) instanceof Furnace f)) return;
        FurnaceInventory fi = f.getInventory();
        String before = String.valueOf(fi.getResult()) + fi.getSmelting() + fi.getFuel();
        ItemStack out = fi.getResult();
        if (out != null && !out.getType().isAir()) {
            Map<Integer, ItemStack> left = w.pockets.addItem(out.clone());
            fi.setResult(left.isEmpty() ? null : left.values().iterator().next());
        }
        fill(w, fi, true, wantedInput);
        fill(w, fi, false, FurnaceJob::isFuel);
        w.savePockets();
        boolean changed = !before.equals(String.valueOf(fi.getResult()) + fi.getSmelting() + fi.getFuel());
        if (!changed) w.skip(b, 600); // nothing it could do here: leave it for half a minute
    }

    private static int countCarried(Worker w, Predicate<Material> test) {
        int n = 0;
        for (ItemStack it : w.pockets.getContents()) if (it != null && test.test(it.getType())) n += it.getAmount();
        return n;
    }

    /** Fewer than a batch is all that's wanted of what this makes (asked for, or a target that's nearly reached). */
    private static boolean fewerWanted(Worker w, Town t, Map<Material, Material> recipes, Map<Material, Integer> asked, Material inFurnace,
                                       int batch, int radius) {
        Set<Material> outs = new HashSet<>();
        if (inFurnace != null && recipes.containsKey(inFurnace)) outs.add(recipes.get(inFurnace));
        for (ItemStack it : w.pockets.getContents()) if (it != null && recipes.containsKey(it.getType())) outs.add(recipes.get(it.getType()));
        if (outs.isEmpty()) return false;
        for (Material o : outs) {
            if (asked.containsKey(o)) continue;
            Integer target = t.targets.get(o);
            if (target == null || target - t.count(o, radius) > batch) return false; // more wanted than it has: wait for more
        }
        return true;
    }

    /** Can this go into a furnace slot holding {@code cur}? */
    private static boolean fits(ItemStack cur, Material m) {
        return cur == null || cur.getType().isAir() || (cur.getType() == m && cur.getAmount() < cur.getMaxStackSize());
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
