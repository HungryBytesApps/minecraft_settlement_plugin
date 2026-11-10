package villagerworkers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Settlers making music together: a band of 2 to 5 plays a real (old, well-known) song on note-block
 * sounds. The first plays the melody, the second the bass, the third the drums, the others the melody
 * an octave lower and higher. Everyone holds an "instrument" and note particles fly.
 */
final class Music {
    private Music() {}

    /** Instrument: what they hold, and which note block sound it makes. */
    record Instrument(Material item, String sound) {}

    static final List<Instrument> INSTRUMENTS = List.of(
            new Instrument(Material.GOAT_HORN, "flute"),
            new Instrument(Material.BELL, "bell"),
            new Instrument(Material.STRING, "guitar"),
            new Instrument(Material.STICK, "basedrum"),
            new Instrument(Material.AMETHYST_SHARD, "chime"),
            new Instrument(Material.BAMBOO, "didgeridoo"));

    private static final Instrument[] MELODY = {INSTRUMENTS.get(0), INSTRUMENTS.get(1), INSTRUMENTS.get(2), INSTRUMENTS.get(4)};
    private static final Instrument BASS = new Instrument(Material.BAMBOO, "bass");
    private static final Instrument DRUM = new Instrument(Material.STICK, "basedrum");
    private static final Instrument HAT = new Instrument(Material.STICK, "hat");

    static Instrument random(Random r) {
        return INSTRUMENTS.get(r.nextInt(INSTRUMENTS.size()));
    }

    // ---- Songs (all very old, free to play) ----

    /** A song: notes as "C4:2" (note, octave, # for sharp; length in eighths), "R:2" is a rest. Bars of 8 eighths. */
    record Song(String title, String notes) {}

    static final List<Song> SONGS = List.of(
            new Song("Twinkle, Twinkle, Little Star",
                    "C4:2 C4:2 G4:2 G4:2 A4:2 A4:2 G4:4 F4:2 F4:2 E4:2 E4:2 D4:2 D4:2 C4:4 "
                            + "G4:2 G4:2 F4:2 F4:2 E4:2 E4:2 D4:4 G4:2 G4:2 F4:2 F4:2 E4:2 E4:2 D4:4 "
                            + "C4:2 C4:2 G4:2 G4:2 A4:2 A4:2 G4:4 F4:2 F4:2 E4:2 E4:2 D4:2 D4:2 C4:4"),
            new Song("Ode to Joy",
                    "E4:2 E4:2 F4:2 G4:2 G4:2 F4:2 E4:2 D4:2 C4:2 C4:2 D4:2 E4:2 E4:3 D4:1 D4:4 "
                            + "E4:2 E4:2 F4:2 G4:2 G4:2 F4:2 E4:2 D4:2 C4:2 C4:2 D4:2 E4:2 D4:3 C4:1 C4:4 "
                            + "D4:2 D4:2 E4:2 C4:2 D4:2 E4:1 F4:1 E4:2 C4:2 D4:2 E4:1 F4:1 E4:2 D4:2 C4:2 D4:2 G3:4 "
                            + "E4:2 E4:2 F4:2 G4:2 G4:2 F4:2 E4:2 D4:2 C4:2 C4:2 D4:2 E4:2 D4:3 C4:1 C4:4"),
            new Song("Frère Jacques",
                    "C4:2 D4:2 E4:2 C4:2 C4:2 D4:2 E4:2 C4:2 E4:2 F4:2 G4:4 E4:2 F4:2 G4:4 "
                            + "G4:1 A4:1 G4:1 F4:1 E4:2 C4:2 G4:1 A4:1 G4:1 F4:1 E4:2 C4:2 C4:2 G3:2 C4:4 C4:2 G3:2 C4:4"),
            new Song("Mary Had a Little Lamb",
                    "E4:2 D4:2 C4:2 D4:2 E4:2 E4:2 E4:4 D4:2 D4:2 D4:4 E4:2 G4:2 G4:4 "
                            + "E4:2 D4:2 C4:2 D4:2 E4:2 E4:2 E4:2 E4:2 D4:2 D4:2 E4:2 D4:2 C4:8"),
            new Song("Old MacDonald Had a Farm",
                    "G4:2 G4:2 G4:2 D4:2 E4:2 E4:2 D4:4 B4:2 B4:2 A4:2 A4:2 G4:6 D4:2 "
                            + "G4:2 G4:2 G4:2 D4:2 E4:2 E4:2 D4:4 B4:2 B4:2 A4:2 A4:2 G4:8"),
            new Song("Row, Row, Row Your Boat",
                    "C4:3 C4:3 C4:2 D4:1 E4:3 E4:2 D4:1 E4:2 F4:1 G4:6 "
                            + "C5:1 C5:1 C5:1 G4:1 G4:1 G4:1 E4:1 E4:1 E4:1 C4:1 C4:1 C4:1 G4:2 F4:1 E4:2 D4:1 C4:6"),
            new Song("Happy Birthday",
                    "G4:1 G4:1 A4:2 G4:2 C5:2 B4:4 G4:1 G4:1 A4:2 G4:2 D5:2 C5:4 "
                            + "G4:1 G4:1 G5:2 E5:2 C5:2 B4:2 A4:4 F5:1 F5:1 E5:2 C5:2 D5:2 C5:4"),
            new Song("Jingle Bells",
                    "E4:2 E4:2 E4:4 E4:2 E4:2 E4:4 E4:2 G4:2 C4:3 D4:1 E4:8 "
                            + "F4:2 F4:2 F4:3 F4:1 F4:2 E4:2 E4:2 E4:1 E4:1 E4:2 D4:2 D4:2 E4:2 D4:4 G4:4"),
            new Song("Greensleeves",
                    "A4:2 C5:4 D5:2 E5:3 F5:1 E5:2 D5:4 B4:2 G4:3 A4:1 B4:2 C5:4 A4:2 A4:3 G#4:1 A4:2 B4:4 G#4:2 E4:4 "
                            + "A4:2 C5:4 D5:2 E5:3 F5:1 E5:2 D5:4 B4:2 G4:3 A4:1 B4:2 C5:3 B4:1 A4:2 G#4:3 F#4:1 G#4:2 A4:6"));

    /** One note: when (eighth), which note-block pitch (0-24), how long. */
    private record Note(int at, int pitch, int length) {}

    private static List<Note> parse(Song song) {
        List<Note> out = new ArrayList<>();
        int at = 0;
        for (String tok : song.notes().trim().split("\\s+")) {
            String[] p = tok.split(":");
            int len = Integer.parseInt(p[1]);
            if (!p[0].equals("R")) out.add(new Note(at, pitch(p[0]), len));
            at += len;
        }
        return out;
    }

    /** "C4", "F#4" → note block pitch (F#3 = 0 … F#5 = 24). */
    private static int pitch(String n) {
        int base = switch (n.charAt(0)) {
            case 'C' -> 0;
            case 'D' -> 2;
            case 'E' -> 4;
            case 'F' -> 5;
            case 'G' -> 7;
            case 'A' -> 9;
            default -> 11;
        };
        boolean sharp = n.indexOf('#') > 0;
        int octave = n.charAt(n.length() - 1) - '0';
        int midi = 12 * (octave + 1) + base + (sharp ? 1 : 0);
        return midi - 54;
    }

    private static int fit(int p) {
        while (p < 0) p += 12;
        while (p > 24) p -= 12;
        return p;
    }

    /** Play one note: the sound, a note particle above the head, and a little movement. */
    static void play(Worker w, Instrument inst, int pitchIndex, float volume) {
        int n = fit(pitchIndex);
        float pitch = (float) Math.pow(2, (n - 12) / 12.0);
        Location head = w.body.getLocation().add(0, 2.3, 0);
        w.body.getWorld().playSound(w.body.getLocation(), "minecraft:block.note_block." + inst.sound(), SoundCategory.RECORDS, volume, pitch);
        w.body.getWorld().spawnParticle(Particle.NOTE, head, 0, n / 24.0, 0, 0, 1);
        if (w.body.getEquipment().getItemInMainHand().getType() == Material.GOAT_HORN) w.anim.use(10); // horn to the mouth
        else if (w.random.nextInt(3) == 0) w.body.swingMainHand();
    }

    /** For sitting alone with an instrument (evening): a random note from a nice scale. */
    static void note(Worker w, Instrument inst) {
        int[] scale = {6, 8, 10, 13, 15, 18, 20, 22};
        play(w, inst, scale[w.random.nextInt(scale.length)], 0.7f);
    }

    /** Start a band: the leader plus 1–4 free settlers nearby. Null if nobody's around. */
    static Activity start(Worker leader) {
        List<Worker> band = new ArrayList<>();
        band.add(leader);
        Location me = leader.body.getLocation();
        int want = 2 + leader.random.nextInt(4); // 2 to 5 players
        for (Worker o : leader.plugin.workers().all()) {
            if (band.size() >= want) break;
            if (o == leader || o.body.getWorld() != me.getWorld() || !o.available() || o.body.isInsideVehicle()) continue;
            if (o.body.getLocation().distanceSquared(me) <= 24 * 24) band.add(o);
        }
        if (band.size() < 2) return null;
        Band b = new Band(band);
        for (int i = 1; i < band.size(); i++) band.get(i).start(new Member(b, band.get(i), false));
        return new Member(b, leader, true);
    }

    private static final class Band {
        static final int TICKS_PER_EIGHTH = 4;
        final List<Worker> members;
        final List<Instrument> instruments = new ArrayList<>();
        final Song song;
        final List<Note> notes;
        final int songLength; // in eighths
        final Location[] spots;
        boolean done, playing;
        int t, start, lastEighth = -1, plays;

        Band(List<Worker> members) {
            this.members = members;
            Random r = members.get(0).random;
            this.song = SONGS.get(r.nextInt(SONGS.size()));
            this.notes = parse(song);
            int len = 0;
            for (Note n : notes) len = Math.max(len, n.at() + n.length());
            this.songLength = ((len + 7) / 8) * 8;
            for (int i = 0; i < members.size(); i++) {
                instruments.add(switch (i) {
                    case 0 -> MELODY[r.nextInt(MELODY.length)];
                    case 1 -> BASS;
                    case 2 -> DRUM;
                    default -> MELODY[r.nextInt(MELODY.length)];
                });
            }
            // Stand in a half circle around the first player.
            spots = new Location[members.size()];
            Location here = members.get(0).body.getLocation();
            spots[0] = here;
            double base = r.nextDouble() * Math.PI * 2;
            for (int i = 1; i < members.size(); i++) {
                double a = base + (i - (members.size() - 1) / 2.0 - 0.5) * 0.8;
                spots[i] = here.clone().add(Math.cos(a) * 1.8, 0, Math.sin(a) * 1.8);
            }
        }

        void tick() {
            t++;
            for (Worker w : members) if (!w.body.isValid()) done = true;
            if (t > 3000) done = true;
            if (done) return;
            if (!playing) {
                boolean all = true;
                for (int i = 1; i < members.size(); i++) {
                    Worker w = members.get(i);
                    if (w.body.getLocation().distanceSquared(spots[i]) <= 1.0 || (t > 200 && w.body.getLocation().distanceSquared(spots[0]) <= 3.5 * 3.5)) continue;
                    all = false;
                    if (t % 30 == 1 && !w.mover.moving() && !w.mover.go(spots[i], 0.5, w.plugin.settings().walkSpeed()))
                        w.mover.go(spots[0], 2.0, w.plugin.settings().walkSpeed());
                }
                if (t > 600) {
                    done = true;
                    return;
                }
                if (all) {
                    playing = true;
                    start = t + 20; // a moment to get ready
                    for (int i = 0; i < members.size(); i++) {
                        members.get(i).mover.stop();
                        members.get(i).hold(instruments.get(i).item());
                    }
                }
                return;
            }
            if (t % 10 == 0) for (Worker w : members) w.anim.look(center());
            if (t < start || (t - start) % TICKS_PER_EIGHTH != 0) return;
            int eighth = (t - start) / TICKS_PER_EIGHTH;
            if (eighth == lastEighth) return;
            lastEighth = eighth;
            if (eighth >= songLength) {
                if (++plays >= 2 || members.get(0).random.nextBoolean()) done = true; // once or twice
                else start = t + 30;
                lastEighth = -1;
                return;
            }
            // Melody (and the doubling players).
            for (Note n : notes) {
                if (n.at() != eighth) continue;
                play(members.get(0), instruments.get(0), n.pitch(), 0.9f);
                if (members.size() > 3) play(members.get(3), instruments.get(3), n.pitch() - 12, 0.6f);
                if (members.size() > 4 && n.length() >= 2) play(members.get(4), instruments.get(4), n.pitch() + 12, 0.5f);
            }
            // Bass: the root of the bar (the first melody note), twice a bar.
            if (members.size() > 1 && eighth % 4 == 0) {
                int bar = eighth / 8 * 8, root = 6;
                for (Note n : notes) if (n.at() >= bar) {
                    root = n.pitch();
                    break;
                }
                play(members.get(1), instruments.get(1), root - 12, 0.8f);
            }
            // Drums: kick on the beat, hi-hat in between.
            if (members.size() > 2 && eighth % 2 == 0) {
                play(members.get(2), eighth % 4 == 0 ? DRUM : HAT, 12, 0.6f);
            }
        }

        Location center() {
            Location c = new Location(spots[0].getWorld(), 0, 0, 0);
            for (Worker w : members) c.add(w.body.getLocation());
            c.multiply(1.0 / members.size());
            return c.add(0, 1.4, 0);
        }
    }

    private static final class Member implements Activity {
        private final Band band;
        private final Worker me;
        private final boolean leader;

        Member(Band band, Worker me, boolean leader) {
            this.band = band;
            this.me = me;
            this.leader = leader;
        }

        @Override
        public boolean tick() {
            if (leader) band.tick();
            return band.done;
        }

        @Override
        public void stop() {
            band.done = true;
            me.mover.stop();
            me.holdTool();
        }

        @Override
        public String label() {
            return band.playing ? "Playing " + band.song.title() : "Making music";
        }

        @Override
        public boolean freeTime() {
            return true;
        }
    }
}
