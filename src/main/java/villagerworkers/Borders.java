package villagerworkers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Shows a settlement's border (orange band) and work areas (green bands) to one player only.
 * The bands are a few see-through colored panels (text displays), not particles: they don't
 * depend on the resource pack, are sent once and cost nothing per tick.
 * Per player: always on, off, or only while holding the ledger. "Show work area" shows them for 10 seconds.
 */
final class Borders {
    enum Mode {
        LEDGER("When holding ledger"), ON("On"), OFF("Off");

        final String label;

        Mode(String label) {
            this.label = label;
        }

        Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final Color BORDER = Color.fromARGB(80, 255, 120, 30);
    private static final Color WORK = Color.fromARGB(80, 80, 230, 60);
    private static final double BORDER_HEIGHT = 1.5, WORK_HEIGHT = 0.75;
    private static final double SEGMENT = 2.0; // blocks per panel

    /** What a player currently sees: the state it was built from, and the panels. */
    private static final class Shown {
        final String state;
        final List<TextDisplay> panels = new ArrayList<>();
        boolean partial; // some chunks weren't loaded
        int builtAt;

        Shown(String state) {
            this.state = state;
        }
    }

    private final Map<UUID, Integer> flashUntil = new HashMap<>();
    private final Map<UUID, Shown> shown = new HashMap<>();

    private final VillagerWorkersPlugin plugin;
    private final NamespacedKey key;

    Borders(VillagerWorkersPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "border_mode");
    }

    Mode mode(Player p) {
        String s = p.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        try {
            return s == null ? Mode.LEDGER : Mode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Mode.LEDGER;
        }
    }

    /** Cycle: When holding ledger → On → Off → … */
    Mode cycle(Player p) {
        Mode next = mode(p).next();
        p.getPersistentDataContainer().set(key, PersistentDataType.STRING, next.name());
        tick();
        return next;
    }

    /** Show the bands for 10 seconds, whatever the setting ("Show work area"). */
    void flash(Player p) {
        flashUntil.put(p.getUniqueId(), Bukkit.getCurrentTick() + 200);
        Shown s = shown.remove(p.getUniqueId());
        if (s != null) remove(s); // rebuild: the work area may have changed
        tick();
    }

    /** Every half second: show or hide the bands for each player. */
    void tick() {
        int now = Bukkit.getCurrentTick();
        flashUntil.values().removeIf(end -> now > end);
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
            Town t = wanted(p);
            String state = t == null ? null : state(t);
            Shown cur = shown.get(p.getUniqueId());
            boolean refresh = cur != null && cur.partial && now - cur.builtAt > 200;
            if (cur != null && !refresh && Objects.equals(cur.state, state)) continue;
            if (cur != null) {
                remove(cur);
                shown.remove(p.getUniqueId());
            }
            if (t != null) shown.put(p.getUniqueId(), build(p, t, state));
        }
        shown.entrySet().removeIf(e -> {
            if (online.contains(e.getKey())) return false;
            remove(e.getValue());
            return true;
        });
    }

    void clearAll() {
        for (Shown s : shown.values()) remove(s);
        shown.clear();
    }

    private Town wanted(Player p) {
        if (!flashUntil.containsKey(p.getUniqueId())) {
            Mode m = mode(p);
            if (m == Mode.OFF) return null;
            if (m == Mode.LEDGER && !Ledger.is(p.getInventory().getItemInMainHand())) return null;
        }
        return nearest(p);
    }

    /** Changes when the border or a work area changes. */
    private String state(Town t) {
        StringBuilder sb = new StringBuilder(t.id).append('|').append(plugin.settings().townRadius());
        for (Town.Workplace wp : t.workplaces.values()) sb.append('|').append(Keys.ser(wp.loc)).append(':').append(wp.radius);
        return sb.toString();
    }

    private Town nearest(Player p) {
        double reach = plugin.settings().townRadius() + 96;
        Town best = null;
        double bestD = reach * reach;
        for (Town t : plugin.towns().all()) {
            if (t.bell.getWorld() != p.getWorld()) continue;
            double d = t.bell.distanceSquared(p.getLocation());
            if (d <= bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    private Shown build(Player p, Town t, String state) {
        Shown s = new Shown(state);
        s.builtAt = Bukkit.getCurrentTick();
        World w = t.bell.getWorld();
        // Settlement border: a ring of panels facing outwards.
        int r = plugin.settings().townRadius();
        double cx = t.bell.getBlockX() + 0.5, cz = t.bell.getBlockZ() + 0.5;
        int n = Math.max(12, (int) Math.ceil(2 * Math.PI * r / SEGMENT));
        double width = 2 * Math.PI * r / n * 1.04;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * (i + 0.5) / n;
            float yaw = (float) Math.toDegrees(a) - 90f;
            panel(p, s, w, cx + Math.cos(a) * r, cz + Math.sin(a) * r, yaw, width, BORDER_HEIGHT, BORDER);
        }
        // Work areas: a square around each workplace.
        for (Town.Workplace wp : t.workplaces.values()) {
            if (wp.loc.getWorld() != w) continue;
            int wr = wp.radius;
            double x0 = wp.loc.getBlockX() - wr, x1 = wp.loc.getBlockX() + wr + 1;
            double z0 = wp.loc.getBlockZ() - wr, z1 = wp.loc.getBlockZ() + wr + 1;
            double side = x1 - x0;
            int parts = Math.max(1, (int) Math.ceil(side / SEGMENT));
            double len = side / parts;
            for (int i = 0; i < parts; i++) {
                double mid = (i + 0.5) * len;
                panel(p, s, w, x0 + mid, z0, 0f, len * 1.02, WORK_HEIGHT, WORK);   // north side
                panel(p, s, w, x0 + mid, z1, 0f, len * 1.02, WORK_HEIGHT, WORK);   // south side
                panel(p, s, w, x0, z0 + mid, 90f, len * 1.02, WORK_HEIGHT, WORK);  // west side
                panel(p, s, w, x1, z0 + mid, 90f, len * 1.02, WORK_HEIGHT, WORK);  // east side
            }
        }
        return s;
    }

    /** One see-through colored panel standing on the ground, only visible to this player. */
    private void panel(Player p, Shown s, World w, double x, double z, float yaw, double width, double height, Color color) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
            s.partial = true;
            return;
        }
        double ground = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        Location at = new Location(w, x, ground, z, yaw, 0f);
        TextDisplay d = w.spawn(at, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            Keys.markTemp(e);
            e.text(Component.text(" "));
            e.setBackgroundColor(color);
            e.setDefaultBackground(false);
            e.setShadowed(false);
            e.setBillboard(Display.Billboard.FIXED);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(4f);
            // A single space has a background of 1/8 x 1/4 block: scale it up to the panel size.
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f((float) (width * 8), (float) (height * 4), 1f), new AxisAngle4f()));
        });
        p.showEntity(plugin, d);
        s.panels.add(d);
    }

    private static void remove(Shown s) {
        for (TextDisplay d : s.panels) if (d.isValid()) d.remove();
        s.panels.clear();
    }
}
