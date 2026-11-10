package villagerworkers;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;

/**
 * Bad weather: settlers with nothing to do go inside when it rains; in a thunderstorm everyone
 * (except guards) goes inside, even from work. They wait it out under a roof, chatting a little.
 */
final class Shelter implements Activity {
    private final Worker w;
    private final Town t;
    private final boolean storm; // thunder: even work waits
    private final Set<String> unreachable = new HashSet<>();
    private Location spot;
    private int time, tries;

    Shelter(Worker w, Town t, boolean storm) {
        this.w = w;
        this.t = t;
        this.storm = storm;
        if (w.random.nextInt(3) == 0) {
            String line = storm ? "Thunder! Inside, quick!" : "Rain... let's get inside.";
            Bubbles.say(w.plugin, w.body, line, 50);
            Voice.speak(w, line);
        }
    }

    /** Is it raining (or storming) on this settler? */
    static boolean exposed(Worker w) {
        World world = w.body.getWorld();
        if (!world.hasStorm()) return false;
        Location l = w.body.getLocation();
        return world.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING) <= l.getBlockY();
    }

    /** Check now and then: should this settler go inside? */
    static Activity check(Worker w, Town t) {
        if (w.guard || w.activity instanceof Shelter || w.activity instanceof Defense.Reaction || !exposed(w)) return null;
        boolean thunder = w.body.getWorld().isThundering();
        boolean idle = w.activity == null || w.activity.freeTime();
        if (!thunder && !idle) return null; // just rain: work goes on
        return new Shelter(w, t, thunder);
    }

    @Override
    public boolean tick() {
        time++;
        World world = w.body.getWorld();
        if (!world.hasStorm() || (storm && !world.isThundering() && time > 200)) return true; // over
        if (spot == null || (w.mover.failed() && time % 20 == 0)) {
            if (spot != null) unreachable.add(Keys.ser(spot));
            if (++tries > 4) return true; // nowhere to go: give up
            spot = Defense.shelter(w, t, unreachable);
            if (spot == null) return true;
            w.mover.go(spot, 0.8, w.plugin.settings().workSpeed());
            return false;
        }
        if (w.mover.moving()) return false;
        // Waiting it out: look around, now and then a word.
        if (time % 60 == 0) w.anim.glance();
        if (time % 300 == 150 && w.random.nextInt(3) == 0) {
            String line = storm ? "What a storm!" : w.random.nextBoolean() ? "Still raining..." : "Good weather for the crops, at least.";
            Bubbles.say(w.plugin, w.body, line, 50);
            Voice.speak(w, line);
        }
        return false;
    }

    @Override
    public void stop() {
        w.mover.stop();
    }

    @Override
    public String label() {
        return storm ? "Hiding from the thunderstorm" : "Sheltering from the rain";
    }

    @Override
    public boolean freeTime() {
        return !storm; // in the rain, work may call; in a thunderstorm, it waits
    }
}
