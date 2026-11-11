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
    static Activity fetch(Worker w, Town t, Predicate<Material> test, int max, String label) {
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
        if (best == null) return null;
        w.workStatus = label;
        return new GoAndDo(w, best, 2.0, label, b -> Town.isStorage(b) && t.rule(b)[1], b -> {
            Inventory inv = Town.inventory(b);
            if (inv == null) return;
            Material m = first(inv, test);
            if (m == null) return;
            take(w, inv, m, max);
            t.countsChanged();
        });
    }

    /** Is there anything matching in storage settlers may take from? */
    static boolean inStorage(Worker w, Town t, Predicate<Material> test) {
        for (Block b : t.containers(w.plugin.settings().townRadius())) {
            if (!t.rule(b)[1]) continue;
            Inventory inv = Town.inventory(b);
            if (inv != null && first(inv, test) != null) return true;
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

    /** Everything in the pockets except what the job keeps. */
    static Map<Material, Integer> excess(Worker w, Predicate<Material> keep) {
        Map<Material, Integer> totals = new HashMap<>();
        for (ItemStack it : w.pockets.getContents()) {
            if (it != null && !it.getType().isAir() && !keep.test(it.getType())) totals.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        return totals;
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
        if (Bukkit.getCurrentTick() < w.storageFullUntil) return null;
        int radius = w.plugin.settings().townRadius();
        Map<Material, Integer> ex = excess(w, keep);
        Material first = ex.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (first == null) return null;
        Block chest = t.storeChest(first, w.body.getLocation(), radius);
        if (chest == null) {
            w.storageFullUntil = Bukkit.getCurrentTick() + 400;
            w.warnStorageFull(first);
            w.workStatus = "No room in storage";
            return null;
        }
        w.workStatus = "Bringing things to storage";
        return new GoAndDo(w, chest, 2.0, "Bringing things to storage", b -> Town.isStorage(b) && t.rule(b)[0], b -> {
            Inventory inv = Town.inventory(b);
            if (inv == null) return;
            for (Map.Entry<Material, Integer> e : excess(w, keep).entrySet()) {
                Material m = e.getKey();
                if (!b.equals(t.storeChest(m, b.getLocation(), radius))) continue; // belongs elsewhere
                ItemStack stack = new ItemStack(m, e.getValue());
                w.pockets.removeItem(stack.clone());
                for (ItemStack left : inv.addItem(stack).values()) w.pockets.addItem(left);
            }
            t.countsChanged();
        });
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
        Location me = w.body.getLocation();
        org.bukkit.entity.Item best = null;
        double bestD = Double.MAX_VALUE;
        // Anywhere in the settlement, plus close around the worker (a lumberjack outside).
        int r = w.plugin.settings().townRadius();
        java.util.Set<org.bukkit.entity.Item> items = new java.util.HashSet<>();
        if (t.bell.getWorld() == me.getWorld()) {
            int[] box = t.box(0);
            Location c = new Location(me.getWorld(), (box[0] + box[1] + 1) / 2.0, t.bell.getY(), (box[2] + box[3] + 1) / 2.0);
            items.addAll(me.getWorld().getNearbyEntitiesByType(org.bukkit.entity.Item.class, c, (box[1] - box[0]) / 2.0 + 1, 16, (box[3] - box[2]) / 2.0 + 1));
        }
        items.addAll(me.getWorld().getNearbyEntitiesByType(org.bukkit.entity.Item.class, me, range, 8, range));
        for (org.bukkit.entity.Item it : items) {
            if (!it.isValid() || it.getPickupDelay() > 20 || !wanted.test(it.getItemStack().getType())) continue;
            if (!t.contains(it.getLocation(), r) && it.getLocation().distanceSquared(me) > range * range) continue;
            if (taken(w, it.getUniqueId())) continue;
            double d = it.getLocation().distanceSquared(me);
            if (d < bestD) {
                bestD = d;
                best = it;
            }
        }
        if (best == null || w.pockets.firstEmpty() == -1) return null;
        claim(w, best.getUniqueId());
        Block at = best.getLocation().getBlock();
        return new GoAndDo(w, at, 1.3, "Picking things up", b -> true, b -> pickUp(w, b.getLocation().add(0.5, 0.5, 0.5), 2.5, wanted));
    }

    /** Put wanted items lying around this spot into the pockets. */
    static void pickUp(Worker w, Location at, double r, Predicate<Material> wanted) {
        boolean any = false;
        for (org.bukkit.entity.Item it : at.getWorld().getNearbyEntitiesByType(org.bukkit.entity.Item.class, at, r, 2, r)) {
            ItemStack stack = it.getItemStack();
            if (!it.isValid() || !wanted.test(stack.getType())) continue;
            Map<Integer, ItemStack> left = w.pockets.addItem(stack.clone());
            if (left.isEmpty()) it.remove();
            else it.setItemStack(left.values().iterator().next());
            any = true;
        }
        if (any) {
            at.getWorld().playSound(at, "minecraft:entity.item.pickup", org.bukkit.SoundCategory.NEUTRAL, 0.4f, 1.4f);
            w.savePockets();
        }
    }

    // ---- Sharing the work: what someone is busy with ----

    private record Claim(java.util.UUID who, int until) {}

    private static final Map<Object, Claim> claims = new HashMap<>();

    /** Mark a crop, tree or animal as "mine" for a while, so others pick something else. */
    static void claim(Worker w, Object thing) {
        claims.put(thing, new Claim(w.body.getUniqueId(), Bukkit.getCurrentTick() + 600));
    }

    /** Is someone else busy with this? */
    static boolean taken(Worker w, Object thing) {
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

    /** Nearest block in the work area (square around the workplace) matching the test. */
    static Block nearest(Worker w, Town.Workplace wp, int dyDown, int dyUp, Predicate<Block> test) {
        World world = wp.loc.getWorld();
        if (world == null || world != w.body.getWorld()) return null;
        int cx = wp.loc.getBlockX(), cy = wp.loc.getBlockY(), cz = wp.loc.getBlockZ(), r = wp.radius;
        Location me = w.body.getLocation();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = cy - dyDown; y <= cy + dyUp; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (w.isSkipped(b) || !test.test(b)) continue;
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

    /** Is this spot inside the work area? */
    static boolean inArea(Town.Workplace wp, Location l) {
        return l.getWorld() == wp.loc.getWorld() && Math.abs(l.getBlockX() - wp.loc.getBlockX()) <= wp.radius
                && Math.abs(l.getBlockZ() - wp.loc.getBlockZ()) <= wp.radius && Math.abs(l.getBlockY() - wp.loc.getBlockY()) <= 6;
    }

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
            if (!target.isValid() || target.isDead() || ++time > 600) return true;
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
                if (!w.mover.go(them, reach * 0.8, w.plugin.settings().workSpeed()) && time > 100) return true;
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

    static boolean isEntityOk(Entity e) {
        return e != null && e.isValid() && !e.isDead();
    }
}
