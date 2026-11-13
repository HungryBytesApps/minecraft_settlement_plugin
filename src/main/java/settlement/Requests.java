package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Settlers asking each other for things: a settler that needs something another job makes (glass, gold,
 * milk...) walks over and asks for it, waits, and the other makes just enough (whatever the storage targets
 * say) and hands it over: the item goes from hand to hand, "Here you go", "Thanks".
 */
final class Requests {
    private Requests() {}

    static final int ASKED = 0, ACCEPTED = 1, DELIVERED = 2, FAILED = 3;

    static final class Request {
        final UUID from, to;
        final Material item;
        final int amount;
        final int created = Bukkit.getCurrentTick();
        int state = ASKED;
        String why = "";

        Request(UUID from, UUID to, Material item, int amount) {
            this.from = from;
            this.to = to;
            this.item = item;
            this.amount = amount;
        }
    }

    /** Requester + material → no asking again until this tick (after a "sorry"). */
    private static final Map<String, Integer> cooldown = new HashMap<>();

    /** Which job makes this on request (null: nobody). */
    static Worker.Job makerJob(Material m) {
        if (m == Material.MILK_BUCKET) return Worker.Job.HERDER;
        if (FurnaceJob.products(Worker.Job.FURNACE_WORKER).contains(m)) return Worker.Job.FURNACE_WORKER;
        if (FurnaceJob.products(Worker.Job.SMELTER).contains(m)) return Worker.Job.SMELTER;
        if (FurnaceJob.products(Worker.Job.COOK).contains(m)) return Worker.Job.COOK;
        return null;
    }

    /** Clean up: finished, too old, or someone left. */
    static void tidy(SettlementPlugin plugin, Town t) {
        int now = Bukkit.getCurrentTick();
        t.requests.removeIf(r -> {
            boolean gone = plugin.workers().get(r.from) == null || plugin.workers().get(r.to) == null;
            boolean done = (r.state == DELIVERED || r.state == FAILED) && now - r.created > 200;
            return gone || done || now - r.created > 9000;
        });
    }

    private static Request open(Town t, Worker from, Material m) {
        for (Request r : t.requests) {
            if (r.from.equals(from.body.getUniqueId()) && r.item == m && (r.state == ASKED || r.state == ACCEPTED)) return r;
        }
        return null;
    }

    /** What this maker has been asked for and still has to hand over: item → amount. */
    static Map<Material, Integer> asked(Town t, Worker maker) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        for (Request r : t.requests) if (r.to.equals(maker.body.getUniqueId()) && r.state == ACCEPTED) out.merge(r.item, r.amount, Integer::sum);
        return out;
    }

    /** The maker can't make it: tell the one who asked (and don't ask again for a while). */
    static void decline(SettlementPlugin plugin, Town t, Worker maker, Material item, String why) {
        for (Request r : t.requests) {
            if (!r.to.equals(maker.body.getUniqueId()) || r.item != item || r.state != ACCEPTED) continue;
            r.state = FAILED;
            r.why = why;
            cooldown.put(r.from + ":" + item, Bukkit.getCurrentTick() + 2400);
            Worker from = plugin.workers().get(r.from);
            Bubbles.say(plugin, maker.body, "Sorry, " + (from != null ? from.name : "") + ", " + why + ".", 70);
            Voice.speak(maker, why);
        }
    }

    // ---- The one who needs something ----

    /**
     * The settler needs {@code amount} of {@code m} that another job makes: ask someone (or keep waiting for
     * what it asked). Null: nobody can make it now (see the work status).
     */
    static Activity need(Worker w, Town t, Material m, int amount) {
        tidy(w.plugin, t);
        Request r = open(t, w, m);
        if (r != null) return new Wait(w, t, r);
        Integer until = cooldown.get(w.body.getUniqueId() + ":" + m);
        if (until != null && Bukkit.getCurrentTick() < until) return null;
        Worker.Job job = makerJob(m);
        if (job == null || job == w.job()) return null;
        Worker maker = null;
        double best = Double.MAX_VALUE;
        for (Worker o : w.plugin.workers().all()) {
            if (o == w || o.job() != job || !Objects.equals(o.townId, w.townId) || o.guard || o.body.getWorld() != w.body.getWorld()) continue;
            double d = o.body.getLocation().distanceSquared(w.body.getLocation()) + asked(t, o).size() * 400.0; // the least busy nearby
            if (d < best) {
                best = d;
                maker = o;
            }
        }
        if (maker == null) {
            w.workStatus = "Needs " + Work.nice(m) + " (no " + job.title.toLowerCase(Locale.ROOT) + " to make it)";
            return null;
        }
        Request req = new Request(w.body.getUniqueId(), maker.body.getUniqueId(), m, amount);
        t.requests.add(req);
        return new Ask(w, t, maker, req);
    }

    /** Walk over to the maker and ask. */
    private static final class Ask implements Activity {
        private final Worker w, maker;
        private final Town t;
        private final Request r;
        private int time, repath, talked = -1;

        Ask(Worker w, Town t, Worker maker, Request r) {
            this.w = w;
            this.t = t;
            this.maker = maker;
            this.r = r;
        }

        @Override
        public boolean tick() {
            time++;
            if (!maker.body.isValid()) {
                r.state = FAILED;
                return true;
            }
            if (talked < 0) {
                Location me = w.body.getLocation(), them = maker.body.getLocation();
                if (me.getWorld() == them.getWorld() && me.distanceSquared(them) <= 2.6 * 2.6 || time > 600) {
                    w.mover.stop();
                    w.anim.look(maker.body.getEyeLocation());
                    String what = r.amount + " " + Work.nice(r.item);
                    Bubbles.say(w.plugin, w.body, maker.name + ", could you make me " + what + "?", 60);
                    Voice.speak(w, "could you?");
                    talked = time;
                } else if (--repath <= 0) {
                    repath = 15;
                    w.mover.go(them, 2.0, w.plugin.settings().walkSpeed());
                }
                return false;
            }
            w.anim.look(maker.body.getEyeLocation());
            if (time == talked + 30) {
                maker.anim.look(w.body.getEyeLocation());
                maker.anim.nod();
                Bubbles.say(w.plugin, maker.body, "Sure, " + w.name + ", coming up!", 50);
                Voice.speak(maker, "sure!");
                r.state = ACCEPTED;
            }
            return time > talked + 50;
        }

        @Override
        public void stop() {
            w.mover.stop();
            if (r.state == ASKED) r.state = FAILED;
        }

        @Override
        public String label() {
            return "Asking " + maker.name + " for " + Work.nice(r.item);
        }
    }

    /** Waiting for what it asked for (the maker comes to hand it over). */
    private static final class Wait implements Activity {
        private final Worker w;
        private final Town t;
        private final Request r;
        private int time;

        Wait(Worker w, Town t, Request r) {
            this.w = w;
            this.t = t;
            this.r = r;
        }

        @Override
        public boolean tick() {
            time++;
            if (r.state == DELIVERED || r.state == FAILED) return true;
            if (Bukkit.getCurrentTick() - r.created > 6000) { // 5 minutes: give up (and don't ask again right away)
                r.state = FAILED;
                cooldown.put(r.from + ":" + r.item, Bukkit.getCurrentTick() + 2400);
                return true;
            }
            if (time % 40 == 0) {
                Worker maker = w.plugin.workers().get(r.to);
                if (maker != null && maker.body.getWorld() == w.body.getWorld()
                        && maker.body.getLocation().distanceSquared(w.body.getLocation()) < 24 * 24) w.anim.look(maker.body.getEyeLocation());
                else w.anim.glance();
            }
            return false;
        }

        @Override
        public String label() {
            Worker maker = w.plugin.workers().get(r.to);
            return "Waiting for " + r.amount + " " + Work.nice(r.item) + (maker != null ? " from " + maker.name : "");
        }
    }

    // ---- The one who makes it ----

    /** Made enough for someone who asked: go and hand it over. Null if nothing is ready. */
    static Activity deliver(Worker maker, Town t) {
        tidy(maker.plugin, t);
        for (Request r : t.requests) {
            if (!r.to.equals(maker.body.getUniqueId()) || r.state != ACCEPTED || maker.count(r.item) < r.amount) continue;
            Worker to = maker.plugin.workers().get(r.from);
            if (to == null || to.body.getWorld() != maker.body.getWorld()) {
                r.state = FAILED;
                continue;
            }
            maker.workStatus = "Bringing " + Work.nice(r.item) + " to " + to.name;
            return new Handover(maker, to, r);
        }
        return null;
    }

    /** Walk to the one who asked, hand it over (the item flies from hand to hand), "Here you go", "Thanks". */
    private static final class Handover implements Activity {
        private final Worker w, to;
        private final Request r;
        private int time, repath, at = -1;
        private ItemDisplay flying;

        Handover(Worker w, Worker to, Request r) {
            this.w = w;
            this.to = to;
            this.r = r;
        }

        private static Location hand(Worker x) {
            Location l = x.body.getLocation();
            Vector fwd = l.getDirection().setY(0);
            if (fwd.lengthSquared() < 1e-4) fwd = new Vector(0, 0, 1);
            fwd.normalize();
            Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());
            return l.clone().add(fwd.multiply(0.45)).add(right.multiply(-0.3)).add(0, 1.0, 0);
        }

        @Override
        public boolean tick() {
            time++;
            boolean handedOver = at >= 0 && time - at > 11;
            if (!to.body.isValid() || (!handedOver && r.state != ACCEPTED)) {
                cleanup();
                return true;
            }
            if (at < 0) {
                Location me = w.body.getLocation(), them = to.body.getLocation();
                if (me.distanceSquared(them) <= 2.2 * 2.2) {
                    w.mover.stop();
                    at = time;
                    w.anim.look(to.body.getEyeLocation());
                    to.anim.look(w.body.getEyeLocation());
                    to.mover.stop();
                    w.hold(r.item);
                    w.body.swingMainHand();
                    Bubbles.say(w.plugin, w.body, "Here you go, " + to.name + ": " + r.amount + " " + Work.nice(r.item) + "!", 60);
                    Voice.speak(w, "here you go!");
                    Location from = hand(w);
                    flying = w.body.getWorld().spawn(from, ItemDisplay.class, d -> {
                        d.setItemStack(new ItemStack(r.item));
                        d.setTeleportDuration(8);
                        d.setPersistent(false);
                        Keys.markTemp(d);
                        d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                                new org.joml.Vector3f(0.4f, 0.4f, 0.4f), new org.joml.AxisAngle4f()));
                    });
                    w.hold(null);
                } else if (time > 900) {
                    r.state = FAILED; // couldn't get there: keep it for storage
                    return true;
                } else if (--repath <= 0) {
                    repath = 15;
                    w.mover.go(them, 1.8, w.plugin.settings().walkSpeed());
                }
                return false;
            }
            int t = time - at;
            if (t == 2 && flying != null) flying.teleport(hand(to)); // glides over (teleport duration)
            if (t == 11) {
                if (flying != null) flying.remove();
                flying = null;
                if (w.take(r.item, r.amount)) {
                    to.give(List.of(new ItemStack(r.item, r.amount)), to.body.getLocation());
                    to.savePockets();
                    w.savePockets();
                    to.hold(r.item);
                    r.state = DELIVERED;
                } else {
                    r.state = FAILED;
                }
            }
            if (t == 26) {
                to.anim.nod();
                Bubbles.say(to.plugin, to.body, "Thanks, " + w.name + "!", 45);
                Voice.speak(to, "thanks!");
            }
            if (t >= 45) {
                w.holdTool();
                Bukkit.getScheduler().runTaskLater(w.plugin, () -> {
                    if (to.body.isValid()) to.holdTool();
                }, 30L);
                return true;
            }
            return false;
        }

        private void cleanup() {
            if (flying != null) flying.remove();
            flying = null;
        }

        @Override
        public void stop() {
            cleanup();
            w.mover.stop();
        }

        @Override
        public String label() {
            return "Bringing " + Work.nice(r.item) + " to " + to.name;
        }
    }
}
