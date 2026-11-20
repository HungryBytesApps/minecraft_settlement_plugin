package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Data keys stored on the villager (survive restarts with the entity) and location helpers. */
final class Keys {
    /** The id under which settlers, ledgers and private chests store their data. */
    static final String NS = "settlement";

    private Keys() {}

    static NamespacedKey OWNER, NAME, TOWN, WORKPLACE, BED, TEMP, LEDGER, SKIN, POCKETS, ROLE, STANCE, POST, GUARD_RADIUS, WEAPON;

    static void init(Plugin p) {
        OWNER = new NamespacedKey(Keys.NS, "owner");
        NAME = new NamespacedKey(Keys.NS, "name");
        TOWN = new NamespacedKey(Keys.NS, "town");
        WORKPLACE = new NamespacedKey(Keys.NS, "workplace");
        BED = new NamespacedKey(Keys.NS, "bed");
        TEMP = new NamespacedKey(Keys.NS, "temp"); // marks speech bubbles and seats
        LEDGER = new NamespacedKey(Keys.NS, "ledger");
        SKIN = new NamespacedKey(Keys.NS, "skin");
        POCKETS = new NamespacedKey(Keys.NS, "pockets");
        ROLE = new NamespacedKey(Keys.NS, "role");
        STANCE = new NamespacedKey(Keys.NS, "stance");
        POST = new NamespacedKey(Keys.NS, "post");
        GUARD_RADIUS = new NamespacedKey(Keys.NS, "guard_radius");
        WEAPON = new NamespacedKey(Keys.NS, "weapon");
    }

    /** Random per server start: temporary entities from an earlier run are leftovers. */
    static final int SESSION = new java.util.Random().nextInt();

    static void markTemp(org.bukkit.entity.Entity e) {
        e.getPersistentDataContainer().set(TEMP, org.bukkit.persistence.PersistentDataType.INTEGER, SESSION);
    }

    static boolean isTemp(org.bukkit.entity.Entity e) {
        return e.getPersistentDataContainer().has(TEMP);
    }

    /** A bubble, seat or panel left over from before a restart. */
    static boolean isLeftover(org.bukkit.entity.Entity e) {
        if (!isTemp(e)) return false;
        Integer s = e.getPersistentDataContainer().get(TEMP, org.bukkit.persistence.PersistentDataType.INTEGER);
        return s == null || s != SESSION;
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
