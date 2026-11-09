package villagerworkers;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Storage targets as a grid: every product a button with its icon, what's in storage and the
 * target. Click one to change its target with − / + buttons (no typing, applies at once).
 */
final class TargetsMenu {
    private TargetsMenu() {}

    private static final int COLUMNS = 4;

    /** What this settlement's workplaces can produce (farm products if it has none yet). */
    static List<Material> products(Town t) {
        java.util.Set<Worker.Job> jobs = java.util.EnumSet.noneOf(Worker.Job.class);
        for (Town.Workplace wp : t.workplaces.values()) jobs.add(wp.job());
        java.util.LinkedHashSet<Material> out = new java.util.LinkedHashSet<>();
        if (jobs.contains(Worker.Job.FARMER) || jobs.isEmpty()) out.addAll(FarmerJob.TARGET_ITEMS);
        if (jobs.contains(Worker.Job.HERDER)) out.addAll(HerderJob.PRODUCTS);
        if (jobs.contains(Worker.Job.LUMBERJACK)) out.addAll(LumberjackJob.PRODUCTS);
        for (Worker.Job j : new Worker.Job[]{Worker.Job.COOK, Worker.Job.SMELTER, Worker.Job.FURNACE_WORKER}) {
            if (!jobs.contains(j)) continue;
            for (Town.Workplace wp : t.workplaces.values()) if (wp.job() == j) out.addAll(FurnaceJob.recipes(wp).values());
        }
        if (jobs.contains(Worker.Job.SMITH)) out.addAll(SmithJob.PRODUCTS);
        return new ArrayList<>(out);
    }

    /** Smith products: 0 means "don't make" instead of "no limit". */
    private static boolean madeToOrder(Material m) {
        return SmithJob.PRODUCTS.contains(m);
    }

    static void open(VillagerWorkersPlugin plugin, Player p, Town t) {
        open(plugin, p, t, null);
    }

    /** The grid; {@code only}: just these products (the smith's "What to make"). */
    static void open(VillagerWorkersPlugin plugin, Player p, Town t, List<Material> only) {
        int radius = plugin.settings().townRadius();
        List<ActionButton> buttons = new ArrayList<>();
        for (Material m : only != null ? only : products(t)) {
            int target = t.targets.getOrDefault(m, 0);
            int stored = t.count(m, radius);
            boolean reached = target > 0 && stored >= target;
            String none = madeToOrder(m) ? "off" : "∞";
            Component label = icon(m).append(Component.text(" " + stored + " / " + (target > 0 ? target : none),
                    reached ? NamedTextColor.GREEN : NamedTextColor.WHITE));
            Component tip = Component.text(nice(m) + ": " + stored + " in storage, target " + (target > 0 ? target : madeToOrder(m) ? "don't make" : "no limit"));
            buttons.add(ActionButton.create(label, tip, 90,
                    Menus.click(plugin, (view, audience) -> {
                        if (audience instanceof Player pl) org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> item(plugin, pl, t, m, only));
                    }, Menus.ONCE)));
        }
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> Menus.ledger(plugin, pl, t));
        List<DialogBody> body = List.of(Menus.line("In storage / target", NamedTextColor.GRAY));
        Menus.show(p, only != null ? "What to make" : "Storage targets", body, List.of(),
                DialogType.multiAction(buttons).columns(Math.min(COLUMNS, Math.max(1, buttons.size()))).exitAction(back).build());
    }

    /** One product: change its target in steps, applied at once. */
    private static void item(VillagerWorkersPlugin plugin, Player p, Town t, Material m, List<Material> only) {
        int target = t.targets.getOrDefault(m, 0);
        int stored = t.count(m, plugin.settings().townRadius());
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(icon(m).append(Component.text(" " + nice(m), NamedTextColor.WHITE))),
                Menus.line("In storage: " + stored + "   ·   Target: " + (target > 0 ? target : madeToOrder(m) ? "don't make" : "no limit"), NamedTextColor.GRAY));
        List<ActionButton> buttons = new ArrayList<>();
        for (int step : new int[]{-64, -16, 16, 64}) {
            buttons.add(Menus.button(plugin, (step > 0 ? "+" : "−") + Math.abs(step), NamedTextColor.WHITE, null, 70, pl -> {
                int now = Math.max(0, t.targets.getOrDefault(m, 0) + step);
                if (now == 0) t.targets.remove(m);
                else t.targets.put(m, now);
                plugin.towns().save();
                item(plugin, pl, t, m, only);
            }));
        }
        buttons.add(Menus.button(plugin, madeToOrder(m) ? "Don't make" : "No limit", NamedTextColor.WHITE,
                madeToOrder(m) ? "The smith stops making this" : "Keep producing, whatever is in storage", 140, pl -> {
                    t.targets.remove(m);
                    plugin.towns().save();
                    item(plugin, pl, t, m, only);
                }));
        buttons.add(Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 140, pl -> open(plugin, pl, t, only)));
        Menus.show(p, nice(m), body, List.of(), DialogType.multiAction(buttons).columns(4).build());
    }

    /** The item's own icon inside the text (uses the player's resource pack). */
    static Component icon(Material m) {
        // Since 1.21.11 item textures live in their own "items" atlas.
        return Component.object(ObjectContents.sprite(Key.key("minecraft", "items"), Key.key("minecraft", "item/" + m.getKey().getKey())));
    }

    static String nice(Material m) {
        String s = FarmerJob.nice(m);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
