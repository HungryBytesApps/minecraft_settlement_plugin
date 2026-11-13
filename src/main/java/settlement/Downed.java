package settlement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A settler whose health runs out is downed, not dead: it lies on the ground, wounded, asking for help, with a
 * glowing outline and a dot on the locator bar. Hold right-click on it for a few seconds (revive-seconds) to
 * help it up. Nobody comes within downed-minutes: it dies, and its things lie in a grave (see Graves).
 * Also here: Find (a settler glows and shows on the locator bar for a while).
 */
final class Downed {
    private Downed() {}

    private static final NamespacedKey KEY = new NamespacedKey(Keys.NS, "downed");
    /** How far away the locator bar shows a marked settler or grave. */
    static final double MARK_RANGE = 1000;

    private static final List<String> HELP = List.of("Help...", "Please, help me...", "I'm hurt...", "Someone... help...", "Over here...");
    private static final List<String> THANKS = List.of("Thank you!", "I owe you one.", "Thanks... that was close.", "Back on my feet!");

    static boolean is(Worker w) {
        return w.downedUntil > 0;
    }

    /** Down instead of dead. */
    static void down(Worker w) {
        if (is(w)) return;
        SettlementPlugin plugin = w.plugin;
        int minutes = Math.max(1, plugin.getConfig().getInt("downed-minutes", 5));
        w.downedUntil = Bukkit.getCurrentTick() + minutes * 1200;
        w.stopAll();
        w.body.setHealth(1);
        w.body.setInvulnerable(true);
        w.body.setPose(Pose.SLEEPING, true); // flat on the ground
        w.downedAt = w.body.getLocation();
        w.body.setCollidable(false); // can't be pushed around
        clickArea(w, true);
        mark(w.body, true);
        w.body.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        Bubbles.say(plugin, w.body, "Aargh...", 60);
        w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.player.hurt", SoundCategory.NEUTRAL, 1f, 0.8f);
        Location l = w.body.getLocation();
        Notify.send(w.town(), w.owner, Notify.Kind.DEATH, "⚠ " + (w.guard ? "Guard " : "") + w.name + " is down at " + l.getBlockX() + ", " + l.getBlockY()
                + ", " + l.getBlockZ() + ". Hold right-click on them within " + minutes + " minute" + (minutes == 1 ? "" : "s") + " to help them up.", NamedTextColor.GOLD);
    }

    /** Every tick while downed: small movements, a groan, a call for help; out of time: dies. */
    static void tick(Worker w, long ticks) {
        int now = Bukkit.getCurrentTick();
        if (now >= w.downedUntil) {
            die(w);
            return;
        }
        // Lies still, exactly where and how it fell.
        Location here = w.body.getLocation();
        if (w.downedAt != null && (here.getWorld() != w.downedAt.getWorld() || here.distanceSquared(w.downedAt) > 0.0004
                || Math.abs(here.getYaw() - w.downedAt.getYaw()) > 0.5f || Math.abs(here.getPitch() - w.downedAt.getPitch()) > 0.5f)) {
            w.body.teleport(w.downedAt);
            w.body.setVelocity(new org.bukkit.util.Vector());
        }
        if (ticks % 20 == 0 && !w.body.isGlowing()) mark(w.body, true); // the outline never goes missing
        if (ticks % 30 == 0) w.body.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, w.body.getLocation().add(0, 0.4, 0), 1, 0.3, 0.1, 0.3, 0);
        if (ticks % 400 == 0) {
            Bubbles.say(w.plugin, w.body, HELP.get(w.random.nextInt(HELP.size())), 60);
            w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.player.hurt", SoundCategory.NEUTRAL, 0.5f, 0.7f);
        }
        if (ticks % 100 == 0 && w.body.getPose() != Pose.SLEEPING) w.body.setPose(Pose.SLEEPING, true);
    }

    /** Back on its feet (revived). */
    static void up(Worker w) {
        w.downedUntil = 0;
        w.body.getPersistentDataContainer().remove(KEY);
        w.body.setInvulnerable(false);
        w.body.setCollidable(true);
        w.downedAt = null;
        w.body.setPose(Pose.STANDING, false);
        clickArea(w, false);
        w.body.setHealth(Math.max(1, Combat.maxHealth(w.body) * 0.4));
        if (w.markUntil == 0) mark(w.body, false);
        Bubbles.say(w.plugin, w.body, THANKS.get(w.random.nextInt(THANKS.size())), 60);
        w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.player.levelup", SoundCategory.NEUTRAL, 0.5f, 1.4f);
    }

    /** Nobody came: it dies; its things go into a grave. */
    static void die(Worker w) {
        Location at = w.body.getLocation();
        String name = w.name;
        boolean guard = w.guard;
        Town t = w.town();
        clickArea(w, false);
        boolean grave = Graves.bury(w.plugin, w, at);
        w.plugin.forget(w);
        w.body.getWorld().spawnParticle(Particle.POOF, at.clone().add(0, 0.5, 0), 12, 0.3, 0.2, 0.3, 0.01);
        w.body.remove();
        Notify.send(t, w.owner, Notify.Kind.DEATH, "✝ " + (guard ? "Guard " : "Settler ") + name + " has died."
                + (grave ? " Their things are at the skull (" + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ")." : ""), NamedTextColor.RED);
    }

    /** After a restart: still downed (with a fresh time limit). */
    static void restore(Worker w) {
        if (w.body.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE)) {
            w.body.getPersistentDataContainer().remove(KEY);
            down(w);
        }
    }

    // ---- Reviving: hold right-click ----

    /** Lying down it's hard to click (tiny), so a click area lies over it. */
    static final NamespacedKey CLICK = new NamespacedKey(Keys.NS, "downed_click");
    private static final Map<UUID, List<org.bukkit.entity.Interaction>> areas = new HashMap<>();

    /** Click boxes over the whole body: lying down it reaches out from the feet (which way depends on how it fell). */
    private static void clickArea(Worker w, boolean on) {
        List<org.bukkit.entity.Interaction> old = areas.remove(w.body.getUniqueId());
        if (old != null) old.forEach(org.bukkit.entity.Entity::remove);
        if (!on) return;
        Location at = w.body.getLocation();
        double yaw = Math.toRadians(at.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw); // forward
        List<org.bukkit.entity.Interaction> boxes = new java.util.ArrayList<>();
        double[][] offsets = {{0, 0}, {fx, fz}, {-fx, -fz}, {-fz, fx}, {fz, -fx}};
        for (double[] o : offsets) {
            Location l = at.clone().add(o[0], 0, o[1]);
            l.setYaw(0);
            l.setPitch(0);
            boxes.add(at.getWorld().spawn(l, org.bukkit.entity.Interaction.class, i -> {
                i.setInteractionWidth(1.2f);
                i.setInteractionHeight(0.6f);
                i.setResponsive(true);
                i.setPersistent(false);
                i.getPersistentDataContainer().set(CLICK, PersistentDataType.STRING, w.body.getUniqueId().toString());
            }));
        }
        areas.put(w.body.getUniqueId(), boxes);
    }

    /** The click area of a downed settler: whose it is (null: not one). */
    static UUID clicked(org.bukkit.entity.Entity e) {
        if (!(e instanceof org.bukkit.entity.Interaction)) return null;
        String id = e.getPersistentDataContainer().get(CLICK, PersistentDataType.STRING);
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private record Help(UUID worker, int start, int last) {}

    private static final Map<UUID, Help> helping = new HashMap<>();

    /** A right-click on a downed settler (they come every few ticks while held). */
    static void helpClick(Player p, Worker w) {
        int now = Bukkit.getCurrentTick();
        Help h = helping.get(p.getUniqueId());
        if (h == null || !h.worker().equals(w.body.getUniqueId()) || now - h.last() > 10) h = new Help(w.body.getUniqueId(), now, now);
        else h = new Help(h.worker(), h.start(), now);
        helping.put(p.getUniqueId(), h);
        int need = Math.max(1, w.plugin.getConfig().getInt("revive-seconds", 3)) * 20;
        double done = Math.min(1.0, (now - h.start()) / (double) need);
        int bars = 20, full = (int) Math.round(done * bars);
        p.sendActionBar(Component.text("Helping " + w.name + " up  ", NamedTextColor.WHITE)
                .append(Component.text("|".repeat(full), NamedTextColor.GREEN))
                .append(Component.text("|".repeat(bars - full), NamedTextColor.DARK_GRAY)));
        if (done >= 1.0) {
            helping.remove(p.getUniqueId());
            up(w);
            p.sendActionBar(Component.text(w.name + " is back on their feet.", NamedTextColor.GREEN));
        } else if ((now - h.start()) % 8 == 0) {
            w.body.getWorld().spawnParticle(Particle.HEART, w.body.getLocation().add(0, 0.6, 0), 1, 0.3, 0.1, 0.3, 0);
        }
    }

    // ---- Markers: glowing outline + locator bar ----

    /** Glowing outline and a dot on the locator bar (or neither). */
    static void mark(LivingEntity e, boolean on) {
        e.setGlowing(on);
        try {
            AttributeInstance a = e.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
            if (a == null && on) {
                e.registerAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
                a = e.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
            }
            if (a != null) a.setBaseValue(on ? MARK_RANGE : 0);
        } catch (RuntimeException ignored) {
            // no locator bar for this kind of entity: the outline still works
        }
    }

    /** Find: the settler glows and shows on the locator bar for find-minutes. */
    static void find(Worker w) {
        int minutes = Math.max(1, w.plugin.getConfig().getInt("find-minutes", 1));
        w.markUntil = Bukkit.getCurrentTick() + minutes * 1200;
        mark(w.body, true);
    }

    /** Found (clicked): the Find marker ends now. */
    static void found(Worker w) {
        if (w.markUntil == 0) return;
        w.markUntil = 0;
        if (!is(w)) mark(w.body, false);
    }

    /** Called now and then: the Find marker ends (a downed settler keeps its marker). */
    static void markTick(Worker w) {
        if (w.markUntil == 0 || Bukkit.getCurrentTick() < w.markUntil) return;
        w.markUntil = 0;
        if (!is(w)) mark(w.body, false);
    }
}
