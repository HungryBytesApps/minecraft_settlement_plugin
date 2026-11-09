package villagerworkers;

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

    static final List<Material> PRODUCTS = List.of(
            Material.ARROW, Material.BOW, Material.CROSSBOW, Material.SHIELD,
            Material.STONE_SWORD, Material.IRON_SWORD, Material.DIAMOND_SWORD,
            Material.STONE_AXE, Material.IRON_AXE, Material.DIAMOND_AXE,
            Material.IRON_HOE, Material.SHEARS,
            Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);

    /** What one craft needs (material → amount) and gives. */
    private record Plan(Material product, int amount, Map<Material, Integer> needs) {}

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        int radius = w.plugin.settings().townRadius();
        Predicate<Material> keep = m -> !PRODUCTS.contains(m) && !m.isAir();
        if (Work.needsDeposit(w, keep, true)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        boolean anyWanted = false;
        String missing = null;
        for (Material product : PRODUCTS) {
            int target = t.targets.getOrDefault(product, 0);
            if (target <= 0 || t.count(product, radius) >= target) continue;
            anyWanted = true;
            Plan plan = plan(w, t, product);
            if (plan == null) {
                if (missing == null) missing = Work.nice(product);
                continue;
            }
            // Fetch what it doesn't carry yet, then work at the table.
            for (Map.Entry<Material, Integer> e : plan.needs().entrySet()) {
                int short_ = e.getValue() - w.count(e.getKey());
                if (short_ > 0) {
                    Activity f = Work.fetch(w, t, m -> m == e.getKey(), short_, "Getting " + Work.nice(e.getKey()));
                    if (f != null) return f;
                }
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
        w.workStatus = !anyWanted ? "Nothing to make (set targets in Storage targets)" : "Missing materials for " + missing;
        return null;
    }

    private static void craft(Worker w, Block table, Plan plan) {
        for (Map.Entry<Material, Integer> e : plan.needs().entrySet()) if (w.count(e.getKey()) < e.getValue()) return;
        for (Map.Entry<Material, Integer> e : plan.needs().entrySet()) w.take(e.getKey(), e.getValue());
        w.give(List.of(new ItemStack(plan.product(), plan.amount())), w.body.getLocation());
        table.getWorld().playSound(table.getLocation(), "minecraft:block.anvil.use", SoundCategory.BLOCKS, 0.6f, 1.1f);
    }

    /** A recipe for the product whose materials are in storage plus pockets, or null. */
    private static Plan plan(Worker w, Town t, Material product) {
        int radius = w.plugin.settings().townRadius();
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
                    int have = t.count(m, radius) + w.count(m) - needs.getOrDefault(m, 0);
                    if (have > 0) {
                        pick = m;
                        break;
                    }
                }
                if (pick == null) {
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
