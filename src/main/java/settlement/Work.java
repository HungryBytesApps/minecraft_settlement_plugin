package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Shared bits for all jobs: fetching from storage, bringing things to storage, tools, finding blocks. */
final class Work {
    private Work() {}

    // ---- Storage ----

    /** Take up to {@code max} of one material matching {@code test} from the nearest chest settlers may take from. */
    /** Storage blocks a settler can break open: one block gives 9 (hay → wheat, iron block → ingots...). */
    static final Map<Material, Material> UNPACK = Map.ofEntries(
            Map.entry(Material.HAY_BLOCK, Material.WHEAT), Map.entry(Material.IRON_BLOCK, Material.IRON_INGOT),
            Map.entry(Material.GOLD_BLOCK, Material.GOLD_INGOT), Map.entry(Material.COPPER_BLOCK, Material.COPPER_INGOT),
            Map.entry(Material.DIAMOND_BLOCK, Material.DIAMOND), Map.entry(Material.EMERALD_BLOCK, Material.EMERALD),
            Map.entry(Material.COAL_BLOCK, Material.COAL), Map.entry(Material.REDSTONE_BLOCK, Material.REDSTONE),
            Map.entry(Material.LAPIS_BLOCK, Material.LAPIS_LAZULI), Map.entry(Material.NETHERITE_BLOCK, Material.NETHERITE_INGOT),
            Map.entry(Material.RAW_IRON_BLOCK, Material.RAW_IRON), Map.entry(Material.RAW_GOLD_BLOCK, Material.RAW_GOLD),
            Map.entry(Material.RAW_COPPER_BLOCK, Material.RAW_COPPER), Map.entry(Material.BONE_BLOCK, Material.BONE_MEAL),
            Map.entry(Material.DRIED_KELP_BLOCK, Material.DRIED_KELP), Map.entry(Material.SLIME_BLOCK, Material.SLIME_BALL));

    /** How many of m are in storage, counting what's packed in blocks (an iron block = 9 ingots). */
    static int available(Town t, Material m, int radius) {
        int n = t.count(m, radius);
        for (Map.Entry<Material, Material> e : UNPACK.entrySet()) if (e.getValue() == m) n += 9 * t.count(e.getKey(), radius);
        return n;
    }

    /** Like fetch, but only loose items (never opens a packed block: hay for wheat it would pack again). */
    static Activity fetchLoose(Worker w, Town t, Predicate<Material> test, int max, String label) {
        return fetch(w, t, test, max, label, false);
    }

    static Activity fetch(Worker w, Town t, Predicate<Material> test, int max, String label) {
        return fetch(w, t, test, max, label, true);
    }

    private static Activity fetch(Worker w, Town t, Predicate<Material> test, int max, String label, boolean unpack) {
        int radius = w.plugin.settings().townRadius();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (Block b : t.containers(radius)) {
            if (!t.rule(b)[1] || w.isSkipped(b)) continue;
            Inventory inv = Town.inventory(b);
            if (inv == null || first(inv, test) == null) continue;
            double d = b.getLocation().distanceSquared(w.body.getLocation());
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        if (best == null && !unpack) return null;
        if (best == null) { // none loose: a block of it (hay for wheat, an iron block for ingots), opened up
            Predicate<Material> packed = m -> UNPACK.containsKey(m) && test.test(UNPACK.get(m));
            for (Block b : t.containers(radius)) {
                if (!t.rule(b)[1] || w.isSkipped(b)) continue;
                Inventory inv = Town.inventory(b);
                if (inv == null || first(inv, packed) == null) continue;
                double d = b.getLocation().distanceSquared(w.body.getLocation());
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            if (best == null) return null;
            w.workStatus = label;
            return new GoAndDo(w, best, 2.0, label, b -> Town.isStorage(b) && t.rule(b)[1], b -> {
                Inventory inv = Town.inventory(b);
                if (inv == null) return;
                Material block = first(inv, packed);
                if (block == null) return;
                Material loose = UNPACK.get(block);
                // As many blocks as it needs and has room for (opened here: 9 each, nothing dropped).
                int room = 0;
                for (ItemStack it : w.pockets.getStorageContents()) {
                    if (it == null || it.getType().isAir()) room += loose.getMaxStackSize();
                    else if (it.getType() == loose) room += it.getMaxStackSize() - it.getAmount();
                }
                int blocks = Math.min(Math.min(9, (max + 8) / 9), room / 9);
                if (blocks <= 0) return;
                ItemStack packedOnes = null;
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack it = inv.getItem(i);
                    if (it == null || it.getType() != block) continue;
                    int n = Math.min(blocks, it.getAmount());
                    it.setAmount(it.getAmount() - n);
                    inv.setItem(i, it.getAmount() > 0 ? it : null);
                    blocks -= n;
                    packedOnes = packedOnes == null ? new ItemStack(block, n) : packedOnes.add(n);
                    if (blocks == 0) break;
                }
                if (packedOnes == null) {
                    w.skip(b, 1200);
                    return;
                }
                for (ItemStack left : w.pockets.addItem(new ItemStack(loose, packedOnes.getAmount() * 9)).values()) inv.addItem(left);
                w.savePockets();
                t.countsChanged();
            });
        }
        w.workStatus = label;
        return new GoAndDo(w, best, 2.0, label, b -> Town.isStorage(b) && t.rule(b)[1], b -> {
            Inventory inv = Town.inventory(b);
            if (inv == null) return;
            Material m = first(inv, test);
            if (m == null) return;
            if (take(w, inv, m, max) == 0) w.skip(b, 1200); // couldn't take any (pockets full): not this chest again for a minute
            t.countsChanged();
        });
    }

    /** Is there anything matching in storage settlers may take from (also packed in a block)? */
    static boolean inStorage(Worker w, Town t, Predicate<Material> test) {
        for (Block b : t.containers(w.plugin.settings().townRadius())) {
            if (!t.rule(b)[1]) continue;
            Inventory inv = Town.inventory(b);
            if (inv != null && first(inv, m -> test.test(m) || (UNPACK.containsKey(m) && test.test(UNPACK.get(m)))) != null) return true;
        }
        return false;
    }

    private static Material first(Inventory inv, Predicate<Material> test) {
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir() && test.test(it.getType())) return it.getType();
        return null;
    }

    /** Move up to {@code max} of a material from an inventory into the pockets. Returns how many. */
    static int take(Worker w, Inventory from, Material m, int max) {
        int moved = 0;
        for (int i = 0; i < from.getSize() && moved < max; i++) {
            ItemStack it = from.getItem(i);
            if (it == null || it.getType() != m) continue;
            int n = Math.min(max - moved, it.getAmount());
            ItemStack part = it.clone();
            part.setAmount(n);
            Map<Integer, ItemStack> left = w.pockets.addItem(part);
            int notFit = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            n -= notFit;
            it.setAmount(it.getAmount() - n);
            from.setItem(i, it.getAmount() > 0 ? it : null);
            moved += n;
            if (notFit > 0) break;
        }
        return moved;
    }

    /** Weapons, armor, healing potions: a settler keeps those (unless it made them). */
    static boolean gear(Material m) {
        return Combat.isWeapon(m) || Combat.isBow(m) || Combat.armorSlot(m) != null || m == Material.ARROW
                || m == Material.POTION || m == Material.SPLASH_POTION;
    }

    /** Gear a guard carries in its pockets (armor is worn, not carried: spare armor goes to storage). */
    static boolean carriedGear(Material m) {
        return gear(m) && Combat.armorSlot(m) == null;
    }

    /**
     * Gear it doesn't need in its pockets: spare armor, and all weapons but its best melee weapon and one bow
     * (axes count as tools unless it's a guard: a former lumberjack brings its axe back).
     */
    static Map<Material, Integer> gearExcess(Worker w) {
        Map<Material, Integer> out = excess(w, Work::carriedGear);
        boolean guard = w.guard;
        Material bestMelee = null, bestBow = null;
        for (ItemStack it : w.pockets.getContents()) {
            if (it == null) continue;
            Material m = it.getType();
            if (Combat.isBow(m)) {
                if (bestBow == null || m == Material.CROSSBOW) bestBow = m;
            } else if (Combat.isWeapon(m) && (guard || !m.name().endsWith("_AXE"))) {
                if (bestMelee == null || Combat.rank(m) > Combat.rank(bestMelee)) bestMelee = m; // (the one gearUp picks too)
            }
        }
        Map<Material, Integer> weapons = new HashMap<>();
        for (ItemStack it : w.pockets.getContents()) {
            if (it == null || !carriedGear(it.getType())) continue;
            Material m = it.getType();
            if (Combat.isWeapon(m) || Combat.isBow(m)) weapons.merge(m, it.getAmount(), Integer::sum);
        }
        // A little food stays (it fetched it to eat after a fight).
        int food = 0;
        for (Map.Entry<Material, Integer> e : new ArrayList<>(out.entrySet())) {
            if (Combat.foodValue(e.getKey()) <= 0) continue;
            int keep = Math.min(e.getValue(), Math.max(0, 3 - food));
            food += keep;
            if (e.getValue() - keep <= 0) out.remove(e.getKey());
            else out.put(e.getKey(), e.getValue() - keep);
        }
        for (Map.Entry<Material, Integer> e : weapons.entrySet()) {
            int extra = e.getValue() - ((e.getKey() == bestMelee || e.getKey() == bestBow) ? 1 : 0);
            if (extra > 0) out.merge(e.getKey(), extra, Integer::sum);
        }
        return out;
    }

    static boolean needsGearDeposit(Worker w, boolean anything) {
        Map<Material, Integer> ex = gearExcess(w);
        if (ex.isEmpty()) return false;
        return anything || ex.values().stream().mapToInt(Integer::intValue).sum() >= 48 || w.pockets.firstEmpty() == -1;
    }

    static Activity depositGear(Worker w, Town t) {
        return deposit(w, t, () -> gearExcess(w), "Bringing things to storage");
    }

    /** Everything in the pockets except what the job keeps. */
    static Map<Material, Integer> excess(Worker w, Predicate<Material> keep) {
        Map<Material, Integer> totals = new HashMap<>();
        for (ItemStack it : w.pockets.getContents()) {
            if (it != null && !it.getType().isAir() && !keep.test(it.getType())) totals.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        return totals;
    }

    /** Like {@link #excess(Worker, Predicate)}, but of what it keeps only up to {@code limit} each (the rest is extra). */
    static Map<Material, Integer> excess(Worker w, Predicate<Material> keep, java.util.function.ToIntFunction<Material> limit) {
        Map<Material, Integer> totals = new HashMap<>();
        for (ItemStack it : w.pockets.getContents()) {
            if (it != null && !it.getType().isAir()) totals.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        Map<Material, Integer> out = new HashMap<>();
        for (Map.Entry<Material, Integer> e : totals.entrySet()) {
            int extra = keep.test(e.getKey()) ? e.getValue() - limit.applyAsInt(e.getKey()) : e.getValue();
            if (extra > 0) out.put(e.getKey(), extra);
        }
        return out;
    }

    static boolean needsDeposit(Worker w, Predicate<Material> keep, java.util.function.ToIntFunction<Material> limit, boolean anything) {
        Map<Material, Integer> ex = excess(w, keep, limit);
        if (ex.isEmpty()) return false;
        if (anything) return true;
        return ex.values().stream().mapToInt(Integer::intValue).sum() >= 48 || w.pockets.firstEmpty() == -1;
    }

    static Activity deposit(Worker w, Town t, Predicate<Material> keep, java.util.function.ToIntFunction<Material> limit) {
        return deposit(w, t, () -> excess(w, keep, limit), "Bringing things to storage");
    }

    /** Pockets (nearly) full or a good load: time to bring things to storage. {@code anything}: any load at all. */
    static boolean needsDeposit(Worker w, Predicate<Material> keep, boolean anything) {
        Map<Material, Integer> ex = excess(w, keep);
        if (ex.isEmpty()) return false;
        if (anything) return true;
        return ex.values().stream().mapToInt(Integer::intValue).sum() >= 48 || w.pockets.firstEmpty() == -1;
    }

    /** Bring what it made to storage (a chest that already has it, else the nearest with room). */
    static Activity deposit(Worker w, Town t, Predicate<Material> keep) {
        return deposit(w, t, () -> excess(w, keep), "Bringing things to storage");
    }

    /**
     * Bring these things from the pockets to storage: {@code what} gives material → how many (asked again at
     * the chest). Each chest gets what belongs there (its filter, or it already holds it); the rest goes on the
     * next trip.
     */
    static Activity deposit(Worker w, Town t, java.util.function.Supplier<Map<Material, Integer>> what, String label) {
        if (Bukkit.getCurrentTick() < w.storageFullUntil) return null;
        int radius = w.plugin.settings().townRadius();
        Map<Material, Integer> ex = what.get();
        if (ex.isEmpty()) return null;
        Material first = null;
        Block chest = null;
        boolean waiting = false;
        List<Map.Entry<Material, Integer>> order = new ArrayList<>(ex.entrySet());
        order.sort(Map.Entry.<Material, Integer>comparingByValue().reversed());
        for (Map.Entry<Material, Integer> e : order) { // the biggest load that has a chest to go to
            Block c = t.storeChest(e.getKey(), w.body.getLocation(), radius, w);
            if (c != null) {
                first = e.getKey();
                chest = c;
                break;
            }
            if (t.waitsForOwnChest(e.getKey(), w.body.getLocation(), radius, w)) waiting = true;
        }
        if (chest == null) {
            if (waiting) { // its chest is just held up: it waits (not "storage full")
                w.workStatus = "Waiting to get to the chest";
                if (w.pockets.firstEmpty() == -1) w.storageFullUntil = Bukkit.getCurrentTick() + 100; // full: no more work meanwhile
                return null;
            }
            Material any = order.get(0).getKey();
            w.storageFullUntil = Bukkit.getCurrentTick() + 400;
            w.warnStorageFull(any);
            w.workStatus = "No room in storage";
            return null;
        }
        Material firstF = first;
        w.workStatus = label;
        return new GoAndDo(w, chest, 2.0, label, b -> Town.isStorage(b) && t.rule(b)[0], b -> {
            Inventory inv = Town.inventory(b);
            if (inv == null) return;
            Map<Material, Integer> here = new HashMap<>();
            for (Map.Entry<Material, Integer> e : what.get().entrySet()) {
                Block to = t.storeChest(e.getKey(), b.getLocation(), radius, w);
                // What it came here for goes in anyway (no going back and forth); the rest only if this is its chest.
                if (e.getKey() == firstF || (to != null && Town.halves(to).contains(b))) here.put(e.getKey(), e.getValue());
            }
            // Nothing went in after all (no room for just this item): wait a bit before trying again.
            if (store(w, inv, here).isEmpty()) w.storageFullUntil = Bukkit.getCurrentTick() + 400;
            w.savePockets();
            t.countsChanged();
        });
    }

    /**
     * Move up to these amounts from the pockets into an inventory: the real stacks (enchantments, potions,
     * damage stay as they are), the last pocket slots first. Returns what was moved.
     */
    static Map<Material, Integer> store(Worker w, Inventory inv, Map<Material, Integer> amounts) {
        Map<Material, Integer> moved = new HashMap<>();
        for (int i = w.pockets.getSize() - 1; i >= 0; i--) {
            ItemStack it = w.pockets.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            Material m = it.getType();
            int left = amounts.getOrDefault(m, 0) - moved.getOrDefault(m, 0);
            if (left <= 0) continue;
            int n = Math.min(left, it.getAmount());
            ItemStack part = it.clone();
            part.setAmount(n);
            int notFit = 0;
            for (ItemStack rest : inv.addItem(part).values()) notFit += rest.getAmount();
            int done = n - notFit;
            if (done <= 0) continue;
            it.setAmount(it.getAmount() - done);
            w.pockets.setItem(i, it.getAmount() > 0 ? it : null);
            moved.merge(m, done, Integer::sum);
        }
        if (!moved.isEmpty() && w.plugin.getConfig().getBoolean("sort-storage", true)) sort(inv);
        return moved;
    }

    /**
     * Tidy a chest: full stacks, alike things together (all logs, all ingots...), from the first slot on, no gaps.
     * Enchanted, named or damaged things stay their own stacks.
     */
    static void sort(Inventory inv) {
        ItemStack[] now = inv.getStorageContents();
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack it : now) {
            if (it == null || it.getType().isAir()) continue;
            ItemStack left = it.clone();
            for (ItemStack m : merged) {
                if (left.getAmount() <= 0) break;
                if (!m.isSimilar(left) || m.getAmount() >= m.getMaxStackSize()) continue;
                int n = Math.min(left.getAmount(), m.getMaxStackSize() - m.getAmount());
                m.setAmount(m.getAmount() + n);
                left.setAmount(left.getAmount() - n);
            }
            if (left.getAmount() > 0) merged.add(left);
        }
        merged.sort(Comparator.comparing((ItemStack it) -> {
            String n = it.getType().getKey().getKey();
            return n.substring(n.lastIndexOf('_') + 1) + " " + n; // oak_log, birch_log together; then by name
        }).thenComparing(it -> -it.getAmount()));
        ItemStack[] sorted = new ItemStack[now.length];
        for (int i = 0; i < merged.size() && i < sorted.length; i++) sorted[i] = merged.get(i);
        if (!java.util.Arrays.equals(now, sorted)) inv.setStorageContents(sorted);
    }

    // ---- Tools ----

    /** Use up one point of a tool's durability (Unbreaking helps, like for players). The tool breaks when worn out. */
    static void wear(Worker w, ItemStack tool) {
        if (tool == null || tool.getType().getMaxDurability() <= 0) return;
        if (!(tool.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm) || dm.isUnbreakable()) return;
        int unbreaking = tool.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);
        if (unbreaking > 0 && w.random.nextInt(unbreaking + 1) != 0) return;
        dm.setDamage(dm.getDamage() + 1);
        if (dm.getDamage() >= tool.getType().getMaxDurability()) {
            tool.setAmount(0); // broken
            w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.item.break", org.bukkit.SoundCategory.NEUTRAL, 0.8f, 1f);
        } else {
            tool.setItemMeta(dm);
        }
    }

    /** Wear the first tool in the pockets whose name ends with this ("_AXE", "SHEARS"...). */
    static void wearTool(Worker w, String suffix) {
        for (int i = 0; i < w.pockets.getSize(); i++) {
            ItemStack it = w.pockets.getItem(i);
            if (it == null || !it.getType().name().endsWith(suffix)) continue;
            wear(w, it);
            w.pockets.setItem(i, it.getAmount() > 0 ? it : null);
            w.holdTool();
            return;
        }
    }

    /** Wear this item in the pockets (the one similar to the given copy, e.g. the weapon in hand). */
    static void wearSimilar(Worker w, ItemStack copy) {
        if (copy == null) return;
        for (int i = 0; i < w.pockets.getSize(); i++) {
            ItemStack it = w.pockets.getItem(i);
            if (it == null || !it.isSimilar(copy)) continue;
            wear(w, it);
            w.pockets.setItem(i, it.getAmount() > 0 ? it : null);
            return;
        }
    }

    /** Does it carry a tool whose name ends with this (e.g. "_AXE", "SHEARS")? */
    static boolean hasTool(Worker w, String suffix) {
        for (ItemStack it : w.pockets.getContents()) if (it != null && it.getType().name().endsWith(suffix)) return true;
        return false;
    }

    /** Get such a tool from storage (null if there is none). */
    static Activity fetchTool(Worker w, Town t, String suffix, String what) {
        return fetch(w, t, m -> m.name().endsWith(suffix), 1, "Getting " + what);
    }

    // ---- Picking things up ----

    /** Walk to the nearest dropped item it wants (within range, in the settlement) and pick up everything wanted there. */
    static Activity collectDrops(Worker w, Town t, Predicate<Material> wanted, double range) {
        return collectDrops(w, t, wanted, range, null);
    }

    /** around != null: only what lies near that spot, and nothing a player threw or owns (a guard after a fight). */
    static Activity collectDrops(Worker w, Town t, Predicate<Material> wanted, double range, Location around) {
        Location me = w.body.getLocation();
        org.bukkit.entity.Item best = null;
        double bestD = Double.MAX_VALUE;
        // Anywhere in the settlement, plus close around the worker (a lumberjack outside).
        int r = w.plugin.settings().townRadius();
        java.util.Set<org.bukkit.entity.Item> items = new java.util.HashSet<>();
        if (around != null) {
            if (around.getWorld() != me.getWorld()) return null;
            items.addAll(me.getWorld().getNearbyEntitiesByType(org.bukkit.entity.Item.class, around, range, 8, range));
        } else if (t.bell.getWorld() == me.getWorld()) {
            int[] box = t.box(0);
            Location c = new Location(me.getWorld(), (box[0] + box[1] + 1) / 2.0, t.bell.getY(), (box[2] + box[3] + 1) / 2.0);
            items.addAll(me.getWorld().getNearbyEntitiesByType(org.bukkit.entity.Item.class, c, (box[1] - box[0]) / 2.0 + 1, 16, (box[3] - box[2]) / 2.0 + 1));
        }
        if (around == null) items.addAll(me.getWorld().getNearbyEntitiesByType(org.bukkit.entity.Item.class, me, range, 8, range));
        for (org.bukkit.entity.Item it : items) {
            if (!it.isValid() || it.getPickupDelay() > 20 || !wanted.test(it.getItemStack().getType())) continue;
            if (around != null && !lootable(it)) continue; // a player's things
            if (around == null && !t.contains(it.getLocation(), r) && it.getLocation().distanceSquared(me) > range * range) continue;
            if (taken(w, it.getUniqueId()) || w.isSkipped(it.getLocation().getBlock())) continue; // (couldn't get to it lately)
            double d = it.getLocation().distanceSquared(me);
            if (d < bestD) {
                bestD = d;
                best = it;
            }
        }
        if (best == null || w.pockets.firstEmpty() == -1) return null;
        claim(w, best.getUniqueId());
        Block at = best.getLocation().getBlock();
        return new GoAndDo(w, at, 1.3, "Picking things up", b -> true, b -> pickUp(w, t, b.getLocation().add(0.5, 0.5, 0.5), 2.5, wanted, around != null));
    }

    /** Where players died lately (their things lie there: guards leave them alone). */
    private static final java.util.List<Location> playerDeaths = new java.util.ArrayList<>();
    private static final java.util.List<Integer> playerDeathTimes = new java.util.ArrayList<>();

    static void playerDied(Location at) {
        int now = Bukkit.getCurrentTick();
        for (int i = playerDeathTimes.size() - 1; i >= 0; i--) {
            if (now - playerDeathTimes.get(i) > 6000 || now < playerDeathTimes.get(i)) {
                playerDeathTimes.remove(i);
                playerDeaths.remove(i);
            }
        }
        playerDeaths.add(at.clone());
        playerDeathTimes.add(now);
    }

    /** Loot a guard may take: dropped by a monster, not thrown by or belonging to a player, not where a player died. */
    static boolean lootable(org.bukkit.entity.Item it) {
        if (it.getThrower() != null || it.getOwner() != null) return false;
        int now = Bukkit.getCurrentTick();
        Location l = it.getLocation();
        for (int i = 0; i < playerDeaths.size(); i++) {
            Location d = playerDeaths.get(i);
            if (now - playerDeathTimes.get(i) <= 6000 && d.getWorld() == l.getWorld() && d.distanceSquared(l) < 6 * 6) return false;
        }
        return true;
    }

    /** Put wanted items lying around this spot into the pockets. */
    static void pickUp(Worker w, Location at, double r, Predicate<Material> wanted) {
        pickUp(w, null, at, r, wanted, false);
    }

    /** Things that pile up by themselves (chickens laying): only as many as the storage target still needs. */
    private static final java.util.Set<Material> UP_TO_TARGET = java.util.EnumSet.of(Material.EGG);

    static void pickUp(Worker w, Town t, Location at, double r, Predicate<Material> wanted, boolean lootOnly) {
        boolean any = false;
        int radius = w.plugin.settings().townRadius();
        for (org.bukkit.entity.Item it : at.getWorld().getNearbyEntitiesByType(org.bukkit.entity.Item.class, at, r, 2, r)) {
            ItemStack stack = it.getItemStack();
            if (!it.isValid() || !wanted.test(stack.getType())) continue;
            if (lootOnly && !lootable(it)) continue;
            ItemStack take = stack.clone();
            if (t != null && UP_TO_TARGET.contains(take.getType()) && t.targets.get(take.getType()) != null) {
                int room = t.targets.get(take.getType()) - t.count(take.getType(), radius) - w.count(take.getType());
                if (room <= 0) continue; // enough: the rest stays where it lies
                take.setAmount(Math.min(room, take.getAmount()));
            }
            int rest = stack.getAmount() - take.getAmount();
            Map<Integer, ItemStack> left = w.pockets.addItem(take);
            for (ItemStack l : left.values()) rest += l.getAmount();
            if (rest <= 0) it.remove();
            else {
                ItemStack back = stack.clone();
                back.setAmount(rest);
                it.setItemStack(back);
            }
            any = true;
        }
        if (any) {
            at.getWorld().playSound(at, "minecraft:entity.item.pickup", org.bukkit.SoundCategory.NEUTRAL, 0.4f, 1.4f);
            w.savePockets();
        }
    }

    // ---- Sharing the work: what someone is busy with ----

    private record Claim(java.util.UUID who, int until) {}

    /** Forget claims that ran out (called now and then). */
    static void cleanup() {
        int now = Bukkit.getCurrentTick();
        claims.values().removeIf(c -> c.until() < now);
    }

    private static final Map<Object, Claim> claims = new HashMap<>();

    /** Mark a crop, tree or animal as "mine" for a while, so others pick something else. */
    static void claim(Worker w, Object thing) {
        claims.put(thing, new Claim(w.body.getUniqueId(), Bukkit.getCurrentTick() + 600));
    }

    /** Is someone else busy with this? */
    static boolean taken(Worker w, Object thing) {
        if (thing instanceof java.util.UUID id && w.isSkippedEntity(id)) return true; // it couldn't get to it lately
        Claim c = claims.get(thing);
        if (c == null) return false;
        if (Bukkit.getCurrentTick() > c.until()) {
            claims.remove(thing);
            return false;
        }
        return !c.who().equals(w.body.getUniqueId());
    }

    /** The nearest of these blocks that passes the test and nobody else is busy with. */
    static Block nearestOf(Worker w, List<Block> blocks, Predicate<Block> test) {
        Location me = w.body.getLocation();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (Block b : blocks) {
            if (b.getWorld() != me.getWorld()) continue;
            double dx = b.getX() + 0.5 - me.getX(), dy = b.getY() - me.getY(), dz = b.getZ() + 0.5 - me.getZ();
            double d = dx * dx + dy * dy * 4 + dz * dz;
            if (d >= bestD || w.isSkipped(b) || taken(w, b) || !test.test(b)) continue;
            bestD = d;
            best = b;
        }
        return best;
    }

    // ---- Finding things ----

    static String nice(Material m) {
        return FarmerJob.nice(m);
    }

    // ---- Walking up to an animal ----

    /** Walk up to an entity (it may move), then do something with it. */
    static final class GoToEntity implements Activity {
        private final Worker w;
        private final LivingEntity target;
        private final double reach;
        private final String label;
        private final Consumer<LivingEntity> action;
        private int time, repath;

        GoToEntity(Worker w, LivingEntity target, double reach, String label, Consumer<LivingEntity> action) {
            this.w = w;
            this.target = target;
            this.reach = reach;
            this.label = label;
            this.action = action;
        }

        @Override
        public boolean tick() {
            if (!target.isValid() || target.isDead()) return true;
            if (++time > 600) {
                w.skipEntity(target, 1200); // couldn't get to it: leave it a minute (no picking it again and again)
                return true;
            }
            Location me = w.body.getLocation(), them = target.getLocation();
            if (me.getWorld() != them.getWorld()) return true;
            if (me.distanceSquared(them) <= reach * reach) {
                w.mover.stop();
                w.anim.look(target.getEyeLocation());
                w.body.swingMainHand();
                action.accept(target);
                return true;
            }
            if (--repath <= 0) {
                repath = 15;
                if (!w.mover.go(them, reach * 0.8, w.plugin.settings().workSpeed()) && time > 100) {
                    w.skipEntity(target, 1200);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return label;
        }
    }
}
