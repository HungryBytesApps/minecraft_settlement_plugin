package settlement;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Pottering about: with nothing to do, a settler busies itself around its workplace (checking the
 * crops, watching the animals, sweeping up) or around the settlement: walks a few spots, crouches,
 * looks things over, fiddles with something.
 */
final class Potter implements Activity {
    private final Worker w;
    private final Location center;
    private final int radius;
    private final String label;
    private int spots, wait, time;

    private Potter(Worker w, Location center, int radius, String label) {
        this.w = w;
        this.center = center;
        this.radius = radius;
        this.label = label;
        this.spots = 3 + w.random.nextInt(3);
    }

    static Activity start(Worker w) {
        Town.Workplace wp = w.workplace();
        if (wp != null && wp.loc.getWorld() == w.body.getWorld()) {
            String label = switch (wp.job()) {
                case FARMER -> "Checking the crops";
                case HERDER -> "Watching the animals";
                case LUMBERJACK -> "Looking over the trees";
                case COOK -> "Cleaning the kitchen";
                case SMELTER, FURNACE_WORKER -> "Sweeping up the ashes";
                case SMITH -> "Sharpening tools";
                default -> "Tidying up";
            };
            int r = wp.job().usesArea ? 6 : 4;
            return new Potter(w, wp.loc.clone().add(0.5, 0, 0.5), r, label);
        }
        Location c = w.center();
        if (c.getWorld() != w.body.getWorld()) return null;
        String[] labels = {"Tidying up", "Picking flowers", "Fixing a fence", "Looking for a lost button"};
        return new Potter(w, c.clone().add(0.5, 0, 0.5), w.plugin.settings().socialRadius(), labels[w.random.nextInt(labels.length)]);
    }

    @Override
    public boolean tick() {
        time++;
        if (time > 2400) return true;
        if (w.mover.moving()) return false;
        if (wait > 0) {
            wait--;
            // Busy hands: now and then a swing, a crouch or a look down.
            if (wait % 25 == 0) {
                int r = w.random.nextInt(4);
                if (r == 0) {
                    w.body.swingMainHand();
                    Voice.tap(w, w.body.getLocation().clone().subtract(0, 0.2, 0).getBlock());
                } else if (r == 1) {
                    w.anim.crouch(12);
                    Voice.tap(w, w.body.getLocation().clone().subtract(0, 0.2, 0).getBlock());
                }
                else if (r == 2) w.anim.lookDown(30 + w.random.nextInt(20));
                else w.anim.glance();
            }
            return false;
        }
        if (spots-- <= 0) return true;
        Location to = spot();
        if (to == null || !w.mover.go(to, 0.8, w.plugin.settings().walkSpeed())) return spots <= 0;
        wait = 50 + w.random.nextInt(70);
        return false;
    }

    private Location spot() {
        World world = center.getWorld();
        for (int i = 0; i < 10; i++) {
            int x = center.getBlockX() + w.random.nextInt(radius * 2 + 1) - radius;
            int z = center.getBlockZ() + w.random.nextInt(radius * 2 + 1) - radius;
            for (int y = center.getBlockY() + 3; y >= center.getBlockY() - 4; y--) {
                if (PathFinder.walkable(world, x, y, z)) return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    @Override
    public void stop() {
        w.mover.stop();
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean freeTime() {
        return true;
    }
}
