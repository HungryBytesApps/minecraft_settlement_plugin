package villagerworkers;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record Settings(
        int maxTowns,
        int townRadius,
        int maxSettlers,
        boolean chestDefault,
        List<ItemStack> recruitCost,
        boolean showNames,
        double workSpeed,
        double walkSpeed,
        int defaultRadius,
        int maxRadius,
        int socialRadius,
        boolean workAtNight,
        int seedsToKeep,
        double sitHeight,
        int arrowsToCarry,
        int guardRadius,
        int regenSeconds,
        int eatBelowPercent,
        org.bukkit.Particle borderParticle,
        org.bukkit.Particle workParticle,
        List<String> skins,
        List<String> names,
        List<String> chatLines,
        java.util.Map<String, List<String>> jobSkins,
        List<String> maleNames,
        List<String> femaleNames,
        java.util.Map<String, String> skinGender,
        boolean talkSounds,
        String voiceSound,
        String laughSound,
        java.util.TreeMap<Integer, List<ItemStack>> recruitTiers,
        int recruitIncrease
) {
    /** What the next settler costs, with this many settlers already living there. */
    List<ItemStack> recruitCostFor(int settlers) {
        if (!recruitTiers.isEmpty()) {
            java.util.Map.Entry<Integer, List<ItemStack>> e = recruitTiers.floorEntry(settlers);
            return e != null ? e.getValue() : recruitTiers.firstEntry().getValue();
        }
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : recruitCost) {
            ItemStack c = it.clone();
            c.setAmount((int) Math.ceil(it.getAmount() * (1 + recruitIncrease * settlers / 100.0)));
            out.add(c);
        }
        return out;
    }

    static Settings from(FileConfiguration c) {
        List<String> names = c.getStringList("names");
        if (names.isEmpty()) names = List.of("Bob");
        List<String> lines = c.getStringList("chat-lines");
        if (lines.isEmpty()) lines = List.of("Hmm...");
        List<String> skins = c.getStringList("skins");
        List<ItemStack> cost = parseCost(c.getStringList("recruit-cost"));
        // recruit-cost can also be a list per number of settlers: "0: [...]", "3: [...]"
        java.util.TreeMap<Integer, List<ItemStack>> tiers = new java.util.TreeMap<>();
        org.bukkit.configuration.ConfigurationSection rc = c.getConfigurationSection("recruit-cost");
        if (rc != null) {
            for (String k : rc.getKeys(false)) {
                try {
                    tiers.put(Integer.parseInt(k.trim()), parseCost(rc.getStringList(k)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        int maxRadius = (int) clamp(c.getInt("max-work-radius", 12), 2, 32);
        return build(c, cost, tiers, names, lines, skins, maxRadius);
    }

    private static List<ItemStack> parseCost(List<String> list) {
        List<ItemStack> cost = new ArrayList<>();
        for (String s : list) {
            String[] p = s.split(":");
            Material m = Material.matchMaterial(p[0].trim().toLowerCase(Locale.ROOT));
            if (m == null || !m.isItem()) continue;
            int n = 1;
            try {
                if (p.length > 1) n = Integer.parseInt(p[1].trim());
            } catch (NumberFormatException ignored) {
            }
            if (n > 0) cost.add(new ItemStack(m, n));
        }
        return cost;
    }

    private static Settings build(FileConfiguration c, List<ItemStack> cost, java.util.TreeMap<Integer, List<ItemStack>> tiers,
                                  List<String> names, List<String> lines, List<String> skins, int maxRadius) {
        return new Settings(
                (int) clamp(c.getInt("max-settlements-per-player", 1), 0, 100),
                (int) clamp(c.getInt("settlement-radius", 48), 8, 128),
                (int) clamp(c.getInt("max-settlers-per-settlement", 10), 0, 1000),
                c.getBoolean("chests-allowed-by-default", true),
                cost,
                c.getBoolean("show-names", true),
                clamp(c.getDouble("work-speed", 0.15), 0.02, 0.4),
                clamp(c.getDouble("walk-speed", 0.11), 0.02, 0.4),
                (int) clamp(c.getInt("default-work-radius", 6), 2, maxRadius),
                maxRadius,
                (int) clamp(c.getInt("social-radius", 16), 4, 48),
                c.getBoolean("work-at-night", false),
                (int) clamp(c.getInt("seeds-to-keep", 16), 1, 64),
                clamp(c.getDouble("sit-height", 0.0), -1.5, 1.5),
                (int) clamp(c.getInt("arrows-to-carry", 32), 1, 256),
                (int) clamp(c.getInt("guard-radius", 16), 4, 48),
                (int) clamp(c.getInt("regen-seconds", 4), 1, 600),
                (int) clamp(c.getInt("eat-below-health-percent", 60), 1, 99),
                Borders.particle(c.getString("border-particle", "SOUL_FIRE_FLAME"), org.bukkit.Particle.SOUL_FIRE_FLAME),
                Borders.particle(c.getString("work-area-particle", "HAPPY_VILLAGER"), org.bukkit.Particle.HAPPY_VILLAGER),
                skins.isEmpty() ? List.of("steve", "alex") : skins,
                names,
                lines,
                jobSkins(c),
                orDefault(c.getStringList("names-male"), List.of("Bram", "Cor", "Daan", "Finn", "Gijs", "Hugo", "Jens", "Joep", "Kees", "Lars", "Otto", "Rik", "Sem", "Teun", "Thijs", "Wim")),
                orDefault(c.getStringList("names-female"), List.of("Anna", "Eva", "Femke", "Fleur", "Greet", "Ilse", "Lieke", "Lotte", "Maud", "Noor", "Pien", "Roos", "Saar", "Sanne", "Vera", "Yara")),
                skinGender(c),
                c.getBoolean("talk-sounds", true),
                c.getString("voice-sound", "minecraft:entity.villager.ambient"),
                c.getString("laugh-sound", "minecraft:entity.villager.yes"),
                tiers,
                (int) clamp(c.getInt("recruit-cost-increase", 20), 0, 1000)
        );
    }

    /** job-skins: farmer: [...], guard: [...]: skins per job (empty = the settler's own skin). */
    private static java.util.Map<String, List<String>> jobSkins(FileConfiguration c) {
        java.util.Map<String, List<String>> out = new java.util.HashMap<>();
        org.bukkit.configuration.ConfigurationSection s = c.getConfigurationSection("job-skins");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            List<String> l = s.getStringList(k);
            if (!l.isEmpty()) out.put(k.toLowerCase(Locale.ROOT).replace('_', '-'), l);
        }
        return out;
    }

    private static List<String> orDefault(List<String> l, List<String> def) {
        return l == null || l.isEmpty() ? def : l;
    }

    /** skin-gender: steve: male, alex: female… (built-in defaults if missing). */
    private static java.util.Map<String, String> skinGender(FileConfiguration c) {
        java.util.Map<String, String> m = new java.util.HashMap<>(java.util.Map.of(
                "steve", "male", "alex", "female", "ari", "female", "efe", "female", "kai", "male",
                "makena", "female", "noor", "female", "sunny", "male", "zuri", "male"));
        org.bukkit.configuration.ConfigurationSection s = c.getConfigurationSection("skin-gender");
        if (s != null) for (String k : s.getKeys(false)) m.put(k.toLowerCase(Locale.ROOT), String.valueOf(s.getString(k)).toLowerCase(Locale.ROOT));
        return m;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
