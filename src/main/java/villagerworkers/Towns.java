package villagerworkers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** All towns, saved in towns.yml. */
final class Towns {
    private final VillagerWorkersPlugin plugin;
    private final File file;
    private final Map<String, Town> byId = new LinkedHashMap<>();

    Towns(VillagerWorkersPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "towns.yml");
        load();
    }

    Town get(String id) {
        return id == null ? null : byId.get(id);
    }

    Collection<Town> all() {
        return new ArrayList<>(byId.values());
    }

    Town atBell(Block b) {
        for (Town t : byId.values()) {
            if (t.bell.getWorld() == b.getWorld() && t.bell.getBlockX() == b.getX()
                    && t.bell.getBlockY() == b.getY() && t.bell.getBlockZ() == b.getZ()) return t;
        }
        return null;
    }

    /** The town whose area this location is in. */
    Town at(Location l) {
        int r = plugin.settings().townRadius();
        Town best = null;
        double bestD = Double.MAX_VALUE;
        for (Town t : byId.values()) {
            if (!t.contains(l, r)) continue;
            double d = t.bell.distanceSquared(l);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    List<Town> ownedBy(UUID owner) {
        List<Town> out = new ArrayList<>();
        for (Town t : byId.values()) if (t.owner.equals(owner)) out.add(t);
        return out;
    }

    Town create(UUID owner, String name, Location bell) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Town t = new Town(id, owner, name, bell.getBlock().getLocation(), plugin.settings().chestDefault());
        byId.put(id, t);
        save();
        return t;
    }

    void remove(Town t) {
        byId.remove(t.id);
        save();
    }

    void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Town t : byId.values()) {
            ConfigurationSection s = yml.createSection("towns." + t.id);
            s.set("owner", t.owner.toString());
            s.set("name", t.name);
            s.set("bell", Keys.ser(t.bell));
            s.set("chest-default", t.chestDefault);
            s.set("when-attacked", t.reaction.name());
            s.set("may-eat", t.mayEat);
            s.set("auto-jobs", t.autoJobs);
            s.set("guard-potions", t.guardPotions);
            for (Map.Entry<String, boolean[]> e : t.chestRules.entrySet()) {
                s.set("chests." + e.getKey().replace('.', '_'), List.of(e.getValue()[0], e.getValue()[1]));
            }
            for (Map.Entry<Material, Integer> e : t.targets.entrySet()) {
                if (e.getValue() > 0) s.set("targets." + e.getKey().name(), e.getValue());
            }
            int i = 0;
            for (Town.Workplace wp : t.workplaces.values()) {
                ConfigurationSection w = s.createSection("workplaces.w" + (i++));
                w.set("loc", Keys.ser(wp.loc));
                w.set("radius", wp.radius);
                w.set("enabled", wp.enabled);
                List<String> ids = new ArrayList<>();
                for (UUID id : wp.workers) ids.add(id.toString());
                w.set("workers", ids);
                w.set("herd", wp.herd);
                for (Map.Entry<String, Integer> h : wp.herds.entrySet()) w.set("herds." + h.getKey(), h.getValue());
                for (Map.Entry<String, Boolean> o : wp.options.entrySet()) w.set("options." + o.getKey(), o.getValue());
            }
            for (Map.Entry<UUID, String> e : t.settlers.entrySet()) s.set("settlers." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save towns.yml: " + ex.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("towns");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Location bell = Keys.deser(s.getString("bell"));
            if (bell == null) continue;
            UUID owner;
            try {
                owner = UUID.fromString(s.getString("owner", ""));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Town t = new Town(id, owner, s.getString("name", "Town"), bell, s.getBoolean("chest-default", true));
            try {
                t.reaction = Town.Reaction.valueOf(s.getString("when-attacked", "TAKE_COVER"));
            } catch (IllegalArgumentException ignored) {
            }
            t.mayEat = s.getBoolean("may-eat", true);
            t.autoJobs = s.getBoolean("auto-jobs", false);
            t.guardPotions = s.getBoolean("guard-potions", false);
            ConfigurationSection chests = s.getConfigurationSection("chests");
            if (chests != null) {
                for (String k : chests.getKeys(false)) {
                    List<Boolean> v = chests.getBooleanList(k);
                    if (v.size() == 2) t.chestRules.put(k, new boolean[]{v.get(0), v.get(1)});
                }
            }
            ConfigurationSection targets = s.getConfigurationSection("targets");
            if (targets != null) {
                for (String k : targets.getKeys(false)) {
                    Material m = Material.getMaterial(k);
                    if (m != null) t.targets.put(m, targets.getInt(k));
                }
            }
            ConfigurationSection wps = s.getConfigurationSection("workplaces");
            if (wps != null) {
                for (String k : wps.getKeys(false)) {
                    ConfigurationSection w = wps.getConfigurationSection(k);
                    if (w == null) continue;
                    Location loc = Keys.deser(w.getString("loc"));
                    if (loc == null) continue;
                    Town.Workplace wp = new Town.Workplace(loc, w.getInt("radius", plugin.settings().defaultRadius()));
                    wp.enabled = w.getBoolean("enabled", true);
                    wp.herd = w.getInt("herd", 6);
                    ConfigurationSection herds = w.getConfigurationSection("herds");
                    if (herds != null) for (String kind : herds.getKeys(false)) wp.herds.put(kind, herds.getInt(kind));
                    ConfigurationSection opts = w.getConfigurationSection("options");
                    if (opts != null) for (String opt : opts.getKeys(false)) wp.options.put(opt, opts.getBoolean(opt));
                    try {
                        String worker = w.getString("worker");
                        if (worker != null) wp.workers.add(UUID.fromString(worker)); // older versions: one worker
                        for (String workerId : w.getStringList("workers")) wp.workers.add(UUID.fromString(workerId));
                    } catch (IllegalArgumentException ignored) {
                    }
                    t.workplaces.put(Keys.ser(loc), wp);
                }
            }
            ConfigurationSection settlers = s.getConfigurationSection("settlers");
            if (settlers != null) {
                for (String k : settlers.getKeys(false)) {
                    try {
                        t.settlers.put(UUID.fromString(k), settlers.getString(k, "Settler"));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            byId.put(id, t);
        }
    }
}
