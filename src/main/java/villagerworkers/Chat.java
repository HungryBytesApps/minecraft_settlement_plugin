package villagerworkers;

import org.bukkit.Location;
import org.bukkit.SoundCategory;

import java.util.List;

/** Two workers walk up to each other and have a little conversation with speech bubbles. */
final class Chat {
    private Chat() {}

    private static final String[] SOUNDS = {
            "minecraft:entity.villager.ambient", "minecraft:entity.villager.ambient",
            "minecraft:entity.villager.yes", "minecraft:entity.villager.trade"};

    static Activity start(Worker a, Worker b) {
        Talk talk = new Talk(a, b);
        b.start(new Side(talk, b, a, false));
        return new Side(talk, a, b, true);
    }

    /** Shared state of one conversation. */
    private static final class Talk {
        final Worker a, b;
        boolean done, talking;
        int t, said, nextLine, lines, endAt = -1;

        Talk(Worker a, Worker b) {
            this.a = a;
            this.b = b;
            this.lines = 3 + a.random.nextInt(4);
        }

        void tick() {
            t++;
            if (t > 900 || !a.villager.isValid() || !b.villager.isValid()) {
                done = true;
                return;
            }
            Location la = a.villager.getLocation(), lb = b.villager.getLocation();
            if (!talking) {
                if (la.distanceSquared(lb) <= 2.6 * 2.6) {
                    talking = true;
                    a.mover.stop();
                    b.mover.stop();
                    nextLine = t + 10;
                } else if (t % 40 == 1) {
                    Location mid = la.clone().add(lb).multiply(0.5);
                    double speed = a.plugin.settings().walkSpeed();
                    if (!a.mover.go(mid, 1.2, speed) || !b.mover.go(mid, 1.2, speed)) done = true;
                }
                return;
            }
            Mover.lookAt(a.villager, b.villager.getEyeLocation());
            Mover.lookAt(b.villager, a.villager.getEyeLocation());
            if (endAt >= 0) {
                if (t >= endAt) done = true;
                return;
            }
            if (t < nextLine) return;
            Worker speaker = said % 2 == 0 ? a : b;
            List<String> chat = a.plugin.settings().chatLines();
            Bubbles.say(a.plugin, speaker.villager, chat.get(speaker.random.nextInt(chat.size())), 45);
            String sound = SOUNDS[speaker.random.nextInt(SOUNDS.length)];
            speaker.villager.getWorld().playSound(speaker.villager.getLocation(), sound, SoundCategory.NEUTRAL,
                    0.7f, 0.85f + speaker.random.nextFloat() * 0.3f);
            if (speaker.random.nextInt(5) == 0) speaker.villager.shakeHead();
            said++;
            nextLine = t + 50 + speaker.random.nextInt(30);
            if (said >= lines) endAt = t + 45;
        }
    }

    /** Each worker in the conversation runs one of these; the leader drives it. */
    private static final class Side implements Activity {
        private final Talk talk;
        private final Worker me, other;
        private final boolean leader;

        Side(Talk talk, Worker me, Worker other, boolean leader) {
            this.talk = talk;
            this.me = me;
            this.other = other;
            this.leader = leader;
        }

        @Override
        public boolean tick() {
            if (talk.done) return true;
            if (!(other.activity instanceof Side s) || s.talk != talk) {
                // The other one got pulled away (or hasn't started yet on the follower's first tick).
                if (leader && talk.t > 2) talk.done = true;
                if (!leader) return talk.done;
            }
            if (leader) talk.tick();
            return talk.done;
        }

        @Override
        public void stop() {
            talk.done = true;
            me.mover.stop();
            Bubbles.clear(me.villager);
        }

        @Override
        public String label() {
            return "Chatting with " + other.name;
        }
    }
}
