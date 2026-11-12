package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Settlers making music together: a band of 2 to 5 plays along with a music disc (heard only near the band),
 * now and then (config: music-every-minutes). Alone in the evening, a settler plays a few quiet notes.
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

    static Instrument random(Random r) {
        return INSTRUMENTS.get(r.nextInt(INSTRUMENTS.size()));
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

    // ---- A band ----

    /** Music discs the band can play: name → length in seconds. */
    static final Map<String, Integer> DISCS = Map.ofEntries(
            Map.entry("13", 178), Map.entry("cat", 185), Map.entry("blocks", 345), Map.entry("chirp", 185),
            Map.entry("far", 174), Map.entry("mall", 197), Map.entry("mellohi", 96), Map.entry("stal", 150),
            Map.entry("strad", 188), Map.entry("ward", 251), Map.entry("11", 71), Map.entry("wait", 238),
            Map.entry("otherside", 195), Map.entry("5", 178), Map.entry("pigstep", 149), Map.entry("relic", 218),
            Map.entry("creator", 176), Map.entry("creator_music_box", 73), Map.entry("precipice", 299),
            Map.entry("tears", 175), Map.entry("lava_chicken", 134));

    /** The calm ones, when the config doesn't say. */
    private static final List<String> DEFAULT_DISCS = List.of("cat", "chirp", "far", "mall", "mellohi", "stal", "strad",
            "wait", "blocks", "otherside", "relic", "creator_music_box", "precipice");

    /** Per settlement: no new band before this tick. */
    private static final Map<String, Integer> nextBand = new HashMap<>();

    /** Start a band: the leader plus 1–4 free settlers nearby. Null if nobody's around, or the last band was not long ago. */
    static Activity start(Worker leader) {
        Town t = leader.town();
        if (t == null) return null;
        org.bukkit.configuration.file.FileConfiguration c = leader.plugin.getConfig();
        String key = String.valueOf(t.id);
        int now = Bukkit.getCurrentTick();
        Integer next = nextBand.get(key);
        if (next != null && now < next) return null;
        List<String> discs = new ArrayList<>();
        for (String d : c.isSet("music-discs") ? c.getStringList("music-discs") : DEFAULT_DISCS) {
            String k = d.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace("music_disc_", "").replace("music_disc.", "");
            if (DISCS.containsKey(k)) discs.add(k);
        }
        if (discs.isEmpty()) return null; // no music
        List<Worker> band = new ArrayList<>();
        band.add(leader);
        Location me = leader.body.getLocation();
        int want = 2 + leader.random.nextInt(4); // 2 to 5 players
        for (Worker o : leader.plugin.workers().all()) {
            if (band.size() >= want) break;
            if (o == leader || o.body.getWorld() != me.getWorld() || !o.available() || o.body.isInsideVehicle()) continue;
            if (!java.util.Objects.equals(o.townId, leader.townId)) continue; // its own settlement's band
            if (o.body.getLocation().distanceSquared(me) <= 24 * 24) band.add(o);
        }
        if (band.size() < 2) return null;
        nextBand.put(key, now + 1200); // getting together: not another band meanwhile
        String disc = discs.get(leader.random.nextInt(discs.size()));
        float volume = (float) Math.max(0.2, Math.min(4.0, c.getDouble("music-volume", 1.0)));
        int pause = Math.max(1, c.getInt("music-every-minutes", 20)) * 1200;
        Band b = new Band(key, disc, volume, pause);
        Member first = null;
        for (int i = 0; i < band.size(); i++) {
            Member m = new Member(b, band.get(i), i == 0, i == 0 ? MELODY[leader.random.nextInt(MELODY.length)] : switch (i) {
                case 1 -> BASS;
                case 2 -> DRUM;
                default -> MELODY[leader.random.nextInt(MELODY.length)];
            });
            b.members.add(m);
            if (i == 0) first = m;
        }
        b.placeAround();
        for (Member m : b.members) if (!m.leader) m.me.start(m);
        return first;
    }

    /** "music_disc.creator_music_box" → "Creator (Music Box)". */
    private static String title(String disc) {
        if (disc.equals("creator_music_box")) return "Creator (Music Box)";
        String n = disc.replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /**
     * A band: they get together in a half circle, then one music disc plays where they stand (heard only
     * near the band) while everyone plays along: instruments in hand, note particles, a swing now and then.
     */
    private static final class Band {
        final List<Member> members = new ArrayList<>();
        final String town, disc, sound;
        final float volume;
        final int pause, length;
        boolean done, playing;
        int t, start = -1;
        Location soundAt;

        Band(String town, String disc, float volume, int pause) {
            this.town = town;
            this.disc = disc;
            this.sound = "minecraft:music_disc." + disc;
            this.volume = volume;
            this.pause = pause;
            this.length = DISCS.get(disc) * 20;
        }

        Member leader() {
            return members.get(0);
        }

        void placeAround() {
            Location here = leader().me.body.getLocation();
            leader().spot = here;
            double base = leader().me.random.nextDouble() * Math.PI * 2;
            for (int i = 1; i < members.size(); i++) {
                double a = base + (i - (members.size() - 1) / 2.0 - 0.5) * 0.8;
                members.get(i).spot = here.clone().add(Math.cos(a) * 1.8, 0, Math.sin(a) * 1.8);
            }
        }

        void tick() {
            t++;
            if (done) return;
            if (!leader().me.body.isValid()) {
                end();
                return;
            }
            members.removeIf(m -> !m.me.body.isValid());
            if (!playing) {
                boolean all = true;
                Location centre = leader().spot;
                for (int i = 1; i < members.size(); i++) {
                    Member m = members.get(i);
                    Worker w = m.me;
                    if (w.body.getLocation().distanceSquared(m.spot) <= 1.0 || (t > 200 && w.body.getLocation().distanceSquared(centre) <= 3.5 * 3.5)) continue;
                    all = false;
                    if (t % 30 == 1 && !w.mover.moving() && !w.mover.go(m.spot, 0.5, w.plugin.settings().walkSpeed()))
                        w.mover.go(centre, 2.0, w.plugin.settings().walkSpeed());
                }
                if (t > 600 || members.size() < 2) {
                    end();
                    return;
                }
                if (all) {
                    playing = true;
                    start = t + 20; // a moment to get ready
                    for (Member m : members) {
                        m.me.mover.stop();
                        m.me.hold(m.instrument.item());
                    }
                }
                return;
            }
            if (t == start) {
                soundAt = center();
                soundAt.getWorld().playSound(soundAt, sound, SoundCategory.RECORDS, volume, 1f);
                nextBand.put(town, Bukkit.getCurrentTick() + length + pause);
            }
            if (t < start) return;
            if (t - start >= length) { // the song is over
                done = true;
                return;
            }
            if (t % 10 == 0) for (Member m : members) m.me.anim.look(center());
            for (int i = 0; i < members.size(); i++) {
                Member m = members.get(i);
                if ((t + i * 3) % m.every == 0) along(m);
            }
        }

        /** Playing along with the disc: a note particle above the head and the hands moving (no sound of its own). */
        private void along(Member m) {
            Worker w = m.me;
            w.body.getWorld().spawnParticle(Particle.NOTE, w.body.getLocation().add(0, 2.3, 0), 0, w.random.nextInt(25) / 24.0, 0, 0, 1);
            if (m.instrument.item() == Material.GOAT_HORN) w.anim.use(10);
            else if (w.random.nextInt(2) == 0) w.body.swingMainHand();
        }

        /** Stop early (someone left, an alarm...): the music stops too. */
        void end() {
            if (done) return;
            done = true;
            if (soundAt == null) return;
            double range = 16 * Math.max(1, volume) + 8;
            for (org.bukkit.entity.Player p : soundAt.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(soundAt) <= range * range) p.stopSound(sound, SoundCategory.RECORDS);
            }
        }

        Location center() {
            Location c = new Location(leader().me.body.getWorld(), 0, 0, 0);
            for (Member m : members) c.add(m.me.body.getLocation());
            c.multiply(1.0 / members.size());
            return c.add(0, 1.4, 0);
        }
    }

    private static final class Member implements Activity {
        private final Band band;
        private final Worker me;
        private final boolean leader;
        private final Instrument instrument;
        private final int every;
        private Location spot;

        Member(Band band, Worker me, boolean leader, Instrument instrument) {
            this.band = band;
            this.me = me;
            this.leader = leader;
            this.instrument = instrument;
            this.every = 8 + me.random.nextInt(5);
        }

        @Override
        public boolean tick() {
            if (leader) band.tick();
            return band.done || !band.members.contains(this);
        }

        @Override
        public void stop() {
            if (leader) band.end(); // the leader leaves: the band stops
            else band.members.remove(this); // someone else leaves: the rest play on
            me.mover.stop();
            me.holdTool();
        }

        @Override
        public String label() {
            return band.playing ? "Playing " + title(band.disc) : "Making music";
        }

        @Override
        public boolean freeTime() {
            return true;
        }
    }
}
