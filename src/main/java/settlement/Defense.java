package settlement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Settlement defense: finds hostile mobs inside the settlement, sounds the alarm (bell + message),
 * points mobs at settlers, and makes workers take cover or grab a weapon (call to arms).
 */
final class Defense {
    private Defense() {}

    private static final int MESSAGE_COOLDOWN = 20 * 60;
    private static final Map<String, Integer> lastMessage = new HashMap<>();
    private static final Map<String, Boolean> messaged = new HashMap<>();

    /** Marks the activities that are a reaction to an attack. */
    interface Reaction extends Activity {}

    /** Every second, for every settlement. */
    static void tick(SettlementPlugin plugin) {
        int r = plugin.settings().townRadius();
        int now = Bukkit.getCurrentTick();
        for (Town t : plugin.towns().all()) {
            t.hostiles.clear();
            World world = t.world();
            if (world == null || !world.isChunkLoaded(t.bell.getBlockX() >> 4, t.bell.getBlockZ() >> 4)) continue;
            int[] box = t.box(0);
            Location c = new Location(world, (box[0] + box[1] + 1) / 2.0, t.bell.getY() + 0.5, (box[2] + box[3] + 1) / 2.0);
            t.unreachable.values().removeIf(until -> now > until);
            java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
            for (LivingEntity e : world.getNearbyLivingEntities(c, (box[1] - box[0]) / 2.0 + 1, 32, (box[3] - box[2]) / 2.0 + 1)) {
                if (!Combat.hostile(e) || !t.contains(e.getLocation(), r) || !aboveGround(e)) continue;
                seen.add(e.getUniqueId());
                if (harmless(plugin, t, e, now)) continue; // can't get to anyone: no reason for alarm
                t.hostiles.add(e);
            }
            t.stuckSpot.keySet().retainAll(seen);
            t.stuckSince.keySet().retainAll(seen);
            for (LivingEntity h : t.hostiles) {
                if (h instanceof Mob m && m.getTarget() == null) {
                    Worker near = nearestSettler(plugin, t, h.getLocation(), 16);
                    if (near != null) m.setTarget(near.body);
                }
            }
            if (!t.hostiles.isEmpty()) {
                t.quietTicks = 0;
                if (!t.alarm) {
                    t.alarm = true;
                    ring(t);
                    if (now - lastMessage.getOrDefault(t.id, -MESSAGE_COOLDOWN) >= MESSAGE_COOLDOWN) {
                        lastMessage.put(t.id, now);
                        messaged.put(t.id, true);
                        tell(t, "⚔ " + t.name + " is under attack!", NamedTextColor.RED);
                    }
                }
            } else if (t.alarm && (t.quietTicks += 20) >= 100) {
                t.alarm = false;
                if (Boolean.TRUE.equals(messaged.remove(t.id))) tell(t, "✔ " + t.name + ": all clear.", NamedTextColor.GREEN);
            }
        }
    }

    /**
     * A monster that can't get to anyone: a guard couldn't reach it, or it has hardly moved for a minute
     * without coming near a settler (stuck in a pit, in water, behind a fence).
     */
    private static boolean harmless(SettlementPlugin plugin, Town t, LivingEntity e, int now) {
        Worker near = nearestSettler(plugin, t, e.getLocation(), 6);
        if (near != null) return false; // close to someone: a real threat
        if (t.unreachable.containsKey(e.getUniqueId())) return true;
        Location at = e.getLocation();
        Location first = t.stuckSpot.get(e.getUniqueId());
        if (first == null || first.getWorld() != at.getWorld() || first.distanceSquared(at) > 4 * 4) {
            t.stuckSpot.put(e.getUniqueId(), at);
            t.stuckSince.put(e.getUniqueId(), now);
            return false;
        }
        return now - t.stuckSince.getOrDefault(e.getUniqueId(), now) > 1200;
    }

    /** Monsters in caves under the settlement don't count: only ones on the surface or in buildings. */
    private static boolean aboveGround(LivingEntity e) {
        Location l = e.getLocation();
        if (l.getBlock().getLightFromSky() > 0) return true;
        int surface = l.getWorld().getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return l.getBlockY() >= surface - 4;
    }

    private static void ring(Town t) {
        if (!Notify.anyOn(t, Notify.Kind.BELL)) return;
        if (t.bell.getBlock().getState() instanceof org.bukkit.block.Bell bell) bell.ring();
    }

    private static void tell(Town t, String text, NamedTextColor color) {
        Notify.send(t, t.owner, Notify.Kind.ATTACK, text, color);
    }

    private static Worker nearestSettler(SettlementPlugin plugin, Town t, Location l, double max) {
        Worker best = null;
        double bestD = max * max;
        for (Worker w : plugin.workers().all()) {
            if (!t.id.equals(w.townId) || !w.body.isValid() || w.body.getWorld() != l.getWorld()) continue;
            double d = w.body.getLocation().distanceSquared(l);
            if (d < bestD) {
                bestD = d;
                best = w;
            }
        }
        return best;
    }

    private static LivingEntity nearestHostile(Worker w, Town t, double max) {
        LivingEntity best = null;
        double bestD = max * max;
        for (LivingEntity h : t.hostiles) {
            if (!h.isValid() || h.isDead() || h.getWorld() != w.body.getWorld()) continue;
            double d = h.getLocation().distanceSquared(w.body.getLocation());
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    /** A monster close by that the settler can see (or that is right next to it). */
    private static boolean seesHostile(Worker w, Town t) {
        for (LivingEntity h : t.hostiles) {
            if (!h.isValid() || h.isDead() || h.getWorld() != w.body.getWorld()) continue;
            double d = h.getLocation().distanceSquared(w.body.getLocation());
            if (d <= 6 * 6 || (d <= 24 * 24 && w.body.hasLineOfSight(h))) return true;
        }
        return false;
    }

    /** What a (non-guard) settler does now: take cover / fight, return a borrowed weapon, or null. */
    static Activity react(Worker w, Town t) {
        if (t.alarm && seesHostile(w, t)) {
            return t.reaction == Town.Reaction.CALL_TO_ARMS ? new CallToArms(w, t) : new TakeCover(w, t);
        }
        if (!t.alarm && w.borrowedWeapon != null && (w.activity == null || w.activity.freeTime())) return new CallToArms(w, t);
        return null;
    }

    // ---- Food ----

    /** Is a guard of this settlement hurt? Then workers leave the food for the guards. */
    static boolean guardNeedsFood(Worker me, Town t) {
        int pct = me.plugin.settings().eatBelowPercent();
        for (Worker w : me.plugin.workers().all()) {
            if (w.guard && t.id.equals(w.townId) && w.body.isValid() && w.hurtBelow(pct)) return true;
        }
        return false;
    }

    /** Walk to a chest with food (that settlers may take from), take a few and eat one. */
    static Activity fetchFood(Worker w, Town t) {
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (Block b : t.containers(w.plugin.settings().townRadius())) {
            if (!t.rule(b)[1] || w.isSkipped(b)) continue;
            Inventory inv = Town.inventory(b);
            if (inv == null || bestFood(inv, Combat.missing(w.body)) < 0) continue;
            double d = b.getLocation().distanceSquared(w.body.getLocation());
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        if (best == null) return null;
        return new GoAndDo(w, best, 2.0, "Getting food", b -> true, b -> {
            Inventory inv = Town.inventory(b);
            if (inv == null) return;
            int slot = bestFood(inv, Combat.missing(w.body));
            if (slot < 0) return;
            ItemStack it = inv.getItem(slot);
            int n = Math.min(3, it.getAmount());
            ItemStack part = it.clone();
            part.setAmount(n);
            if (!w.pockets.addItem(part).isEmpty()) return;
            it.setAmount(it.getAmount() - n);
            inv.setItem(slot, it.getAmount() > 0 ? it : null);
            Combat.eat(w);
        });
    }

    /** The slot with the food that fits the damage best (see Combat.fittingFood), or -1. */
    private static int bestFood(Inventory inv, double missing) {
        java.util.List<Material> kinds = new java.util.ArrayList<>();
        for (ItemStack it : inv.getContents()) if (it != null && Combat.foodValue(it.getType()) > 0) kinds.add(it.getType());
        Material pick = Combat.fittingFood(kinds, missing);
        if (pick == null) return -1;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == pick) return i;
        }
        return -1;
    }

    // ---- Take cover ----

    /** Hide under a roof near the gathering spot until the alarm is over. */
    static final class TakeCover implements Reaction {
        private final Worker w;
        private final Town t;
        private Location spot;
        private int time, calm, nextFlee;
        private boolean fleeing;
        private final java.util.Set<String> unreachable = new java.util.HashSet<>();

        TakeCover(Worker w, Town t) {
            this.w = w;
            this.t = t;
            Bubbles.say(w.plugin, w.body, "Monsters! Hide!", 40);
        }

        @Override
        public boolean tick() {
            time++;
            if (!t.alarm || w.town() != t) return true;
            // No monster within 32 blocks for 10 seconds: back to work.
            if (time % 20 == 0) {
                if (nearestHostile(w, t, 32) != null) calm = 0;
                else if ((calm += 20) >= 200) return true;
            }
            if (fleeing) return flee();
            if (spot == null || (w.mover.failed() && time % 20 == 0)) {
                if (spot != null) unreachable.add(Keys.ser(spot));
                spot = unreachable.size() >= 4 ? null : shelter(w, t, unreachable);
                if (spot == null) {
                    fleeing = true; // nowhere to hide: keep away from the monsters
                    return flee();
                }
                if (!w.mover.go(spot, 0.8, w.plugin.settings().workSpeed() * 1.2)) unreachable.add(Keys.ser(spot));
            } else if (!w.mover.moving() && time % 40 == 0 && w.body.getLocation().distanceSquared(spot) > 2 * 2) {
                w.mover.go(spot, 0.8, w.plugin.settings().workSpeed() * 1.2);
            } else if (!doorsShut && !w.mover.moving() && w.body.getLocation().distanceSquared(spot) <= 2 * 2) {
                doorsShut = true;
                closeDoorsNear(w, spot, 5); // inside: shut the doors, like villagers
            }
            return false;
        }

        private boolean doorsShut;

        /** Run to a spot in the settlement away from the monsters; pick a new one when there or when one comes close. */
        private boolean flee() {
            boolean danger = nearestHostile(w, t, 7) != null;
            if (w.mover.moving() && !danger) return false;
            if (!danger && time < nextFlee) return false;
            nextFlee = time + 40 + w.random.nextInt(40);
            Location me = w.body.getLocation();
            // Direction away from the monsters (closer ones count more).
            double ax = 0, az = 0;
            for (LivingEntity h : t.hostiles) {
                if (!h.isValid() || h.getWorld() != me.getWorld()) continue;
                double dx = me.getX() - h.getX(), dz = me.getZ() - h.getZ();
                double d2 = Math.max(1, dx * dx + dz * dz);
                if (d2 > 32 * 32) continue;
                ax += dx / d2;
                az += dz / d2;
            }
            double base = (ax == 0 && az == 0) ? w.random.nextDouble() * Math.PI * 2 : Math.atan2(az, ax);
            int r = w.plugin.settings().townRadius() - 4;
            World world = me.getWorld();
            for (int tries = 0; tries < 12; tries++) {
                double a = base + (w.random.nextDouble() - 0.5) * (tries < 6 ? Math.PI / 1.5 : Math.PI * 1.5);
                double dist = 8 + w.random.nextInt(8);
                int x = (int) Math.floor(me.getX() + Math.cos(a) * dist), z = (int) Math.floor(me.getZ() + Math.sin(a) * dist);
                if (!t.contains(new Location(world, x, me.getY(), z), r)) continue;
                for (int y = me.getBlockY() + 4; y >= me.getBlockY() - 6; y--) {
                    if (!PathFinder.walkable(world, x, y, z)) continue;
                    Location to = new Location(world, x + 0.5, y, z + 0.5);
                    if (tooClose(to, 8)) break;
                    if (w.mover.go(to, 1.0, w.plugin.settings().workSpeed() * 1.5)) return false;
                    break;
                }
            }
            return false;
        }

        private boolean tooClose(Location l, double min) {
            for (LivingEntity h : t.hostiles) {
                if (h.isValid() && h.getWorld() == l.getWorld() && h.getLocation().distanceSquared(l) < min * min) return true;
            }
            return false;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return fleeing ? "Running from monsters" : "Taking cover";
        }

        @Override
        public boolean interruptible() {
            return false;
        }
    }

    /** Indoor spots (just inside a door) per settlement, found by scanning for doors. Cached. */
    private static final Map<String, java.util.List<Location>> indoors = new HashMap<>();
    private static final Map<String, Integer> indoorsAt = new HashMap<>();

    /**
     * Where to hide: inside the nearest building with a door (the settler closes the door behind
     * it, like villagers). No such building: under a roof near the gathering spot. Null: nowhere.
     */
    static Location shelter(Worker w, Town t, java.util.Set<String> skip) {
        return shelter(w, t, skip, false);
    }

    /**
     * Where to go: {@code anyRoof} (thunder): the nearest spot with a block above the head, anywhere in
     * the settlement. Otherwise: inside the nearest building (with a door) in the whole settlement, else
     * a roofed spot close by.
     */
    static Location shelter(Worker w, Town t, java.util.Set<String> skip, boolean anyRoof) {
        int townR = w.plugin.settings().townRadius();
        if (anyRoof) {
            Location roof = nearbyCover(w, skip, townR, false);
            if (roof != null) return roof;
        }
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (Location in : indoorSpots(w.plugin, t)) {
            if (in.getWorld() != w.body.getWorld() || skip.contains(Keys.ser(in))) continue;
            double d = in.distanceSquared(w.body.getLocation());
            if (d < bestD) {
                bestD = d;
                best = in;
            }
        }
        if (best != null) return spreadInside(w, best);

        // No building with a door: a spot with a roof and walls (a shed), else just a roof.
        Location near = nearbyCover(w, skip, townR, true);
        if (near != null) return near;

        Location c = Seats.gatheringSpot(t, townR);
        if (c == null || c.getWorld() != w.body.getWorld()) c = t.bell;
        World world = c.getWorld();
        for (int i = 0; i < 80; i++) {
            int x = c.getBlockX() + w.random.nextInt(25) - 12, z = c.getBlockZ() + w.random.nextInt(25) - 12;
            for (int y = c.getBlockY() + 4; y >= c.getBlockY() - 4; y--) {
                if (!PathFinder.walkable(world, x, y, z)) continue;
                if (roofed(world, x, y, z)) {
                    Location l = new Location(world, x + 0.5, y, z + 0.5);
                    double d = l.distanceSquared(w.body.getLocation());
                    if (d < bestD) {
                        bestD = d;
                        best = l;
                    }
                }
                break;
            }
        }
        return best; // null: nowhere to hide
    }

    /** Search around the settler (rings, nearest first) for cover: indoors first if asked, else the nearest roof. */
    private static Location nearbyCover(Worker w, java.util.Set<String> skip, int maxR, boolean indoorFirst) {
        Location me = w.body.getLocation();
        World world = me.getWorld();
        Location roof = null;
        for (int r = 0; r <= maxR; r++) {
            if (!indoorFirst && roof != null) return roof; // the nearest roof will do
            for (int i = 0; i < Math.max(1, 8 * r); i += Math.max(1, r / 8)) {
                double a = r == 0 ? 0 : Math.PI * 2 * i / (8.0 * r);
                int x = (int) Math.floor(me.getX() + Math.cos(a) * r), z = (int) Math.floor(me.getZ() + Math.sin(a) * r);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = me.getBlockY() + 3; y >= me.getBlockY() - 3; y--) {
                    if (!PathFinder.walkable(world, x, y, z)) continue;
                    Location l = new Location(world, x + 0.5, y, z + 0.5);
                    if (skip.contains(Keys.ser(l))) break;
                    if (indoorFirst && indoor(world, x, y, z)) return l;
                    if (roof == null && roofed(world, x, y, z)) roof = l;
                    break;
                }
            }
        }
        return roof;
    }

    /** A free spot a little further into the room, so not everyone stands on one block. */
    private static Location spreadInside(Worker w, Location in) {
        World world = in.getWorld();
        for (int i = 0; i < 12; i++) {
            int x = in.getBlockX() + w.random.nextInt(5) - 2, z = in.getBlockZ() + w.random.nextInt(5) - 2;
            int y = in.getBlockY();
            if (PathFinder.walkable(world, x, y, z) && indoor(world, x, y, z)) return new Location(world, x + 0.5, y, z + 0.5);
        }
        return in;
    }

    private static java.util.List<Location> indoorSpots(SettlementPlugin plugin, Town t) {
        int now = Bukkit.getCurrentTick();
        Integer at = indoorsAt.get(t.id);
        if (at != null && now - at < 1200 && indoors.containsKey(t.id)) return indoors.get(t.id);
        java.util.List<Location> out = new java.util.ArrayList<>();
        World world = t.world();
        int by = t.bell.getBlockY();
        int[] box = t.box(0);
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                if (!t.inside(x + 0.5, z + 0.5, 0)) continue;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = by - 8; y <= by + 8; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!Mover.handDoor(b.getType())) continue;
                    if (!(b.getBlockData() instanceof org.bukkit.block.data.type.Door door)
                            || door.getHalf() != org.bukkit.block.data.Bisected.Half.BOTTOM) continue;
                    org.bukkit.block.BlockFace f = door.getFacing();
                    // The inside is the side that is roofed and walled in.
                    for (org.bukkit.block.BlockFace side : new org.bukkit.block.BlockFace[]{f, f.getOppositeFace()}) {
                        int ix = x + side.getModX() * 2, iz = z + side.getModZ() * 2;
                        if (PathFinder.walkable(world, ix, y, iz) && indoor(world, ix, y, iz)) {
                            out.add(new Location(world, ix + 0.5, y, iz + 0.5));
                            break;
                        }
                    }
                }
            }
        }
        indoors.put(t.id, out);
        indoorsAt.put(t.id, now);
        return out;
    }

    /** Under a roof with walls on (at least) three sides. */
    private static boolean indoor(World world, int x, int y, int z) {
        if (!roofed(world, x, y, z)) return false;
        int walls = 0;
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            for (int d = 1; d <= 8; d++) {
                Block b = world.getBlockAt(x + f.getModX() * d, y + 1, z + f.getModZ() * d);
                if (b.getType().isOccluding() || org.bukkit.Tag.DOORS.isTagged(b.getType()) || b.getType().name().contains("GLASS")) {
                    walls++;
                    break;
                }
            }
        }
        return walls >= 3;
    }

    /** Shut the open doors (that you open by hand) around this spot, with the sound. */
    static void closeDoorsNear(Worker w, Location at, int r) {
        World world = at.getWorld();
        for (int x = -r; x <= r; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = world.getBlockAt(at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z);
                    if (!Mover.handDoor(b.getType()) && !org.bukkit.Tag.FENCE_GATES.isTagged(b.getType())) continue;
                    if (!(b.getBlockData() instanceof org.bukkit.block.data.Openable o) || !o.isOpen()) continue;
                    if (b.getBlockData() instanceof org.bukkit.block.data.Bisected bi && bi.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) continue;
                    o.setOpen(false);
                    b.setBlockData(o, true);
                    world.playSound(b.getLocation().add(0.5, 0.5, 0.5), Mover.handDoor(b.getType()) ? "minecraft:block.wooden_door.close"
                            : "minecraft:block.fence_gate.close", org.bukkit.SoundCategory.BLOCKS, 0.8f, 1f);
                    w.body.swingMainHand();
                }
            }
        }
    }

    private static boolean roofed(World world, int x, int y, int z) {
        for (int dy = 2; dy <= 8; dy++) {
            Material m = world.getBlockAt(x, y + dy, z).getType();
            if (m.isSolid() && !org.bukkit.Tag.LEAVES.isTagged(m)) return true; // a real roof (glass too), not a tree
        }
        return false;
    }

    // ---- Call to arms ----

    /** Grab a weapon from storage and fight; afterwards the weapon goes back. No weapon: take cover. */
    static final class CallToArms implements Reaction {
        private final Worker w;
        private final Town t;
        private Activity sub;
        private TakeCover cover;
        private boolean fetched, returning;
        private String label = "Called to arms";

        CallToArms(Worker w, Town t) {
            this.w = w;
            this.t = t;
            if (t.alarm) Bubbles.say(w.plugin, w.body, "To arms!", 40);
        }

        @Override
        public boolean tick() {
            if (w.town() != t) return true;
            if (sub != null) {
                if (sub.tick()) {
                    sub.stop();
                    sub = null;
                    w.holdTool();
                    if (returning) return true;
                }
                return false;
            }
            if (!t.alarm) return returnWeapon();
            if (cover != null) return cover.tick();
            ItemStack melee = Combat.bestMelee(w);
            if (melee == null) {
                if (!fetched) {
                    fetched = true;
                    Block chest = weaponChest();
                    if (chest != null) {
                        label = "Getting a weapon";
                        sub = new GoAndDo(w, chest, 2.0, "Getting a weapon", b -> true, this::borrow);
                        return false;
                    }
                }
                cover = new TakeCover(w, t);
                return false;
            }
            LivingEntity target = nearestHostile(w, t, 40);
            if (target == null) {
                label = "On the lookout";
                w.mover.stop();
                return false;
            }
            label = "Fighting";
            Combat.melee(w, target, melee);
            return false;
        }

        private Block weaponChest() {
            Block best = null;
            double bestD = Double.MAX_VALUE;
            for (Block b : t.containers(w.plugin.settings().townRadius())) {
                if (!t.rule(b)[1]) continue;
                Inventory inv = Town.inventory(b);
                if (inv == null || bestWeapon(inv) < 0) continue;
                double d = b.getLocation().distanceSquared(w.body.getLocation());
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            return best;
        }

        private static int bestWeapon(Inventory inv) {
            int best = -1;
            double bestD = 2.49;
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || Combat.isBow(it.getType()) || Combat.armorSlot(it.getType()) != null) continue;
                double d = Combat.damage(it.getType());
                if (d > bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        private void borrow(Block b) {
            Inventory inv = Town.inventory(b);
            if (inv == null) return;
            int slot = bestWeapon(inv);
            if (slot < 0) return;
            ItemStack it = inv.getItem(slot);
            ItemStack one = it.asOne();
            if (!w.pockets.addItem(one).isEmpty()) return;
            it.subtract();
            inv.setItem(slot, it.getAmount() > 0 ? it : null);
            w.borrowedWeapon = one;
            w.borrowedFrom = b;
            w.holdTool();
        }

        /** After the fight: bring the borrowed weapon back. */
        private boolean returnWeapon() {
            if (cover != null) {
                cover.stop();
                cover = null;
            }
            if (w.borrowedWeapon == null || returning) return true;
            returning = true;
            Block back = w.borrowedFrom != null && Town.isStorage(w.borrowedFrom) ? w.borrowedFrom
                    : t.storeChest(w.borrowedWeapon.getType(), w.body.getLocation(), w.plugin.settings().townRadius());
            if (back == null) {
                w.borrowedWeapon = null; // nowhere to put it: keep it
                w.borrowedFrom = null;
                w.holdTool();
                return true;
            }
            label = "Returning the weapon";
            sub = new GoAndDo(w, back, 2.0, "Returning the weapon", b -> true, b -> {
                Inventory inv = Town.inventory(b);
                ItemStack wpn = w.borrowedWeapon;
                if (inv != null && wpn != null) {
                    // The one it carries now: worn from the fight, so no longer the same as when it took it.
                    for (int i = 0; i < w.pockets.getSize(); i++) {
                        ItemStack it = w.pockets.getItem(i);
                        if (it == null || it.getType() != wpn.getType()) continue;
                        ItemStack one = it.clone();
                        one.setAmount(1);
                        if (inv.addItem(one).isEmpty()) {
                            it.setAmount(it.getAmount() - 1);
                            w.pockets.setItem(i, it.getAmount() > 0 ? it : null);
                            w.savePockets();
                        }
                        break;
                    }
                }
                w.borrowedWeapon = null;
                w.borrowedFrom = null;
            });
            return false;
        }

        @Override
        public void stop() {
            if (sub != null) sub.stop();
            if (cover != null) cover.stop();
            w.mover.stop();
            w.holdTool();
        }

        @Override
        public String label() {
            return cover != null ? cover.label() : label;
        }
    }
}
