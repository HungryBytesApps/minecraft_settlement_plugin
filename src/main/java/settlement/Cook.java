package settlement;

import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * The cook's crafting-table food, made at its smoker by storage target (no target or 0 = don't make):
 * bread, cookies, cake, pumpkin pie and the stews/soups. Ingredients come from storage, are made by hand
 * (sugar, bowls) or asked for (milk from the herder).
 */
final class Cook {
    private Cook() {}

    /** Product → (amount made, ingredients). In menu order. */
    static final Map<Material, Supply.Recipe> FOOD = new LinkedHashMap<>();

    static {
        add(Material.BREAD, 1, Material.WHEAT, 3);
        add(Material.COOKIE, 8, Material.WHEAT, 2, Material.COCOA_BEANS, 1);
        add(Material.CAKE, 1, Material.MILK_BUCKET, 3, Material.SUGAR, 2, Material.EGG, 1, Material.WHEAT, 3);
        add(Material.PUMPKIN_PIE, 1, Material.PUMPKIN, 1, Material.SUGAR, 1, Material.EGG, 1);
        add(Material.MUSHROOM_STEW, 1, Material.BOWL, 1, Material.RED_MUSHROOM, 1, Material.BROWN_MUSHROOM, 1);
        add(Material.BEETROOT_SOUP, 1, Material.BOWL, 1, Material.BEETROOT, 6);
        add(Material.RABBIT_STEW, 1, Material.BOWL, 1, Material.COOKED_RABBIT, 1, Material.CARROT, 1, Material.BAKED_POTATO, 1, Material.BROWN_MUSHROOM, 1);
    }

    private static void add(Material out, int yield, Object... in) {
        Map<Material, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < in.length; i += 2) m.put((Material) in[i], (Integer) in[i + 1]);
        FOOD.put(out, new Supply.Recipe(out, yield, m));
    }

    static boolean wanted(Town t, Material product, int radius) {
        Integer target = t.targets.get(product);
        return target != null && target > 0 && t.count(product, radius) < target;
    }

    /** Ingredients (and what they're made from) of the food the settlement wants now: kept in the pockets. */
    static Set<Material> keep(Town t, int radius) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (Supply.Recipe r : FOOD.values()) {
            if (!wanted(t, r.out(), radius)) continue;
            for (Material in : r.in().keySet()) {
                out.add(in);
                out.addAll(Supply.ingredientsOf(in));
            }
        }
        return out;
    }

    /** The next step towards wanted food, or null (nothing wanted, or ingredients missing: see workStatus). */
    static Activity next(Worker w, Town t, Town.Workplace wp) {
        int radius = w.plugin.settings().townRadius();
        String missing = null;
        for (Supply.Recipe r : FOOD.values()) {
            if (!wanted(t, r.out(), radius)) continue;
            boolean ready = true;
            for (Map.Entry<Material, Integer> e : r.in().entrySet()) {
                Supply.Result got = Supply.obtain(w, t, e.getKey(), e.getValue());
                if (got.act() != null) return got.act();
                if (got.missing() != null) {
                    if (missing == null) missing = got.missing() + " for " + Work.nice(r.out());
                    ready = false;
                    break;
                }
            }
            if (!ready) continue;
            Block smoker = wp.loc.getBlock();
            w.workStatus = "Making " + Work.nice(r.out());
            return new GoAndDo(w, smoker, 2.0, "Making " + Work.nice(r.out()), b -> b.getType() == Material.SMOKER, b -> {
                for (Map.Entry<Material, Integer> e : r.in().entrySet()) if (w.count(e.getKey()) < e.getValue()) return;
                for (Map.Entry<Material, Integer> e : r.in().entrySet()) w.take(e.getKey(), e.getValue());
                List<ItemStack> made = new ArrayList<>();
                made.add(new ItemStack(r.out(), r.yield()));
                if (r.in().containsKey(Material.MILK_BUCKET)) made.add(new ItemStack(Material.BUCKET, r.in().get(Material.MILK_BUCKET)));
                w.give(made, b.getLocation().add(0.5, 1, 0.5));
                w.savePockets();
                b.getWorld().playSound(b.getLocation(), "minecraft:block.crafter.craft", SoundCategory.BLOCKS, 0.7f, 1f);
            }).working(50, "minecraft:block.wood.hit");
        }
        if (missing != null) w.workStatus = "Needs " + missing;
        return null;
    }
}
