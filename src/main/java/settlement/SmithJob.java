package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.*;
import java.util.function.Predicate;

/**
 * Smith (smithing table): makes weapons, armor, bows and arrows from materials in storage, but only
 * what you ask for: the storage target of a product is how many to keep in stock (0 = don't make).
 * Fetches the materials, works at the table for a bit, brings the result to storage.
 */
final class SmithJob {
    private SmithJob() {}

    /** The materials it works in (menu filter), then "other" (bows, arrows, shields, shears). */
    static final List<String> METALS = List.of("leather", "stone", "copper", "iron", "golden", "diamond", "netherite");
    private static final List<String> PIECES = List.of("sword", "axe", "pickaxe", "shovel", "hoe", "spear",
            "helmet", "chestplate", "leggings", "boots");

    static final List<Material> PRODUCTS = products();

    private static List<Material> products() {
        List<Material> out = new ArrayList<>(List.of(Material.ARROW, Material.BOW, Material.CROSSBOW, Material.SHIELD, Material.SHEARS,
                Material.FISHING_ROD));
        for (String metal : METALS)
            for (String piece : PIECES) {
                Material m = Material.matchMaterial(metal + "_" + piece); // only what exists (no stone armor)
                if (m != null && m.isItem()) out.add(m);
            }
        return List.copyOf(out);
    }

    /** Which material a product is made of (leather, stone, copper, iron, golden, diamond, netherite), or null: other. */
    static String metal(Material m) {
        String n = m.getKey().getKey();
        for (String metal : METALS) if (n.startsWith(metal + "_")) return metal;
        return null;
    }

    /** What one craft needs (material → amount) and gives. */
    private record Plan(Material product, int amount, Map<Material, Integer> needs) {}

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        int radius = w.plugin.settings().townRadius();
        // Products go to storage, except the ones a wanted product is made from (diamond gear → netherite).
        Set<Material> parts = new HashSet<>();
        for (Material product : PRODUCTS) {
            int target = t.targets.getOrDefault(product, 0);
            if (target <= 0 || t.count(product, radius) + t.carried(w, product) >= target) continue;
            if ("netherite".equals(metal(product))) {
                Material d = Material.matchMaterial("diamond_" + product.getKey().getKey().substring("netherite_".length()));
                if (d != null) parts.add(d);
            }
        }
        Predicate<Material> keep = m -> (!PRODUCTS.contains(m) || parts.contains(m)) && !m.isAir();
        if (Work.needsDeposit(w, keep, true)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        if (org.bukkit.Bukkit.getCurrentTick() < w.storageFullUntil) { // nowhere to put what it makes: don't make more
            w.workStatus = "Storage is full";
            return null;
        }
        if (w.isSkipped(wp.loc.getBlock())) { // can't get to the table lately
            w.workStatus = "Can't get to the smithing table";
            return null;
        }
        boolean anyWanted = false;
        String missing = null; // materials there, but it can't get to them (a problem)
        String waiting = null; // materials simply not in storage (no problem: it waits for them)
        for (Material product : PRODUCTS) {
            int target = t.targets.getOrDefault(product, 0);
            if (target <= 0 || t.count(product, radius) + t.carried(w, product) >= target) continue; // (counting what it carries too)
            anyWanted = true;
            Plan plan = plan(w, t, product);
            if (plan == null) {
                if (waiting == null) waiting = lastMissing + " for " + Work.nice(product).toLowerCase(java.util.Locale.ROOT);
                continue;
            }
            // Fetch what it doesn't carry yet, then work at the table.
            boolean has = true;
            for (Map.Entry<Material, Integer> e : plan.needs().entrySet()) {
                int short_ = e.getValue() - w.count(e.getKey());
                if (short_ > 0) {
                    Activity f = Work.fetch(w, t, m -> m == e.getKey(), short_, "Getting " + Work.nice(e.getKey()));
                    if (f != null) return f;
                    has = false; // it's there, but in a chest it may not take from (or can't get to)
                }
            }
            if (!has) {
                if (missing == null) missing = Work.nice(product);
                continue;
            }
            w.workStatus = "Making " + Work.nice(product);
            Block table = wp.loc.getBlock();
            return new GoAndDo(w, table, 2.0, "Making " + Work.nice(product), b -> b.getType() == Material.SMITHING_TABLE,
                    b -> craft(w, b, plan)).working(60, "minecraft:block.smithing_table.use");
        }
        // Leftover materials: back to storage.
        Predicate<Material> keepNothing = Work::gear;
        if (!anyWanted && Work.needsDeposit(w, keepNothing, true)) {
            Activity d = Work.deposit(w, t, keepNothing);
            if (d != null) return d;
        }
        w.workStatus = !anyWanted ? "Nothing to make (set targets in Storage targets)"
                : missing != null ? "Missing materials for " + missing // in storage, but it can't take them
                : "Waiting for " + waiting; // not in storage: not a problem, it makes it once there is some
        return null;
    }

    private static void craft(Worker w, Block table, Plan plan) {
        for (Map.Entry<Material, Integer> e : plan.needs().entrySet()) if (w.count(e.getKey()) < e.getValue()) return;
        for (Map.Entry<Material, Integer> e : plan.needs().entrySet()) w.take(e.getKey(), e.getValue());
        w.give(List.of(new ItemStack(plan.product(), plan.amount())), w.body.getLocation());
        table.getWorld().playSound(table.getLocation(), "minecraft:block.anvil.use", SoundCategory.BLOCKS, 0.6f, 1.1f);
    }

    /** What the last failed plan lacked (e.g. "flint"). */
    private static String lastMissing = "materials";

    /** A recipe for the product whose materials are in storage plus pockets, or null. */
    private static Plan plan(Worker w, Town t, Material product) {
        lastMissing = "materials";
        int radius = w.plugin.settings().townRadius();
        if ("netherite".equals(metal(product))) { // smithing: the diamond one + a netherite ingot + the upgrade template
            Material diamond = Material.matchMaterial("diamond_" + product.getKey().getKey().substring("netherite_".length()));
            if (diamond == null) return null;
            Map<Material, Integer> needs = Map.of(diamond, 1, Material.NETHERITE_INGOT, 1, Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1);
            for (Map.Entry<Material, Integer> e : needs.entrySet())
                if (Work.available(t, e.getKey(), radius) + w.count(e.getKey()) < e.getValue()) {
                    lastMissing = Work.nice(e.getKey()).toLowerCase(java.util.Locale.ROOT);
                    return null;
                }
            return new Plan(product, 1, needs);
        }
        for (Recipe r : Bukkit.getRecipesFor(new ItemStack(product))) {
            List<RecipeChoice> choices = new ArrayList<>();
            if (r instanceof ShapedRecipe sr) {
                Map<Character, RecipeChoice> map = sr.getChoiceMap();
                for (String row : sr.getShape()) {
                    for (char c : row.toCharArray()) {
                        RecipeChoice ch = map.get(c);
                        if (ch != null) choices.add(ch);
                    }
                }
            } else if (r instanceof ShapelessRecipe sl) {
                choices.addAll(sl.getChoiceList());
            } else {
                continue;
            }
            Map<Material, Integer> needs = new HashMap<>();
            boolean ok = true;
            for (RecipeChoice ch : choices) {
                if (!(ch instanceof RecipeChoice.MaterialChoice mc)) {
                    ok = false;
                    break;
                }
                Material pick = null;
                for (Material m : mc.getChoices()) {
                    int have = Work.available(t, m, radius) + w.count(m) - needs.getOrDefault(m, 0);
                    if (have > 0) {
                        pick = m;
                        break;
                    }
                }
                if (pick == null) {
                    List<Material> cs = mc.getChoices();
                    lastMissing = cs.size() == 1 ? Work.nice(cs.get(0)).toLowerCase(java.util.Locale.ROOT)
                            : Work.nice(cs.get(0)).toLowerCase(java.util.Locale.ROOT) + " (or similar)";
                    ok = false;
                    break;
                }
                needs.merge(pick, 1, Integer::sum);
            }
            if (ok && !needs.isEmpty()) return new Plan(product, r.getResult().getAmount(), needs);
        }
        return null;
    }
}
