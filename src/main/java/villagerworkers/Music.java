package villagerworkers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Settlers making music: holding an "instrument" and playing note-block notes with note particles. */
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

    /** A pentatonic scale sounds nice whatever notes are picked. */
    private static final int[] SCALE = {6, 8, 10, 13, 15, 18, 20, 22};

    static Instrument random(Random r) {
        return INSTRUMENTS.get(r.nextInt(INSTRUMENTS.size()));
    }

    static void note(Worker w, Instrument inst) {
        int n = SCALE[w.random.nextInt(SCALE.length)];
        float pitch = (float) Math.pow(2, (n - 12) / 12.0);
        Location head = w.body.getLocation().add(0, 2.3, 0);
        w.body.getWorld().playSound(w.body.getLocation(), "minecraft:block.note_block." + inst.sound(), SoundCategory.RECORDS, 0.8f, pitch);
        w.body.getWorld().spawnParticle(Particle.NOTE, head, 0, n / 24.0, 0, 0, 1);
        if (w.random.nextInt(4) == 0) w.body.swingMainHand();
    }

    /** Start a little band: the leader plus 1–2 free settlers nearby. Null if nobody's around. */
    static Activity start(Worker leader) {
        List<Worker> band = new ArrayList<>();
        band.add(leader);
        Location me = leader.body.getLocation();
        for (Worker o : leader.plugin.workers().all()) {
            if (band.size() >= 3) break;
            if (o == leader || o.body.getWorld() != me.getWorld() || !o.available() || o.body.isInsideVehicle()) continue;
            if (o.body.getLocation().distanceSquared(me) <= 20 * 20) band.add(o);
        }
        if (band.size() < 2) return null;
        Band b = new Band(band);
        for (int i = 1; i < band.size(); i++) band.get(i).start(new Member(b, band.get(i), false));
        return new Member(b, leader, true);
    }

    private static final class Band {
        final List<Worker> members;
        final List<Instrument> instruments = new ArrayList<>();
        final int[] nextNote;
        boolean done, playing;
        int t, length;
        Location spot;

        Band(List<Worker> members) {
            this.members = members;
            for (Worker w : members) instruments.add(random(w.random));
            this.nextNote = new int[members.size()];
            this.length = 300 + members.get(0).random.nextInt(200);
        }

        void tick() {
            t++;
            if (t > length + 400) done = true;
            for (Worker w : members) if (!w.body.isValid()) done = true;
            if (done) return;
            if (!playing) {
                if (spot == null) {
                    spot = members.get(0).body.getLocation();
                    for (Worker w : members) w.mover.go(spot, 1.8, w.plugin.settings().walkSpeed());
                }
                boolean all = true;
                for (Worker w : members) if (w.body.getLocation().distanceSquared(spot) > 3.2 * 3.2) all = false;
                if (all || t > 300) {
                    playing = true;
                    length += t;
                    for (int i = 0; i < members.size(); i++) {
                        members.get(i).mover.stop();
                        members.get(i).hold(instruments.get(i).item());
                    }
                }
                return;
            }
            for (int i = 0; i < members.size(); i++) {
                Worker w = members.get(i);
                Mover.lookAt(w.body, center());
                if (t >= nextNote[i]) {
                    note(w, instruments.get(i));
                    nextNote[i] = t + 5 + w.random.nextInt(8);
                }
            }
            if (t >= length) done = true;
        }

        Location center() {
            Location c = new Location(spot.getWorld(), 0, 0, 0);
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
            return "Making music";
        }

        @Override
        public boolean freeTime() {
            return true;
        }
    }
}
