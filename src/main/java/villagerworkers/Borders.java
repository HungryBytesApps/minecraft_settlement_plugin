package villagerworkers;

import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Shows a settlement's border (orange circle) and its work areas (green squares) as walls of
 * particles, only for that player, visible from far away. Per player: always on, off, or only
 * while holding the ledger (saved on the player). "Show work area" shows them for 10 seconds anyway.
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

    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(255, 120, 30), 2.2f);
    private static final Particle.DustOptions WORK = new Particle.DustOptions(Color.fromRGB(80, 230, 60), 1.8f);
    private static final double[] HEIGHTS = {0.3, 1.3, 2.3, 3.3};
    private static final double[] WORK_HEIGHTS = {0.3, 1.3, 2.3};

    private final java.util.Map<java.util.UUID, Integer> flashUntil = new java.util.HashMap<>();

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
        return next;
    }

    /** Show the walls for 10 seconds, whatever the setting ("Show work area"). */
    void flash(Player p) {
        flashUntil.put(p.getUniqueId(), Bukkit.getCurrentTick() + 200);
        tick();
    }

    /** Every half second: draw the walls for whoever should see them. */
    void tick() {
        int now = Bukkit.getCurrentTick();
        flashUntil.values().removeIf(end -> now > end);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Mode m = mode(p);
            boolean flashing = flashUntil.containsKey(p.getUniqueId());
            if (!flashing) {
                if (m == Mode.OFF) continue;
                if (m == Mode.LEDGER && !Ledger.is(p.getInventory().getItemInMainHand())) continue;
            }
            Town t = nearest(p);
            if (t == null) continue;
            draw(p, t);
            for (Town.Workplace wp : t.workplaces.values()) {
                if (wp.loc.getWorld() == p.getWorld() && wp.loc.distanceSquared(p.getLocation()) <= 96 * 96) drawWork(p, wp);
            }
        }
    }

    /** Square wall around a work area (the blocks within the radius of the composter). */
    private void drawWork(Player p, Town.Workplace wp) {
        World w = wp.loc.getWorld();
        int r = wp.radius;
        double x0 = wp.loc.getBlockX() - r, x1 = wp.loc.getBlockX() + r + 1;
        double z0 = wp.loc.getBlockZ() - r, z1 = wp.loc.getBlockZ() + r + 1;
        ParticleBuilder pb = new ParticleBuilder(Particle.DUST).data(WORK).receivers(p).force(true).count(1).offset(0, 0, 0);
        for (double d = 0; d <= 2 * r + 1; d += 1) {
            post(w, pb, x0 + d, z0);
            post(w, pb, x0 + d, z1);
            post(w, pb, x0, z0 + d);
            post(w, pb, x1, z0 + d);
        }
    }

    private static void post(World w, ParticleBuilder pb, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return;
        double ground = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        for (double h : WORK_HEIGHTS) pb.location(new Location(w, x, ground + h, z)).spawn();
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

    private void draw(Player p, Town t) {
        World w = t.bell.getWorld();
        int r = plugin.settings().townRadius();
        double cx = t.bell.getBlockX() + 0.5, cz = t.bell.getBlockZ() + 0.5;
        int points = (int) Math.ceil(2 * Math.PI * r);
        ParticleBuilder pb = new ParticleBuilder(Particle.DUST).data(DUST).receivers(p).force(true).count(1).offset(0, 0, 0);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            double ground = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            for (double h : HEIGHTS) pb.location(new Location(w, x, ground + h, z)).spawn();
        }
    }
}
