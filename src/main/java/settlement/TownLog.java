package settlement;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A settlement's problem log (in the ledger): missing tools and materials, full storage, places settlers
 * can't reach, settlers that got stuck, attacks, deaths... The newest 100 lines, kept in memory (gone after
 * a restart). The same message isn't added again for a while, so a lasting problem doesn't flood it; adding
 * a line costs next to nothing.
 */
final class TownLog {
    record Entry(long at, String text, NamedTextColor color) {}

    static final int MAX = 100;
    private static final int PAGE = 15;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    /** Message key → tick it was last added. */
    private final Map<String, Integer> recent = new HashMap<>();

    /** Add a line, unless the same key was added less than {@code window} ticks ago. */
    void add(String key, String text, NamedTextColor color, int window) {
        int now = Bukkit.getCurrentTick();
        Integer last = recent.get(key);
        if (last != null && now >= last && now - last < window) return;
        if (recent.size() > 300) recent.values().removeIf(v -> v > now || now - v > 24000);
        recent.put(key, now);
        entries.addFirst(new Entry(System.currentTimeMillis(), text, color));
        while (entries.size() > MAX) entries.removeLast();
    }

    int size() {
        return entries.size();
    }

    /** Something about a settler: "Bram [Fisher]: Needs a fishing rod" (the same again only after 10 minutes). */
    static void settler(Worker w, String text, NamedTextColor color) {
        Town t = w.town();
        if (t == null) return;
        t.log.add(w.body.getUniqueId() + ":" + text, w.name + " [" + w.title() + "]: " + text, color, 12000);
    }

    /** Something about the settlement (the same text again only after a minute). */
    static void town(Town t, String text, NamedTextColor color) {
        if (t != null) t.log.add(text, text, color, 1200);
    }

    /** A work status that means something is wrong (missing things, no room, nothing reachable...). */
    static boolean problem(String status) {
        if (status == null || status.isEmpty()) return false;
        return status.startsWith("Needs") || status.startsWith("Missing") || status.startsWith("No room") || status.startsWith("No water")
                || status.startsWith("No trees") || status.startsWith("No animals") || status.startsWith("Has no settlement")
                || (status.startsWith("Nothing to ") && status.endsWith(" in storage"));
    }

    /** "now", "5m", "2h 10m", "3d". */
    private static String ago(long at) {
        long min = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        if (min < 1) return "now";
        if (min < 60) return min + "m";
        if (min < 24 * 60) return (min / 60) + "h " + (min % 60) + "m";
        return (min / (24 * 60)) + "d";
    }

    // ---- In the ledger ----

    static void open(SettlementPlugin plugin, Player p, Town t, int page) {
        List<Entry> all = new ArrayList<>(t.log.entries);
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        List<DialogBody> body = new ArrayList<>();
        if (all.isEmpty()) body.add(Menus.line("Nothing has gone wrong.", NamedTextColor.GRAY));
        for (int i = pg * PAGE; i < Math.min(all.size(), (pg + 1) * PAGE); i++) {
            Entry e = all.get(i);
            body.add(DialogBody.plainMessage(Component.text(ago(e.at()) + "  ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(e.text(), e.color())), 400));
        }
        if (pages > 1) body.add(Menus.line("Page " + (pg + 1) + " of " + pages, NamedTextColor.DARK_GRAY));
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> Menus.ledger(plugin, pl, t));
        List<ActionButton> buttons = new ArrayList<>();
        if (pg > 0) buttons.add(Menus.button(plugin, "◀ Newer", NamedTextColor.WHITE, null, 100, pl -> open(plugin, pl, t, pg - 1)));
        if (pg < pages - 1) buttons.add(Menus.button(plugin, "Older ▶", NamedTextColor.WHITE, null, 100, pl -> open(plugin, pl, t, pg + 1)));
        if (!all.isEmpty()) buttons.add(Menus.button(plugin, "Clear", NamedTextColor.RED, "Empty the log", 100, pl -> {
            t.log.entries.clear();
            t.log.recent.clear(); // problems that are still there come back
            open(plugin, pl, t, 0);
        }));
        String title = "Problems";
        if (buttons.isEmpty()) {
            Menus.show(p, title, body, List.of(), DialogType.notice(back));
            return;
        }
        Menus.show(p, title, body, List.of(), DialogType.multiAction(buttons).columns(3).exitAction(back).build());
    }
}
