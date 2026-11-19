package settlement;

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
    static final List<Material> PRODUCTS = products();

    private static List<Material> products() {
        List<Material> out = new ArrayList<>(List.of(Material.BEEF, Material.PORKCHOP, Material.MUTTON, Material.CHICKEN,
                Material.RABBIT, Material.LEATHER, Material.RABBIT_HIDE, Material.FEATHER, Material.EGG,
                Material.HONEY_BOTTLE, Material.HONEYCOMB));
        for (org.bukkit.DyeColor c : org.bukkit.DyeColor.values()) { // wool in every colour the sheep have
            Material wool = Material.matchMaterial(c.name() + "_WOOL");
            if (wool != null) out.add(wool);
        }
        return List.copyOf(out);
    }

    private static Material woolOf(Sheep s) {
        org.bukkit.DyeColor c = s.getColor();
        Material wool = c == null ? null : Material.matchMaterial(c.name() + "_WOOL");
        return wool != null ? wool : Material.WHITE_WOOL;
    }

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
        // Someone asked for milk and it's ready: hand it over first.
        Activity handover = Requests.deliver(w, t);
        if (handover != null) return handover;
        Map<Material, Integer> asked = Requests.asked(t, w);
        Predicate<Material> keep = m -> m == Material.SHEARS || m == Material.LEAD || isFood(m) || Work.gear(m) || asked.containsKey(m)
                || (asked.containsKey(Material.MILK_BUCKET) && m == Material.BUCKET) || m == Material.GLASS_BOTTLE || m == Material.GLASS;
        if (Work.needsDeposit(w, keep, false)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        // Meat, wool, leather, eggs, feathers lying around (after slaughtering, shearing, chickens laying): pick them up first.
        // Eggs only up to their storage target: the rest is left (they vanish after a while, like in vanilla).
        int eggRadius = w.plugin.settings().townRadius();
        Activity pick = Work.collectDrops(w, t, m -> m == Material.EGG ? t.wants(Material.EGG, eggRadius, w) : wanted(m), 24);
        if (pick != null) return pick;
        Map<EntityType, List<Animals>> herd = herd(t, w.plugin.settings().townRadius());

        // Milk for someone who asked (a bucket from storage, then a cow).
        Activity milk = milk(w, t, herd, asked);
        if (milk != null) return milk;

        int radius = w.plugin.settings().townRadius();
        // The pens and how many each holds: about one animal per 3 blocks of floor, shared by the kinds in it.
        // Half of a kind's share are adults it always keeps; while that animal's meat, leather or feathers are below
        // their storage targets it raises the pen full and slaughters the grown-up extra ones.
        List<Pen> pens = pens(t, herd, wp);
        Map<EntityType, Boolean> forTargets = new HashMap<>();
        for (EntityType kind : herd.keySet()) forTargets.put(kind, productWanted(t, w, kind, radius));

        // Too many adults of a kind in a pen: slaughter one.
        for (Pen pen : pens) {
            for (Map.Entry<EntityType, List<Animals>> e : pen.animals.entrySet()) {
                if (!wp.herding(e.getKey())) continue; // switched off: leave this kind alone
                List<Animals> adults = adults(e.getValue());
                int keepAlive = forTargets.getOrDefault(e.getKey(), false) ? pen.keep(e.getKey()) : pen.share(e.getKey());
                if (adults.size() <= keepAlive) continue;
                List<Animals> free = new ArrayList<>();
                for (Animals a : adults) {
                    if (Work.taken(w, a.getUniqueId()) || a.customName() != null || a.isLeashed()) continue; // named or on a lead: someone's pet
                    free.add(a);
                }
                if (free.isEmpty()) continue;
                Animals a = free.get(w.random.nextInt(free.size()));
                Work.claim(w, a.getUniqueId());
                return slaughter(w, a);
            }
        }
        // Sheep with wool: shear (needs shears).
        for (Animals a : herd.getOrDefault(EntityType.SHEEP, List.of())) {
            if (!wp.herding(EntityType.SHEEP)) break;
            if (a instanceof Sheep s && s.isAdult() && !s.isSheared() && !Work.taken(w, s.getUniqueId())
                    && t.wants(woolOf(s), radius, w)) { // enough of this colour: leave it
                if (!Work.hasTool(w, "SHEARS")) {
                    Activity f = Work.fetchTool(w, t, "SHEARS", "shears");
                    if (f != null) return f;
                    w.warnMissingTool("shears");
                    break; // no shears in storage: no shearing
                }
                w.hold(Material.SHEARS);
                Work.claim(w, s.getUniqueId());
                return new Work.GoToEntity(w, s, 2.0, "Shearing a sheep", en -> shear(w, (Sheep) en));
            }
        }
        // Animals that got out of their pen: led back on a lead.
        Activity back = leadBack(w, t, wp, herd, radius, keep);
        if (back != null) return back;

        // Breeding, per pen: up to the adults to keep, or the pen full while a target needs them.
        String missingFood = null, penFull = null;
        for (Pen pen : pens) {
            for (Map.Entry<EntityType, List<Animals>> e : pen.animals.entrySet()) {
                EntityType kind = e.getKey();
                if (!wp.herding(kind)) continue;
                List<Animals> adults = adults(e.getValue());
                if (adults.size() < 2) continue;
                boolean wanted = forTargets.getOrDefault(kind, false);
                int goal = wanted ? pen.share(kind) : pen.keep(kind);
                if (e.getValue().size() >= goal) {
                    if (wanted && penFull == null) penFull = kindName(kind).toLowerCase(Locale.ROOT);
                    continue;
                }
                List<Animals> ready = new ArrayList<>();
                for (Animals a : adults) if (a.canBreed() && !a.isLoveMode() && !Work.taken(w, a.getUniqueId())) ready.add(a);
                long inLove = adults.stream().filter(Animals::isLoveMode).count();
                if (ready.isEmpty() || (ready.size() < 2 && inLove == 0)) continue;
                List<Material> foods = FOOD.get(kind);
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
                return new Work.GoToEntity(w, a, 2.0, "Feeding the " + kind.name().toLowerCase(Locale.ROOT) + "s",
                        en -> feed(w, (Animals) en, feed));
            }
        }
        // Too few chickens in a pen: throw eggs into it, only spare ones (the eggs' storage target is reached).
        Integer eggTarget = t.targets.get(Material.EGG);
        int spare = eggTarget == null ? 0 : t.count(Material.EGG, radius) - eggTarget;
        Pen henPen = null;
        if (wp.isOn("EGGS") && wp.herding(EntityType.CHICKEN) && eggTarget != null) {
            boolean wanted = forTargets.getOrDefault(EntityType.CHICKEN, false);
            for (Pen pen : pens) {
                List<Animals> c = pen.animals.get(EntityType.CHICKEN);
                if (c != null && !c.isEmpty() && c.size() < (wanted ? pen.share(EntityType.CHICKEN) : pen.keep(EntityType.CHICKEN))) {
                    henPen = pen;
                    break;
                }
            }
        }
        if (henPen != null && w.count(Material.EGG) == 0 && spare > 0) {
            Activity f = Work.fetch(w, t, m -> m == Material.EGG, Math.min(4, spare), "Getting eggs for chicks");
            if (f != null) return f;
        }
        if (henPen != null && spare >= 0 && w.count(Material.EGG) > 0) {
            List<Animals> chickens = henPen.animals.get(EntityType.CHICKEN);
            int room = (forTargets.getOrDefault(EntityType.CHICKEN, false) ? henPen.share(EntityType.CHICKEN) : henPen.keep(EntityType.CHICKEN)) - chickens.size();
            Animals hen = chickens.get(w.random.nextInt(chickens.size()));
            w.workStatus = "Throwing eggs for chicks";
            return new Work.GoToEntity(w, hen, 3.0, "Throwing eggs", en -> {
                // Only spots well inside the pen, near the hen: an egg never lands on (or past) the fence.
                List<Location> spots = inner(pen(en), en.getLocation(), 3);
                if (spots.isEmpty()) return;
                int eggs = Math.min(4, Math.min(w.count(Material.EGG), room));
                for (int i = 0; i < eggs; i++) {
                    Location target = spots.get(w.random.nextInt(spots.size()));
                    w.plugin.getServer().getScheduler().runTaskLater(w.plugin, () -> throwEgg(w, target), i * 10L);
                }
            });
        }
        // Honey and honeycomb from full beehives (under a campfire it lights for a moment).
        if (wp.isOn("BEES")) {
            Activity bees = Bees.next(w, t);
            if (bees != null) return bees;
        }
        if (Work.needsDeposit(w, keep, true)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        if (herd.isEmpty()) w.workStatus = "No animals in pens (fences, walls, gates)";
        else if (missingFood != null) w.workStatus = "Needs " + missingFood + " to breed (none in storage)";
        else if (penFull != null) w.workStatus = "Pen full: no room for more " + penFull + " (a bigger pen holds more)";
        else w.workStatus = "Looking after the animals";
        return null;
    }

    /** Milk for a request: a bucket from storage, then milk a cow. Null when not asked (or it says sorry). */
    private static Activity milk(Worker w, Town t, Map<EntityType, List<Animals>> herd, Map<Material, Integer> asked) {
        Integer want = asked.get(Material.MILK_BUCKET);
        if (want == null || w.count(Material.MILK_BUCKET) >= want) return null;
        if (w.count(Material.BUCKET) == 0) {
            Activity f = Work.fetch(w, t, m -> m == Material.BUCKET, want - w.count(Material.MILK_BUCKET), "Getting buckets");
            if (f != null) return f;
            Requests.decline(w.plugin, t, w, Material.MILK_BUCKET, "I have no buckets for milk");
            return null;
        }
        Animals cow = null;
        double best = Double.MAX_VALUE;
        for (EntityType kind : new EntityType[]{EntityType.COW, EntityType.MOOSHROOM}) {
            for (Animals a : herd.getOrDefault(kind, List.of())) {
                if (!a.isAdult()) continue;
                double d = a.getLocation().distanceSquared(w.body.getLocation());
                if (d < best) {
                    best = d;
                    cow = a;
                }
            }
        }
        if (cow == null) {
            Requests.decline(w.plugin, t, w, Material.MILK_BUCKET, "there's no cow to milk");
            return null;
        }
        w.workStatus = "Milking a cow";
        return new Work.GoToEntity(w, cow, 2.0, "Milking a cow", en -> {
            if (!w.take(Material.BUCKET, 1)) return;
            w.give(List.of(new ItemStack(Material.MILK_BUCKET)), en.getLocation());
            w.hold(Material.MILK_BUCKET);
            en.getWorld().playSound(en.getLocation(), "minecraft:entity.cow.milk", SoundCategory.NEUTRAL, 1f, 1f);
        });
    }

    /** Animals of the kinds it herds in the whole settlement, per kind. */
    static Map<EntityType, List<Animals>> herd(Town t, int radius) {
        Map<EntityType, List<Animals>> out = new LinkedHashMap<>();
        if (t.world() == null) return out;
        int[] box = t.box(radius - Town.BASE);
        Location c = new Location(t.world(), (box[0] + box[1] + 1) / 2.0, t.bell.getY(), (box[2] + box[3] + 1) / 2.0);
        for (Animals a : c.getWorld().getNearbyEntitiesByType(Animals.class, c, (box[1] - box[0]) / 2.0 + 1, 16, (box[3] - box[2]) / 2.0 + 1)) {
            if (!FOOD.containsKey(a.getType()) || !t.contains(a.getLocation(), radius) || a.isDead()) continue;
            if (a.getPersistentDataContainer().has(Keys.TOWN)) continue; // never someone's settler
            if (!penned(a)) continue; // only animals in a pen: wild ones are left alone
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
        Work.wearTool(w, "SHEARS");
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
        // A high lob (about 1.5 blocks above its head), so it clears the fence and lands on the spot.
        double g = 0.03, vy = 0.3;
        double time = (vy + Math.sqrt(Math.max(0, vy * vy - 2 * g * dy))) / g; // ticks in the air
        double drag = (1 - Math.pow(0.99, time)) / (0.01 * time); // air slows it down a little
        org.bukkit.util.Vector v = new org.bukkit.util.Vector(dx / time / drag, vy, dz / time / drag);
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
        List<Location> cells = penCells(animal);
        if (cells == null) { // not fenced in (or huge): aim close to the animal
            List<Location> near = new ArrayList<>();
            org.bukkit.block.Block b = animal.getLocation().getBlock();
            near.add(new Location(animal.getWorld(), b.getX() + 0.5, b.getY(), b.getZ() + 0.5));
            return near;
        }
        return cells;
    }

    /** The pen's floor spots (same rules as penned: no going over a fence), or null when it's bigger than 400. */
    static List<Location> penCells(Entity animal) {
        return penCells(animal, 400);
    }

    static List<Location> penCells(Entity animal, int max) {
        org.bukkit.World world = animal.getWorld();
        org.bukkit.block.Block start = animal.getLocation().getBlock();
        List<Location> cells = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> todo = new ArrayDeque<>();
        todo.add(new int[]{start.getX(), start.getY(), start.getZ()});
        seen.add(key(start.getX(), start.getY(), start.getZ()));
        while (!todo.isEmpty()) {
            if (cells.size() >= max) return null;
            int[] c = todo.poll();
            cells.add(new Location(world, c[0] + 0.5, c[1], c[2] + 0.5));
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = c[0] + d[0], y = c[1] + dy, z = c[2] + d[1];
                    if (!PathFinder.walkable(world, x, y, z)) continue;
                    org.bukkit.Material m = world.getBlockAt(x, y, z).getType();
                    if (isPenSide(m) || isPenSide(world.getBlockAt(x, y - 1, z).getType())) continue; // the way out (or over)
                    if (seen.add(key(x, y, z))) todo.add(new int[]{x, y, z});
                    break;
                }
            }
        }
        return cells;
    }

    /** Spots of the pen at least a block from its sides, within r of a spot (fallback: any pen spot that close). */
    static List<Location> inner(List<Location> cells, Location near, double r) {
        Set<Long> in = new HashSet<>();
        for (Location l : cells) in.add(key(l.getBlockX(), 0, l.getBlockZ()));
        List<Location> out = new ArrayList<>(), close = new ArrayList<>();
        for (Location l : cells) {
            if (l.getWorld() != near.getWorld() || l.distanceSquared(near) > r * r) continue;
            close.add(l);
            int x = l.getBlockX(), z = l.getBlockZ();
            boolean inside = true;
            for (int dx = -1; dx <= 1 && inside; dx++)
                for (int dz = -1; dz <= 1; dz++) if (!in.contains(key(x + dx, 0, z + dz))) { inside = false; break; }
            if (inside) out.add(l);
        }
        return out.isEmpty() ? close : out;
    }

    /** Most animals one pen holds, however big (lag). */
    static final int MAX_PER_PEN = 40;

    /** A pen: its floor, and the animals in it per kind. */
    static final class Pen {
        final Set<Long> floor = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        final Map<EntityType, List<Animals>> animals = new LinkedHashMap<>();
        int capacity;

        /** This kind's part of the pen (shared evenly by the kinds in it). */
        int share(EntityType kind) {
            return Math.max(2, capacity / Math.max(1, animals.size()));
        }

        /** Adults always kept (half its share). */
        int keep(EntityType kind) {
            return Math.max(2, share(kind) / 2);
        }
    }

    private record PenCache(List<Pen> pens, int at) {}

    private static final Map<String, PenCache> penCache = new HashMap<>();

    /** The pens of a settlement with the animals it looks after (worked out again every 10 seconds). */
    static List<Pen> pens(Town t, Map<EntityType, List<Animals>> herd, Town.Workplace wp) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        PenCache c = penCache.get(t.id);
        if (c != null && now - c.at() < 200 && now >= c.at()) {
            // Same pens: just put the animals in them again (they move, are born, die).
            for (Pen p : c.pens()) p.animals.clear();
            assign(c.pens(), herd, wp);
            return c.pens();
        }
        List<Pen> pens = new ArrayList<>();
        for (Map.Entry<EntityType, List<Animals>> e : herd.entrySet()) {
            for (Animals a : e.getValue()) {
                if (inPen(pens, a) != null) continue;
                List<Location> cells = penCells(a, 1600);
                if (cells == null) continue;
                Pen p = new Pen();
                for (Location l : cells) {
                    p.floor.add(key(l.getBlockX(), l.getBlockY(), l.getBlockZ()));
                    p.minX = Math.min(p.minX, l.getBlockX());
                    p.maxX = Math.max(p.maxX, l.getBlockX());
                    p.minZ = Math.min(p.minZ, l.getBlockZ());
                    p.maxZ = Math.max(p.maxZ, l.getBlockZ());
                }
                p.capacity = Math.min(MAX_PER_PEN, Math.max(2, cells.size() / 3));
                pens.add(p);
            }
        }
        assign(pens, herd, wp);
        penCache.put(t.id, new PenCache(pens, now));
        return pens;
    }

    private static void assign(List<Pen> pens, Map<EntityType, List<Animals>> herd, Town.Workplace wp) {
        for (Map.Entry<EntityType, List<Animals>> e : herd.entrySet()) {
            if (!wp.herding(e.getKey())) continue;
            for (Animals a : e.getValue()) {
                Pen p = inPen(pens, a);
                if (p != null) p.animals.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(a);
            }
        }
    }

    private static Pen inPen(List<Pen> pens, Entity a) {
        Location l = a.getLocation();
        long k = key(l.getBlockX(), l.getBlockY(), l.getBlockZ()), up = key(l.getBlockX(), l.getBlockY() + 1, l.getBlockZ());
        for (Pen p : pens) if (p.floor.contains(k) || p.floor.contains(up)) return p;
        return null;
    }

    /** For the workstation menu: per pen its size, how full it is and the animals in it. */
    static List<String> summary(Town t, Town.Workplace wp, int radius) {
        Map<EntityType, List<Animals>> herd = herd(t, radius);
        List<Pen> pens = pens(t, herd, wp);
        List<String> out = new ArrayList<>();
        for (Pen p : pens) {
            int n = 0;
            StringBuilder kinds = new StringBuilder();
            for (Map.Entry<EntityType, List<Animals>> e : p.animals.entrySet()) {
                int young = e.getValue().size() - adults(e.getValue()).size();
                n += e.getValue().size();
                if (kinds.length() > 0) kinds.append(", ");
                kinds.append(e.getValue().size()).append(' ').append(kindName(e.getKey()).toLowerCase(Locale.ROOT));
                if (young > 0) kinds.append(" (").append(young).append(" young)");
            }
            if (n == 0) continue;
            out.add("Pen " + (p.maxX - p.minX + 1) + "x" + (p.maxZ - p.minZ + 1) + " · " + n + "/" + p.capacity + " · " + kinds);
        }
        return out;
    }

    /** What each kind gives when slaughtered (for its storage targets). */
    private static final Map<EntityType, List<Material>> MEAT = Map.of(
            EntityType.COW, List.of(Material.BEEF, Material.LEATHER),
            EntityType.SHEEP, List.of(Material.MUTTON),
            EntityType.PIG, List.of(Material.PORKCHOP),
            EntityType.CHICKEN, List.of(Material.CHICKEN, Material.FEATHER),
            EntityType.RABBIT, List.of(Material.RABBIT, Material.RABBIT_HIDE));

    /** A storage target for its meat, leather, feathers or hide is set and not reached (counting what it carries). */
    static boolean productWanted(Town t, Worker w, EntityType kind, int radius) {
        for (Material m : MEAT.getOrDefault(kind, List.of())) {
            Integer target = t.targets.get(m);
            if (target != null && target > 0 && t.count(m, radius) + w.count(m) < target) return true;
        }
        return false;
    }

    // ---- Escaped animals: back into the pen on a lead ----

    private static Activity leadBack(Worker w, Town t, Town.Workplace wp, Map<EntityType, List<Animals>> herd, int radius,
                                     Predicate<Material> keep) {
        if (t.world() == null || herd.isEmpty()) return null;
        int[] box = t.box(radius - Town.BASE);
        Location c = new Location(t.world(), (box[0] + box[1] + 1) / 2.0, t.bell.getY(), (box[2] + box[3] + 1) / 2.0);
        Animals out = null;
        Animals home = null;
        double best = Double.MAX_VALUE;
        for (Animals a : c.getWorld().getNearbyEntitiesByType(Animals.class, c, (box[1] - box[0]) / 2.0 + 1, 16, (box[3] - box[2]) / 2.0 + 1)) {
            List<Animals> kin = herd.get(a.getType());
            if (kin == null || kin.isEmpty() || !wp.herding(a.getType())) continue;
            if (!t.contains(a.getLocation(), radius) || a.isDead() || a.isLeashed() || a.customName() != null) continue;
            if (a.getPersistentDataContainer().has(Keys.TOWN) || kin.contains(a) || Work.taken(w, a.getUniqueId())) continue;
            // Near its kind's pen (a wild animal far away is left alone).
            Animals nearest = null;
            double nd = Double.MAX_VALUE;
            for (Animals k : kin) {
                double d = k.getLocation().distanceSquared(a.getLocation());
                if (d < nd) {
                    nd = d;
                    nearest = k;
                }
            }
            if (nearest == null || nd > 24 * 24) continue;
            double d = a.getLocation().distanceSquared(w.body.getLocation());
            if (d < best) {
                best = d;
                out = a;
                home = nearest;
            }
        }
        if (out == null) return null;
        if (w.count(Material.LEAD) == 0) {
            Activity f = Work.fetch(w, t, m -> m == Material.LEAD, 1, "Getting a lead");
            if (f != null) return f;
            w.workStatus = "Needs a lead: animals got out of their pen";
            return null;
        }
        Work.claim(w, out.getUniqueId());
        return new LeadBack(w, out, home);
    }

    /** Walk up to the animal, put it on a lead, walk it back into its pen (through the gate), let it go. */
    private static final class LeadBack implements Activity {
        private final Worker w;
        private final Animals animal;
        private final Animals home;
        private boolean onLead;
        private int time, repath;

        LeadBack(Worker w, Animals animal, Animals home) {
            this.w = w;
            this.animal = animal;
            this.home = home;
            w.workStatus = "Bringing an animal back to its pen";
        }

        @Override
        public boolean tick() {
            if (!animal.isValid() || animal.isDead() || ++time > 1500) return true;
            Location me = w.body.getLocation();
            if (me.getWorld() != animal.getWorld()) return true;
            if (!onLead) {
                if (me.distanceSquared(animal.getLocation()) <= 2.5 * 2.5) {
                    w.mover.stop();
                    w.anim.look(animal.getEyeLocation());
                    w.body.swingMainHand();
                    if (animal.isLeashed() || !w.take(Material.LEAD, 1)) return true;
                    if (!animal.setLeashHolder(w.body)) {
                        w.give(List.of(new ItemStack(Material.LEAD)), me);
                        return true;
                    }
                    onLead = true;
                    w.leading = animal;
                    animal.getWorld().playSound(animal.getLocation(), "minecraft:item.lead.tied", SoundCategory.NEUTRAL, 0.8f, 1f);
                    repath = 0;
                    return false;
                }
                if (--repath <= 0) {
                    repath = 15;
                    if (!w.mover.go(animal.getLocation(), 1.5, w.plugin.settings().workSpeed()) && time > 100) return true;
                }
                return false;
            }
            if (!animal.isLeashed()) return true; // the lead broke (too far): it stays where it is
            Location dest = home.isValid() ? home.getLocation() : animal.getLocation();
            boolean there = me.distanceSquared(dest) <= 2.5 * 2.5 && animal.getLocation().distanceSquared(me) <= 3 * 3;
            if (there) {
                pennedCache.remove(animal.getUniqueId());
                if (penned(animal) || time > 1200) return true; // in (stop() lets it go)
            }
            if (--repath <= 0) {
                repath = 15;
                // Slowly, so it keeps up.
                if (!w.mover.go(dest, 1.5, w.plugin.settings().workSpeed() * 0.8) && time > 200) return true;
            }
            return false;
        }

        @Override
        public void stop() {
            w.mover.stop();
            if (w.leading == animal) w.leading = null;
            if (onLead && animal.isValid() && animal.isLeashed()) {
                Location at = animal.getLocation();
                animal.setLeashHolder(null);
                animal.getWorld().playSound(at, "minecraft:item.lead.untied", SoundCategory.NEUTRAL, 0.8f, 1f);
                // The lead comes back to the herder (not left lying in the pen).
                w.plugin.getServer().getScheduler().runTask(w.plugin, () -> {
                    for (Item it : at.getWorld().getNearbyEntitiesByType(Item.class, at, 3))
                        if (it.getItemStack().getType() == Material.LEAD && it.getTicksLived() < 10) {
                            it.remove();
                            break;
                        }
                });
                w.give(List.of(new ItemStack(Material.LEAD)), w.body.getLocation());
            }
        }

        @Override
        public String label() {
            return onLead ? "Bringing an animal back to its pen" : "Going to an animal that got out";
        }
    }

    private record Penned(boolean in, int at) {}

    private static final Map<UUID, Penned> pennedCache = new HashMap<>();

    /**
     * In a pen: it can't walk far (fenced in) and fences, walls or gates are part of what holds it in
     * (not just a hole or a cave). Checked every 10 seconds per animal.
     */
    static boolean penned(Entity animal) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        Penned c = pennedCache.get(animal.getUniqueId());
        if (c != null && now - c.at() < 200) return c.in();
        if (pennedCache.size() > 2000) pennedCache.values().removeIf(p -> now - p.at() >= 200);
        org.bukkit.World world = animal.getWorld();
        org.bukkit.block.Block start = animal.getLocation().getBlock();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> todo = new ArrayDeque<>();
        todo.add(new int[]{start.getX(), start.getY(), start.getZ()});
        seen.add(key(start.getX(), start.getY(), start.getZ()));
        int cells = 0;
        boolean fenced = false;
        while (!todo.isEmpty() && cells < 1600) {
            int[] p = todo.poll();
            cells++;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = p[0] + d[0], z = p[2] + d[1];
                boolean moved = false;
                for (int dy = -1; dy <= 1 && !moved; dy++) {
                    int y = p[1] + dy;
                    if (!PathFinder.walkable(world, x, y, z)) continue;
                    org.bukkit.Material m = world.getBlockAt(x, y, z).getType();
                    if (isPenSide(m) || isPenSide(world.getBlockAt(x, y - 1, z).getType())) continue; // can't go over a fence either
                    moved = true;
                    if (seen.add(key(x, y, z))) todo.add(new int[]{x, y, z});
                }
                if (!moved && !fenced)
                    for (int dy = -1; dy <= 1; dy++) if (isPenSide(world.getBlockAt(x, p[1] + dy, z).getType())) fenced = true;
            }
        }
        boolean in = cells < 1600 && fenced; // up to about 40x40
        pennedCache.put(animal.getUniqueId(), new Penned(in, now));
        return in;
    }

    private static boolean isPenSide(org.bukkit.Material m) {
        return org.bukkit.Tag.FENCES.isTagged(m) || org.bukkit.Tag.WALLS.isTagged(m) || org.bukkit.Tag.FENCE_GATES.isTagged(m) || m.name().endsWith("_CARPET");
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
