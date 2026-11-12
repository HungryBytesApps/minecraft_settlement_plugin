package settlement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.UUID;

/** Which settlement messages a player gets in the chat (per player, all on by default). */
final class Notify {
    private Notify() {}

    enum Kind {
        ATTACK("Under attack / all clear"), BELL("Alarm bell rings"), TOOLS("Missing tools & weapons"),
        STORAGE("Storage full"), DEATH("Settler died"), BEES("Beehive needs room for a campfire");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        NamespacedKey key() {
            return new NamespacedKey(Keys.NS, "notify_" + name().toLowerCase(Locale.ROOT));
        }
    }

    static boolean on(Player p, Kind k) {
        Byte b = p.getPersistentDataContainer().get(k.key(), PersistentDataType.BYTE);
        return b == null || b != 0;
    }

    static boolean on(UUID player, Kind k) {
        Player p = Bukkit.getPlayer(player);
        return p == null || on(p, k); // offline: nothing to send anyway
    }

    static void toggle(Player p, Kind k) {
        p.getPersistentDataContainer().set(k.key(), PersistentDataType.BYTE, (byte) (on(p, k) ? 0 : 1));
    }

    /** Send to the settlement's owner and members (or just the given player without a settlement). */
    static void send(Town t, UUID fallback, Kind k, String text, NamedTextColor color) {
        if (k == Kind.TOOLS || k == Kind.STORAGE || k == Kind.BEES) TownLog.town(t, text, color); // management problems: also in the ledger's log
        if (t == null) send(fallback, k, text, color);
        else for (UUID id : t.people()) send(id, k, text, color);
    }

    /** Unless everyone of the settlement who is online switched it off. */
    static boolean anyOn(Town t, Kind k) {
        boolean someoneOnline = false;
        for (UUID id : t.people()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (on(p, k)) return true;
            someoneOnline = true;
        }
        return !someoneOnline;
    }

    /** Send to the player if they want this kind of message. */
    static void send(UUID player, Kind k, String text, NamedTextColor color) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && on(p, k)) p.sendMessage(Component.text(text, color));
    }
}
