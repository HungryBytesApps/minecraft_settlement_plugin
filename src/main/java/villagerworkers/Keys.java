package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Data keys stored on the villager (survive restarts with the entity) and location helpers. */
final class Keys {
    private Keys() {}

    static NamespacedKey OWNER, NAME, TOWN, WORKPLACE, BED, TEMP, LEDGER, SKIN, POCKETS, ROLE, STANCE, POST, GUARD_RADIUS, WEAPON;

    static void init(Plugin p) {
        OWNER = new NamespacedKey(p, "owner");
        NAME = new NamespacedKey(p, "name");
        TOWN = new NamespacedKey(p, "town");
        WORKPLACE = new NamespacedKey(p, "workplace");
        BED = new NamespacedKey(p, "bed");
        TEMP = new NamespacedKey(p, "temp"); // marks speech bubbles and seats
        LEDGER = new NamespacedKey(p, "ledger");
        SKIN = new NamespacedKey(p, "skin");
        POCKETS = new NamespacedKey(p, "pockets");
        ROLE = new NamespacedKey(p, "role");
        STANCE = new NamespacedKey(p, "stance");
        POST = new NamespacedKey(p, "post");
        GUARD_RADIUS = new NamespacedKey(p, "guard_radius");
        WEAPON = new NamespacedKey(p, "weapon");
    }

    static String ser(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    static Location deser(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String pos(Location l) {
        return l == null ? "-" : l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }
}
