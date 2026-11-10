package villagerworkers;

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
        STORAGE("Storage full"), DEATH("Settler died");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        NamespacedKey key() {
            return new NamespacedKey("villagerworkers", "notify_" + name().toLowerCase(Locale.ROOT));
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

    /** Send to the player if they want this kind of message. */
    static void send(UUID player, Kind k, String text, NamedTextColor color) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && on(p, k)) p.sendMessage(Component.text(text, color));
    }
}
