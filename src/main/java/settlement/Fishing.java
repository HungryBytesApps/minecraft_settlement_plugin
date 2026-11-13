package settlement;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Fisher (a barrel within 2 blocks of water): fishes next to its barrel with a rod from storage, keeps the
 * catch in the barrel (3 slots, only for the fisher; a pile of fish on the lid shows how full it is) and
 * carries it to the chests when it fills up. Fish whose storage target is reached go back into the water;
 * junk is kept only when chosen in the junk filter; treasure (in open water) is always kept.
 */
final class Fishing implements Listener {
    private final SettlementPlugin plugin;

    Fishing(SettlementPlugin plugin) {
        this.plugin = plugin;
    }

    static final List<Material> FISH = List.of(Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH);
    /** Vanilla junk (bamboo only in jungles: left out). */
    static final List<Material> JUNK = List.of(Material.LILY_PAD, Material.BOWL, Material.LEATHER, Material.LEATHER_BOOTS, Material.ROTTEN_FLESH,
            Material.STICK, Material.STRING, Material.POTION, Material.BONE, Material.INK_SAC, Material.TRIPWIRE_HOOK, Material.FISHING_ROD);
    private static final Set<Material> KEEP_BY_DEFAULT = Set.of(Material.LEATHER, Material.STRING, Material.BONE, Material.INK_SAC, Material.BOWL);
    static final int SLOTS = 3;
    private static final NamespacedKey PILE = new NamespacedKey(Keys.NS, "fish_pile");

    // ---- Where ----

    /** Water (a source with air above) within 2 blocks of the barrel. */
    static List<Block> waterNear(Block barrel) {
        List<Block> out = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    Block b = barrel.getRelative(dx, dy, dz);
                    if (b.getType() == Material.WATER && b.getRelative(BlockFace.UP).getType().isAir()) out.add(b);
                }
            }
        }
        return out;
    }

    static boolean isFishingBarrel(Block b) {
        return b.getType() == Material.BARREL && !waterNear(b).isEmpty();
    }

    /** Open water like vanilla (roughly): 5x5 of water two deep around it, nothing on top. Treasure only there. */
    static boolean open(Block water) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    if (!stillWater(water.getRelative(dx, dy, dz))) return false;
                }
                Material above = water.getRelative(dx, 1, dz).getType();
                if (!above.isAir() && above != Material.LILY_PAD) return false;
            }
        }
        return true;
    }

    /** Still water like vanilla counts it: a water source, also with seagrass or kelp in it, or a bubble column. */
    private static boolean stillWater(Block b) {
        switch (b.getType()) {
            case SEAGRASS, TALL_SEAGRASS, KELP, KELP_PLANT, BUBBLE_COLUMN -> {
                return true;
            }
            case WATER -> {
                return b.getBlockData() instanceof Levelled l && l.getLevel() == 0;
            }
            default -> {
                return b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged() && b.isPassable();
            }
        }
    }

    /** Open water anywhere the fishers of this barrel can cast (within 7 blocks of it, from the shore out). */
    static boolean anyOpen(Block barrel) {
        for (Block w : waterAround(barrel)) if (open(w)) return true;
        return false;
    }

    /** The water surface (water with air above) within 7 blocks of the barrel. */
    private static List<Block> waterAround(Block barrel) {
        List<Block> out = new ArrayList<>();
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                for (int dy = 1; dy >= -3; dy--) {
                    Block b = barrel.getRelative(dx, dy, dz);
                    if (b.getType() == Material.WATER && b.getRelative(BlockFace.UP).getType().isAir()) {
                        out.add(b);
                        break;
                    }
                }
            }
        }
        return out;
    }

    // ---- What it keeps ----

    static boolean keepJunk(Town.Workplace wp, Material m) {
        Boolean v = wp.options.get("JUNK_" + m.name());
        return v != null ? v : KEEP_BY_DEFAULT.contains(m);
    }

    private static boolean fishWanted(Town t, Material fish, int radius) {
        Integer target = t.targets.get(fish);
        return target == null || t.count(fish, radius) < target; // no target: keep all
    }

    // ---- The work ----

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        Block barrel = wp.loc.getBlock();
        if (!(barrel.getState(false) instanceof Barrel b)) return null;
        int radius = w.plugin.settings().townRadius();
        Inventory inv = b.getInventory();
        pile(barrel);

        // The catch is everything it carries except its rod (also the bows, boots and rods it fished up).
        Map<Material, Integer> haul = haul(w);
        int carried = 0;
        for (int n : haul.values()) carried += n;
        boolean anyWanted = false;
        for (Material f : FISH) if (fishWanted(t, f, radius)) anyWanted = true;
        boolean rod = w.count(Material.FISHING_ROD) > 0 || Work.inStorage(w, t, m -> m == Material.FISHING_ROD);
        // Emptied the barrel: on the way to the chests with it.
        if (w.carryToStorage) {
            if (carried > 0) {
                Activity d = Work.deposit(w, t, () -> haul(w), "Taking the catch to storage");
                if (d == null) w.workStatus = "No room in storage for the catch";
                return d;
            }
            w.carryToStorage = false;
        }
        // A few catches in the pockets (or done fishing): into the barrel.
        boolean unload = carried >= CARRY || (carried > 0 && (!anyWanted || !rod));
        if (unload && fitsInBarrel(inv, w)) {
            w.workStatus = "Putting the catch in the barrel";
            return new GoAndDo(w, barrel, 2.0, "Putting the catch in the barrel", Fishing::isBarrel, bl -> {
                if (!(bl.getState(false) instanceof Barrel br)) return;
                Inventory bi = br.getInventory();
                for (int i : catchSlots(w)) {
                    ItemStack it = w.pockets.getItem(i);
                    w.pockets.setItem(i, add(bi, it.clone()));
                }
                w.savePockets();
                t.countsChanged();
                pile(bl);
            });
        }
        // A full barrel (or catch that doesn't fit in it any more): everything goes to the chests.
        if (full(inv) || unload) {
            w.workStatus = "Taking the catch to storage";
            return new GoAndDo(w, barrel, 2.0, "Emptying the barrel", Fishing::isBarrel, bl -> {
                if (!(bl.getState(false) instanceof Barrel br)) return;
                Inventory bi = br.getInventory();
                for (int i = 0; i < bi.getSize(); i++) {
                    ItemStack it = bi.getItem(i);
                    if (it == null || it.getType().isAir()) continue;
                    Map<Integer, ItemStack> left = w.pockets.addItem(it.clone());
                    bi.setItem(i, left.isEmpty() ? null : left.values().iterator().next());
                }
                w.savePockets();
                t.countsChanged();
                pile(bl);
                w.carryToStorage = true;
            });
        }
        // Enough of every fish: nothing to do.
        if (!anyWanted) {
            w.workStatus = "Enough fish in storage";
            return null;
        }
        if (w.count(Material.FISHING_ROD) == 0) {
            Activity f = Work.fetch(w, t, m -> m == Material.FISHING_ROD, 1, "Getting a fishing rod");
            if (f != null) return f;
            w.workStatus = "Needs a fishing rod";
            w.warnMissingTool("a fishing rod");
            return null;
        }
        if (waterNear(barrel).isEmpty()) {
            w.workStatus = "No water next to the barrel";
            return null;
        }
        w.workStatus = "Fishing";
        return new Cast(w, t, wp, barrel, stand(w, barrel));
    }

    /** Catches it carries before taking them to the barrel. */
    private static final int CARRY = 4;

    // ---- Where it stands ----

    /** Where each fisher stands: its own spot on the shore, kept between casts, apart from the others. */
    private static final Map<UUID, Location> stands = new HashMap<>();

    /** Water to cast into from here (water with air above): 2.5 to 5 blocks out, closer only if that's all there is. */
    private static List<Block> castTargets(Location stand) {
        List<Block> far = new ArrayList<>(), near = new ArrayList<>();
        int sx = stand.getBlockX(), sy = stand.getBlockY(), sz = stand.getBlockZ();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 25 || d2 < 1) continue;
                for (int dy = 0; dy >= -3; dy--) {
                    Block b = stand.getWorld().getBlockAt(sx + dx, sy + dy, sz + dz);
                    if (b.getType() != Material.WATER || !b.getRelative(BlockFace.UP).getType().isAir()) continue;
                    (d2 >= 7 ? far : near).add(b);
                    break;
                }
            }
        }
        return far.isEmpty() ? near : far;
    }

    /** Water right next to this spot (so it stands at the edge). */
    private static boolean shore(org.bukkit.World world, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 0; dy++) {
                    Block b = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (b.getType() == Material.WATER && b.getRelative(BlockFace.UP).getType().isAir()) return true;
                }
            }
        }
        return false;
    }

    /** Another fisher stands (or will stand) within 3 blocks of this spot. Forgets spots of settlers that stopped fishing. */
    private static boolean crowded(Worker me, Location l) {
        for (Iterator<Map.Entry<UUID, Location>> it = stands.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Location> e = it.next();
            if (e.getKey().equals(me.body.getUniqueId())) continue;
            Worker o = me.plugin.workers().get(e.getKey());
            if (o == null || o.job() != Worker.Job.FISHER) {
                it.remove();
                continue;
            }
            Location s = e.getValue();
            if (s.getWorld() == l.getWorld() && s.distanceSquared(l) < 3 * 3) return true;
        }
        return false;
    }

    /**
     * Its spot: on the shore within 7 blocks of the barrel (not right against it), at least 3 blocks from the
     * other fishers, with water a few blocks out to cast into. Null: none (it stands by the barrel instead).
     */
    private static Location stand(Worker w, Block barrel) {
        UUID id = w.body.getUniqueId();
        org.bukkit.World world = barrel.getWorld();
        Location cur = stands.get(id);
        if (cur != null && cur.getWorld() == world && Math.abs(cur.getBlockX() - barrel.getX()) <= 7 && Math.abs(cur.getBlockZ() - barrel.getZ()) <= 7
                && !w.isSkipped(cur.getBlock()) && PathFinder.walkable(world, cur.getBlockX(), cur.getBlockY(), cur.getBlockZ())
                && !crowded(w, cur)) return cur;
        stands.remove(id);
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 2) continue; // not right against the barrel
                for (int dy = -2; dy <= 2; dy++) {
                    int x = barrel.getX() + dx, y = barrel.getY() + dy, z = barrel.getZ() + dz;
                    if (!shore(world, x, y, z) || !PathFinder.walkable(world, x, y, z)) continue;
                    Location l = new Location(world, x + 0.5, y, z + 0.5);
                    if (w.isSkipped(l.getBlock()) || crowded(w, l)) continue;
                    List<Block> targets = castTargets(l);
                    if (targets.isEmpty()) continue;
                    double out = Math.sqrt(l.distanceSquared(targets.get(0).getLocation().add(0.5, 0, 0.5)));
                    double score = Math.abs(Math.sqrt(dx * dx + dz * dz) - 3) + (out < 2.6 ? 3 : 0) + w.random.nextDouble();
                    if (score < bestScore) {
                        bestScore = score;
                        best = l;
                    }
                }
            }
        }
        if (best != null) stands.put(id, best);
        return best;
    }

    /** The real fishing look is possible (the PacketEvents plugin is on the server). Else the simple look. */
    static boolean realLook() {
        return Bukkit.getPluginManager().isPluginEnabled("packetevents");
    }

    private static boolean isBarrel(Block b) {
        return b.getType() == Material.BARREL;
    }

    /**
     * The pocket slots with catch: everything except the rod it fishes with (the first one it carries) and a
     * weapon borrowed for a fight. So a bow, boots or another rod it fished up count as catch too.
     */
    private static List<Integer> catchSlots(Worker w) {
        List<Integer> out = new ArrayList<>();
        boolean rod = false;
        for (int i = 0; i < w.pockets.getSize(); i++) {
            ItemStack it = w.pockets.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            if (it.getType() == Material.FISHING_ROD && !rod) {
                rod = true;
                continue;
            }
            if (w.borrowedWeapon != null && it.getType() == w.borrowedWeapon.getType()) continue;
            out.add(i);
        }
        return out;
    }

    /** The catch it carries: material → amount. */
    private static Map<Material, Integer> haul(Worker w) {
        Map<Material, Integer> out = new HashMap<>();
        for (int i : catchSlots(w)) {
            ItemStack it = w.pockets.getItem(i);
            out.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        return out;
    }

    /** Can any of the catch it carries go into the barrel's 3 slots? */
    private static boolean fitsInBarrel(Inventory inv, Worker w) {
        for (int i : catchSlots(w)) {
            ItemStack it = w.pockets.getItem(i);
            for (int s = 0; s < SLOTS; s++) {
                ItemStack cur = inv.getItem(s);
                if (cur == null || cur.getType().isAir() || (cur.isSimilar(it) && cur.getAmount() < cur.getMaxStackSize())) return true;
            }
        }
        return false;
    }

    /** Full: all 3 slots used, or a good load (so the fisher goes to the chests often). */
    private static boolean full(Inventory inv) {
        int used = 0, total = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            used++;
            total += it.getAmount();
        }
        return used >= SLOTS || total >= 16;
    }

    /** Add to the first 3 slots only; returns what's left (or null). */
    private static ItemStack add(Inventory inv, ItemStack it) {
        int left = it.getAmount();
        for (int i = 0; i < SLOTS && left > 0; i++) { // first onto a pile of the same
            ItemStack cur = inv.getItem(i);
            if (cur == null || cur.getType().isAir() || !cur.isSimilar(it) || cur.getAmount() >= cur.getMaxStackSize()) continue;
            int n = Math.min(left, cur.getMaxStackSize() - cur.getAmount());
            cur.setAmount(cur.getAmount() + n);
            inv.setItem(i, cur);
            left -= n;
        }
        for (int i = 0; i < SLOTS && left > 0; i++) { // then an empty slot
            ItemStack cur = inv.getItem(i);
            if (cur != null && !cur.getType().isAir()) continue;
            ItemStack put = it.clone();
            put.setAmount(left);
            inv.setItem(i, put);
            left = 0;
        }
        if (left <= 0) return null;
        ItemStack rest = it.clone();
        rest.setAmount(left);
        return rest;
    }

    /**
     * One cast: walk to its spot, throw: the bobber flies out and lands, a line (a lead) runs from the hand to
     * it; wait for a bite (the bobber bobs, then dips with a splash), reel in, keep the catch or throw it back.
     */
    private static final class Cast implements Activity {
        /** How high the line's end sits on the invisible bat that holds it. */
        private static final double LINE_Y = 0.45;
        private final Worker w;
        private final Town t;
        private final Town.Workplace wp;
        private final Block barrel;
        private Location stand;
        private Block water;
        private final List<BlockDisplay> bobber = new ArrayList<>();
        private org.bukkit.entity.Bat line;
        /** The real fishing look (with PacketEvents): a fake player with rod, line and bobber. */
        private FakeFisher fake;
        private int time, phase, biteAt, repath;

        Cast(Worker w, Town t, Town.Workplace wp, Block barrel, Location stand) {
            this.w = w;
            this.t = t;
            this.wp = wp;
            this.barrel = barrel;
            this.stand = stand;
        }

        /** Where the real bobber floats. */
        private Location floatAt() {
            return water.getLocation().add(0.5, 0.85, 0.5);
        }

        /** The middle of the bobber: half in the water. */
        private Location bobberAt() {
            return water.getLocation().add(0.5, 0.9, 0.5);
        }

        private Location hand() {
            Location l = w.body.getLocation();
            org.bukkit.util.Vector dir = l.getDirection().setY(0);
            if (dir.lengthSquared() < 1e-4) dir = new org.bukkit.util.Vector(0, 0, 1);
            return l.add(0, 1.4, 0).add(dir.normalize().multiply(0.6));
        }

        @Override
        public boolean tick() {
            time++;
            if (phase == 0) { // walk to its spot
                if (stand == null) stand = besideBarrel();
                if (stand == null) return true;
                if (w.body.getLocation().distanceSquared(stand) < 0.8 * 0.8 || (time > 2 && w.mover.arrived())) {
                    w.mover.stop();
                    List<Block> targets = castTargets(w.body.getLocation());
                    if (targets.isEmpty()) targets = waterNear(barrel);
                    if (targets.isEmpty()) return true;
                    List<Block> open = new ArrayList<>();
                    for (Block b : targets) if (open(b)) open.add(b);
                    if (!open.isEmpty()) targets = open; // casts into open water when it can reach it (treasure)
                    water = targets.get(w.random.nextInt(targets.size()));
                    phase = 1;
                    time = 0;
                } else if (--repath <= 0) {
                    repath = 20;
                    if (!w.mover.go(stand, 0.5, w.plugin.settings().workSpeed()) && time > 60) {
                        w.skip(stand.getBlock(), 2400); // can't get there: another spot next time
                        stands.remove(w.body.getUniqueId());
                        return true;
                    }
                }
                return time > 600;
            }
            if (fake != null) fake.tick();
            if (phase == 1 && realLook()) { // the real look: the fake player appears, then casts
                w.hold(Material.FISHING_ROD);
                w.anim.look(bobberAt());
                fake = FakeFisher.get(w);
                fake.look(bobberAt());
                phase = 5;
                time = 0;
                return false;
            }
            if (phase == 5) { // the fake appears once the skin had time to load, then casts
                if ((fake.ready() && time >= 3) || time >= 60) {
                    fake.look(bobberAt());
                    fake.cast(floatAt());
                    w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.fishing_bobber.throw", SoundCategory.NEUTRAL, 0.6f, 0.8f);
                    phase = 4;
                    time = 0;
                }
                return false;
            }
            if (phase == 1) { // cast: the bobber flies out
                w.hold(Material.FISHING_ROD);
                w.anim.look(bobberAt());
                w.body.swingMainHand();
                w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.fishing_bobber.throw", SoundCategory.NEUTRAL, 0.6f, 0.8f);
                Location from = hand();
                bobber.add(part(from, Material.RED_CONCRETE, 0f));
                bobber.add(part(from, Material.WHITE_CONCRETE, -0.08f));
                phase = 4;
                time = 0;
                return false;
            }
            if (phase == 4 && fake != null) { // flying (the game moves the real bobber)
                if (time >= 16) {
                    fake.land();
                    water.getWorld().spawnParticle(Particle.SPLASH, bobberAt(), 5, 0.1, 0.02, 0.1);
                    water.getWorld().playSound(bobberAt(), "minecraft:entity.fishing_bobber.splash", SoundCategory.NEUTRAL, 0.25f, 1.4f);
                    int wait = 100 + w.random.nextInt(500); // 5 to 30 seconds, like vanilla
                    if (water.getWorld().hasStorm()) wait = wait * 3 / 4;
                    biteAt = wait;
                    phase = 2;
                    time = 0;
                }
                return false;
            }
            if (phase == 4) { // flying
                if (time == 2) for (BlockDisplay d : bobber) d.teleport(bobberAt());
                if (time >= 12) { // landed: the line runs from the hand to the bobber
                    water.getWorld().spawnParticle(Particle.SPLASH, bobberAt(), 5, 0.1, 0.02, 0.1);
                    water.getWorld().playSound(bobberAt(), "minecraft:entity.fishing_bobber.splash", SoundCategory.NEUTRAL, 0.25f, 1.4f);
                    line = water.getWorld().spawn(bobberAt().add(0, 0.08 - LINE_Y, 0), org.bukkit.entity.Bat.class, b -> {
                        b.setAI(false);
                        b.setAwake(true);
                        b.setSilent(true);
                        b.setInvisible(true);
                        b.setInvulnerable(true);
                        b.setGravity(false);
                        b.setCollidable(false);
                        b.setPersistent(false);
                        Keys.markTemp(b);
                    });
                    line.setLeashHolder(w.body);
                    int wait = 100 + w.random.nextInt(500); // 5 to 30 seconds, like vanilla
                    if (water.getWorld().hasStorm()) wait = wait * 3 / 4;
                    biteAt = wait;
                    phase = 2;
                    time = 0;
                }
                return false;
            }
            if (phase == 2) { // waiting
                if (time % 10 == 0) w.anim.look(bobberAt());
                if (time % 20 == 0) for (BlockDisplay d : bobber) d.teleport(bobberAt().add(0, (time / 20) % 2 == 0 ? 0.03 : -0.03, 0));
                if (time >= biteAt) {
                    water.getWorld().spawnParticle(Particle.SPLASH, bobberAt(), 12, 0.15, 0.05, 0.15);
                    water.getWorld().spawnParticle(Particle.BUBBLE, bobberAt().add(0, -0.2, 0), 6, 0.1, 0.05, 0.1);
                    water.getWorld().playSound(bobberAt(), "minecraft:entity.fishing_bobber.splash", SoundCategory.NEUTRAL, 0.5f, 1f);
                    for (BlockDisplay d : bobber) d.teleport(bobberAt().add(0, -0.2, 0));
                    if (fake != null) fake.bite();
                    phase = 3;
                    time = 0;
                }
                return false;
            }
            if (phase == 3 && time >= 8) { // reel in
                w.body.swingMainHand();
                if (fake != null) fake.reel();
                w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.fishing_bobber.retrieve", SoundCategory.NEUTRAL, 0.6f, 1f);
                removeBobber();
                ItemStack caught = roll(w, open(water));
                Work.wearTool(w, "FISHING_ROD");
                int radius = w.plugin.settings().townRadius();
                Material m = caught.getType();
                boolean keep;
                if (FISH.contains(m)) keep = fishWanted(t, m, radius);
                else if (JUNK.contains(m)) keep = keepJunk(wp, m) && t.wants(m, radius); // kept junk, up to its storage target
                else keep = true; // treasure
                if (keep) {
                    w.give(List.of(caught), w.body.getLocation());
                    w.savePockets();
                    w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.item.pickup", SoundCategory.NEUTRAL, 0.4f, 1.4f);
                } else {
                    water.getWorld().spawnParticle(Particle.SPLASH, bobberAt(), 8, 0.1, 0.05, 0.1); // back into the water
                    water.getWorld().playSound(bobberAt(), "minecraft:entity.generic.splash", SoundCategory.NEUTRAL, 0.3f, 1.5f);
                }
                return true;
            }
            return false;
        }

        /** Half of the bobber (red top, white bottom), flying smoothly when moved. */
        private BlockDisplay part(Location at, Material m, float y) {
            return at.getWorld().spawn(at, BlockDisplay.class, d -> {
                d.setBlock(m.createBlockData());
                d.setTransformation(new Transformation(new Vector3f(-0.07f, y, -0.07f), new AxisAngle4f(),
                        new Vector3f(0.14f, 0.08f, 0.14f), new AxisAngle4f()));
                d.setTeleportDuration(10);
                d.setPersistent(false);
                Keys.markTemp(d);
            });
        }

        /** No spot on the shore: right next to the barrel, closest to the water. */
        private Location besideBarrel() {
            List<Block> near = waterNear(barrel);
            if (near.isEmpty()) return null;
            Location wat = near.get(0).getLocation().add(0.5, 0.5, 0.5);
            Location best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        int x = barrel.getX() + dx, y = barrel.getY() + dy, z = barrel.getZ() + dz;
                        if (!PathFinder.walkable(barrel.getWorld(), x, y, z)) continue;
                        Location l = new Location(barrel.getWorld(), x + 0.5, y, z + 0.5);
                        double d = l.distanceSquared(wat);
                        if (d < bestD) {
                            bestD = d;
                            best = l;
                        }
                    }
                }
            }
            return best;
        }

        private void removeBobber() {
            for (BlockDisplay d : bobber) d.remove();
            bobber.clear();
            if (line != null) line.remove();
            line = null;
        }

        @Override
        public void stop() {
            removeBobber();
            if (fake != null) fake.release(); // stays a moment for the next cast, unless it walks off
            fake = null;
            w.mover.stop();
            w.holdTool();
        }

        @Override
        public String label() {
            return "Fishing";
        }
    }

    /** The line holder (an invisible bat on a lead): never drops a lead, can't be untied. */
    @EventHandler
    public void onUnleash(org.bukkit.event.entity.EntityUnleashEvent e) {
        if (Keys.isTemp(e.getEntity())) e.setDropLeash(false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTouchLine(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof org.bukkit.entity.Bat && Keys.isTemp(e.getRightClicked())) e.setCancelled(true);
    }

    /** What bites, with vanilla's chances (Luck of the Sea helps; treasure only in open water). */
    static ItemStack roll(Worker w, boolean openWater) {
        Random r = w.random;
        int luck = 0;
        for (ItemStack it : w.pockets.getContents()) {
            if (it != null && it.getType() == Material.FISHING_ROD) {
                luck = it.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA);
                break;
            }
        }
        double treasure = openWater ? 0.05 + 0.021 * luck : 0, junk = Math.max(0, 0.10 - 0.019 * luck);
        double x = r.nextDouble();
        if (x < treasure) {
            switch (r.nextInt(6)) {
                case 0 -> {
                    return enchanted(new ItemStack(Material.BOW), r);
                }
                case 1 -> {
                    return enchanted(new ItemStack(Material.BOOK), r);
                }
                case 2 -> {
                    return enchanted(new ItemStack(Material.FISHING_ROD), r);
                }
                case 3 -> {
                    return new ItemStack(Material.NAME_TAG);
                }
                case 4 -> {
                    return new ItemStack(Material.NAUTILUS_SHELL);
                }
                default -> {
                    return new ItemStack(Material.SADDLE);
                }
            }
        }
        if (x < treasure + junk) {
            int[] weights = {17, 10, 10, 10, 10, 5, 5, 10, 10, 1, 10, 2};
            int sum = 0;
            for (int wgt : weights) sum += wgt;
            int pick = r.nextInt(sum);
            for (int i = 0; i < weights.length; i++) {
                pick -= weights[i];
                if (pick < 0) {
                    Material m = JUNK.get(i);
                    ItemStack it = new ItemStack(m, m == Material.INK_SAC ? 10 : 1);
                    if (m == Material.POTION && it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                        pm.setBasePotionType(org.bukkit.potion.PotionType.WATER);
                        it.setItemMeta(pm);
                    }
                    if ((m == Material.LEATHER_BOOTS || m == Material.FISHING_ROD) && it.getItemMeta() instanceof Damageable dm) {
                        dm.setDamage((int) (m.getMaxDurability() * (0.1 + r.nextDouble() * 0.8)));
                        it.setItemMeta(dm);
                    }
                    return it;
                }
            }
        }
        int f = r.nextInt(100);
        Material fish = f < 60 ? Material.COD : f < 85 ? Material.SALMON : f < 87 ? Material.TROPICAL_FISH : Material.PUFFERFISH;
        return new ItemStack(fish);
    }

    private static ItemStack enchanted(ItemStack it, Random r) {
        try {
            return Bukkit.getItemFactory().enchantWithLevels(it, 30, true, r);
        } catch (RuntimeException e) {
            return it.getType() == Material.BOOK ? new ItemStack(Material.ENCHANTED_BOOK) : it;
        }
    }

    // ---- The pile of fish on the lid ----

    private static final Map<Block, String> shown = new HashMap<>();

    /** Up to 3 items lying on the barrel (one per used slot), only redrawn when it changes. */
    static void pile(Block barrel) {
        if (!(barrel.getState(false) instanceof Barrel b)) {
            clearPile(barrel);
            return;
        }
        Inventory inv = b.getInventory();
        List<Material> items = new ArrayList<>();
        for (int i = 0; i < inv.getSize() && items.size() < 3; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) items.add(it.getType());
        }
        String sig = items.toString();
        if (sig.equals(shown.get(barrel))) return;
        clearPile(barrel);
        shown.put(barrel, sig);
        float[][] spots = {{0.32f, 0.3f, 0.6f}, {0.62f, 0.45f, 2.2f}, {0.45f, 0.68f, 4.0f}};
        for (int i = 0; i < items.size(); i++) {
            float[] s = spots[i];
            Material m = items.get(i);
            float yaw = s[2];
            barrel.getWorld().spawn(barrel.getLocation().add(s[0], 1.02 + i * 0.015, s[1]), ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(m));
                // lying flat on the lid, each turned a bit
                AxisAngle4f flat = new AxisAngle4f((float) (Math.PI / 2), 1, 0, 0);
                AxisAngle4f turn = new AxisAngle4f(yaw, 0, 1, 0);
                d.setTransformation(new Transformation(new Vector3f(), turn, new Vector3f(0.45f, 0.45f, 0.45f), flat));
                d.setPersistent(true);
                d.getPersistentDataContainer().set(PILE, PersistentDataType.BYTE, (byte) 1);
            });
        }
    }

    static void clearPile(Block barrel) {
        shown.remove(barrel);
        for (Entity e : barrel.getWorld().getNearbyEntities(barrel.getLocation().add(0.5, 1.1, 0.5), 0.7, 0.4, 0.7)) {
            if (e instanceof ItemDisplay && e.getPersistentDataContainer().has(PILE)) e.remove();
        }
    }

    // ---- Junk filter ----

    static void junkMenu(SettlementPlugin plugin, Player p, Town t, Town.Workplace wp) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Material m : JUNK) {
            boolean on = keepJunk(wp, m);
            String name = m == Material.POTION ? "Water bottle" : TargetsMenu.nice(m);
            Component label = TargetsMenu.icon(m).append(Component.text(" " + name, on ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            buttons.add(ActionButton.create(label, Component.text(on ? "Kept: click to throw back" : "Thrown back: click to keep"), 150,
                    Menus.click(plugin, (view, audience) -> {
                        if (!(audience instanceof Player pl)) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            wp.options.put("JUNK_" + m.name(), !keepJunk(wp, m));
                            plugin.towns().save();
                            junkMenu(plugin, pl, t, wp);
                        });
                    }, Menus.ONCE)));
        }
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> Menus.showWorkplace(plugin, pl, t, wp));
        Menus.show(p, "Junk filter", List.of(Menus.line("Green: kept. Grey: thrown back into the water. Treasure is always kept.", NamedTextColor.GRAY)),
                List.of(), DialogType.multiAction(buttons).columns(3).exitAction(back).build());
    }

    // ---- Protection ----

    /** The fisher's barrel is only for the fisher (and the settlers taking the catch). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !(e.getInventory().getHolder(false) instanceof Barrel b)) return;
        Town t = plugin.towns().at(b.getLocation());
        if (t == null || !t.fishersBarrel(b.getBlock())) return; // no fisher works here: a normal barrel
        if (b.getBlock().equals(viewing.get(p.getUniqueId()))) return; // opened from the workstation menu: take only
        e.setCancelled(true);
        p.sendActionBar(Component.text("This is the fisher's barrel. Open it from its workstation menu.", NamedTextColor.YELLOW));
    }

    /** Players looking into a fisher's barrel from its workstation menu: they may only take things out. */
    private static final Map<UUID, Block> viewing = new HashMap<>();

    /** "Barrel contents" in the workstation menu (founder and members): take the catch out, put nothing in. */
    static void openBarrel(SettlementPlugin plugin, Player p, Town t, Town.Workplace wp) {
        Block b = wp.loc.getBlock();
        if (!(b.getState(false) instanceof Barrel br) || !t.canManage(p)) return;
        viewing.put(p.getUniqueId(), b);
        Menus.markShown(p); // the barrel replaces the menu: don't close it after the click
        p.openInventory(br.getInventory());
        if (!(p.getOpenInventory().getTopInventory().getHolder(false) instanceof Barrel)) viewing.remove(p.getUniqueId());
    }

    private static boolean takeOnly(org.bukkit.inventory.InventoryView view, org.bukkit.entity.HumanEntity who) {
        Block b = viewing.get(who.getUniqueId());
        return b != null && view.getTopInventory().getHolder(false) instanceof Barrel br && br.getBlock().equals(b);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBarrelClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!takeOnly(e.getView(), e.getWhoClicked())) return;
        boolean top = e.getRawSlot() >= 0 && e.getRawSlot() < e.getView().getTopInventory().getSize();
        switch (e.getAction()) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, DROP_ALL_SLOT, DROP_ONE_SLOT, DROP_ALL_CURSOR, DROP_ONE_CURSOR,
                 COLLECT_TO_CURSOR, NOTHING, CLONE_STACK -> {
                return; // taking out, or nothing goes in
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                if (top) return; // shift-click out of the barrel
            }
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                if (!top) return; // in their own inventory
            }
            default -> {
                if (!top) return; // number keys and such in their own inventory
            }
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBarrelDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (!takeOnly(e.getView(), e.getWhoClicked())) return;
        int size = e.getView().getTopInventory().getSize();
        for (int slot : e.getRawSlots()) {
            if (slot < size) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** A barrel with a pile on its lid that a player looked into: the pile follows what's in it (gone without a fisher). */
    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Barrel b)) return;
        if (b.getBlock().equals(viewing.get(e.getPlayer().getUniqueId()))) viewing.remove(e.getPlayer().getUniqueId());
        Town t = plugin.towns().at(b.getLocation());
        if (t != null) t.countsChanged(); // a player may have taken things out
        if (!shown.containsKey(b.getBlock())) return;
        if (t != null && t.fishersBarrel(b.getBlock())) pile(b.getBlock());
        else clearPile(b.getBlock());
    }

    /** Broken: its contents drop (vanilla), the pile goes. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() == Material.BARREL) clearPile(e.getBlock());
    }
}
