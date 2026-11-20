package settlement;

import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Predicate;

/**
 * Getting the ingredients for something: what a settler carries, else from storage, else it makes it itself
 * (sugar, glass bottles, bowls, planks, blaze powder, gold nuggets, glistering melon...), else it asks the
 * settler whose job makes it (glass, gold ingots, milk...). Ingredients never need storage targets.
 */
final class Supply {
    private Supply() {}

    /** What to do next to get it (act), or why it can't (missing). Both null: it has everything. */
    record Result(Activity act, String missing) {
        boolean ready() {
            return act == null && missing == null;
        }

        static final Result READY = new Result(null, null);
    }

    /** A recipe a settler makes by hand: amount per craft and what it uses. */
    record Recipe(Material out, int yield, Map<Material, Integer> in) {}

    static final Map<Material, Recipe> SELF = new EnumMap<>(Material.class);

    static {
        add(Material.SUGAR, 1, Material.SUGAR_CANE, 1);
        add(Material.GLASS_BOTTLE, 3, Material.GLASS, 3);
        add(Material.BLAZE_POWDER, 2, Material.BLAZE_ROD, 1);
        add(Material.GOLD_NUGGET, 9, Material.GOLD_INGOT, 1);
        add(Material.GLISTERING_MELON_SLICE, 1, Material.MELON_SLICE, 1, Material.GOLD_NUGGET, 8);
        add(Material.GOLDEN_CARROT, 1, Material.CARROT, 1, Material.GOLD_NUGGET, 8);
        add(Material.FERMENTED_SPIDER_EYE, 1, Material.SPIDER_EYE, 1, Material.BROWN_MUSHROOM, 1, Material.SUGAR, 1);
        add(Material.MAGMA_CREAM, 1, Material.BLAZE_POWDER, 1, Material.SLIME_BALL, 1);
    }

    private static void add(Material out, int yield, Object... in) {
        Map<Material, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < in.length; i += 2) m.put((Material) in[i], (Integer) in[i + 1]);
        SELF.put(out, new Recipe(out, yield, m));
    }

    /** Everything these recipes use, also further down (to keep in the pockets while working). */
    static Set<Material> ingredientsOf(Material product) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        collect(product, out, 0);
        return out;
    }

    private static void collect(Material m, Set<Material> out, int depth) {
        Recipe r = SELF.get(m);
        if (m == Material.BOWL) {
            out.addAll(Tag.PLANKS.getValues());
            out.addAll(Tag.LOGS.getValues());
        }
        if (r == null || depth > 4) return;
        for (Material in : r.in().keySet()) {
            out.add(in);
            collect(in, out, depth + 1);
        }
    }

    /** Make sure the settler carries {@code amount} of {@code m}: returns what to do next. */
    static Result obtain(Worker w, Town t, Material m, int amount) {
        return obtain(w, t, m, amount, 0);
    }

    private static Result obtain(Worker w, Town t, Material m, int amount, int depth) {
        int have = w.count(m);
        if (have >= amount) return Result.READY;
        int short_ = amount - have;
        if (depth > 4) return new Result(null, Work.nice(m));
        // 1. From storage.
        if (Work.inStorage(w, t, x -> x == m)) {
            Activity f = Work.fetch(w, t, x -> x == m, short_, "Getting " + Work.nice(m));
            if (f != null) return new Result(f, null);
        }
        // 2. Bowls: from any planks (and planks from any log).
        if (m == Material.BOWL) return bowls(w, t, short_);
        // 3. Made by hand.
        Recipe r = SELF.get(m);
        if (r != null) {
            int crafts = (short_ + r.yield() - 1) / r.yield();
            for (Map.Entry<Material, Integer> e : r.in().entrySet()) {
                Result sub = obtain(w, t, e.getKey(), e.getValue() * crafts, depth + 1);
                if (!sub.ready()) return sub;
            }
            return new Result(craft(w, r, crafts), null);
        }
        // 4. Ask the settler whose job makes it.
        Activity ask = Requests.need(w, t, m, short_);
        if (ask != null) return new Result(ask, null);
        return new Result(null, Work.nice(m));
    }

    private static int countAny(Worker w, Predicate<Material> test) {
        int n = 0;
        for (ItemStack it : w.pockets.getContents()) if (it != null && test.test(it.getType())) n += it.getAmount();
        return n;
    }

    private static Result bowls(Worker w, Town t, int need) {
        int crafts = (need + 3) / 4; // 3 planks → 4 bowls
        int planksNeeded = crafts * 3;
        int planks = countAny(w, Tag.PLANKS::isTagged);
        if (planks < planksNeeded) {
            if (Work.inStorage(w, t, Tag.PLANKS::isTagged)) {
                Activity f = Work.fetch(w, t, Tag.PLANKS::isTagged, planksNeeded - planks, "Getting planks");
                if (f != null) return new Result(f, null);
            }
            int logs = countAny(w, Tag.LOGS::isTagged);
            int logsNeeded = (planksNeeded - planks + 3) / 4;
            if (logs < logsNeeded) {
                Activity f = Work.fetch(w, t, Tag.LOGS::isTagged, logsNeeded - logs, "Getting wood");
                if (f != null) return new Result(f, null);
                return new Result(null, "wood for bowls");
            }
            return new Result(new WorkHere(w, 20, "Making planks", "minecraft:block.wood.hit", () -> {
                for (int i = 0; i < logsNeeded; i++) {
                    Material log = firstOf(w, Tag.LOGS::isTagged);
                    if (log == null || !w.take(log, 1)) break;
                    w.give(List.of(new ItemStack(planksFor(log), 4)), w.body.getLocation());
                }
            }), null);
        }
        return new Result(new WorkHere(w, 20, "Making bowls", "minecraft:block.wood.place", () -> {
            for (int i = 0; i < crafts; i++) {
                for (int p = 0; p < 3; p++) {
                    Material pl = firstOf(w, Tag.PLANKS::isTagged);
                    if (pl == null) return;
                    w.take(pl, 1);
                }
                w.give(List.of(new ItemStack(Material.BOWL, 4)), w.body.getLocation());
            }
        }), null);
    }

    private static Material firstOf(Worker w, Predicate<Material> test) {
        for (ItemStack it : w.pockets.getContents()) if (it != null && test.test(it.getType())) return it.getType();
        return null;
    }

    /** OAK_LOG → OAK_PLANKS, CRIMSON_STEM → CRIMSON_PLANKS, … (oak if unknown). */
    private static Material planksFor(Material log) {
        String n = log.name().replace("STRIPPED_", "").replace("_LOG", "").replace("_WOOD", "").replace("_STEM", "").replace("_HYPHAE", "");
        Material p = Material.matchMaterial(n + "_PLANKS");
        return p != null ? p : Material.OAK_PLANKS;
    }

    private static Activity craft(Worker w, Recipe r, int crafts) {
        return new WorkHere(w, 25, "Making " + Work.nice(r.out()), "minecraft:block.crafter.craft", () -> {
            for (int i = 0; i < crafts; i++) {
                for (Map.Entry<Material, Integer> e : r.in().entrySet()) if (w.count(e.getKey()) < e.getValue()) return;
                for (Map.Entry<Material, Integer> e : r.in().entrySet()) w.take(e.getKey(), e.getValue());
                w.give(List.of(new ItemStack(r.out(), r.yield())), w.body.getLocation());
            }
            w.savePockets();
        });
    }

    /** Work with the hands where it stands for a moment, then the action happens. */
    static final class WorkHere implements Activity {
        private final Worker w;
        private final int ticks;
        private final String label, sound;
        private final Runnable action;
        private int time;

        WorkHere(Worker w, int ticks, String label, String sound, Runnable action) {
            this.w = w;
            this.ticks = ticks;
            this.label = label;
            this.sound = sound;
            this.action = action;
        }

        @Override
        public boolean tick() {
            if (time == 0) {
                w.mover.stop();
                w.workStatus = label;
            }
            if (time % 8 == 0) {
                w.body.swingMainHand();
                w.body.getWorld().playSound(w.body.getLocation(), sound, SoundCategory.NEUTRAL, 0.5f, 1f);
            }
            if (++time >= ticks) {
                action.run();
                return true;
            }
            return false;
        }

        @Override
        public String label() {
            return label;
        }
    }
}
