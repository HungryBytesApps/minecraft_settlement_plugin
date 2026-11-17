package settlement;

import org.bukkit.entity.Mannequin;

import java.util.*;

/** The workers that are currently loaded in the world. */
final class Workers {
    private final SettlementPlugin plugin;
    private final Map<UUID, Worker> loaded = new LinkedHashMap<>();

    Workers(SettlementPlugin plugin) {
        this.plugin = plugin;
    }

    Worker register(Mannequin v) {
        Worker w = loaded.get(v.getUniqueId());
        if (w != null && w.body == v) return w;
        if (w != null) w.stop();
        w = new Worker(plugin, v);
        w.applyLook();
        loaded.put(v.getUniqueId(), w);
        return w;
    }

    void unregister(UUID id) {
        Worker w = loaded.remove(id);
        if (w != null) w.stop();
    }

    Worker get(UUID id) {
        return loaded.get(id);
    }

    Collection<Worker> all() {
        return new ArrayList<>(loaded.values());
    }

    void tick() {
        for (Worker w : all()) {
            if (!w.body.isValid()) continue;
            try {
                w.tick();
            } catch (RuntimeException ex) {
                // One broken settler must not freeze all the others. Log it (at most once a minute).
                int now = org.bukkit.Bukkit.getCurrentTick();
                if (now >= w.errorLoggedUntil) {
                    w.errorLoggedUntil = now + 1200;
                    w.plugin.getLogger().log(java.util.logging.Level.WARNING, "Settler " + w.name + " ran into an error", ex);
                }
            }
        }
    }
}
