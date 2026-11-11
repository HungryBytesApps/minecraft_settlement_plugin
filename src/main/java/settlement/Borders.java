package settlement;

import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shows a settlement's border and its work areas with particles (type set in the config), only
 * for that player. Kept light: a point every 1.5 blocks, two rows, only near the player.
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

    private static final double STEP = 0.6;               // close together: a real line
    private static final double[] HEIGHTS = {0.3};
    private static final double[] WORK_HEIGHTS = {0.4};
    private static final double VIEW = 64;

    private final Map<UUID, Integer> flashUntil = new HashMap<>();

    private final SettlementPlugin plugin;
    private final NamespacedKey key;

    Borders(SettlementPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(Keys.NS, "border_mode");
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
        return next;
    }

    /** Show the border for 10 seconds, whatever the setting ("Show work area"). */
    void flash(Player p) {
        flashUntil.put(p.getUniqueId(), Bukkit.getCurrentTick() + 200);
        tick();
    }

    void clearAll() {
        flashUntil.clear();
    }

    /** A particle from the config, or the fallback (only particles that need no extra data). */
    static Particle particle(String name, Particle fallback) {
        try {
            Particle p = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            return p.getDataType() == Void.class ? p : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Every half second: draw for whoever should see it. */
    void tick() {
        int now = Bukkit.getCurrentTick();
        flashUntil.values().removeIf(end -> now > end);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!flashUntil.containsKey(p.getUniqueId())) {
                Mode m = mode(p);
                if (m == Mode.OFF) continue;
                if (m == Mode.LEDGER && !Ledger.is(p.getInventory().getItemInMainHand())) continue;
            }
            Town t = nearest(p);
            if (t == null) continue;
            draw(p, t);
            for (Town.Workplace wp : t.workplaces.values()) {
                // (workplaces work in the whole settlement: no separate work areas to show)
            }
        }
    }

    private void draw(Player p, Town t) {
        World w = t.bell.getWorld();
        ParticleBuilder pb = new ParticleBuilder(plugin.settings().borderParticle()).receivers(p).force(true).count(1).offset(0, 0, 0).extra(0);
        Location me = p.getLocation();
        // The outline of all squares together: every side, except the parts inside another square.
        java.util.List<int[]> squares = t.squares();
        for (int i = 0; i < squares.size(); i++) {
            int[] q = squares.get(i);
            double cx = q[0] + 0.5, cz = q[1] + 0.5, r = q[2];
            double x0 = cx - r - 0.5, x1 = cx + r + 0.5, z0 = cz - r - 0.5, z1 = cz + r + 0.5;
            for (double d = 0; d <= x1 - x0; d += STEP) {
                edge(w, pb, x0 + d, z0, me, squares, i);
                edge(w, pb, x0 + d, z1, me, squares, i);
                edge(w, pb, x0, z0 + d, me, squares, i);
                edge(w, pb, x1, z0 + d, me, squares, i);
            }
        }
    }

    private static void edge(World w, ParticleBuilder pb, double x, double z, Location me, java.util.List<int[]> squares, int own) {
        for (int j = 0; j < squares.size(); j++) {
            if (j == own) continue;
            int[] o = squares.get(j);
            if (Math.abs(x - (o[0] + 0.5)) < o[2] + 0.45 && Math.abs(z - (o[1] + 0.5)) < o[2] + 0.45) return; // inside another square
        }
        post(w, pb, x, z, me, HEIGHTS);
    }

    /** Square around a work area (the blocks within the radius of the workplace). */
    private void drawWork(Player p, Town.Workplace wp) {
        World w = wp.loc.getWorld();
        int r = wp.radius;
        double x0 = wp.loc.getBlockX() - r, x1 = wp.loc.getBlockX() + r + 1;
        double z0 = wp.loc.getBlockZ() - r, z1 = wp.loc.getBlockZ() + r + 1;
        ParticleBuilder pb = new ParticleBuilder(plugin.settings().workParticle()).receivers(p).force(true).count(1).offset(0, 0, 0).extra(0);
        Location me = p.getLocation();
        for (double d = 0; d <= 2 * r + 1; d += 1) {
            post(w, pb, x0 + d, z0, me, WORK_HEIGHTS);
            post(w, pb, x0 + d, z1, me, WORK_HEIGHTS);
            post(w, pb, x0, z0 + d, me, WORK_HEIGHTS);
            post(w, pb, x1, z0 + d, me, WORK_HEIGHTS);
        }
    }

    private static void post(World w, ParticleBuilder pb, double x, double z, Location me, double[] heights) {
        double dx = x - me.getX(), dz = z - me.getZ();
        if (dx * dx + dz * dz > VIEW * VIEW) return; // too far away to see anyway
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return;
        double ground = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        for (double h : heights) pb.location(new Location(w, x, ground + h, z)).spawn();
    }

    private Town nearest(Player p) {
        Town best = null;
        double bestD = Double.MAX_VALUE;
        for (Town t : plugin.towns().all()) {
            if (t.bell.getWorld() != p.getWorld() || !t.inside(p.getLocation().getX(), p.getLocation().getZ(), VIEW)) continue;
            double d = t.bell.distanceSquared(p.getLocation());
            if (d <= bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }
}
