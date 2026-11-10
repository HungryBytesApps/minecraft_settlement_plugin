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

    static String kindName(EntityType kind) {
        return switch (kind) {
            case COW -> "Cows";
            case SHEEP -> "Sheep";
            case PIG -> "Pigs";
            case CHICKEN -> "Chickens";
            case RABBIT -> "Rabbits";
            default -> kind.name();
        };
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
        // Meat, wool, leather, eggs, feathers lying around (after slaughtering, shearing, chickens laying): pick them up first.
        Activity pick = Work.collectDrops(w, t, HerderJob::wanted, 24);
        if (pick != null) return pick;
        Map<EntityType, List<Animals>> herd = herd(t, w.plugin.settings().townRadius());

        // Too many adults of a kind: slaughter one.
        for (Map.Entry<EntityType, List<Animals>> e : herd.entrySet()) {
            int size = wp.herdSize(e.getKey());
            if (size <= 0) continue; // leave this kind alone
            List<Animals> adults = adults(e.getValue());
            if (adults.size() > size) {
                List<Animals> free = new ArrayList<>();
                for (Animals a : adults) if (!Work.taken(w, a.getUniqueId())) free.add(a);
                if (free.isEmpty()) continue;
                Animals a = free.get(w.random.nextInt(free.size()));
                Work.claim(w, a.getUniqueId());
                return slaughter(w, a);
            }
        }
        // Sheep with wool: shear (needs shears).
        for (Animals a : herd.getOrDefault(EntityType.SHEEP, List.of())) {
            if (a instanceof Sheep s && s.isAdult() && !s.isSheared() && !Work.taken(w, s.getUniqueId())) {
                if (!Work.hasTool(w, "SHEARS")) {
                    Activity f = Work.fetchTool(w, t, "SHEARS", "shears");
                    if (f != null) return f;
                    break; // no shears in storage: no shearing
                }
                w.hold(Material.SHEARS);
                Work.claim(w, s.getUniqueId());
                return new Work.GoToEntity(w, s, 2.0, "Shearing a sheep", en -> shear(w, (Sheep) en));
            }
        }
        // Too few: breed (a pair that can), or throw eggs for chicks.
        String missingFood = null;
        for (Map.Entry<EntityType, List<Animals>> e : herd.entrySet()) {
            int size = wp.herdSize(e.getKey());
            List<Animals> adults = adults(e.getValue());
            if (size <= 0 || adults.size() >= size || adults.size() < 2) continue;
            if (e.getValue().size() - adults.size() >= size - adults.size()) continue; // enough young ones growing up
            List<Animals> ready = new ArrayList<>();
            for (Animals a : adults) if (a.canBreed() && !a.isLoveMode() && !Work.taken(w, a.getUniqueId())) ready.add(a);
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
            Work.claim(w, a.getUniqueId());
            Material feed = food;
            w.workStatus = "Breeding";
            return new Work.GoToEntity(w, a, 2.0, "Feeding the " + e.getKey().name().toLowerCase(Locale.ROOT) + "s",
                    en -> feed(w, (Animals) en, feed));
        }
        // Too few chickens: throw eggs into their pen.
        List<Animals> chickens = herd.getOrDefault(EntityType.CHICKEN, List.of());
        int chickenSize = wp.herdSize(EntityType.CHICKEN);
        if (!chickens.isEmpty() && chickens.size() < chickenSize && w.count(Material.EGG) > 0) {
            Animals hen = chickens.get(w.random.nextInt(chickens.size()));
            List<Location> pen = pen(hen);
            w.workStatus = "Throwing eggs for chicks";
            return new Work.GoToEntity(w, hen, 5.5, "Throwing eggs", en -> {
                int eggs = Math.min(4, Math.min(w.count(Material.EGG), chickenSize - chickens.size()));
                for (int i = 0; i < eggs; i++) {
                    Location target = pen.get(w.random.nextInt(pen.size()));
                    w.plugin.getServer().getScheduler().runTaskLater(w.plugin, () -> throwEgg(w, target), i * 10L);
                }
            });
        }
        if (Work.needsDeposit(w, keep, true)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        if (herd.isEmpty()) w.workStatus = "No animals in the settlement";
        else if (missingFood != null) w.workStatus = "Needs " + missingFood + " to breed (none in storage)";
        else w.workStatus = "Looking after the animals";
        return null;
    }

    /** Animals of the kinds it herds in the whole settlement, per kind. */
    static Map<EntityType, List<Animals>> herd(Town t, int radius) {
        Map<EntityType, List<Animals>> out = new LinkedHashMap<>();
        Location c = t.bell.clone().add(0.5, 0, 0.5);
        if (c.getWorld() == null) return out;
        for (Animals a : c.getWorld().getNearbyEntitiesByType(Animals.class, c, radius, 16, radius)) {
            if (!FOOD.containsKey(a.getType()) || !t.contains(a.getLocation(), radius) || a.isDead()) continue;
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

    /** Throw one egg in an arc so it lands on this spot. */
    private static void throwEgg(Worker w, Location at) {
        if (!w.body.isValid() || !w.take(Material.EGG, 1)) return;
        w.hold(Material.EGG);
        w.anim.look(at);
        w.body.swingMainHand();
        Location eye = w.body.getEyeLocation();
        double dx = at.getX() - eye.getX(), dy = at.getY() + 0.2 - eye.getY(), dz = at.getZ() - eye.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double time = Math.max(8, dist * 2.2); // ticks in the air
        double g = 0.03;
        org.bukkit.util.Vector v = new org.bukkit.util.Vector(dx / time * 1.05, (dy + 0.5 * g * time * time) / time, dz / time * 1.05);
        Egg egg = w.body.launchProjectile(Egg.class, v);
        egg.setVelocity(v);
        w.body.getWorld().playSound(eye, "minecraft:entity.egg.throw", SoundCategory.NEUTRAL, 0.6f, 1f);
        w.plugin.getServer().getScheduler().runTaskLater(w.plugin, w::holdTool, 10L);
    }

    /**
     * The fenced area an animal is in: all spots it could walk to without passing a fence, wall,
     * gate or carpet-on-fence. If it's not fenced in (too big), just the spots around it.
     */
    static List<Location> pen(Entity animal) {
        org.bukkit.World world = animal.getWorld();
        org.bukkit.block.Block start = animal.getLocation().getBlock();
        List<Location> cells = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> todo = new ArrayDeque<>();
        todo.add(new int[]{start.getX(), start.getY(), start.getZ()});
        seen.add(key(start.getX(), start.getY(), start.getZ()));
        while (!todo.isEmpty() && cells.size() < 400) {
            int[] c = todo.poll();
            cells.add(new Location(world, c[0] + 0.5, c[1], c[2] + 0.5));
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = c[0] + d[0], y = c[1] + dy, z = c[2] + d[1];
                    if (!seen.add(key(x, y, z))) continue;
                    org.bukkit.block.Block feet = world.getBlockAt(x, y, z);
                    org.bukkit.Material m = feet.getType();
                    if (org.bukkit.Tag.FENCE_GATES.isTagged(m) || m.name().endsWith("_CARPET")) continue; // the way out
                    if (!PathFinder.walkable(world, x, y, z)) continue;
                    todo.add(new int[]{x, y, z});
                    break;
                }
            }
        }
        if (cells.size() >= 400) { // not fenced in: aim close to the animal
            List<Location> near = new ArrayList<>();
            for (Location l : cells) if (l.distanceSquared(animal.getLocation()) <= 3 * 3) near.add(l);
            return near.isEmpty() ? List.of(animal.getLocation()) : near;
        }
        return cells;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
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
