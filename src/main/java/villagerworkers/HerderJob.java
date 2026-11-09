package villagerworkers;

import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Predicate;

/**
 * Herder (loom): looks after the animals in its work area. Keeps each kind at the herd size: breeds
 * them with food from storage (and throws eggs for chicks), slaughters adults above the herd size,
 * shears sheep (with shears from storage) and brings meat, wool, leather, eggs and feathers to storage.
 */
final class HerderJob {
    private HerderJob() {}

    /** Animal → what it eats to breed. */
    static final Map<EntityType, List<Material>> FOOD = new LinkedHashMap<>();

    static {
        FOOD.put(EntityType.COW, List.of(Material.WHEAT));
        FOOD.put(EntityType.SHEEP, List.of(Material.WHEAT));
        FOOD.put(EntityType.PIG, List.of(Material.CARROT, Material.POTATO, Material.BEETROOT));
        FOOD.put(EntityType.CHICKEN, List.of(Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS, Material.MELON_SEEDS, Material.PUMPKIN_SEEDS));
        FOOD.put(EntityType.RABBIT, List.of(Material.CARROT, Material.DANDELION, Material.GOLDEN_CARROT));
    }

    /** What herding gives (for storage targets and picking things up). */
    static final List<Material> PRODUCTS = List.of(Material.BEEF, Material.PORKCHOP, Material.MUTTON, Material.CHICKEN,
            Material.RABBIT, Material.LEATHER, Material.RABBIT_HIDE, Material.FEATHER, Material.EGG, Material.WHITE_WOOL);

    /** Animals this settler's slaughter hit is allowed on (settlers normally can't hurt animals). */
    private static final Set<UUID> mayKill = new HashSet<>();

    static boolean mayKill(Entity e) {
        return mayKill.contains(e.getUniqueId());
    }

    static boolean wanted(Material m) {
        return PRODUCTS.contains(m) || m.name().endsWith("_WOOL") || m == Material.RABBIT_FOOT;
    }

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        Predicate<Material> keep = m -> m == Material.SHEARS || isFood(m) || Work.gear(m);
        if (Work.needsDeposit(w, keep, false)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        Map<EntityType, List<Animals>> herd = herd(wp);
        int size = wp.herd;

        // Too many adults of a kind: slaughter one.
        for (Map.Entry<EntityType, List<Animals>> e : herd.entrySet()) {
            List<Animals> adults = adults(e.getValue());
            if (adults.size() > size) {
                Animals a = adults.get(w.random.nextInt(adults.size()));
                return slaughter(w, a);
            }
        }
        // Sheep with wool: shear (needs shears).
        for (Animals a : herd.getOrDefault(EntityType.SHEEP, List.of())) {
            if (a instanceof Sheep s && s.isAdult() && !s.isSheared()) {
                if (!Work.hasTool(w, "SHEARS")) {
                    Activity f = Work.fetchTool(w, t, "SHEARS", "shears");
                    if (f != null) return f;
                    break; // no shears in storage: no shearing
                }
                w.hold(Material.SHEARS);
                return new Work.GoToEntity(w, s, 2.0, "Shearing a sheep", en -> shear(w, (Sheep) en));
            }
        }
        // Too few: breed (a pair that can), or throw eggs for chicks.
        String missingFood = null;
        for (Map.Entry<EntityType, List<Animals>> e : herd.entrySet()) {
            List<Animals> adults = adults(e.getValue());
            if (adults.size() >= size || adults.size() < 2) continue;
            if (e.getValue().size() - adults.size() >= size - adults.size()) continue; // enough young ones growing up
            List<Animals> ready = new ArrayList<>();
            for (Animals a : adults) if (a.canBreed() && !a.isLoveMode()) ready.add(a);
            long inLove = adults.stream().filter(Animals::isLoveMode).count();
            if (ready.isEmpty() || (ready.size() < 2 && inLove == 0)) continue;
            List<Material> foods = FOOD.get(e.getKey());
            Material food = carried(w, foods);
            if (food == null) {
                Activity f = Work.fetch(w, t, foods::contains, 16, "Getting animal feed");
                if (f != null) return f;
                missingFood = Work.nice(foods.get(0));
                continue;
            }
            Animals a = ready.get(0);
            Material feed = food;
            w.workStatus = "Breeding";
            return new Work.GoToEntity(w, a, 2.0, "Feeding the " + e.getKey().name().toLowerCase(Locale.ROOT) + "s",
                    en -> feed(w, (Animals) en, feed));
        }
        List<Animals> chickens = herd.getOrDefault(EntityType.CHICKEN, List.of());
        if (!chickens.isEmpty() && chickens.size() < size && w.count(Material.EGG) > 0) {
            Location spot = chickens.get(0).getLocation();
            return new Work.GoToEntity(w, chickens.get(0), 3.0, "Throwing eggs", en -> throwEgg(w, spot));
        }
        if (Work.needsDeposit(w, keep, true)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        if (herd.isEmpty()) w.workStatus = "No animals in the work area";
        else if (missingFood != null) w.workStatus = "Needs " + missingFood + " to breed (none in storage)";
        else w.workStatus = "Looking after the animals";
        return null;
    }

    /** Animals of the kinds it herds inside the work area, per kind. */
    static Map<EntityType, List<Animals>> herd(Town.Workplace wp) {
        Map<EntityType, List<Animals>> out = new LinkedHashMap<>();
        Location c = wp.loc.clone().add(0.5, 0, 0.5);
        for (Animals a : c.getWorld().getNearbyEntitiesByType(Animals.class, c, wp.radius + 0.5, 6, wp.radius + 0.5)) {
            if (!FOOD.containsKey(a.getType()) || !Work.inArea(wp, a.getLocation()) || a.isDead()) continue;
            if (a.getPersistentDataContainer().has(Keys.TOWN)) continue; // never someone's settler
            out.computeIfAbsent(a.getType(), k -> new ArrayList<>()).add(a);
        }
        return out;
    }

    private static List<Animals> adults(List<Animals> all) {
        List<Animals> out = new ArrayList<>();
        for (Animals a : all) if (a.isAdult()) out.add(a);
        return out;
    }

    private static Activity slaughter(Worker w, Animals a) {
        w.workStatus = "Slaughtering";
        return new Work.GoToEntity(w, a, 2.2, "Slaughtering", en -> {
            mayKill.add(en.getUniqueId());
            try {
                en.damage(1000, w.body);
            } finally {
                mayKill.remove(en.getUniqueId());
            }
        });
    }

    private static void shear(Worker w, Sheep s) {
        if (s.isSheared() || !s.isAdult()) return;
        s.setSheared(true);
        DyeColor color = s.getColor() == null ? DyeColor.WHITE : s.getColor();
        Material wool = Material.matchMaterial(color.name() + "_WOOL");
        if (wool == null) wool = Material.WHITE_WOOL;
        w.give(List.of(new ItemStack(wool, 1 + w.random.nextInt(3))), s.getLocation());
        s.getWorld().playSound(s.getLocation(), "minecraft:entity.sheep.shear", SoundCategory.NEUTRAL, 1f, 1f);
    }

    private static void feed(Worker w, Animals a, Material food) {
        if (!a.canBreed() || a.isLoveMode() || !w.take(food, 1)) return;
        a.setLoveModeTicks(600);
        a.getWorld().spawnParticle(org.bukkit.Particle.HEART, a.getLocation().add(0, a.getHeight() + 0.3, 0), 3, 0.3, 0.2, 0.3);
        a.getWorld().playSound(a.getLocation(), "minecraft:entity.generic.eat", SoundCategory.NEUTRAL, 0.8f, 1f);
    }

    private static void throwEgg(Worker w, Location at) {
        if (!w.take(Material.EGG, 1)) return;
        w.hold(Material.EGG);
        w.anim.look(at);
        Egg egg = w.body.launchProjectile(Egg.class);
        egg.setVelocity(at.toVector().subtract(w.body.getEyeLocation().toVector()).normalize().multiply(0.6));
        w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.egg.throw", SoundCategory.NEUTRAL, 0.6f, 1f);
        w.plugin.getServer().getScheduler().runTaskLater(w.plugin, w::holdTool, 10L);
    }

    private static boolean isFood(Material m) {
        for (List<Material> l : FOOD.values()) if (l.contains(m)) return true;
        return false;
    }

    private static Material carried(Worker w, List<Material> foods) {
        for (Material m : foods) if (w.count(m) > 0) return m;
        return null;
    }
}
