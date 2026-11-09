package villagerworkers;

import org.bukkit.entity.Villager;

import java.util.*;

/** The workers that are currently loaded in the world. */
final class Workers {
    private final VillagerWorkersPlugin plugin;
    private final Map<UUID, Worker> loaded = new LinkedHashMap<>();

    Workers(VillagerWorkersPlugin plugin) {
        this.plugin = plugin;
    }

    Worker register(Villager v) {
        Worker w = loaded.get(v.getUniqueId());
        if (w != null && w.villager == v) return w;
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
            if (!w.villager.isValid()) continue;
            w.tick();
        }
    }
}
