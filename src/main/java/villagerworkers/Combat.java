package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;

/** Weapons, armor, food and fighting for settlers. */
final class Combat {
    private Combat() {}

    // ---- What things are ----

    static boolean hostile(Entity e) {
        return e instanceof Enemy && e instanceof LivingEntity le && !le.isDead() && !(e instanceof Player);
    }

    /** 0 = wood/gold, 1 = stone/copper, 2 = iron, 3 = diamond, 4 = netherite. */
    private static int tier(String name) {
        if (name.startsWith("NETHERITE")) return 4;
        if (name.startsWith("DIAMOND")) return 3;
        if (name.startsWith("IRON")) return 2;
        if (name.startsWith("STONE") || name.startsWith("COPPER")) return 1;
        return 0;
    }

    /** Melee damage of a held item (0 = not a weapon). */
    static double damage(Material m) {
        if (m == null) return 0;
        String n = m.name();
        int t = tier(n);
        if (n.endsWith("_SWORD")) return 4 + t;                        // wood 4 … netherite 8
        if (n.endsWith("_AXE")) return t == 0 ? 7 : t == 4 ? 10 : 9;    // slower, but hits harder
        if (n.endsWith("_SHOVEL")) return 2.5 + t;
        if (n.endsWith("_PICKAXE")) return 2 + t;
        if (n.endsWith("_HOE")) return 1;
        if (m == Material.TRIDENT) return 9;
        if (m == Material.MACE) return 6;
        return 0;
    }

    /** Ticks between hits. */
    static int cooldown(Material m) {
        String n = m.name();
        if (n.endsWith("_SWORD")) return 12;
        if (n.endsWith("_AXE")) return 20;
        return 16;
    }

    static boolean isBow(Material m) {
        return m == Material.BOW || m == Material.CROSSBOW;
    }

    static boolean isWeapon(Material m) {
        return damage(m) >= 2.5 || isBow(m);
    }

    /** Armor slot of an item, or null. */
    static EquipmentSlot armorSlot(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET")) return EquipmentSlot.HEAD;
        if (n.endsWith("_CHESTPLATE")) return EquipmentSlot.CHEST;
        if (n.endsWith("_LEGGINGS")) return EquipmentSlot.LEGS;
        if (n.endsWith("_BOOTS")) return EquipmentSlot.FEET;
        return null;
    }

    static int armorRank(Material m) {
        if (m == null || m.isAir()) return 0;
        String n = m.name();
        if (n.startsWith("LEATHER")) return 1;
        if (n.startsWith("CHAINMAIL") || n.startsWith("GOLDEN") || n.startsWith("COPPER")) return 2;
        if (n.startsWith("TURTLE")) return 3;
        if (n.startsWith("IRON")) return 3;
        if (n.startsWith("DIAMOND")) return 4;
        if (n.startsWith("NETHERITE")) return 5;
        return 1;
    }

    private static final Map<Material, Integer> FOOD = Map.ofEntries(
            Map.entry(Material.BREAD, 5), Map.entry(Material.COOKED_BEEF, 8), Map.entry(Material.COOKED_PORKCHOP, 8),
            Map.entry(Material.COOKED_MUTTON, 6), Map.entry(Material.COOKED_CHICKEN, 6), Map.entry(Material.COOKED_RABBIT, 5),
            Map.entry(Material.COOKED_SALMON, 6), Map.entry(Material.COOKED_COD, 5), Map.entry(Material.BAKED_POTATO, 5),
            Map.entry(Material.APPLE, 4), Map.entry(Material.CARROT, 3), Map.entry(Material.GOLDEN_CARROT, 6),
            Map.entry(Material.PUMPKIN_PIE, 8), Map.entry(Material.COOKIE, 2), Map.entry(Material.MELON_SLICE, 2),
            Map.entry(Material.BEETROOT, 1), Map.entry(Material.MUSHROOM_STEW, 6), Map.entry(Material.RABBIT_STEW, 10),
            Map.entry(Material.BEETROOT_SOUP, 6), Map.entry(Material.SWEET_BERRIES, 2), Map.entry(Material.DRIED_KELP, 1));

    static int foodValue(Material m) {
        return FOOD.getOrDefault(m, 0);
    }

    static boolean isHealingPotion(ItemStack it) {
        if (it == null || (it.getType() != Material.POTION && it.getType() != Material.SPLASH_POTION)) return false;
        if (!(it.getItemMeta() instanceof PotionMeta pm)) return false;
        PotionType t = pm.getBasePotionType();
        return t == PotionType.HEALING || t == PotionType.STRONG_HEALING;
    }

    static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    static void heal(LivingEntity e, double amount) {
        e.setHealth(Math.min(maxHealth(e), e.getHealth() + amount));
    }

    // ---- What a settler carries ----

    /** Best melee weapon in hand or pockets. */
    static ItemStack bestMelee(Worker w) {
        ItemStack best = null;
        double bestD = 0;
        for (ItemStack it : allItems(w)) {
            if (it == null) continue;
            double d = damage(it.getType());
            if (d > bestD && !isBow(it.getType())) {
                bestD = d;
                best = it;
            }
        }
        return bestD >= 2.5 ? best : null;
    }

    static ItemStack bow(Worker w) {
        for (ItemStack it : allItems(w)) if (it != null && isBow(it.getType())) return it;
        return null;
    }

    private static ItemStack[] allItems(Worker w) {
        return w.pockets.getContents(); // the hand only shows a copy
    }

    // ---- Gear from storage ----

    /**
     * Take better armor, a weapon (and a bow / arrows / potions if wanted) from this chest.
     * Old gear goes back into the chest. Returns true if anything changed.
     */
    static boolean gearUp(Worker w, Block chest, boolean wantBow, int arrows, int potions) {
        Inventory inv = Town.inventory(chest);
        if (inv == null) return false;
        boolean changed = false;
        EntityEquipment eq = w.body.getEquipment();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            EquipmentSlot slot = armorSlot(it.getType());
            if (slot != null) {
                ItemStack cur = eq.getItem(slot);
                if (armorRank(it.getType()) > armorRank(cur == null ? null : cur.getType())) {
                    ItemStack one = it.asOne();
                    it.subtract();
                    inv.setItem(i, it.getAmount() > 0 ? it : null);
                    eq.setItem(slot, one);
                    if (cur != null && !cur.getType().isAir()) inv.addItem(cur);
                    changed = true;
                }
            }
        }
        ItemStack melee = bestMelee(w);
        double have = melee == null ? 0 : damage(melee.getType());
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || isBow(it.getType()) || armorSlot(it.getType()) != null) continue;
            if (damage(it.getType()) >= 2.5 && damage(it.getType()) > have) {
                ItemStack one = it.asOne();
                it.subtract();
                inv.setItem(i, it.getAmount() > 0 ? it : null);
                if (melee != null) {
                    w.pockets.removeItem(melee);
                    if (eq.getItemInMainHand().isSimilar(melee)) eq.setItemInMainHand(null);
                    inv.addItem(melee);
                }
                w.pockets.addItem(one);
                melee = one;
                have = damage(one.getType());
                changed = true;
            }
        }
        if (wantBow && bow(w) == null) changed |= takeFirst(w, inv, it -> isBow(it.getType()), 1);
        if (arrows > 0) {
            int need = arrows - w.count(Material.ARROW);
            if (need > 0) changed |= takeFirst(w, inv, it -> it.getType() == Material.ARROW, need);
        }
        if (potions > 0) {
            int need = potions - potionCount(w);
            if (need > 0) changed |= takeFirst(w, inv, Combat::isHealingPotion, need);
        }
        return changed;
    }

    /** Does this chest have something that would improve the settler's gear? */
    static boolean hasUpgrade(Worker w, Block chest, boolean wantBow, int arrows, int potions) {
        Inventory inv = Town.inventory(chest);
        if (inv == null) return false;
        EntityEquipment eq = w.body.getEquipment();
        ItemStack melee = bestMelee(w);
        double have = melee == null ? 0 : damage(melee.getType());
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            EquipmentSlot slot = armorSlot(it.getType());
            if (slot != null) {
                ItemStack cur = eq.getItem(slot);
                if (armorRank(it.getType()) > armorRank(cur == null ? null : cur.getType())) return true;
            } else if (!isBow(it.getType()) && damage(it.getType()) >= 2.5 && damage(it.getType()) > have) {
                return true;
            } else if (wantBow && isBow(it.getType()) && bow(w) == null) {
                return true;
            } else if (arrows > 0 && it.getType() == Material.ARROW && w.count(Material.ARROW) < arrows) {
                return true;
            } else if (potions > 0 && isHealingPotion(it) && potionCount(w) < potions) {
                return true;
            }
        }
        return false;
    }

    static int potionCount(Worker w) {
        int n = 0;
        for (ItemStack it : w.pockets.getContents()) if (isHealingPotion(it)) n += it.getAmount();
        return n;
    }

    private static boolean takeFirst(Worker w, Inventory inv, java.util.function.Predicate<ItemStack> test, int amount) {
        boolean took = false;
        for (int i = 0; i < inv.getSize() && amount > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || !test.test(it)) continue;
            int n = Math.min(amount, it.getAmount());
            ItemStack part = it.clone();
            part.setAmount(n);
            if (!w.pockets.addItem(part).isEmpty()) break;
            it.setAmount(it.getAmount() - n);
            inv.setItem(i, it.getAmount() > 0 ? it : null);
            amount -= n;
            took = true;
        }
        return took;
    }

    // ---- Fighting ----

    /** Hit the target with the best melee weapon if close enough and ready. */
    static void melee(Worker w, LivingEntity target, ItemStack weapon) {
        Location me = w.body.getLocation(), them = target.getLocation();
        if (!w.body.getEquipment().getItemInMainHand().isSimilar(weapon)) w.body.getEquipment().setItemInMainHand(weapon.clone());
        double d = me.distance(them);
        if (d > 2.6) {
            if (w.chaseRepath-- <= 0) {
                w.chaseRepath = 10;
                w.mover.go(them, 1.6, w.plugin.settings().workSpeed() * 1.4);
            }
            return;
        }
        w.mover.stop();
        Mover.lookAt(w.body, target.getEyeLocation());
        int now = Bukkit.getCurrentTick();
        if (now < w.attackReady) return;
        w.attackReady = now + cooldown(weapon.getType());
        w.body.swingMainHand();
        target.damage(damage(weapon.getType()), w.body);
        w.body.getWorld().playSound(me, "minecraft:entity.player.attack.strong", SoundCategory.NEUTRAL, 0.8f, 1f);
        w.body.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, target.getHeight() / 2, 0), 6, 0.3, 0.3, 0.3, 0.1);
        if (target instanceof Mob m && m.getTarget() == null) m.setTarget(w.body);
    }

    /** Shoot an arrow at the target if ready and in sight. Returns false if it can't shoot. */
    static boolean shoot(Worker w, LivingEntity target, ItemStack bow) {
        if (w.count(Material.ARROW) <= 0) return false;
        if (!w.body.getEquipment().getItemInMainHand().isSimilar(bow)) w.body.getEquipment().setItemInMainHand(bow.clone());
        w.mover.stop();
        Mover.lookAt(w.body, target.getEyeLocation());
        if (!w.body.hasLineOfSight(target)) {
            if (w.chaseRepath-- <= 0) {
                w.chaseRepath = 10;
                w.mover.go(target.getLocation(), 6, w.plugin.settings().workSpeed() * 1.2);
            }
            return true;
        }
        int now = Bukkit.getCurrentTick();
        // Draw the bow first (the real animation), then let go.
        if (w.drawing && now - w.drawStart > 40) w.drawing = false; // lost the target while drawing
        if (!w.drawing) {
            if (now < w.attackReady) return true;
            w.anim.use(45);
            w.drawing = true;
            w.drawStart = now;
            return true;
        }
        if (now - w.drawStart < (bow.getType() == Material.CROSSBOW ? 20 : 18)) return true;
        w.drawing = false;
        w.anim.stopUsing();
        w.attackReady = now + 6;
        Location eye = w.body.getEyeLocation();
        Vector to = target.getLocation().add(0, target.getHeight() * 0.6, 0).toVector().subtract(eye.toVector());
        double dist = to.length();
        to.normalize().multiply(2.2).add(new Vector(0, dist * 0.012, 0)); // a bit of arc
        Arrow arrow = w.body.launchProjectile(Arrow.class, to);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        w.take(Material.ARROW, 1);
        w.body.getWorld().playSound(eye, bow.getType() == Material.CROSSBOW ? "minecraft:item.crossbow.shoot" : "minecraft:entity.arrow.shoot",
                SoundCategory.NEUTRAL, 0.8f, 1f);
        return true;
    }

    /** Drink a healing potion from the pockets (guards, when allowed). */
    static boolean drinkPotion(Worker w) {
        for (ItemStack it : w.pockets.getContents()) {
            if (!isHealingPotion(it)) continue;
            boolean strong = ((PotionMeta) it.getItemMeta()).getBasePotionType() == PotionType.STRONG_HEALING;
            w.pockets.removeItem(it.asOne());
            heal(w.body, strong ? 8 : 4);
            w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.generic.drink", SoundCategory.NEUTRAL, 0.8f, 1f);
            w.body.getWorld().spawnParticle(Particle.HEART, w.body.getLocation().add(0, 2, 0), 3, 0.3, 0.2, 0.3);
            return true;
        }
        return false;
    }

    /** Eat food from the pockets. Returns false if there's nothing to eat. */
    static boolean eat(Worker w) {
        for (ItemStack it : w.pockets.getContents()) {
            if (it == null || foodValue(it.getType()) <= 0) continue;
            Material food = it.getType();
            w.pockets.removeItem(new ItemStack(food, 1));
            w.hold(food);
            w.anim.use(24); // the real eating animation
            for (int i = 0; i < 3; i++) {
                int delay = i * 6;
                Bukkit.getScheduler().runTaskLater(w.plugin, () -> {
                    if (!w.body.isValid()) return;
                    w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.generic.eat", SoundCategory.NEUTRAL, 0.6f, 1f);
                }, delay);
            }
            Bukkit.getScheduler().runTaskLater(w.plugin, () -> {
                if (!w.body.isValid()) return;
                heal(w.body, foodValue(food));
                w.holdTool();
            }, 20L);
            return true;
        }
        return false;
    }

    static String name(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
