package settlement;

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
    private final SettlementPlugin plugin;
    private final File file;
    private final Map<String, Town> byId = new LinkedHashMap<>();

    Towns(SettlementPlugin plugin) {
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

    /** The settlement with an expansion bell here. */
    Town atExpansionBell(org.bukkit.block.Block b) {
        for (Town t : byId.values()) if (t.expansionAt(b) != null) return t;
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
            s.set("consumption", t.consumption.name());
            s.set("auto-jobs", t.autoJobs);
            s.set("radius", t.radius);
            List<String> exps = new ArrayList<>();
            for (Town.Expansion x : t.expansions) exps.add(Keys.ser(x.bell) + ";" + x.radius);
            s.set("expansions", exps);
            List<String> mem = new ArrayList<>();
            for (UUID m : t.members) mem.add(m.toString());
            s.set("members", mem);
            for (Map.Entry<String, boolean[]> e : t.chestRules.entrySet()) {
                s.set("chests." + e.getKey().replace('.', '_'), List.of(e.getValue()[0], e.getValue()[1]));
            }
            for (Map.Entry<Material, Integer> e : t.targets.entrySet()) {
                s.set("targets." + e.getKey().name(), Math.max(0, e.getValue()));
            }
            for (Map.Entry<String, Integer> e : t.potionTargets.entrySet()) s.set("potion-targets." + e.getKey().replace(':', '-'), e.getValue());
            for (Map.Entry<String, Town.Filter> e : t.chestFilters.entrySet()) {
                Town.Filter f = e.getValue();
                if (f.mode == Town.FilterMode.OFF && f.items.isEmpty()) continue;
                String k = "filters." + e.getKey().replace('.', '_');
                s.set(k + ".mode", f.mode.name());
                List<String> items = new ArrayList<>();
                for (Material m : f.items) items.add(m.name());
                s.set(k + ".items", items);
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
                w.set("herd-size", wp.herd);
                for (Map.Entry<String, Integer> h : wp.herds.entrySet()) w.set("herd-sizes." + h.getKey(), h.getValue());
                for (Map.Entry<String, Boolean> o : wp.options.entrySet()) w.set("options." + o.getKey(), o.getValue());
            }
            for (Map.Entry<UUID, String> e : t.settlers.entrySet()) {
                s.set("settlers." + e.getKey(), e.getValue());
                String at = Keys.ser(t.lastSeen.get(e.getKey()));
                if (at != null) s.set("last-seen." + e.getKey(), at);
            }
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
            try {
                t.consumption = Town.Consumption.valueOf(s.getString("consumption", "FOOD"));
            } catch (IllegalArgumentException ignored) {
            }
            t.autoJobs = s.getBoolean("auto-jobs", false);
            t.radius = s.getInt("radius", Town.BASE);
            for (String x : s.getStringList("expansions")) {
                int semi = x.lastIndexOf(';');
                Location eb = semi < 0 ? null : Keys.deser(x.substring(0, semi));
                try {
                    if (eb != null) t.expansions.add(new Town.Expansion(eb, Integer.parseInt(x.substring(semi + 1))));
                } catch (NumberFormatException ignored) {
                }
            }
            for (String m : s.getStringList("members")) {
                try {
                    t.members.add(UUID.fromString(m));
                } catch (IllegalArgumentException ignored) {
                }
            }
            ConfigurationSection chests = s.getConfigurationSection("chests");
            if (chests != null) {
                for (String k : chests.getKeys(false)) {
                    List<Boolean> v = chests.getBooleanList(k);
                    if (v.size() == 2) t.chestRules.put(k, new boolean[]{v.get(0), v.get(1)});
                }
            }
            ConfigurationSection pt = s.getConfigurationSection("potion-targets");
            if (pt != null) for (String k : pt.getKeys(false)) t.potionTargets.put(k.replace('-', ':'), pt.getInt(k));
            ConfigurationSection filters = s.getConfigurationSection("filters");
            if (filters != null) {
                for (String k : filters.getKeys(false)) {
                    Town.Filter f = new Town.Filter();
                    try {
                        f.mode = Town.FilterMode.valueOf(filters.getString(k + ".mode", "OFF"));
                    } catch (IllegalArgumentException ignored) {
                    }
                    for (String m : filters.getStringList(k + ".items")) {
                        Material mat = Material.matchMaterial(m);
                        if (mat != null) f.items.add(mat);
                    }
                    t.chestFilters.put(k, f);
                }
                // Both halves of a double chest share one filter again.
                for (String k : new ArrayList<>(t.chestFilters.keySet())) {
                    Location l = Keys.deser(k);
                    if (l == null || l.getWorld() == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
                    for (org.bukkit.block.Block part : Town.halves(l.getBlock())) t.chestFilters.put(Keys.ser(part.getLocation()), t.chestFilters.get(k));
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
                    wp.herd = w.getInt("herd-size", 6);
                    ConfigurationSection herds = w.getConfigurationSection("herd-sizes");
                    if (herds != null) for (String kind : herds.getKeys(false)) wp.herds.put(kind, herds.getInt(kind));
                    ConfigurationSection opts = w.getConfigurationSection("options");
                    if (opts != null) for (String opt : opts.getKeys(false)) wp.options.put(opt, opts.getBoolean(opt));
                    try {
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
                        Location at = Keys.deser(s.getString("last-seen." + k));
                        if (at != null) t.lastSeen.put(UUID.fromString(k), at);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            byId.put(id, t);
        }
    }
}
