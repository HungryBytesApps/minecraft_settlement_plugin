package villagerworkers;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Storage targets, per workstation: pick a workstation (if the settlement has more than one kind),
 * then see its products in alphabetical order, each a row with its item icon and in storage / target.
 * Click a product to change its target with − / + buttons (no typing, applies at once).
 */
final class TargetsMenu {
    private TargetsMenu() {}

    /** The kinds of workstations in this settlement, in a fixed order. */
    private static List<Worker.Job> jobs(Town t) {
        Set<Worker.Job> set = EnumSet.noneOf(Worker.Job.class);
        for (Town.Workplace wp : t.workplaces.values()) if (wp.job() != Worker.Job.NONE) set.add(wp.job());
        return new ArrayList<>(set);
    }

    /** What a kind of workstation makes here, alphabetical. */
    static List<Material> products(Town t, Worker.Job job) {
        Set<Material> out = new HashSet<>();
        switch (job) {
            case FARMER -> out.addAll(FarmerJob.TARGET_ITEMS);
            case HERDER -> out.addAll(HerderJob.PRODUCTS);
            case LUMBERJACK -> out.addAll(LumberjackJob.PRODUCTS);
            case SMITH -> out.addAll(SmithJob.PRODUCTS);
            case COOK, SMELTER, FURNACE_WORKER -> {
                for (Town.Workplace wp : t.workplaces.values()) if (wp.job() == job) out.addAll(FurnaceJob.recipes(wp).values());
            }
            default -> {
            }
        }
        List<Material> list = new ArrayList<>(out);
        list.sort(Comparator.comparing(TargetsMenu::nice));
        return list;
    }

    private static String station(Worker.Job job) {
        return job.title + " (" + Work.nice(job.block) + ")";
    }

    /** Smith products: 0 means "don't make" instead of "no limit". */
    private static boolean madeToOrder(Material m) {
        return SmithJob.PRODUCTS.contains(m);
    }

    /** From the ledger: pick a workstation (or straight to its list if there's only one kind). */
    static void open(VillagerWorkersPlugin plugin, Player p, Town t) {
        List<Worker.Job> jobs = jobs(t);
        if (jobs.isEmpty()) jobs = List.of(Worker.Job.FARMER);
        if (jobs.size() == 1) {
            list(plugin, p, t, jobs.get(0), true);
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Worker.Job job : jobs) {
            buttons.add(Menus.button(plugin, station(job), NamedTextColor.WHITE, null, 200, pl -> list(plugin, pl, t, job, true)));
        }
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> Menus.ledger(plugin, pl, t));
        Menus.show(p, "Storage targets", List.of(), List.of(), DialogType.multiAction(buttons).columns(2).exitAction(back).build());
    }

    /** The smith's "What to make". */
    static void smith(VillagerWorkersPlugin plugin, Player p, Town t) {
        list(plugin, p, t, Worker.Job.SMITH, false);
    }

    /** One workstation's products: rows with the real item icon, then a button per product. */
    private static void list(VillagerWorkersPlugin plugin, Player p, Town t, Worker.Job job, boolean fromLedger) {
        int radius = plugin.settings().townRadius();
        List<DialogBody> body = new ArrayList<>();
        List<ActionButton> buttons = new ArrayList<>();
        for (Material m : products(t, job)) {
            int target = t.targets.getOrDefault(m, 0);
            int stored = t.count(m, radius);
            boolean reached = target > 0 && stored >= target;
            String none = madeToOrder(m) ? "off" : "∞";
            Component text = Component.text(nice(m) + "   ", NamedTextColor.WHITE)
                    .append(Component.text(stored + " / " + (target > 0 ? target : none), reached ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            body.add(DialogBody.item(new ItemStack(m)).description(DialogBody.plainMessage(text, 220))
                    .showDecorations(false).showTooltip(true).width(16).height(16).build());
            buttons.add(Menus.button(plugin, nice(m), NamedTextColor.WHITE, "Change the target", 110,
                    pl -> item(plugin, pl, t, m, job, fromLedger)));
        }
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> {
            if (fromLedger && jobs(t).size() > 1) open(plugin, pl, t);
            else Menus.ledger(plugin, pl, t);
        });
        String title = fromLedger ? "Storage targets: " + job.title : "What to make";
        Menus.show(p, title, body, List.of(),
                DialogType.multiAction(buttons).columns(Math.min(3, Math.max(1, buttons.size()))).exitAction(back).build());
    }

    /** One product: change its target in steps, applied at once. */
    private static void item(VillagerWorkersPlugin plugin, Player p, Town t, Material m, Worker.Job job, boolean fromLedger) {
        int target = t.targets.getOrDefault(m, 0);
        int stored = t.count(m, plugin.settings().townRadius());
        List<DialogBody> body = List.of(
                DialogBody.item(new ItemStack(m)).description(DialogBody.plainMessage(Component.text(nice(m), NamedTextColor.WHITE)))
                        .showDecorations(false).showTooltip(true).width(16).height(16).build(),
                Menus.line("In storage: " + stored + "   ·   Target: " + (target > 0 ? target : madeToOrder(m) ? "don't make" : "no limit"), NamedTextColor.GRAY));
        List<ActionButton> buttons = new ArrayList<>();
        for (int step : new int[]{-64, -16, 16, 64}) {
            buttons.add(Menus.button(plugin, (step > 0 ? "+" : "−") + Math.abs(step), NamedTextColor.WHITE, null, 70, pl -> {
                int now = Math.max(0, t.targets.getOrDefault(m, 0) + step);
                if (now == 0) t.targets.remove(m);
                else t.targets.put(m, now);
                plugin.towns().save();
                item(plugin, pl, t, m, job, fromLedger);
            }));
        }
        buttons.add(Menus.button(plugin, madeToOrder(m) ? "Don't make" : "No limit", NamedTextColor.WHITE,
                madeToOrder(m) ? "The smith stops making this" : "Keep producing, whatever is in storage", 140, pl -> {
                    t.targets.remove(m);
                    plugin.towns().save();
                    item(plugin, pl, t, m, job, fromLedger);
                }));
        buttons.add(Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 140, pl -> list(plugin, pl, t, job, fromLedger)));
        Menus.show(p, nice(m), body, List.of(), DialogType.multiAction(buttons).columns(4).build());
    }

    static String nice(Material m) {
        String s = FarmerJob.nice(m);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
