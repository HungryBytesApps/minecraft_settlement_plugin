package settlement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

/** /settlers info: everything about the settler you look at, to find out why it does nothing. */
final class Diagnose {
    private Diagnose() {}

    static void info(SettlementPlugin plugin, Player p) {
        say(p, "Settlement build " + SettlementPlugin.BUILD + ", " + plugin.workers().all().size() + " settlers running.", NamedTextColor.GOLD);
        Entity e = p.getTargetEntity(16);
        if (e == null) {
            say(p, "Look at a settler (within 16 blocks) and try again.", NamedTextColor.GRAY);
            return;
        }
        say(p, "Looking at: " + e.getType() + " " + e.getUniqueId().toString().substring(0, 8), NamedTextColor.WHITE);
        if (!(e instanceof Mannequin m) || !Worker.isWorker(m)) {
            say(p, "Not a settler.", NamedTextColor.RED);
            return;
        }
        Worker w = plugin.workers().get(m.getUniqueId());
        if (w == null) {
            say(p, "Settler is NOT running (not registered). Starting it now.", NamedTextColor.RED);
            plugin.load(m);
            w = plugin.workers().get(m.getUniqueId());
            if (w == null) {
                say(p, "Could not start it (its settlement is missing?).", NamedTextColor.RED);
                return;
            }
        }
        Location l = m.getLocation();
        say(p, w.name + " — " + w.title() + ", town " + (w.town() == null ? "NONE" : w.town().name), NamedTextColor.WHITE);
        say(p, "Doing: " + w.doing() + (w.activity == null ? "" : " (" + w.activity.getClass().getSimpleName() + ")")
                + ", status: " + w.workStatus, NamedTextColor.WHITE);
        say(p, "Mover: " + w.mover.debug(), NamedTextColor.WHITE);
        say(p, String.format("At %.1f %.1f %.1f, in vehicle: %s, valid: %s, ticks lived: %d, immovable: %s, gravity: %s, AI: %s",
                l.getX(), l.getY(), l.getZ(), m.isInsideVehicle(), m.isValid(), m.getTicksLived(),
                m.isImmovable(), m.hasGravity(), m.hasAI()), NamedTextColor.WHITE);
        int now = org.bukkit.Bukkit.getCurrentTick();
        say(p, "Ticked " + w.ticks + " times, last right-click event: "
                + (w.lastClickSeen < 0 ? "never" : (now - w.lastClickSeen) / 20 + " s ago"), NamedTextColor.WHITE);
        say(p, "Night: " + w.isNight() + ", guard: " + w.guard + ", health: " + w.healthText() + ", owner you: " + w.canManage(p), NamedTextColor.WHITE);
    }

    private static void say(Player p, String s, NamedTextColor c) {
        p.sendMessage(Component.text(s, c));
    }
}
