package settlement;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.*;

/**
 * Brewer (brewing stand): brews every potion by storage target (no target = don't brew), with the real
 * brewing stand: fills glass bottles with water, puts in nether wart, then the ingredients one by one
 * (redstone for longer, glowstone for stronger, gunpowder for splash, dragon's breath for lingering), fuels
 * the stand with blaze powder. Makes the in-between ingredients itself (blaze powder, glistering melon,
 * golden carrot, fermented spider eye, magma cream, sugar, bottles) and asks for glass and gold.
 */
final class Brewer {
    private Brewer() {}

    /** A potion: its form (potion, splash, lingering) and type. */
    record Kind(Material form, PotionType type) {
        String key() {
            return form.name() + ":" + type.name();
        }

        static Kind of(String key) {
            try {
                String[] p = key.split(":");
                return new Kind(Material.valueOf(p[0]), PotionType.valueOf(p[1]));
            } catch (RuntimeException e) {
                return null;
            }
        }

        static Kind of(ItemStack it) {
            if (it == null) return null;
            Material f = it.getType();
            if (f != Material.POTION && f != Material.SPLASH_POTION && f != Material.LINGERING_POTION) return null; // (before reading its data: quicker)
            if (!(it.getItemMeta() instanceof PotionMeta pm) || pm.getBasePotionType() == null) return null;
            return new Kind(f, pm.getBasePotionType());
        }

        ItemStack item(int amount) {
            ItemStack it = new ItemStack(form, amount);
            if (it.getItemMeta() instanceof PotionMeta pm) {
                pm.setBasePotionType(type);
                it.setItemMeta(pm);
            }
            return it;
        }

        String name() {
            String n = type.name();
            String extra = "";
            if (n.startsWith("LONG_")) {
                n = n.substring(5);
                extra = " (long)";
            } else if (n.startsWith("STRONG_")) {
                n = n.substring(7);
                extra = " II";
            }
            String prefix = form == Material.SPLASH_POTION ? "Splash potion" : form == Material.LINGERING_POTION ? "Lingering potion" : "Potion";
            return prefix + " of " + effect(n) + extra;
        }
    }

    static String effect(String base) {
        String s = base.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The effect group (HEALING for STRONG_HEALING, ...). */
    static String base(PotionType t) {
        String n = t.name();
        if (n.startsWith("LONG_")) return n.substring(5);
        if (n.startsWith("STRONG_")) return n.substring(7);
        return n;
    }

    private static PotionType pt(String name) {
        try {
            return PotionType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- The brewing tree ----

    /** From a potion, with an ingredient, to another potion. */
    private record Step(Kind from, Material ingredient, Kind to) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static final Kind WATER = new Kind(Material.POTION, PotionType.WATER);
    /** Everything that can be brewed (not water/awkward), in a fixed order. */
    static final List<Kind> ALL = new ArrayList<>();

    static {
        Map<String, Material> fromAwkward = new LinkedHashMap<>();
        fromAwkward.put("SWIFTNESS", Material.SUGAR);
        fromAwkward.put("LEAPING", Material.RABBIT_FOOT);
        fromAwkward.put("HEALING", Material.GLISTERING_MELON_SLICE);
        fromAwkward.put("POISON", Material.SPIDER_EYE);
        fromAwkward.put("WATER_BREATHING", Material.PUFFERFISH);
        fromAwkward.put("FIRE_RESISTANCE", Material.MAGMA_CREAM);
        fromAwkward.put("NIGHT_VISION", Material.GOLDEN_CARROT);
        fromAwkward.put("STRENGTH", Material.BLAZE_POWDER);
        fromAwkward.put("REGENERATION", Material.GHAST_TEAR);
        fromAwkward.put("TURTLE_MASTER", Material.TURTLE_HELMET);
        fromAwkward.put("SLOW_FALLING", Material.PHANTOM_MEMBRANE);
        fromAwkward.put("WIND_CHARGED", Material.BREEZE_ROD);
        fromAwkward.put("WEAVING", Material.COBWEB);
        fromAwkward.put("OOZING", Material.SLIME_BLOCK);
        fromAwkward.put("INFESTED", Material.STONE);
        PotionType awkward = PotionType.AWKWARD;
        step(PotionType.WATER, Material.NETHER_WART, awkward);
        step(PotionType.WATER, Material.FERMENTED_SPIDER_EYE, pt("WEAKNESS"));
        for (Map.Entry<String, Material> e : fromAwkward.entrySet()) step(awkward, e.getValue(), pt(e.getKey()));
        String[][] corrupt = {{"SWIFTNESS", "SLOWNESS"}, {"LEAPING", "SLOWNESS"}, {"LONG_SWIFTNESS", "LONG_SLOWNESS"}, {"LONG_LEAPING", "LONG_SLOWNESS"},
                {"HEALING", "HARMING"}, {"POISON", "HARMING"}, {"LONG_POISON", "HARMING"}, {"STRONG_HEALING", "STRONG_HARMING"},
                {"STRONG_POISON", "STRONG_HARMING"}, {"NIGHT_VISION", "INVISIBILITY"}, {"LONG_NIGHT_VISION", "LONG_INVISIBILITY"}};
        for (String[] c : corrupt) step(pt(c[0]), Material.FERMENTED_SPIDER_EYE, pt(c[1]));
        for (PotionType t : PotionType.values()) {
            String n = t.name();
            if (n.startsWith("LONG_") || n.startsWith("STRONG_")) continue;
            step(t, Material.REDSTONE, pt("LONG_" + n));
            step(t, Material.GLOWSTONE_DUST, pt("STRONG_" + n));
        }
        // Splash and lingering of everything.
        List<Step> drinkable = new ArrayList<>(STEPS);
        Set<PotionType> types = new LinkedHashSet<>();
        for (Step s : drinkable) types.add(s.to().type());
        for (PotionType t : types) {
            STEPS.add(new Step(new Kind(Material.POTION, t), Material.GUNPOWDER, new Kind(Material.SPLASH_POTION, t)));
            STEPS.add(new Step(new Kind(Material.SPLASH_POTION, t), Material.DRAGON_BREATH, new Kind(Material.LINGERING_POTION, t)));
        }
        List<PotionType> sorted = new ArrayList<>(types);
        sorted.removeIf(t -> t == PotionType.AWKWARD || t == PotionType.WATER);
        sorted.sort(Comparator.comparing((PotionType t) -> base(t)).thenComparing(PotionType::name));
        for (Material form : new Material[]{Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION}) {
            for (PotionType t : sorted) ALL.add(new Kind(form, t));
        }
        ALL.sort(Comparator.comparing((Kind k) -> base(k.type())).thenComparing(k -> k.type().name()).thenComparing(k -> k.form().ordinal()));
    }

    private static void step(PotionType from, Material ingredient, PotionType to) {
        if (from == null || to == null) return;
        STEPS.add(new Step(new Kind(Material.POTION, from), ingredient, new Kind(Material.POTION, to)));
    }

    /** The ingredients from one potion to another (shortest way), or null if it can't be done. */
    static List<Material> path(Kind from, Kind to) {
        if (from.equals(to)) return List.of();
        Map<Kind, Step> via = new HashMap<>();
        Deque<Kind> queue = new ArrayDeque<>(List.of(from));
        Set<Kind> seen = new HashSet<>(List.of(from));
        while (!queue.isEmpty()) {
            Kind k = queue.poll();
            for (Step s : STEPS) {
                if (!s.from().equals(k) || !seen.add(s.to())) continue;
                via.put(s.to(), s);
                if (s.to().equals(to)) {
                    LinkedList<Material> out = new LinkedList<>();
                    for (Kind c = to; !c.equals(from); c = via.get(c).from()) out.addFirst(via.get(c).ingredient());
                    return out;
                }
                queue.add(s.to());
            }
        }
        return null;
    }

    /** Everything a brewer may carry for its work (not the finished potions). */
    static boolean keep(Material m) {
        if (m == Material.GLASS_BOTTLE || m == Material.GLASS || m == Material.BLAZE_ROD || m == Material.BLAZE_POWDER) return true;
        for (Step s : STEPS) if (s.ingredient() == m) return true;
        for (Material made : List.of(Material.GLISTERING_MELON_SLICE, Material.GOLDEN_CARROT, Material.FERMENTED_SPIDER_EYE, Material.MAGMA_CREAM, Material.SUGAR)) {
            if (Supply.ingredientsOf(made).contains(m)) return true;
        }
        return false;
    }

    // ---- Storage ----

    /** Everything brewing uses and makes (for chest filters). */
    static Set<Material> stock() {
        Set<Material> out = EnumSet.of(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION, Material.GLASS_BOTTLE,
                Material.BLAZE_POWDER, Material.BLAZE_ROD);
        for (Step s : STEPS) out.add(s.ingredient());
        return out;
    }

    /** How many of this potion are in the chests settlers may use. */
    static int stored(Town t, Kind k, int radius) {
        int n = 0;
        for (Block b : t.containers(radius)) {
            boolean[] r = t.rule(b);
            if (!r[0] && !r[1]) continue; // not theirs (switched off, or private)
            Inventory inv = Town.inventory(b);
            if (inv == null) continue;
            for (ItemStack it : inv.getContents()) if (k.equals(Kind.of(it))) n += it.getAmount();
        }
        return n;
    }

    static boolean wanted(Town t, Kind k, int radius) {
        Integer target = t.potionTargets.get(k.key());
        return target != null && target > 0 && stored(t, k, radius) < target;
    }

    // ---- The work ----

    /** What each stand is brewing towards. */
    private static final Map<Block, Kind> brewing = new HashMap<>();
    /** When an ingredient went in (to notice one that doesn't brew). */
    private static final Map<Block, Integer> putInAt = new HashMap<>();
    private static final Map<String, Integer> skipKind = new HashMap<>();

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        Block sb = wp.loc.getBlock();
        if (!(sb.getState(false) instanceof BrewingStand stand)) return null;
        BrewerInventory inv = stand.getInventory();
        int radius = w.plugin.settings().townRadius();
        int now = Bukkit.getCurrentTick();

        // Finished potions to storage (the real items, with their potion), other leftovers too.
        Activity potions = depositPotions(w, t, radius);
        if (potions != null) return potions;
        java.util.function.Predicate<Material> keepAll = m -> keep(m) || isPotion(m);
        if (Work.needsDeposit(w, keepAll, false)) {
            Activity d = Work.deposit(w, t, keepAll);
            if (d != null) return d;
        }
        if (stand.getBrewingTime() > 0) {
            Kind goal = brewing.get(sb);
            w.workStatus = "Brewing" + (goal != null ? " " + goal.name().toLowerCase(Locale.ROOT) : "");
            putInAt.remove(sb);
            return null;
        }
        List<ItemStack> bottles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) bottles.add(it);
        }
        ItemStack ing = inv.getIngredient();
        boolean ingredientIn = ing != null && !ing.getType().isAir();

        if (!bottles.isEmpty()) {
            Kind now_ = Kind.of(bottles.get(0));
            boolean same = now_ != null;
            for (ItemStack b : bottles) if (!Objects.equals(Kind.of(b), now_)) same = false;
            Kind goal = brewing.get(sb);
            // An ingredient that doesn't brew (or the plan was lost): take it all out, start again.
            Integer since = putInAt.get(sb);
            boolean stuck = ingredientIn && since != null && now - since > 100 && stand.getFuelLevel() > 0;
            if (!same || stuck || (goal == null && pick(t, now_, radius) == null)) {
                if (goal != null && stuck) skipKind.put(goal.key(), now + 6000);
                brewing.remove(sb);
                putInAt.remove(sb);
                return takeOut(w, sb, true);
            }
            if (goal == null || path(now_, goal) == null) {
                goal = pick(t, now_, radius);
                if (goal == null) { // nothing wanted can be made from these bottles any more
                    brewing.remove(sb);
                    putInAt.remove(sb);
                    return takeOut(w, sb, true);
                }
                brewing.put(sb, goal);
            }
            if (now_.equals(goal)) { // done
                brewing.remove(sb);
                return takeOut(w, sb, false);
            }
            List<Material> todo = path(now_, goal);
            Material next = todo.get(0);
            if (ingredientIn && ing.getType() != next) return takeOut(w, sb, true); // a wrong one in the slot
            boolean needFuel = stand.getFuelLevel() <= 0 && (inv.getFuel() == null || inv.getFuel().getType().isAir());
            if (needFuel) {
                Supply.Result fuel = Supply.obtain(w, t, Material.BLAZE_POWDER, 1);
                if (fuel.act() != null) return fuel.act();
                if (fuel.missing() != null) {
                    w.workStatus = "Needs blaze powder (or blaze rods) for fuel";
                    return null;
                }
            }
            if (!ingredientIn) {
                Supply.Result got = Supply.obtain(w, t, next, 1);
                if (got.act() != null) return got.act();
                if (got.missing() != null) {
                    w.workStatus = "Needs " + got.missing() + " for " + goal.name().toLowerCase(Locale.ROOT);
                    skipKind.put(goal.key(), now + 2400);
                    brewing.remove(sb);
                    return takeOut(w, sb, false);
                }
            }
            Kind g = goal;
            w.workStatus = "Brewing " + g.name().toLowerCase(Locale.ROOT);
            return new GoAndDo(w, sb, 2.0, "Brewing " + g.name().toLowerCase(Locale.ROOT), b -> b.getType() == Material.BREWING_STAND, b -> {
                if (!(b.getState(false) instanceof BrewingStand bs)) return;
                BrewerInventory bi = bs.getInventory();
                if (needFuel && w.take(Material.BLAZE_POWDER, 1)) bi.setFuel(new ItemStack(Material.BLAZE_POWDER));
                ItemStack cur = bi.getIngredient();
                if ((cur == null || cur.getType().isAir()) && w.take(next, 1)) {
                    bi.setIngredient(new ItemStack(next));
                    putInAt.put(b, Bukkit.getCurrentTick());
                }
                w.savePockets();
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), "minecraft:block.brewing_stand.brew", SoundCategory.BLOCKS, 0.6f, 1.2f);
            }).working(20, "minecraft:item.bottle.fill");
        }

        // Empty stand: start a new batch of up to 3.
        Kind goal = pick(t, WATER, radius);
        if (goal == null) {
            // Nothing to brew: the ingredients left over go back to storage (water bottles stay for the next batch).
            java.util.function.Predicate<Material> idleKeep = m -> isPotion(m) || Work.gear(m);
            if (Work.needsDeposit(w, idleKeep, true)) {
                Activity d = Work.deposit(w, t, idleKeep);
                if (d != null) return d;
            }
            w.workStatus = t.potionTargets.isEmpty() ? "Nothing to brew (set potion targets in Storage targets)" : "All potions in stock";
            return null;
        }
        int want = Math.min(3, t.potionTargets.getOrDefault(goal.key(), 0) - stored(t, goal, radius));
        int water = countKind(w, WATER);
        if (water < want) {
            int empty = w.count(Material.GLASS_BOTTLE);
            if (empty < want - water) {
                Supply.Result got = Supply.obtain(w, t, Material.GLASS_BOTTLE, want - water);
                if (got.act() != null) return got.act();
                if (got.missing() != null) {
                    w.workStatus = "Needs glass bottles (or glass)";
                    return null;
                }
            }
            Block wat = water(sb);
            if (wat == null) {
                w.workStatus = "Needs water near the brewing stand";
                return null;
            }
            int toFill = want - water; // only what this batch needs: filled bottles don't stack
            w.workStatus = "Filling bottles with water";
            return new GoAndDo(w, wat, 2.5, "Filling bottles with water", b -> b.getType() == Material.WATER, b -> {
                int n = Math.min(toFill, w.count(Material.GLASS_BOTTLE));
                if (n <= 0 || !w.take(Material.GLASS_BOTTLE, n)) return;
                w.give(List.of(WATER.item(n)), w.body.getLocation());
                w.savePockets();
                b.getWorld().playSound(b.getLocation(), "minecraft:item.bottle.fill", SoundCategory.BLOCKS, 0.8f, 1f);
            });
        }
        Kind g = goal;
        w.workStatus = "Starting " + g.name().toLowerCase(Locale.ROOT);
        return new GoAndDo(w, sb, 2.0, "Putting in bottles", b -> b.getType() == Material.BREWING_STAND, b -> {
            if (!(b.getState(false) instanceof BrewingStand bs)) return;
            BrewerInventory bi = bs.getInventory();
            int put = 0;
            for (int i = 0; i < 3 && put < want; i++) {
                ItemStack cur = bi.getItem(i);
                if (cur != null && !cur.getType().isAir()) continue;
                if (!takeKind(w, WATER)) break;
                bi.setItem(i, WATER.item(1));
                put++;
            }
            if (put > 0) brewing.put(b, g);
            w.savePockets();
            b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), "minecraft:block.glass.place", SoundCategory.BLOCKS, 0.5f, 1.4f);
        });
    }

    static boolean isPotion(Material m) {
        return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION;
    }

    /** Brewed potions in the pockets (not water bottles): into a chest, as they are. */
    private static Activity depositPotions(Worker w, Town t, int radius) {
        boolean any = false;
        for (ItemStack it : w.pockets.getContents()) {
            Kind k = Kind.of(it);
            if (k != null && k.type() != PotionType.WATER) any = true;
        }
        if (!any) return null;
        Block chest = t.storeChest(Material.POTION, w.body.getLocation(), radius);
        if (chest == null) {
            w.warnStorageFull(Material.POTION);
            w.workStatus = "No room in storage for potions";
            return null;
        }
        w.workStatus = "Bringing potions to storage";
        return new GoAndDo(w, chest, 2.0, "Bringing potions to storage", b -> Town.isStorage(b) && t.rule(b)[0], b -> {
            Inventory inv = Town.inventory(b);
            if (inv == null) return;
            for (int i = 0; i < w.pockets.getSize(); i++) {
                ItemStack it = w.pockets.getItem(i);
                Kind k = Kind.of(it);
                if (k == null || k.type() == PotionType.WATER) continue;
                Map<Integer, ItemStack> left = inv.addItem(it.clone());
                w.pockets.setItem(i, left.isEmpty() ? null : left.values().iterator().next());
            }
            w.savePockets();
            t.countsChanged();
        });
    }

    /** A wanted potion that can be brewed from this one (the first in menu order). */
    private static Kind pick(Town t, Kind from, int radius) {
        int now = Bukkit.getCurrentTick();
        for (Kind k : ALL) {
            Integer skip = skipKind.get(k.key());
            if (skip != null && now < skip) continue;
            if (wanted(t, k, radius) && path(from, k) != null) return k;
        }
        return null;
    }

    /** Take the bottles (and with {@code all} the ingredient too) out of the stand. */
    private static Activity takeOut(Worker w, Block sb, boolean all) {
        return new GoAndDo(w, sb, 2.0, "Taking potions out", b -> b.getType() == Material.BREWING_STAND, b -> {
            if (!(b.getState(false) instanceof BrewingStand bs)) return;
            BrewerInventory bi = bs.getInventory();
            List<ItemStack> out = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ItemStack it = bi.getItem(i);
                if (it != null && !it.getType().isAir()) out.add(it.clone());
                bi.setItem(i, null);
            }
            if (all && bi.getIngredient() != null && !bi.getIngredient().getType().isAir()) {
                out.add(bi.getIngredient().clone());
                bi.setIngredient(null);
            }
            w.give(out, w.body.getLocation());
            w.savePockets();
        });
    }

    private static int countKind(Worker w, Kind k) {
        int n = 0;
        for (ItemStack it : w.pockets.getContents()) if (k.equals(Kind.of(it))) n += it.getAmount();
        return n;
    }

    private static boolean takeKind(Worker w, Kind k) {
        for (int i = 0; i < w.pockets.getSize(); i++) {
            ItemStack it = w.pockets.getItem(i);
            if (!k.equals(Kind.of(it))) continue;
            it.setAmount(it.getAmount() - 1);
            w.pockets.setItem(i, it.getAmount() > 0 ? it : null);
            return true;
        }
        return false;
    }

    private static final Map<Block, Block> waterNear = new HashMap<>();

    /** The nearest water source within 12 blocks of the stand. */
    private static Block water(Block stand) {
        Block cached = waterNear.get(stand);
        if (cached != null && cached.getType() == Material.WATER) return cached;
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                for (int dy = -4; dy <= 3; dy++) {
                    Block b = stand.getRelative(dx, dy, dz);
                    if (b.getType() != Material.WATER || !(b.getBlockData() instanceof Levelled l) || l.getLevel() != 0) continue;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
        }
        if (best != null) waterNear.put(stand, best);
        return best;
    }

    // ---- Storage targets for potions ----

    /** First the effects, then one effect's potions with − / + . */
    static void targets(SettlementPlugin plugin, Player p, Town t) {
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (Kind k : ALL) {
            Integer target = t.potionTargets.get(k.key());
            groups.merge(base(k.type()), target != null && target > 0 ? 1 : 0, Integer::sum);
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            String g = e.getKey();
            buttons.add(Menus.button(plugin, effect(g) + (e.getValue() > 0 ? " (" + e.getValue() + ")" : ""), e.getValue() > 0 ? NamedTextColor.GREEN : NamedTextColor.WHITE,
                    null, 150, pl -> group(plugin, pl, t, g)));
        }
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> TargetsMenu.back(plugin, pl, t));
        Menus.show(p, "Storage targets: potions", List.of(Menus.line("Pick an effect. No target = don't brew.", NamedTextColor.GRAY)), List.of(),
                DialogType.multiAction(buttons).columns(3).exitAction(back).build());
    }

    private static void group(SettlementPlugin plugin, Player p, Town t, String g) {
        int radius = plugin.settings().townRadius();
        List<ActionButton> cells = new ArrayList<>();
        for (Kind k : ALL) {
            if (!base(k.type()).equals(g)) continue;
            Integer target = t.potionTargets.get(k.key());
            int stored = stored(t, k, radius);
            boolean reached = target != null && target > 0 && stored >= target;
            Component label = TargetsMenu.icon(k.form()).append(Component.text(" " + k.name() + "  ", NamedTextColor.WHITE))
                    .append(Component.text(stored + " / " + (target != null && target > 0 ? target : "off"), reached ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            cells.add(ActionButton.create(label, null, 240, Menus.click(plugin, (view, audience) -> {
                if (audience instanceof Player pl) Bukkit.getScheduler().runTask(plugin, () -> group(plugin, pl, t, g));
            }, Menus.ONCE)));
            for (int step : new int[]{-8, -1, 1, 8}) {
                cells.add(Menus.button(plugin, (step > 0 ? "+" : "−") + Math.abs(step), step > 0 ? NamedTextColor.GREEN : NamedTextColor.RED, null, 30, pl -> {
                    int v = Math.max(0, t.potionTargets.getOrDefault(k.key(), 0) + step);
                    if (v == 0) t.potionTargets.remove(k.key());
                    else t.potionTargets.put(k.key(), v);
                    plugin.towns().save();
                    group(plugin, pl, t, g);
                }));
            }
            cells.add(Menus.button(plugin, "off", NamedTextColor.WHITE, "Don't brew", 30, pl -> {
                t.potionTargets.remove(k.key());
                plugin.towns().save();
                group(plugin, pl, t, g);
            }));
        }
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> targets(plugin, pl, t));
        Menus.show(p, effect(g), List.of(), List.of(), DialogType.multiAction(cells).columns(6).exitAction(back).build());
    }
}
