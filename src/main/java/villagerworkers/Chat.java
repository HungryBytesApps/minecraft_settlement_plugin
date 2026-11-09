package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

/**
 * Conversations: 2 or 3 settlers get together in a little circle and talk through a short story
 * from conversations.yml (with their own names, jobs, the settlement and what's in storage).
 * The others look at whoever is talking, nod, laugh along.
 */
final class Chat {
    private Chat() {}

    /** One conversation from the file: who says what. */
    private record Story(int people, List<Integer> speakers, List<String> lines) {}

    private static final List<Story> stories = new ArrayList<>();

    /**
     * Load the built-in conversations (always the newest, from the plugin) plus your own from
     * my-conversations.yml (created with an example the first time). Duplicates are skipped.
     */
    static void load(VillagerWorkersPlugin plugin) {
        stories.clear();
        Set<String> seen = new HashSet<>();
        java.io.InputStream in = plugin.getResource("conversations.yml");
        if (in != null) {
            try (java.io.Reader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                read(YamlConfiguration.loadConfiguration(r), seen);
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Could not read the built-in conversations: " + e.getMessage());
            }
        }
        int builtIn = stories.size();
        File old = new File(plugin.getDataFolder(), "conversations.yml"); // from an earlier version
        if (old.exists()) read(YamlConfiguration.loadConfiguration(old), seen);
        File mine = new File(plugin.getDataFolder(), "my-conversations.yml");
        if (!mine.exists()) plugin.saveResource("my-conversations.yml", false);
        read(YamlConfiguration.loadConfiguration(mine), seen);
        plugin.getLogger().info(builtIn + " built-in and " + (stories.size() - builtIn) + " own conversations loaded.");
    }

    private static void read(YamlConfiguration yml, Set<String> seen) {
        for (Map<?, ?> m : yml.getMapList("conversations")) {
            Object p = m.get("people"), l = m.get("lines");
            int people = p instanceof Number n ? n.intValue() : 2;
            if (people < 2 || people > 3 || !(l instanceof List<?> raw)) continue;
            List<Integer> who = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            for (Object o : raw) {
                String s = String.valueOf(o);
                int colon = s.indexOf(':');
                if (colon < 1) continue;
                try {
                    int i = Integer.parseInt(s.substring(0, colon).trim());
                    if (i < 0 || i >= people) continue;
                    who.add(i);
                    lines.add(s.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (!lines.isEmpty() && seen.add(people + "|" + String.join("|", lines))) stories.add(new Story(people, who, lines));
        }
    }

    /** Start a conversation with 1–2 free settlers nearby. Null if nobody's around. */
    static Activity start(Worker leader) {
        Location me = leader.body.getLocation();
        List<Worker> near = new ArrayList<>();
        for (Worker o : leader.plugin.workers().all()) {
            if (o == leader || o.body.getWorld() != me.getWorld() || !o.available() || o.body.isInsideVehicle()) continue;
            if (!Objects.equals(o.townId, leader.townId)) continue;
            if (o.body.getLocation().distanceSquared(me) <= 20 * 20) near.add(o);
        }
        if (near.isEmpty()) return null;
        Collections.shuffle(near, leader.random);
        int size = near.size() >= 2 && leader.random.nextBoolean() && has(3) ? 3 : 2;
        List<Worker> group = new ArrayList<>();
        group.add(leader);
        group.addAll(near.subList(0, size - 1));
        Talk talk = new Talk(group, pick(leader.random, size));
        for (int i = 1; i < group.size(); i++) group.get(i).start(new Member(talk, group.get(i), false));
        return new Member(talk, leader, true);
    }

    private static boolean has(int people) {
        for (Story s : stories) if (s.people() == people) return true;
        return false;
    }

    private static Story pick(Random r, int people) {
        List<Story> fit = new ArrayList<>();
        for (Story s : stories) if (s.people() == people) fit.add(s);
        return fit.isEmpty() ? null : fit.get(r.nextInt(fit.size()));
    }

    /** Shared state of one conversation; the leader drives it. */
    private static final class Talk {
        final List<Worker> group;
        final List<Integer> speakers = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final Location[] spots;
        boolean done, talking;
        int t, line, nextLine, endAt = -1;

        Talk(List<Worker> group, Story story) {
            this.group = group;
            this.spots = new Location[group.size()];
            Worker lead = group.get(0);
            if (story == null) {
                // No conversations file: a few random lines, taking turns.
                List<String> chat = lead.plugin.settings().chatLines();
                int n = 3 + lead.random.nextInt(4);
                for (int i = 0; i < n; i++) {
                    speakers.add(i % group.size());
                    lines.add(chat.get(lead.random.nextInt(chat.size())));
                }
            } else {
                Map<String, String> fill = placeholders(group);
                for (int i = 0; i < story.lines().size(); i++) {
                    speakers.add(story.speakers().get(i));
                    String s = story.lines().get(i);
                    for (Map.Entry<String, String> e : fill.entrySet()) s = s.replace(e.getKey(), e.getValue());
                    lines.add(s);
                }
            }
            // Stand in a little circle around the middle.
            Location mid = lead.body.getLocation().clone();
            for (int i = 1; i < group.size(); i++) mid.add(group.get(i).body.getLocation());
            mid.multiply(1.0 / group.size());
            double r = group.size() == 2 ? 0.9 : 1.3, base = lead.random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < group.size(); i++) {
                double a = base + i * Math.PI * 2 / group.size();
                spots[i] = mid.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            }
        }

        private static Map<String, String> placeholders(List<Worker> group) {
            Worker lead = group.get(0);
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < 3; i++) {
                Worker w = group.get(Math.min(i, group.size() - 1));
                m.put("{" + i + "}", w.name);
                m.put("{job" + i + "}", w.title().toLowerCase(Locale.ROOT));
            }
            Town t = lead.town();
            m.put("{town}", t == null ? "the settlement" : t.name);
            Player owner = t == null ? null : Bukkit.getPlayer(t.owner);
            m.put("{owner}", owner != null ? owner.getName() : "the boss");
            // Someone not in this conversation.
            List<String> others = new ArrayList<>();
            if (t != null) for (Map.Entry<UUID, String> e : t.settlers.entrySet()) {
                boolean inGroup = false;
                for (Worker w : group) if (w.body.getUniqueId().equals(e.getKey())) inGroup = true;
                if (!inGroup) others.add(e.getValue());
            }
            m.put("{other}", others.isEmpty() ? "the new neighbour" : others.get(lead.random.nextInt(others.size())));
            // Something in storage.
            String item = "wheat", count = "no";
            if (t != null) {
                Map<Material, Integer> stock = t.stock(lead.plugin.settings().townRadius());
                List<Material> kinds = new ArrayList<>(stock.keySet());
                if (!kinds.isEmpty()) {
                    Material pickM = kinds.get(lead.random.nextInt(kinds.size()));
                    item = FarmerJob.nice(pickM);
                    count = String.valueOf(stock.get(pickM));
                }
            }
            m.put("{item}", item);
            m.put("{count}", count);
            return m;
        }

        void tick() {
            t++;
            for (Worker w : group) {
                if (!w.body.isValid()) {
                    done = true;
                    return;
                }
            }
            if (t > 2400) {
                done = true;
                return;
            }
            if (!talking) {
                boolean all = true;
                for (int i = 0; i < group.size(); i++) {
                    Worker w = group.get(i);
                    Location l = w.body.getLocation();
                    if (l.getWorld() != spots[i].getWorld() || horizontal(l, spots[i]) > 1.1) {
                        all = false;
                        if (t % 30 == 1 && !w.mover.moving()) {
                            if (!w.mover.go(spots[i], 0.4, w.plugin.settings().walkSpeed()) && t > 60) spots[i] = l; // can't get there: talk from here
                        }
                    }
                }
                if (all || t > 300) {
                    talking = true;
                    for (Worker w : group) w.mover.stop();
                    nextLine = t + 15;
                }
                return;
            }
            // Everyone faces whoever is talking (the speaker looks at the others).
            int speaker = line < speakers.size() ? speakers.get(line) : -1;
            if (t % 8 == 0) {
                int current = line > 0 ? speakers.get(line - 1) : 0; // who's saying something now
                for (int i = 0; i < group.size(); i++) {
                    int lookAt = i == current ? (i + 1 + (t / 60) % Math.max(1, group.size() - 1)) % group.size() : current;
                    group.get(i).anim.look(group.get(lookAt).body.getEyeLocation());
                }
            }
            if (endAt >= 0) {
                if (t >= endAt) done = true;
                return;
            }
            if (t < nextLine || speaker < 0) return;
            Worker who = group.get(speaker);
            String text = lines.get(line);
            int show = 40 + text.length() * 2;
            Bubbles.say(who.plugin, who.body, text, show);
            Voice.speak(who, text);
            if (who.random.nextInt(4) == 0) who.body.swingMainHand(); // a gesture
            for (Worker w : group) if (w != who && w.random.nextInt(3) == 0) w.anim.nod();
            line++;
            nextLine = t + show - 5 + who.random.nextInt(10);
            if (line >= lines.size()) endAt = t + show + 10;
        }

        private static double horizontal(Location a, Location b) {
            double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** Each settler in the conversation runs one of these; the leader drives it. */
    private static final class Member implements Activity {
        private final Talk talk;
        private final Worker me;
        private final boolean leader;

        Member(Talk talk, Worker me, boolean leader) {
            this.talk = talk;
            this.me = me;
            this.leader = leader;
        }

        @Override
        public boolean tick() {
            if (talk.done) return true;
            if (leader && talk.t > 2) {
                for (Worker w : talk.group) {
                    if (!(w.activity instanceof Member m) || m.talk != talk) {
                        talk.done = true; // someone got pulled away
                        return true;
                    }
                }
            }
            if (leader) talk.tick();
            return talk.done;
        }

        @Override
        public void stop() {
            talk.done = true;
            me.mover.stop();
            Bubbles.clear(me.body);
        }

        @Override
        public String label() {
            List<String> names = new ArrayList<>();
            for (Worker w : talk.group) if (w != me) names.add(w.name);
            return "Talking with " + String.join(" and ", names);
        }

        @Override
        public boolean freeTime() {
            return true;
        }
    }
}
