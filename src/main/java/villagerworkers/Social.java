package villagerworkers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Free time: walks, chats, music, reading, sitting, visiting someone at work, and in the evening
 * getting together at the gathering spot. Also: walking to the settlement / following the owner.
 */
final class Social {
    private Social() {}

    /** Something to do during the day when there's no work. */
    static Activity idle(Worker w) {
        int r = w.random.nextInt(100);
        if (r < 30) {
            Activity talk = Chat.start(w);
            if (talk != null) return talk;
        } else if (r < 42) {
            Activity band = Music.start(w);
            if (band != null) return band;
        } else if (r < 54) {
            Block seat = Seats.nearestFree(w.body.getLocation(), 24, w);
            if (seat != null && w.town() != null && w.town().contains(seat.getLocation(), w.plugin.settings().socialRadius() + 8)) {
                return new Sit(w, seat, w.random.nextBoolean() ? Mode.READ : Mode.IDLE, 300 + w.random.nextInt(400), null);
            }
        } else if (r < 61) {
            return new Read(w, 200 + w.random.nextInt(200));
        } else if (r < 71) {
            Worker busy = someoneWorking(w);
            if (busy != null) return new Visit(w, busy);
        } else if (r < 83) {
            Activity p = Potter.start(w);
            if (p != null) return p;
        }
        Activity walk = stroll(w);
        if (walk != null) return walk;
        return new LookAround(w, 40 + w.random.nextInt(60));
    }

    /** In the evening: get together with the others at the gathering spot. */
    static Activity evening(Worker w, Town t) {
        Location spot = Seats.gatheringSpot(t, w.plugin.settings().townRadius());
        Block seat = Seats.nearestFree(spot, 8, w);
        if (seat != null) return new Sit(w, seat, Mode.SOCIAL, Integer.MAX_VALUE, spot);
        return new Hangout(w, spot);
    }

    private static Worker partner(Worker w) {
        List<Worker> options = new ArrayList<>();
        Location me = w.body.getLocation();
        for (Worker o : w.plugin.workers().all()) {
            if (o == w || o.body.getWorld() != me.getWorld() || !o.available() || o.body.isInsideVehicle()) continue;
            if (o.body.getLocation().distanceSquared(me) <= 20 * 20) options.add(o);
        }
        return options.isEmpty() ? null : options.get(w.random.nextInt(options.size()));
    }

    private static Worker someoneWorking(Worker w) {
        List<Worker> options = new ArrayList<>();
        Location me = w.body.getLocation();
        for (Worker o : w.plugin.workers().all()) {
            if (o == w || o.job() == Worker.Job.NONE || o.body.getWorld() != me.getWorld()) continue;
            if (o.body.getLocation().distanceSquared(me) <= 32 * 32) options.add(o);
        }
        return options.isEmpty() ? null : options.get(w.random.nextInt(options.size()));
    }

    /** Someone says something (speech bubble) and gestures. */
    static void say(Worker w, Location lookAt) {
        List<String> lines = w.plugin.settings().chatLines();
        if (lookAt != null) w.anim.look(lookAt);
        String line = lines.get(w.random.nextInt(lines.size()));
        Bubbles.say(w.plugin, w.body, line, 50);
        Voice.speak(w, line);
        if (w.random.nextInt(4) == 0) w.body.swingMainHand();
        else if (w.random.nextBoolean()) w.anim.nod();
    }

    /** The nearest other settler within 8 blocks (to look at while talking). */
    static Location nearestOther(Worker w) {
        Location me = w.body.getLocation();
        Location best = null;
        double bestD = 64;
        for (Worker o : w.plugin.workers().all()) {
            if (o == w || o.body.getWorld() != me.getWorld()) continue;
            double d = o.body.getLocation().distanceSquared(me);
            if (d < bestD) {
                bestD = d;
                best = o.body.getEyeLocation();
            }
        }
        return best;
    }

    static float yaw(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

    // ---- Walk ----

    static Activity stroll(Worker w) {
        Location c = w.center();
        World world = c.getWorld();
        if (world == null || world != w.body.getWorld()) return null;
        int r = w.plugin.settings().socialRadius();
        for (int tries = 0; tries < 12; tries++) {
            int x = c.getBlockX() + w.random.nextInt(r * 2 + 1) - r;
            int z = c.getBlockZ() + w.random.nextInt(r * 2 + 1) - r;
            for (int y = c.getBlockY() + 4; y >= c.getBlockY() - 6; y--) {
                if (PathFinder.walkable(world, x, y, z)) return new Stroll(w, new Location(world, x + 0.5, y, z + 0.5));
            }
        }
        return null;
    }

    private static final class Stroll implements Activity {
        private final Worker w;
        private final Location to;
        private boolean started;
        private int time, pause = -1;

        Stroll(Worker w, Location to) {
            this.w = w;
            this.to = to;
        }

        @Override
        public boolean tick() {
            if (!started) {
                started = true;
                if (!w.mover.go(to, 0.8, w.plugin.settings().walkSpeed())) return true;
            }
            if (pause >= 0) {
                if (pause % 30 == 0) LookAround.glance(w);
                return --pause <= 0;
            }
            if (w.mover.arrived()) pause = 40 + w.random.nextInt(80);
            return w.mover.failed() || ++time > 600;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return "Taking a walk";
        }

        @Override
        public boolean freeTime() {
            return true;
        }

        @Override
        public boolean interruptible() {
            return true;
        }
    }

    // ---- Look around ----

    static final class LookAround implements Activity {
        private final Worker w;
        private int left;

        LookAround(Worker w, int ticks) {
            this.w = w;
            this.left = ticks;
        }

        static void glance(Worker w) {
            w.anim.glance();
        }

        @Override
        public boolean tick() {
            if (left % 30 == 0 && w.random.nextBoolean()) glance(w);
            return --left <= 0;
        }

        @Override
        public String label() {
            return "Looking around";
        }

        @Override
        public boolean freeTime() {
            return true;
        }

        @Override
        public boolean interruptible() {
            return true;
        }
    }

    // ---- Read (standing) ----

    private static final class Read implements Activity {
        private final Worker w;
        private int left;
        private boolean started;

        Read(Worker w, int ticks) {
            this.w = w;
            this.left = ticks;
        }

        @Override
        public boolean tick() {
            if (!started) {
                started = true;
                w.hold(Material.BOOK);
            }
            readTick(w, left);
            return --left <= 0;
        }

        @Override
        public void stop() {
            w.holdTool();
        }

        @Override
        public String label() {
            return "Reading a book";
        }

        @Override
        public boolean freeTime() {
            return true;
        }

        @Override
        public boolean interruptible() {
            return true;
        }
    }

    /** Head down, a page turn now and then. */
    static void readTick(Worker w, int t) {
        if (t % 20 == 0) w.anim.lookDown(35 + w.random.nextInt(10));
        if (t % 80 == 40 && w.random.nextBoolean())
            w.body.getWorld().playSound(w.body.getLocation(), "minecraft:item.book.page_turn", org.bukkit.SoundCategory.NEUTRAL, 0.5f, 1f);
        if (t % 70 == 0 && w.random.nextBoolean()) {
            w.body.getWorld().playSound(w.body.getLocation(), "minecraft:item.book.page_turn", SoundCategory.NEUTRAL, 0.5f, 1f);
        }
    }

    // ---- Sit ----

    enum Mode { IDLE, READ, SOCIAL }

    /** Walk to a seat and sit: just relaxing, reading, or (in the evening) hanging out with the group. */
    private static final class Sit implements Activity {
        private final Worker w;
        private final Block seat;
        private final Mode mode;
        private final Location lookAt;
        private int left, phase, time, next;
        private ArmorStand stand;
        private Music.Instrument playing;
        private int playUntil, readUntil;

        Sit(Worker w, Block seat, Mode mode, int ticks, Location lookAt) {
            this.w = w;
            this.seat = seat;
            this.mode = mode;
            this.left = ticks;
            this.lookAt = lookAt;
        }

        @Override
        public boolean tick() {
            if (phase == 0) {
                phase = 1;
                if (!w.mover.go(seat.getLocation().add(0.5, 1, 0.5), 1.3, w.plugin.settings().walkSpeed())) {
                    w.skip(seat, 1200);
                    return true;
                }
            }
            if (phase == 1) {
                if (mode == Mode.SOCIAL && !w.isNight()) return true;
                if (w.mover.failed() || ++time > 600) {
                    w.skip(seat, 1200);
                    return true;
                }
                if (!w.mover.arrived()) return false;
                stand = Seats.sit(w, seat, lookAt);
                if (stand == null) {
                    w.skip(seat, 600);
                    return true;
                }
                if (mode == Mode.READ) w.hold(Material.BOOK);
                phase = 2;
                next = 40 + w.random.nextInt(80);
            }
            if (stand == null || !stand.isValid() || !w.body.isInsideVehicle()) return true;
            time++;
            switch (mode) {
                case READ -> readTick(w, time);
                case IDLE -> {
                    if (time % 50 == 0 && w.random.nextInt(3) == 0) LookAround.glance(w);
                }
                case SOCIAL -> {
                    if (!w.isNight()) return true;
                    socialTick();
                }
            }
            return --left <= 0;
        }

        /** Evening: talk, play some music, read a bit, look around. */
        private void socialTick() {
            if (playing != null) {
                if (time >= playUntil) {
                    playing = null;
                    w.holdTool();
                } else if (time % (6 + w.random.nextInt(4)) == 0) {
                    Music.note(w, playing);
                }
                return;
            }
            if (readUntil > 0) {
                if (time >= readUntil) {
                    readUntil = 0;
                    w.holdTool();
                } else {
                    readTick(w, time);
                }
                return;
            }
            if (time < next) return;
            next = time + 80 + w.random.nextInt(140);
            int r = w.random.nextInt(100);
            if (r < 55) {
                say(w, nearestOther(w));
            } else if (r < 72) {
                playing = Music.random(w.random);
                w.hold(playing.item());
                playUntil = time + 100 + w.random.nextInt(80);
            } else if (r < 85) {
                w.hold(Material.BOOK);
                readUntil = time + 200;
            } else {
                Location other = nearestOther(w);
                if (other != null) w.anim.look(other);
                else LookAround.glance(w);
            }
        }

        @Override
        public void stop() {
            w.mover.stop();
            if (stand != null) Seats.standUp(w, seat, stand);
            w.holdTool();
        }

        @Override
        public String label() {
            if (phase < 2) return mode == Mode.SOCIAL ? "Going to meet the others" : "Looking for a place to sit";
            return switch (mode) {
                case READ -> "Reading on a bench";
                case IDLE -> "Sitting down";
                case SOCIAL -> playing != null ? "Playing music with the others" : "Hanging out with the others";
            };
        }

        @Override
        public boolean freeTime() {
            return true;
        }

        @Override
        public boolean interruptible() {
            return mode != Mode.SOCIAL && phase < 2;
        }
    }

    /** Evening without a free seat: stand around the gathering spot with the others. */
    private static final class Hangout implements Activity {
        private final Worker w;
        private final Location spot;
        private int phase, time, next;

        Hangout(Worker w, Location spot) {
            this.w = w;
            this.spot = spot;
        }

        @Override
        public boolean tick() {
            if (!w.isNight()) return true;
            if (phase == 0) {
                phase = 1;
                double a = w.random.nextDouble() * Math.PI * 2;
                Location around = spot.clone().add(Math.cos(a) * 2.5, 0, Math.sin(a) * 2.5);
                if (!w.mover.go(around, 1.5, w.plugin.settings().walkSpeed())) return true;
            }
            if (phase == 1) {
                if (w.mover.failed() || ++time > 600) return true;
                if (!w.mover.arrived()) return false;
                phase = 2;
                next = 40;
            }
            time++;
            if (time >= next) {
                next = time + 100 + w.random.nextInt(140);
                if (w.random.nextInt(100) < 60) say(w, nearestOther(w));
                else w.anim.look(spot.clone().add(0, 1.4, 0));
            }
            return time > 2400; // re-check now and then (a seat may have come free)
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return phase < 2 ? "Going to meet the others" : "Hanging out with the others";
        }

        @Override
        public boolean freeTime() {
            return true;
        }
    }

    // ---- Visit someone at work ----

    private static final class Visit implements Activity {
        private final Worker w, other;
        private int time, phase;

        Visit(Worker w, Worker other) {
            this.w = w;
            this.other = other;
        }

        @Override
        public boolean tick() {
            if (!other.body.isValid() || ++time > 600) return true;
            if (phase == 0) {
                if (time % 30 == 1 && !w.mover.go(other.body.getLocation(), 2.5, w.plugin.settings().walkSpeed())) return true;
                if (w.body.getLocation().distanceSquared(other.body.getLocation()) <= 3 * 3) {
                    w.mover.stop();
                    phase = 1;
                    time = 0;
                    say(w, other.body.getEyeLocation());
                }
                return false;
            }
            w.anim.look(other.body.getEyeLocation());
            if (time == 45) {
                List<String> lines = other.plugin.settings().chatLines();
                Bubbles.say(other.plugin, other.body, lines.get(other.random.nextInt(lines.size())), 45);
            }
            return time > 100;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return "Visiting " + other.name;
        }

        @Override
        public boolean freeTime() {
            return true;
        }

        @Override
        public boolean interruptible() {
            return true;
        }
    }

    // ---- Walking to the settlement ----

    /** Walk to the bell; if it's too far to find a path, follow the owner; otherwise wait. */
    static final class GoHome implements Activity {
        private final Worker w;
        private final Town t;
        private int time, repathIn;
        private String label = "Walking to the settlement";

        GoHome(Worker w, Town t) {
            this.w = w;
            this.t = t;
        }

        @Override
        public boolean tick() {
            Location me = w.body.getLocation();
            if (t.contains(me, w.plugin.settings().socialRadius() + 4)) return true;
            if (--repathIn <= 0) {
                repathIn = 40;
                Player owner = org.bukkit.Bukkit.getPlayer(w.owner);
                boolean sameWorld = t.bell.getWorld() == me.getWorld();
                double dx = sameWorld ? t.bell.getX() - me.getX() : 1e9, dz = sameWorld ? t.bell.getZ() - me.getZ() : 1e9;
                Location bell = t.bell.clone().add(0.5, -1, 0.5);
                if (dx * dx + dz * dz <= 46 * 46 && w.mover.go(bell, 4, w.plugin.settings().walkSpeed())) {
                    label = "Walking to the settlement";
                } else if (owner != null && owner.getWorld() == me.getWorld() && owner.getLocation().distanceSquared(me) <= 24 * 24) {
                    w.mover.go(owner.getLocation(), 2.5, w.plugin.settings().workSpeed());
                    label = "Following " + owner.getName();
                } else {
                    w.mover.stop();
                    label = "Waiting to be shown the way";
                    if (w.random.nextInt(8) == 0) Bubbles.say(w.plugin, w.body, "Which way to the settlement?", 50);
                }
            }
            return ++time > 2400;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return label;
        }
    }
}
