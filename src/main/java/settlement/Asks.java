package settlement;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Requests: what settlers ask or tell the founder (a spot for a mine, an elevator floor, something they found,
 * guards needed, a cart that didn't come back). In the ledger (Requests, glowing when there are new ones), and the
 * settler also comes to tell the founder in person: "Got a minute?" — right-click it to see what it is.
 */
final class Asks {
    private Asks() {}

    enum Kind {
        MINE_SITE("A spot for the mine", true), FLOOR("An elevator floor", true), FOUND("Found something", false),
        GUARDS("Guards needed", false), CART("Ore cart", false), BLOCKED("Can't go on", false);

        final String title;
        final boolean question; // needs a yes/no

        Kind(String title, boolean question) {
            this.title = title;
            this.question = question;
        }
    }

    static final class Ask {
        final String id;
        final String town;
        final Kind kind;
        final String text;
        final Location at;
        final UUID worker;
        final String workplace;
        final long created;
        boolean seen;
        /** Told in person (or tried): not again before this tick. */
        transient int tellAfter;
        transient boolean told;
        /** Extra data (e.g. the mine spot: x,y,z,dir; the floor's y). */
        final String data;

        Ask(String id, String town, Kind kind, String text, Location at, UUID worker, String workplace, long created, String data) {
            this.id = id;
            this.town = town;
            this.kind = kind;
            this.text = text;
            this.at = at;
            this.worker = worker;
            this.workplace = workplace;
            this.created = created;
            this.data = data;
        }
    }

    private static final List<Ask> asks = new ArrayList<>();
    /** Declined elevator floors / mine spots: town:key → never asked again. */
    private static final Set<String> declined = new HashSet<>();
    private static File file;
    private static SettlementPlugin plugin;

    static Ask add(Town t, Kind kind, String text, Location at, Worker w, String data) {
        String wpKey = w != null && w.workplace != null ? Keys.ser(w.workplace) : null;
        // The same thing near the same place: once.
        for (Ask a : asks) {
            if (a.town.equals(t.id) && a.kind == kind && Objects.equals(a.workplace, wpKey) && Objects.equals(a.data, data)
                    && (at == null || a.at == null || (a.at.getWorld() == at.getWorld() && a.at.distanceSquared(at) < 16 * 16))) return a;
        }
        Ask a = new Ask(UUID.randomUUID().toString().substring(0, 8), t.id, kind, text, at, w == null ? null : w.body.getUniqueId(), wpKey,
                System.currentTimeMillis(), data);
        asks.add(a);
        save();
        Notify.send(t, t.owner, Notify.Kind.TOOLS, "📜 " + (w != null ? w.name + ": " : "") + text + " (Ledger → Requests)", NamedTextColor.GOLD);
        if (kind == Kind.MINE_SITE) mark(a, true);
        return a;
    }

    static boolean declined(Town t, String what) {
        return declined.contains(t.id + ":" + what);
    }

    static List<Ask> of(Town t) {
        List<Ask> out = new ArrayList<>();
        for (Ask a : asks) if (a.town.equals(t.id)) out.add(a);
        return out;
    }

    static int unseen(Town t) {
        int n = 0;
        for (Ask a : asks) if (a.town.equals(t.id) && !a.seen) n++;
        return n;
    }

    /** An open question for this workplace (e.g. the mine spot), or null. */
    static Ask open(Town.Workplace wp, Kind kind) {
        String k = Keys.ser(wp.loc);
        for (Ask a : asks) if (a.kind == kind && k.equals(a.workplace)) return a;
        return null;
    }

    static void remove(Ask a) {
        asks.remove(a);
        mark(a, false);
        save();
    }

    /** What this settler still has to tell the founder in person, or null. */
    static Ask toTell(Worker w) {
        int now = Bukkit.getCurrentTick();
        for (Ask a : asks) {
            if (!w.body.getUniqueId().equals(a.worker) || a.seen || now < a.tellAfter) continue;
            if (a.told && !a.kind.question) continue; // news: told once
            return a;
        }
        return null;
    }

    /** The founder, if they're around (online, in the settlement, not in a fight). */
    static Player founderNear(Town t, int radius) {
        Player p = Bukkit.getPlayer(t.owner);
        if (p == null || !t.contains(p.getLocation(), radius)) return null;
        for (Entity e : p.getNearbyEntities(10, 5, 10)) if (Combat.hostile(e)) return null;
        return p;
    }

    /** Walk to the founder and wait for a right-click: "Got a minute?" */
    static final class Tell implements Activity {
        private final Worker w;
        private final Player p;
        private final Ask ask;
        private int time, repath;
        private boolean said;

        Tell(Worker w, Player p, Ask ask) {
            this.w = w;
            this.p = p;
            this.ask = ask;
        }

        @Override
        public boolean tick() {
            if (time == 0) {
                ask.tellAfter = Bukkit.getCurrentTick() + 12000; // not again for 10 minutes
                ask.told = true;
            }
            if (!p.isOnline() || p.getWorld() != w.body.getWorld() || ask.seen || !asks.contains(ask) || ++time > 1800) return true;
            double d = p.getLocation().distance(w.body.getLocation());
            if (d > 3.2) {
                if (said && time > 1200) return true;
                if (--repath <= 0) {
                    repath = 30;
                    if (!w.mover.go(p.getLocation(), 2.5, w.plugin.settings().workSpeed()) && d > 40) return true;
                }
                return false;
            }
            w.mover.stop();
            if (time % 10 == 0) w.anim.look(p.getEyeLocation());
            if (!said) {
                said = true;
                time = 600; // wait at most a minute from now
                Bubbles.say(w.plugin, w.body, "Got a minute? (right-click me)", 200);
                Voice.speak(w, "Got a minute?");
            }
            return false;
        }

        @Override
        public String label() {
            return "Looking for " + p.getName();
        }
    }

    /** The founder right-clicks a settler that has something to tell: the dialog (true), or nothing (false). */
    static boolean clicked(SettlementPlugin plugin, Player p, Worker w) {
        Town t = w.town();
        if (t == null || !t.isOwner(p)) return false;
        for (Ask a : asks) {
            if (w.body.getUniqueId().equals(a.worker) && !a.seen) {
                w.pauseFor(p, 1200);
                show(plugin, p, t, a, null);
                return true;
            }
        }
        return false;
    }

    // ---- The ledger: Requests ----

    static void list(SettlementPlugin plugin, Player p, Town t) {
        List<ActionButton> rows = new ArrayList<>();
        for (Ask a : of(t)) {
            Worker wk = a.worker == null ? null : plugin.workers().get(a.worker);
            String who = wk != null ? wk.name + ": " : "";
            rows.add(Menus.button(plugin, (a.seen ? "" : "● ") + who + a.kind.title, a.seen ? NamedTextColor.GRAY : NamedTextColor.GOLD, a.text, 300,
                    pl -> show(plugin, pl, t, a, x -> list(plugin, x, t))));
        }
        List<DialogBody> body = rows.isEmpty() ? List.of(Menus.line("Nothing asked.", NamedTextColor.GRAY)) : List.of();
        Menus.show(p, "Requests", body, List.of(), DialogType.multiAction(rows).columns(1)
                .exitAction(Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> Menus.ledger(plugin, pl, t))).build());
    }

    static void show(SettlementPlugin plugin, Player p, Town t, Ask a, java.util.function.Consumer<Player> back) {
        a.seen = true;
        save();
        List<DialogBody> body = new ArrayList<>();
        body.add(Menus.line(a.text, NamedTextColor.WHITE));
        if (a.at != null) body.add(Menus.line("At " + a.at.getBlockX() + ", " + a.at.getBlockY() + ", " + a.at.getBlockZ(), NamedTextColor.GRAY));
        boolean owner = t.isOwner(p);
        if (a.kind.question && !owner) body.add(Menus.line("Only the founder can answer this.", NamedTextColor.GRAY));
        List<ActionButton> buttons = new ArrayList<>();
        Worker wk = a.worker == null ? null : plugin.workers().get(a.worker);
        if (a.kind == Kind.MINE_SITE) {
            mark(a, true);
            if (wk != null && owner) buttons.add(Menus.button(plugin, "Show me", NamedTextColor.WHITE, wk.name + " walks there (follow them)", 150, pl -> {
                wk.pausedFor = null;
                wk.start(new ShowSpot(wk, a.at));
            }));
        }
        if (a.at != null && owner) buttons.add(Menus.button(plugin, "Teleport there", NamedTextColor.WHITE, null, 150, pl -> {
            Location to = a.at.clone();
            to.setY(a.at.getWorld().getHighestBlockYAt(to) + 1);
            if (a.kind != Kind.MINE_SITE) to = a.at.clone();
            pl.teleport(to.add(0, 0.1, 0));
        }));
        if (a.kind.question && owner) {
            buttons.add(Menus.button(plugin, "Yes", NamedTextColor.GREEN, null, 150, pl -> {
                answer(plugin, t, a, true);
                if (back != null) back.accept(pl);
            }));
            buttons.add(Menus.button(plugin, a.kind == Kind.MINE_SITE ? "No, find another spot" : "No", NamedTextColor.RED, null, 150, pl -> {
                answer(plugin, t, a, false);
                if (back != null) back.accept(pl);
            }));
        } else if (owner || t.canManage(p)) {
            buttons.add(Menus.button(plugin, "Done", NamedTextColor.GREEN, "Remove it from the list", 150, pl -> {
                remove(a);
                if (back != null) back.accept(pl);
            }));
        }
        ActionButton exit = back != null ? Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, back)
                : Menus.button(plugin, "Close", NamedTextColor.GRAY, null, 100, pl -> {});
        Menus.show(p, a.kind.title, body, List.of(), DialogType.multiAction(buttons).columns(2).exitAction(exit).build());
    }

    private static void answer(SettlementPlugin plugin, Town t, Ask a, boolean yes) {
        remove(a);
        if (a.kind == Kind.MINE_SITE) {
            Town.Workplace wp = a.workplace == null ? null : t.workplace(Keys.deser(a.workplace));
            if (wp != null) Miner.answer(plugin, t, wp, a.data, yes);
            if (!yes) declined.add(t.id + ":site:" + a.data);
        } else if (a.kind == Kind.FLOOR && !yes) {
            declined.add(t.id + ":floor:" + a.data);
        }
        save();
    }

    /** A worker walks to the spot and waits there a bit. */
    static final class ShowSpot implements Activity {
        private final Worker w;
        private final Location to;
        private int time;
        private boolean going;

        ShowSpot(Worker w, Location to) {
            this.w = w;
            this.to = to;
        }

        @Override
        public boolean tick() {
            if (++time > 1200) return true;
            if (!going) {
                going = true;
                Bubbles.say(w.plugin, w.body, "Follow me!", 60);
                if (!w.mover.go(to, 2.0, w.plugin.settings().workSpeed())) return true;
            }
            if (w.mover.moving()) return false;
            if (time % 100 == 0) Bubbles.say(w.plugin, w.body, "Here!", 60);
            return time > 1000;
        }

        @Override
        public String label() {
            return "Showing the spot";
        }
    }

    // ---- The glowing outline of a proposed mine spot ----

    private static final Map<String, List<Entity>> marks = new HashMap<>();

    static void mark(Ask a, boolean on) {
        List<Entity> old = marks.remove(a.id);
        if (old != null) old.forEach(Entity::remove);
        if (!on || a.data == null || a.at == null || a.at.getWorld() == null) return;
        String[] p = a.data.split(",");
        if (p.length < 4) return;
        Mine m = new Mine("show");
        m.world = a.at.getWorld();
        m.ox = Integer.parseInt(p[0]);
        m.oy = Integer.parseInt(p[1]);
        m.oz = Integer.parseInt(p[2]);
        m.dir = Integer.parseInt(p[3]);
        List<Entity> list = new ArrayList<>();
        for (int t = -1; t <= 9; t++) {
            for (int s = 0; s <= 4; s++) {
                if (t <= 7 && s > 1) continue; // the first flight (2 wide) and the first landing
                Block b = m.at(t, s, m.oy - 1);
                if (b.getType().isAir()) continue;
                list.add(m.world.spawn(b.getLocation(), BlockDisplay.class, d -> {
                    d.setBlock(b.getBlockData());
                    d.setGlowing(true);
                    d.setGlowColorOverride(org.bukkit.Color.YELLOW);
                    d.setPersistent(false);
                    d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-0.005f, -0.005f, -0.005f),
                            new org.joml.Quaternionf(), new org.joml.Vector3f(1.01f, 1.01f, 1.01f), new org.joml.Quaternionf()));
                }));
            }
        }
        marks.put(a.id, list);
        // Gone after 10 minutes (shown again from the dialog).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            List<Entity> l = marks.get(a.id);
            if (l == list) mark(a, false);
        }, 12000L);
    }

    static void hideAll() {
        for (List<Entity> l : marks.values()) l.forEach(Entity::remove);
        marks.clear();
    }

    // ---- Saving (requests.yml) ----

    static void load(SettlementPlugin pl) {
        plugin = pl;
        file = new File(pl.getDataFolder(), "requests.yml");
        asks.clear();
        declined.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        declined.addAll(y.getStringList("declined"));
        ConfigurationSection s = y.getConfigurationSection("asks");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection a = s.getConfigurationSection(id);
            if (a == null) continue;
            try {
                UUID worker = a.getString("worker") == null ? null : UUID.fromString(a.getString("worker"));
                Ask ask = new Ask(id, a.getString("town", ""), Kind.valueOf(a.getString("kind", "FOUND")), a.getString("text", ""),
                        Keys.deser(a.getString("at")), worker, a.getString("workplace"), a.getLong("created"), a.getString("data"));
                ask.seen = a.getBoolean("seen");
                asks.add(ask);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    static void save() {
        if (file == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("declined", new ArrayList<>(declined));
        for (Ask a : asks) {
            ConfigurationSection s = y.createSection("asks." + a.id);
            s.set("town", a.town);
            s.set("kind", a.kind.name());
            s.set("text", a.text);
            if (a.at != null) s.set("at", Keys.ser(a.at));
            if (a.worker != null) s.set("worker", a.worker.toString());
            if (a.workplace != null) s.set("workplace", a.workplace);
            s.set("created", a.created);
            if (a.data != null) s.set("data", a.data);
            s.set("seen", a.seen);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            Bukkit.getLogger().warning("[Settlement] Could not save requests.yml: " + ex.getMessage());
        }
    }

    static boolean isMaterial(Block b, Material m) {
        return b.getType() == m;
    }
}
