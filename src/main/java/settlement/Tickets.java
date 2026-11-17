package settlement;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * Chunk tickets (keep a chunk loaded) shared by everything in the plugin: the settlements, working miners, a
 * search for a far-away settler. A chunk ticket is one per plugin, so they're counted here: a chunk stays loaded
 * while anything still holds it.
 */
final class Tickets {
    private Tickets() {}

    private static Plugin plugin;
    /** world:x:z → who holds it. */
    private static final Map<String, Set<String>> held = new HashMap<>();

    static void init(Plugin p) {
        plugin = p;
        held.clear();
    }

    private static String key(World w, int x, int z) {
        return w.getName() + ":" + x + ":" + z;
    }

    static void hold(World w, int x, int z, String who) {
        Set<String> s = held.computeIfAbsent(key(w, x, z), k -> new HashSet<>());
        if (s.isEmpty()) w.addPluginChunkTicket(x, z, plugin);
        s.add(who);
    }

    static void release(World w, int x, int z, String who) {
        String k = key(w, x, z);
        Set<String> s = held.get(k);
        if (s == null || !s.remove(who)) return;
        if (s.isEmpty()) {
            held.remove(k);
            w.removePluginChunkTicket(x, z, plugin);
        }
    }

    /** Everything this holder holds. */
    static void releaseAll(String who) {
        for (Iterator<Map.Entry<String, Set<String>>> it = held.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Set<String>> e = it.next();
            if (!e.getValue().remove(who) || !e.getValue().isEmpty()) continue;
            it.remove();
            String[] p = e.getKey().split(":");
            World w = plugin.getServer().getWorld(p[0]);
            if (w != null) w.removePluginChunkTicket(Integer.parseInt(p[1]), Integer.parseInt(p[2]), plugin);
        }
    }

    /** Holders starting with this (e.g. all settlements: "town:"). */
    static void releasePrefix(String prefix) {
        Set<String> who = new HashSet<>();
        for (Set<String> s : held.values()) for (String h : s) if (h.startsWith(prefix)) who.add(h);
        for (String h : who) releaseAll(h);
    }

    static void clear() {
        for (World w : plugin.getServer().getWorlds()) w.removePluginChunkTickets(plugin);
        held.clear();
    }
}
