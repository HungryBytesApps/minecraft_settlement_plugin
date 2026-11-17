package settlement;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Guards in the mine: only when a miner comes to ask them (monsters down there). It walks to a guard, asks, the
 * guards follow it down, clear the level, and walk back up on their own. At least {@code miner-guards-stay} guards
 * stay up top (none go down while the settlement is under attack). About one guard per 3 monsters.
 * A downed miner down there: a guard goes down and carries it up to the settlement.
 */
final class Escort {
    private Escort() {}

    /** Guard → the miner it's helping. */
    private static final Map<UUID, UUID> helping = new HashMap<>();
    /** Miner → how many monsters it saw. */
    static final Map<UUID, Integer> foes = new HashMap<>();

    static boolean waiting(Worker miner) {
        return false;
    }

    /** Guards helping this miner now (in the mine with it). */
    static List<Worker> guardsOf(Worker miner) {
        List<Worker> out = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : helping.entrySet()) {
            if (!e.getValue().equals(miner.body.getUniqueId())) continue;
            Worker g = miner.plugin.workers().get(e.getKey());
            if (g != null && g.body.isValid()) out.add(g);
        }
        return out;
    }

    /** Guards with it (close by: one that couldn't follow doesn't count). */
    static boolean escorted(Worker miner) {
        for (Worker g : guardsOf(miner)) {
            if (g.body.getWorld() == miner.body.getWorld() && g.body.getLocation().distanceSquared(miner.body.getLocation()) < 24 * 24) return true;
        }
        return false;
    }

    /** Guards that may go down (the rest stay up top). */
    private static List<Worker> free(Worker miner, Town t) {
        List<Worker> all = new ArrayList<>();
        for (Worker g : miner.plugin.workers().all()) {
            if (!g.guard || !t.id.equals(g.townId) || !g.body.isValid() || Downed.is(g) || helping.containsKey(g.body.getUniqueId())) continue;
            if (g.activity instanceof Carry) continue; // carrying a downed miner up
            if (g.unarmed()) continue;
            all.add(g);
        }
        int stay = Math.max(1, miner.plugin.getConfig().getInt("miner-guards-stay", 1));
        if (t.alarm) return List.of();
        int n = all.size() - stay;
        if (n <= 0) return List.of();
        all.sort(Comparator.comparingDouble(g -> g.body.getLocation().distanceSquared(miner.body.getLocation())));
        return all.subList(0, n);
    }

    /** Up top after fleeing: go and ask guards (null: none free, it waits). */
    static Activity ask(Worker miner, Town t, Mine m, int k) {
        if (escorted(miner)) {
            Miner.dangerUntil.remove(miner.body.getUniqueId());
            return null;
        }
        List<Worker> free = free(miner, t);
        if (free.isEmpty()) {
            miner.workStatus = "Waiting: monsters in the mine, no guard free";
            return null;
        }
        int want = Math.max(1, (int) Math.ceil(foes.getOrDefault(miner.body.getUniqueId(), 2) / 3.0));
        return new AskGuards(miner, t, new ArrayList<>(free.subList(0, Math.min(want, free.size()))));
    }

    /** Walk to the (first) guard, ask; then they follow. */
    static final class AskGuards implements Activity {
        private final Worker w;
        private final Town t;
        private final List<Worker> guards;
        private int time, repath, talk = -1;

        AskGuards(Worker w, Town t, List<Worker> guards) {
            this.w = w;
            this.t = t;
            this.guards = guards;
        }

        @Override
        public boolean tick() {
            if (++time > 1800 || guards.isEmpty()) return true;
            Worker g = guards.get(0);
            if (!g.body.isValid()) return true;
            double d = g.body.getLocation().distance(w.body.getLocation());
            if (talk < 0) {
                if (d > 3) {
                    if (--repath <= 0) {
                        repath = 30;
                        if (!w.mover.go(g.body.getLocation(), 2.5, w.plugin.settings().workSpeed())) return true;
                    }
                    return false;
                }
                w.mover.stop();
                g.mover.stop();
                talk = 0;
            }
            talk++;
            if (talk == 1) {
                w.anim.look(g.body.getEyeLocation());
                g.anim.look(w.body.getEyeLocation());
                Bubbles.say(w.plugin, w.body, guards.size() > 1 ? "Monsters in the mine. Can you all help me?" : "Monsters in the mine. Can you help me?", 60);
                Voice.speak(w, "Monsters in the mine.");
            } else if (talk == 50) {
                Bubbles.say(w.plugin, g.body, "Lead the way.", 50);
                Voice.speak(g, "Lead the way.");
            } else if (talk >= 90) {
                guards.removeIf(x -> !x.body.isValid() || Downed.is(x) || x.activity instanceof Carry || helping(x));
                if (guards.isEmpty()) return true;
                for (Worker x : guards) {
                    helping.put(x.body.getUniqueId(), w.body.getUniqueId());
                    x.start(new Follow(x, w));
                }
                Miner.dangerUntil.remove(w.body.getUniqueId());
                Notify.send(t, t.owner, Notify.Kind.ATTACK, "⛏ " + guards.size() + " guard" + (guards.size() == 1 ? "" : "s") + " went down the mine with "
                        + w.name + ".", NamedTextColor.GRAY);
                return true;
            }
            return false;
        }

        @Override
        public String label() {
            return "Asking the guards for help";
        }
    }

    /** All clear down there: the guards go back up. */
    static void release(Worker miner) {
        for (Worker g : guardsOf(miner)) {
            helping.remove(g.body.getUniqueId());
            Bubbles.say(miner.plugin, miner.body, "All clear. Thank you!", 60);
        }
    }

    static boolean helping(Worker guard) {
        return helping.containsKey(guard.body.getUniqueId());
    }

    /** A guard following the miner down, fighting what's around it; when released: back up on its own. */
    static final class Follow implements Activity {
        private final Worker g;
        private final Worker miner;
        private final Deque<Location> back = new ArrayDeque<>();
        private Activity lift;
        private int repath, time;
        private boolean returning;

        Follow(Worker g, Worker miner) {
            this.g = g;
            this.miner = miner;
        }

        @Override
        public boolean tick() {
            time++;
            if (lift != null) {
                if (lift.tick()) {
                    lift.stop();
                    lift = null;
                }
                return false;
            }
            LivingEntity foe = nearest();
            if (foe != null) {
                ItemStack wpn = Combat.bestMelee(g);
                ItemStack bow = Combat.bow(g);
                if (wpn != null && (foe.getLocation().distance(g.body.getLocation()) < 6 || bow == null)) Combat.melee(g, foe, wpn);
                else if (bow != null) Combat.shoot(g, foe, bow);
                return false;
            }
            if (!returning && (!helping(g) || !miner.body.isValid() || Downed.is(miner) || time > 24000)) startBack();
            if (returning) {
                if (back.isEmpty()) return true;
                Location to = back.peek();
                if (g.body.getLocation().distanceSquared(to) < 2 * 2) {
                    back.poll();
                    if (back.isEmpty() && lift == null) {
                        Mine m = Miner.mine(miner);
                        Activity l = m == null || !m.floors() ? null : Lift.toTop(g, m, false);
                        if (l != null && g.body.getLocation().getY() < m.surface - 2) lift = l;
                    }
                    return false;
                }
                if (!g.mover.moving() && !g.mover.go(to, 1.2, g.plugin.settings().workSpeed())) back.poll();
                return false;
            }
            if (miner.body.getWorld() != g.body.getWorld()) return true;
            double d = miner.body.getLocation().distance(g.body.getLocation());
            Mine mm = Miner.mine(miner);
            Town.Workplace mwp = miner.workplace();
            if (mm != null && mm.floors() && mwp != null && lift == null) {
                // The miner went to a floor in the elevator: after it.
                Integer lvl = mm.levelOf(miner.body.getLocation(), Miner.size(mwp));
                if (lvl != null && Math.abs(g.body.getLocation().getY() - mm.levelY(lvl)) > 3) {
                    lift = Lift.toFloor(g, mm, lvl, false);
                    if (lift != null) return false;
                }
            }
            if (d > 3 && --repath <= 0) {
                repath = 15;
                g.mover.go(miner.body.getLocation(), 2.5, g.plugin.settings().workSpeed() * 1.2);
            }
            return false;
        }

        private LivingEntity nearest() {
            LivingEntity best = null;
            double bd = 12 * 12;
            for (Entity e : g.body.getNearbyEntities(12, 5, 12)) {
                if (!Combat.hostile(e) || !g.body.hasLineOfSight(e)) continue;
                double d = e.getLocation().distanceSquared(g.body.getLocation());
                if (d < bd) {
                    bd = d;
                    best = (LivingEntity) e;
                }
            }
            return best;
        }

        private void startBack() {
            returning = true;
            helping.remove(g.body.getUniqueId());
            Mine m = Miner.mine(miner);
            Town.Workplace wp = miner.workplace();
            if (m != null && wp != null && Miner.isIn(m, wp, g.body.getLocation())) back.addAll(m.upHops(g.body.getLocation(), Miner.size(wp)));
        }

        @Override
        public void stop() {
            helping.remove(g.body.getUniqueId());
            g.mover.stop();
            if (lift != null) lift.stop();
        }

        @Override
        public String label() {
            return returning ? "Coming back from the mine" : "Helping " + miner.name + " in the mine";
        }
    }

    // ---- Carrying a downed miner up ----

    /** A miner went down in the mine: send a guard (any guard: this is an emergency). */
    static void rescue(Worker miner) {
        Town t = miner.town();
        Mine m = Miner.mine(miner);
        if (t == null || m == null) return;
        Worker best = null;
        double bd = Double.MAX_VALUE;
        for (Worker g : miner.plugin.workers().all()) {
            if (!g.guard || !t.id.equals(g.townId) || !g.body.isValid() || Downed.is(g)) continue;
            if (g.activity instanceof Carry) continue;
            double d = g.body.getLocation().distanceSquared(m.entrance());
            if (d < bd) {
                bd = d;
                best = g;
            }
        }
        if (best == null) return;
        helping.remove(best.body.getUniqueId());
        best.start(new Carry(best, miner, m));
        Notify.send(t, t.owner, Notify.Kind.DEATH, "⛑ Guard " + best.name + " is going down the mine to carry " + miner.name + " up.", NamedTextColor.GOLD);
    }

    static final class Carry implements Activity {
        private final Worker g, miner;
        private final Mine m;
        private final Deque<Location> hops = new ArrayDeque<>();
        private Activity lift;
        private int phase, time, fails;

        Carry(Worker g, Worker miner, Mine m) {
            this.g = g;
            this.miner = miner;
            this.m = m;
        }

        @Override
        public boolean tick() {
            if (++time > 12000 || !miner.body.isValid()) return done();
            if (lift != null) {
                if (lift.tick()) {
                    lift.stop();
                    lift = null;
                }
                return false;
            }
            Town.Workplace wp = miner.workplace();
            int size = wp == null ? 32 : Miner.size(wp);
            switch (phase) {
                case 0 -> { // down to it: the elevator (if the mine starts at one), then the stairs and tunnels
                    if (!Downed.is(miner)) return done();
                    if (m.floors()) {
                        Integer lvl = m.levelOf(miner.body.getLocation(), size);
                        if (lvl != null && Math.abs(g.body.getLocation().getY() - m.levelY(lvl)) > 3) {
                            lift = Lift.toFloor(g, m, lvl, true);
                            if (lift != null) return false;
                        }
                    }
                    hops.addAll(m.downHops(miner.body.getLocation(), size));
                    phase = 1;
                }
                case 1 -> {
                    if (!Downed.is(miner)) return done();
                    if (walk()) return false;
                    if (g.body.getLocation().distanceSquared(miner.body.getLocation()) > 3 * 3) {
                        if (!g.mover.moving() && !g.mover.go(miner.body.getLocation(), 1.5, g.plugin.settings().workSpeed()) && ++fails > 8) return done();
                        return false;
                    }
                    g.body.addPassenger(miner.body); // picked up
                    Bubbles.say(g.plugin, g.body, "I've got you. Hold on.", 60);
                    hops.addAll(m.upHops(g.body.getLocation(), size));
                    phase = 2;
                }
                case 2 -> {
                    if (!Downed.is(miner)) return done(); // helped up meanwhile
                    if (walk()) return false;
                    if (m.floors() && g.body.getLocation().getY() < m.surface - 2) {
                        lift = Lift.toTop(g, m, true);
                        phase = 3;
                        if (lift != null) return false;
                    }
                    phase = 3;
                }
                case 3 -> {
                    if (!Downed.is(miner)) return done();
                    Town t = miner.town();
                    Location bell = t != null ? t.bell : g.body.getLocation();
                    if (g.body.getLocation().distanceSquared(bell) > 6 * 6) {
                        if (!g.mover.moving() && !g.mover.go(bell, 4, g.plugin.settings().workSpeed())) phase = 4;
                        return false;
                    }
                    phase = 4;
                }
                default -> {
                    return done();
                }
            }
            return false;
        }

        private boolean walk() {
            Location to = hops.peek();
            if (to == null) return false;
            if (g.body.getLocation().distanceSquared(to) < 2 * 2) {
                hops.poll();
                return true;
            }
            if (!g.mover.moving() && !g.mover.go(to, 1.2, g.plugin.settings().workSpeed())) hops.poll();
            return true;
        }

        private boolean done() {
            if (g.body.getPassengers().contains(miner.body)) {
                g.body.removePassenger(miner.body);
                Location at = g.body.getLocation();
                miner.body.teleport(at);
                if (Downed.is(miner)) Downed.moved(miner);
                Town t = miner.town();
                if (t != null) Notify.send(t, t.owner, Notify.Kind.DEATH, "⛑ Guard " + g.name + " carried " + miner.name + " up at "
                        + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ". Help them up!", NamedTextColor.GOLD);
            }
            return true;
        }

        @Override
        public void stop() {
            done();
            g.mover.stop();
            if (lift != null) lift.stop();
        }

        @Override
        public String label() {
            return phase >= 2 ? "Carrying " + miner.name + " up" : "Going down the mine for " + miner.name;
        }
    }
}
