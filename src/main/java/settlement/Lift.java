package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;

/**
 * The Elevator plugin (if it's there): settlers call it like a button (same queue and countdown), ride it, and
 * step off. An elevator without rails first; one with rails (for minecarts) only in an emergency.
 */
final class Lift {
    private Lift() {}

    private static Method near, floors, platform, rails, busy, call;
    private static Plugin owner;

    private static boolean ready() {
        Plugin p = Bukkit.getPluginManager().getPlugin("Elevator");
        if (p == null || !p.isEnabled()) return false;
        if (p == owner && call != null) return true;
        try {
            Class<?> api = Class.forName("elevator.ElevatorApi", true, p.getClass().getClassLoader());
            near = api.getMethod("near", World.class, int.class, int.class, int.class);
            floors = api.getMethod("floors", String.class);
            platform = api.getMethod("platform", String.class);
            rails = api.getMethod("rails", String.class);
            busy = api.getMethod("busy", String.class);
            call = api.getMethod("call", String.class, int.class);
            owner = p;
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            call = null;
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> near(World w, int x, int z, int r) {
        if (!ready()) return List.of();
        try {
            return (List<String>) near.invoke(null, w, x, z, r);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return List.of();
        }
    }

    static int[] floors(String id) {
        if (!ready()) return new int[0];
        try {
            return (int[]) floors.invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return new int[0];
        }
    }

    /** minX, minZ, maxX, maxZ, platform y now (Integer.MIN_VALUE while moving); null: unknown. */
    static int[] platform(String id) {
        if (!ready()) return null;
        try {
            return (int[]) platform.invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    static boolean rails(String id) {
        if (!ready()) return false;
        try {
            return (Boolean) rails.invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    static boolean busy(String id) {
        if (!ready()) return true;
        try {
            return (Boolean) busy.invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;
        }
    }

    static boolean call(String id, int y) {
        if (!ready()) return false;
        try {
            return (Boolean) call.invoke(null, id, y);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /** Elevators near a spot: without rails first (rails only in an emergency). */
    static List<String> pick(World w, Location at, int r, boolean emergency) {
        List<String> out = new ArrayList<>(near(w, at.getBlockX(), at.getBlockZ(), r));
        out.removeIf(id -> rails(id) && !emergency);
        out.sort(Comparator.comparing(Lift::rails));
        return out;
    }

    /** Where to stand next to the platform at a floor (feet at floor + 1), nearest to {@code near}. */
    static Location boarding(World w, String id, int floorY, Location near) {
        int[] p = platform(id);
        if (p == null) return null;
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = p[0] - 1; x <= p[2] + 1; x++) {
            for (int z = p[1] - 1; z <= p[3] + 1; z++) {
                boolean inside = x >= p[0] && x <= p[2] && z >= p[1] && z <= p[3];
                if (inside) continue;
                if (!PathFinder.walkable(w, x, floorY + 1, z)) continue;
                Location l = new Location(w, x + 0.5, floorY + 1, z + 0.5);
                double d = near == null ? 0 : l.distanceSquared(near);
                if (d < bestD) {
                    bestD = d;
                    best = l;
                }
            }
        }
        return best;
    }

    static Location center(World w, String id, int floorY) {
        int[] p = platform(id);
        if (p == null) return null;
        return new Location(w, (p[0] + p[2] + 1) / 2.0, floorY + 1, (p[1] + p[3] + 1) / 2.0);
    }

    /** The floor nearest a height. */
    static int nearestFloor(String id, int y) {
        int best = Integer.MIN_VALUE;
        for (int f : floors(id)) if (best == Integer.MIN_VALUE || Math.abs(f + 1 - y) < Math.abs(best + 1 - y)) best = f;
        return best;
    }

    // ---- For the miner ----

    /**
     * A mine that starts at an elevator's lowest floor (when it goes down far enough): {ox,oy,oz,dir,id,surface}
     * as text, or null.
     */
    static String[] mineStart(Worker w, Town t) {
        World world = t.bell.getWorld();
        int r = w.plugin.settings().townRadius();
        for (String id : pick(world, t.bell, r, false)) {
            int[] f = floors(id);
            if (f.length < 2) continue;
            int low = f[0], top = f[f.length - 1];
            if (top - low < 12) continue; // doesn't really go down
            Location board = boarding(world, id, low, null);
            if (board == null) continue;
            int[] p = platform(id);
            double cx = (p[0] + p[2] + 1) / 2.0, cz = (p[1] + p[3] + 1) / 2.0;
            double ax = board.getX() - cx, az = board.getZ() - cz;
            int dir = Math.abs(ax) > Math.abs(az) ? (ax > 0 ? 3 : 1) : (az > 0 ? 0 : 2);
            int fx = Mine.DIRS[dir][0], fz = Mine.DIRS[dir][1], sx = -fz, sz = fx;
            // entrance (t=-1, s=1) is the spot next to the platform
            int ox = board.getBlockX() + fx - sx, oz = board.getBlockZ() + fz - sz;
            // (the id has commas itself: written with ; here)
            String data = ox + "," + (low + 1) + "," + oz + "," + dir + "," + id.replace(',', ';') + "," + (top + 1);
            if (Asks.declined(t, "site:" + data)) continue; // you said no to this one
            return new String[]{data};
        }
        return null;
    }

    /** After a ride that went wrong: not again for a minute (it walks or waits instead). */
    private static final Map<UUID, Integer> failedUntil = new HashMap<>();

    private static boolean resting(Worker w) {
        Integer u = failedUntil.get(w.body.getUniqueId());
        return u != null && Bukkit.getCurrentTick() < u;
    }

    /** Down: the elevator from up top to the mine's start, or straight to a level it has a floor at (connected). */
    /** The mine's elevator by its id; gone (rebuilt, or registered again under another id): the one at the mine's start. */
    static String resolve(Mine m) {
        if (m.elevator == null || platform(m.elevator) != null) return m.elevator;
        Location e = m.entrance();
        List<String> ids = near(m.world, e.getBlockX(), e.getBlockZ(), 4);
        if (!ids.isEmpty()) {
            m.elevator = ids.get(0);
            Mine.changed();
        }
        return m.elevator;
    }

    static Activity down(Worker w, Mine m, int level, boolean emergency) {
        if (resting(w)) return null;
        resolve(m);
        Location here = w.body.getLocation();
        if (level >= 0 && m.levels.containsKey(level)) {
            for (String id : pick(m.world, m.entrance(), 160, emergency)) {
                int floor = m.landingY(level) - 1;
                if (!has(id, floor) || !connected(w, m, id, floor, level)) continue;
                int from = nearestFloor(id, here.getBlockY());
                if (from == floor) continue;
                return new Ride(w, id, from, floor, m.hub(level));
            }
        }
        if (m.elevator != null && here.getY() > m.oy + 2) {
            int from = nearestFloor(m.elevator, here.getBlockY());
            if (from != m.oy - 1 && from != Integer.MIN_VALUE) return new Ride(w, m.elevator, from, m.oy - 1, m.entrance());
        }
        return null;
    }

    /** Up from a level that has an elevator floor (connected), to the top floor. */
    static Activity up(Worker w, Mine m, int level, Location here, boolean emergency) {
        if (resting(w)) return null;
        if (level < 0 || !m.levels.containsKey(level)) return null;
        int floor = m.landingY(level) - 1;
        for (String id : pick(m.world, m.entrance(), 160, emergency)) {
            int[] f = floors(id);
            if (f.length == 0 || !has(id, floor) || !connected(w, m, id, floor, level)) continue;
            return new Ride(w, id, floor, f[f.length - 1], null);
        }
        return null;
    }

    /** From the mine's start (the elevator's lowest floor) up to its top floor. */
    static Activity fromStart(Worker w, Mine m, boolean emergency) {
        if (m.elevator == null || resting(w)) return null;
        resolve(m);
        int[] f = floors(m.elevator);
        if (f.length == 0) return null;
        return new Ride(w, m.elevator, m.oy - 1, f[f.length - 1], null);
    }

    private static boolean has(String id, int floor) {
        for (int f : floors(id)) if (f == floor) return true;
        return false;
    }

    private static final Map<String, Integer> linkedUntil = new HashMap<>();

    static void cleanup() {
        int now = Bukkit.getCurrentTick();
        failedUntil.values().removeIf(u -> u < now);
        linkedUntil.entrySet().removeIf(e -> {
            if (e.getValue() >= now) return false;
            linked.remove(e.getKey());
            return true;
        });
    }
    private static final Map<String, Boolean> linked = new HashMap<>();

    /** Can it walk from that floor to the level's hub? (checked every 5 minutes) */
    private static boolean connected(Worker w, Mine m, String id, int floor, int level) {
        String key = id + ":" + floor + ":" + m.key + ":" + level;
        int now = Bukkit.getCurrentTick();
        Integer until = linkedUntil.get(key);
        if (until != null && now < until) return linked.getOrDefault(key, false);
        Location b = boarding(m.world, id, floor, m.hub(level));
        boolean ok = b != null && PathFinder.find(b, m.hub(level), 2.0) != null;
        linked.put(key, ok);
        linkedUntil.put(key, now + 6000);
        return ok;
    }

    /** Walk to the platform's floor, call it, step on, ride, step off. */
    static final class Ride implements Activity {
        private final Worker w;
        private final String id;
        private final int from, to;
        private final Location toward; // where it's going after (to pick the side to step off), or null
        private int phase, time, calledAt = -1000;
        private Location board, exit;
        private boolean arrived;

        Ride(Worker w, String id, int from, int to, Location toward) {
            this.w = w;
            this.id = id;
            this.from = from;
            this.to = to;
            this.toward = toward;
        }

        @Override
        public boolean tick() {
            World world = w.body.getWorld();
            if (++time > 2400) return true; // something's wrong: give up (walks instead)
            int[] p = platform(id);
            if (p == null) return true;
            int now = Bukkit.getCurrentTick();
            switch (phase) {
                case 0 -> { // walk to the platform at "from"
                    board = boarding(world, id, from, w.body.getLocation());
                    if (board == null) return true;
                    if (!w.mover.go(board, 1.0, w.plugin.settings().workSpeed())) return true;
                    phase = 1;
                }
                case 1 -> {
                    if (w.mover.moving()) return false;
                    if (w.body.getLocation().distanceSquared(board) > 2.5 * 2.5) return true;
                    phase = 2;
                }
                case 2 -> { // the platform here?
                    if (p[4] == from && !busy(id)) {
                        Location c = center(world, id, from);
                        w.mover.go(c, 0.6, w.plugin.settings().workSpeed());
                        phase = 3;
                    } else if (now - calledAt > 200 && p[4] != from) {
                        calledAt = now;
                        call(id, from);
                        w.workStatus = "Waiting for the elevator";
                    }
                }
                case 3 -> { // on the platform?
                    Location l = w.body.getLocation();
                    boolean on = l.getBlockX() >= p[0] && l.getBlockX() <= p[2] && l.getBlockZ() >= p[1] && l.getBlockZ() <= p[3];
                    if (on && !w.mover.moving()) {
                        call(id, to);
                        calledAt = now;
                        phase = 4;
                    } else if (!w.mover.moving() && !on) {
                        phase = 2; // it left without us
                    }
                }
                case 4 -> { // riding: stand still
                    w.mover.stop();
                    if (p[4] == to && !busy(id)) {
                        exit = boarding(world, id, to, toward != null ? toward : w.plugin.towns().get(w.townId) != null ? w.plugin.towns().get(w.townId).bell : null);
                        if (exit == null) return true;
                        if (!w.mover.go(exit, 1.0, w.plugin.settings().workSpeed())) w.body.teleport(exit); // just step off
                        arrived = true;
                        phase = 5;
                    } else if (p[4] != Integer.MIN_VALUE && p[4] != to && !busy(id) && now - calledAt > 100) {
                        call(id, to);
                        calledAt = now;
                    }
                }
                case 5 -> {
                    if (!w.mover.moving()) {
                        if (w.body.getLocation().distanceSquared(exit) > 2 * 2) w.body.teleport(exit); // still on the platform: step off
                        return true;
                    }
                }
                default -> {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void stop() {
            w.mover.stop();
            if (!arrived) failedUntil.put(w.body.getUniqueId(), Bukkit.getCurrentTick() + 1200);
        }

        @Override
        public String label() {
            return phase >= 4 ? "Riding the elevator" : "Going to the elevator";
        }
    }
}
