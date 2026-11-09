package villagerworkers;

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
    static void tick(VillagerWorkersPlugin plugin) {
        int r = plugin.settings().townRadius();
        int now = Bukkit.getCurrentTick();
        for (Town t : plugin.towns().all()) {
            t.hostiles.clear();
            World world = t.world();
            if (world == null || !world.isChunkLoaded(t.bell.getBlockX() >> 4, t.bell.getBlockZ() >> 4)) continue;
            Location c = t.bell.clone().add(0.5, 0.5, 0.5);
            for (LivingEntity e : world.getNearbyLivingEntities(c, r, Math.min(r, 32), r)) {
                if (Combat.hostile(e) && t.contains(e.getLocation(), r)) t.hostiles.add(e);
            }
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

    private static void ring(Town t) {
        if (t.bell.getBlock().getState() instanceof org.bukkit.block.Bell bell) bell.ring();
    }

    private static void tell(Town t, String text, NamedTextColor color) {
        Player owner = Bukkit.getPlayer(t.owner);
        if (owner != null) owner.sendMessage(Component.text(text, color));
    }

    private static Worker nearestSettler(VillagerWorkersPlugin plugin, Town t, Location l, double max) {
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

    /** What a (non-guard) settler does now: take cover / fight, return a borrowed weapon, or null. */
    static Activity react(Worker w, Town t) {
        if (t.alarm && nearestHostile(w, t, 24) != null) {
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
            if (inv == null || bestFood(inv) < 0) continue;
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
            int slot = bestFood(inv);
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

    private static int bestFood(Inventory inv) {
        int best = -1, bestV = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            int v = Combat.foodValue(it.getType());
            if (v > bestV) {
                bestV = v;
                best = i;
            }
        }
        return best;
    }

    // ---- Take cover ----

    /** Hide under a roof near the gathering spot until the alarm is over. */
    static final class TakeCover implements Reaction {
        private final Worker w;
        private final Town t;
        private Location spot;
        private int time;

        TakeCover(Worker w, Town t) {
            this.w = w;
            this.t = t;
            Bubbles.say(w.plugin, w.body, "Monsters! Hide!", 40);
        }

        @Override
        public boolean tick() {
            time++;
            if (!t.alarm || w.town() != t) return true;
            if (spot == null) {
                spot = shelter(w, t);
                if (spot != null) w.mover.go(spot, 0.8, w.plugin.settings().workSpeed() * 1.2);
            } else if (!w.mover.moving() && time % 40 == 0 && w.body.getLocation().distanceSquared(spot) > 2 * 2) {
                w.mover.go(spot, 0.8, w.plugin.settings().workSpeed() * 1.2);
            }
            return false;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return "Taking cover";
        }
    }

    /** A spot with a roof, near the gathering spot (or bell), close to the settler. */
    static Location shelter(Worker w, Town t) {
        Location c = Seats.gatheringSpot(t, w.plugin.settings().socialRadius());
        if (c == null || c.getWorld() != w.body.getWorld()) c = t.bell;
        World world = c.getWorld();
        Location best = null;
        double bestD = Double.MAX_VALUE;
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
        return best != null ? best : c.clone().add(0.5, 1, 0.5);
    }

    private static boolean roofed(World world, int x, int y, int z) {
        for (int dy = 2; dy <= 6; dy++) if (world.getBlockAt(x, y + dy, z).getType().isOccluding()) return true;
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
                if (inv != null && wpn != null && w.pockets.containsAtLeast(wpn, 1) && inv.addItem(wpn.clone()).isEmpty()) {
                    w.pockets.removeItem(wpn);
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
