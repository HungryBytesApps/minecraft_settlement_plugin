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
        List<String> skins,
        List<String> names,
        List<String> chatLines
) {
    static Settings from(FileConfiguration c) {
        List<String> names = c.getStringList("names");
        if (names.isEmpty()) names = List.of("Bob");
        List<String> lines = c.getStringList("chat-lines");
        if (lines.isEmpty()) lines = List.of("Hmm...");
        List<String> skins = c.getStringList("skins");
        List<ItemStack> cost = new ArrayList<>();
        for (String s : c.getStringList("recruit-cost")) {
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
        int maxRadius = (int) clamp(c.getInt("max-work-radius", 12), 2, 32);
        return new Settings(
                (int) clamp(c.getInt("max-settlements-per-player", 1), 0, 100),
                (int) clamp(c.getInt("settlement-radius", 48), 8, 128),
                (int) clamp(c.getInt("max-settlers-per-settlement", 10), 0, 1000),
                c.getBoolean("chests-allowed-by-default", true),
                cost,
                c.getBoolean("show-names", true),
                clamp(c.getDouble("work-speed", 0.11), 0.02, 0.4),
                clamp(c.getDouble("walk-speed", 0.07), 0.02, 0.4),
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
                skins.isEmpty() ? List.of("steve", "alex") : skins,
                names,
                lines
        );
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
