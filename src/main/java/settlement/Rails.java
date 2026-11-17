package settlement;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Rails you build in the mine (stonecutter: Use rails). The miner finds a rail line from a level (near its hub or
 * landing) up to the surface and rides it in a minecart (it carries one): nothing can hurt it on the way, and
 * monsters on the track are run over. A stop is a powered rail with a button or lever next to it (the miner
 * presses it, like you would).
 * With a chest minecart standing at the level's stop and a hopper under the stop up top, it sends its ores up in
 * the cart instead of carrying them (you build what sends the empty cart back down). A cart that doesn't come back:
 * you get a request.
 */
final class Rails {
    private Rails() {}

    static boolean on(Town.Workplace wp) {
        return wp.options.getOrDefault("RAILS", false);
    }

    record Route(List<Block> rails, Block low, Block high) {}

    private static final Map<String, Route> routes = new HashMap<>();
    private static final Map<String, Integer> routeAt = new HashMap<>();
    private static final Map<String, Integer> brokenUntil = new HashMap<>();
    private static final Map<String, Integer> sentAt = new HashMap<>();

    static void cleanup() {
        int now = Bukkit.getCurrentTick();
        brokenUntil.values().removeIf(u -> u < now);
        routeAt.entrySet().removeIf(e -> {
            if (now - e.getValue() < 1200) return false;
            routes.remove(e.getKey());
            return true;
        });
        sentAt.values().removeIf(s -> now - s > 24000);
    }

    /** The rail line from level k up to the surface (looked up every minute), or null. */
    static Route route(Mine m, int k) {
        String key = m.key + ":" + k;
        int now = Bukkit.getCurrentTick();
        Integer b = brokenUntil.get(key);
        if (b != null && now < b) return null;
        Integer at = routeAt.get(key);
        if (at != null && now - at < 1200) return routes.get(key);
        routeAt.put(key, now);
        Route r = find(m, k);
        if (r == null) routes.remove(key);
        else routes.put(key, r);
        return r;
    }

    private static Route find(Mine m, int k) {
        World w = m.world;
        int y = m.landingY(k);
        List<Location> starts = new ArrayList<>();
        starts.add(m.landing(k));
        if (m.levels.containsKey(k) && m.levels.get(k).hub) starts.add(m.hub(k));
        for (Location c : starts) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        Block b = w.getBlockAt(c.getBlockX() + dx, y + dy, c.getBlockZ() + dz);
                        if (!Tag.RAILS.isTagged(b.getType())) continue;
                        List<Block> line = follow(b);
                        if (line.size() < 4) continue;
                        Block a = line.get(0), z = line.get(line.size() - 1);
                        Block high = a.getY() >= z.getY() ? a : z, low = high == a ? z : a;
                        if (high.getY() < m.surface - 3) continue; // doesn't reach the top
                        if (high != line.get(line.size() - 1)) Collections.reverse(line); // low → high
                        return new Route(line, low, high);
                    }
                }
            }
        }
        return null;
    }

    /** The whole rail line through a rail, from one end to the other. */
    static List<Block> follow(Block start) {
        Deque<Block> line = new ArrayDeque<>();
        line.add(start);
        Set<Block> seen = new HashSet<>();
        seen.add(start);
        for (int side = 0; side < 2; side++) {
            Block cur = start;
            while (line.size() < 4000) {
                Block next = null;
                for (Block n : neighbours(cur)) {
                    if (seen.contains(n)) continue;
                    next = n;
                    break;
                }
                if (next == null) break;
                seen.add(next);
                if (side == 0) line.addLast(next);
                else line.addFirst(next);
                cur = next;
                if (side == 0 && cur == start) break;
            }
            if (side == 0 && line.size() == 1) continue;
        }
        return new ArrayList<>(line);
    }

    private static List<Block> neighbours(Block b) {
        List<Block> out = new ArrayList<>();
        if (!(b.getBlockData() instanceof Rail r)) return out;
        int[][] d = switch (r.getShape()) {
            case NORTH_SOUTH -> new int[][]{{0, 0, -1}, {0, 0, 1}};
            case EAST_WEST -> new int[][]{{1, 0, 0}, {-1, 0, 0}};
            case ASCENDING_EAST -> new int[][]{{1, 1, 0}, {-1, 0, 0}};
            case ASCENDING_WEST -> new int[][]{{-1, 1, 0}, {1, 0, 0}};
            case ASCENDING_NORTH -> new int[][]{{0, 1, -1}, {0, 0, 1}};
            case ASCENDING_SOUTH -> new int[][]{{0, 1, 1}, {0, 0, -1}};
            case SOUTH_EAST -> new int[][]{{0, 0, 1}, {1, 0, 0}};
            case SOUTH_WEST -> new int[][]{{0, 0, 1}, {-1, 0, 0}};
            case NORTH_WEST -> new int[][]{{0, 0, -1}, {-1, 0, 0}};
            case NORTH_EAST -> new int[][]{{0, 0, -1}, {1, 0, 0}};
        };
        for (int[] o : d) {
            Block n = b.getRelative(o[0], o[1], o[2]);
            if (Tag.RAILS.isTagged(n.getType())) out.add(n);
            else if (Tag.RAILS.isTagged(n.getRelative(BlockFace.DOWN).getType())) out.add(n.getRelative(BlockFace.DOWN)); // the next one goes down
        }
        return out;
    }

    /** Is another minecart on the line (not this one)? */
    static boolean clear(Route r, Entity except) {
        return clear(r, except, false);
    }

    /** parked: a chest minecart standing at the level's stop doesn't count (it waits there for ore). */
    static boolean clear(Route r, Entity except, boolean parked) {
        Set<Block> set = new HashSet<>(r.rails());
        World w = r.low().getWorld();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Block b : r.rails()) {
            minX = Math.min(minX, b.getX());
            minY = Math.min(minY, b.getY());
            minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX());
            maxY = Math.max(maxY, b.getY());
            maxZ = Math.max(maxZ, b.getZ());
        }
        for (Entity e : w.getNearbyEntities(new org.bukkit.util.BoundingBox(minX, minY, minZ, maxX + 1, maxY + 2, maxZ + 1), e -> e instanceof Minecart)) {
            if (e.equals(except)) continue;
            if (parked && e instanceof StorageMinecart && e.getLocation().distanceSquared(r.low().getLocation().add(0.5, 0, 0.5)) < 2.5 * 2.5) continue;
            if (set.contains(e.getLocation().getBlock())) return false;
        }
        return true;
    }

    /** A button or lever next to a stop: press it (a button pops back, a lever goes back after a moment). */
    static boolean press(Block rail) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    Block b = rail.getRelative(dx, dy, dz);
                    if (!(b.getBlockData() instanceof Switch sw) || sw.isPowered()) continue;
                    set(b, true);
                    b.getWorld().playSound(b.getLocation(), b.getType() == Material.LEVER ? "minecraft:block.lever.click" : "minecraft:block.stone_button.click_on",
                            SoundCategory.BLOCKS, 0.6f, 1f);
                    Bukkit.getScheduler().runTaskLater(owner, () -> {
                        if (b.getBlockData() instanceof Switch) set(b, false);
                    }, b.getType() == Material.LEVER ? 40L : 20L);
                    return true;
                }
            }
        }
        return false;
    }

    private static void set(Block b, boolean powered) {
        BlockData d = b.getBlockData();
        if (!(d instanceof Switch sw)) return;
        sw.setPowered(powered);
        b.setBlockData(sw, true);
        // The block it hangs on passes the power on: update around it too.
        BlockFace att = switch (sw.getAttachedFace()) {
            case FLOOR -> BlockFace.DOWN;
            case CEILING -> BlockFace.UP;
            default -> sw.getFacing().getOppositeFace();
        };
        Block support = b.getRelative(att);
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = support.getRelative(f);
            if (n.equals(b)) continue;
            n.getState().update(true, true);
        }
    }

    static org.bukkit.plugin.Plugin owner;

    // ---- Riding ----

    /** Down by minecart from the top end to level k (rails on, a route, a minecart in the pockets). */
    static Activity down(Worker w, Town t, Town.Workplace wp, Mine m, int k) {
        if (m.floors() || !on(wp) || w.count(Material.MINECART) == 0) return null;
        Route r = route(m, k);
        return r == null ? null : new Ride(w, m, k, r, r.high(), r.low());
    }

    static Activity up(Worker w, Town t, Town.Workplace wp, Mine m, int k, Location here) {
        if (m.floors() || !on(wp) || k < 0 || w.count(Material.MINECART) == 0) return null;
        Route r = route(m, k);
        if (r == null || r.low().getLocation().distanceSquared(here) > 40 * 40) return null;
        return new Ride(w, m, k, r, r.low(), r.high());
    }

    static final class Ride implements Activity {
        private final Worker w;
        private final Mine m;
        private final int k;
        private final Route r;
        private final Block from, to;
        private int phase, time, still, wait;
        private Minecart cart;
        private boolean wasInvulnerable;

        Ride(Worker w, Mine m, int k, Route r, Block from, Block to) {
            this.w = w;
            this.m = m;
            this.k = k;
            this.r = r;
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean tick() {
            time++;
            Location stop = from.getLocation().add(0.5, 0, 0.5);
            switch (phase) {
                case 0 -> {
                    if (!w.mover.go(stop, 1.5, w.plugin.settings().workSpeed())) return fail();
                    phase = 1;
                }
                case 1 -> {
                    if (w.mover.moving()) return time > 1200 && fail();
                    if (w.body.getLocation().distanceSquared(stop) > 3 * 3) return fail();
                    if (!clear(r, null, true)) {
                        w.workStatus = "Waiting for the rails to clear";
                        return ++wait > 600 && fail();
                    }
                    if (!w.take(Material.MINECART, 1)) return true;
                    cart = from.getWorld().spawn(stop.clone().add(0, 0.1, 0), RideableMinecart.class);
                    cart.addPassenger(w.body);
                    wasInvulnerable = w.body.isInvulnerable();
                    w.body.setInvulnerable(true);
                    if (!press(from)) push();
                    phase = 2;
                }
                case 2 -> {
                    if (cart == null || !cart.isValid()) return fail();
                    if (!cart.getPassengers().contains(w.body)) cart.addPassenger(w.body);
                    runOver();
                    Location c = cart.getLocation();
                    double v = cart.getVelocity().length();
                    if (c.distanceSquared(to.getLocation().add(0.5, 0, 0.5)) < 1.6 * 1.6 && v < 0.05) {
                        if (++still > 10) {
                            off();
                            return true;
                        }
                    } else if (v < 0.01) {
                        if (++still > 100) {
                            if (still == 101) push();
                            if (still > 200) return fail();
                        }
                    } else {
                        still = 0;
                    }
                    if (time > 6000) return fail();
                }
                default -> {
                    return true;
                }
            }
            return false;
        }

        private void push() {
            if (cart == null || r.rails().size() < 2) return;
            int i = r.rails().indexOf(from);
            Block next = i == 0 ? r.rails().get(1) : r.rails().get(Math.max(0, i - 1));
            Vector dir = next.getLocation().toVector().subtract(from.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() > 0) cart.setVelocity(dir.normalize().multiply(0.6));
        }

        /** Monsters on the track ahead: run over. */
        private void runOver() {
            Vector vel = cart.getVelocity();
            for (Entity e : cart.getNearbyEntities(1.6, 1.2, 1.6)) {
                if (!Combat.hostile(e)) continue;
                Vector to = e.getLocation().toVector().subtract(cart.getLocation().toVector());
                if (vel.lengthSquared() > 0.0001 && to.dot(vel) < 0) continue;
                ((LivingEntity) e).damage(1000, w.body);
            }
        }

        private void off() {
            if (cart == null) return; // never got in
            Location at = cart.getLocation();
            boolean whole = cart.isValid(); // (broken by someone: it dropped its own minecart item)
            cart.removePassenger(w.body);
            cart.remove();
            cart = null;
            if (whole) w.give(List.of(new ItemStack(Material.MINECART)), w.body.getLocation());
            w.body.setInvulnerable(wasInvulnerable);
            Location step = PathFinderSafe.near(at); // off the rails, where the cart stopped
            if (step != null) w.body.teleport(step);
        }

        private boolean fail() {
            off();
            brokenUntil.put(m.key + ":" + k, Bukkit.getCurrentTick() + 6000); // walk for now
            return true;
        }

        @Override
        public void stop() {
            if (cart != null) off();
        }

        @Override
        public String label() {
            return phase == 2 ? "Riding the minecart" : "Going to the minecart";
        }
    }

    /** A free spot to stand next to a rail. */
    static final class PathFinderSafe {
        static Location near(Location l) {
            World w = l.getWorld();
            for (int[] d : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
                for (int dy : new int[]{0, 1, -1}) {
                    int x = l.getBlockX() + d[0], y = l.getBlockY() + dy, z = l.getBlockZ() + d[1];
                    if (PathFinder.walkable(w, x, y, z) && !Tag.RAILS.isTagged(w.getBlockAt(x, y, z).getType())) return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
            return null;
        }
    }

    // ---- Sending ores up in a chest minecart ----

    /** A hopper under (or next to) the top stop: it empties the cart by itself. */
    static boolean hopperAtTop(Route r) {
        Block h = r.high();
        return h.getRelative(BlockFace.DOWN).getType() == Material.HOPPER || h.getRelative(0, -2, 0).getType() == Material.HOPPER;
    }

    /** Pockets heavy and a chest cart at the level's stop: load it and send it up. True: busy with it. */
    static boolean trySendCart(Worker w, Town t, Town.Workplace wp, Mine m, int k) {
        if (m.floors() || !on(wp) || k < 0) return false;
        int load = 0;
        for (ItemStack it : w.pockets.getContents()) if (it != null && cargo(it.getType())) load++;
        if (load < 4) return false;
        Route r = route(m, k);
        if (r == null || !hopperAtTop(r)) return false;
        String key = m.key + ":" + k;
        StorageMinecart cart = null;
        for (Entity e : r.low().getWorld().getNearbyEntities(r.low().getLocation().add(0.5, 0.5, 0.5), 1.5, 1.5, 1.5)) {
            if (e instanceof StorageMinecart sm) cart = sm;
        }
        int now = Bukkit.getCurrentTick();
        if (cart == null) {
            Integer sent = sentAt.get(key);
            if (sent != null && now - sent > 6000) {
                sentAt.remove(key);
                Asks.add(t, Asks.Kind.CART, "The ore cart I sent up hasn't come back down. Is it stuck, or not sent back?", r.high().getLocation(), w, "cart");
            }
            return false;
        }
        sentAt.remove(key);
        Location stop = r.low().getLocation().add(0.5, 0, 0.5);
        if (w.mover.moving()) return true;
        if (w.body.getLocation().distanceSquared(stop) > 3 * 3) {
            if (!w.mover.go(stop, 2.0, w.plugin.settings().workSpeed())) {
                brokenUntil.put(key, now + 6000); // can't get to the stop: carry it up for now
                return false;
            }
            return true;
        }
        if (!clear(r, cart)) return false;
        Inventory inv = cart.getInventory();
        for (int i = 0; i < w.pockets.getSize(); i++) {
            ItemStack it = w.pockets.getItem(i);
            if (it == null || !cargo(it.getType())) continue;
            Map<Integer, ItemStack> left = inv.addItem(it);
            w.pockets.setItem(i, left.isEmpty() ? null : left.values().iterator().next());
        }
        w.body.swingMainHand();
        if (!press(r.low())) {
            Block next = r.rails().size() > 1 ? r.rails().get(1) : r.high();
            Vector dir = next.getLocation().toVector().subtract(r.low().getLocation().toVector()).setY(0);
            if (dir.lengthSquared() > 0) cart.setVelocity(dir.normalize().multiply(0.6));
        }
        sentAt.put(key, now);
        Bubbles.say(w.plugin, w.body, "Up you go!", 40);
        TownLog.settler(w, "sent a cart of ore up", NamedTextColor.GRAY);
        return true;
    }

    /** What goes up in the cart: what it mined (not its tools, food, torches, logs, cobblestone to seal with). */
    private static boolean cargo(Material m) {
        return !Miner.isPick(m) && Combat.foodValue(m) <= 0 && m != Material.TORCH && !Tag.LOGS.isTagged(m) && !Miner.FILLER.contains(m)
                && m != Material.MINECART && !Work.gear(m);
    }
}
