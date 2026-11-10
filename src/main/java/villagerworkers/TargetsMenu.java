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
        return Menus.station(job);
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

    /** One workstation's products, a row each: icon, name, in storage / target, then − and + right after it. */
    private static void list(VillagerWorkersPlugin plugin, Player p, Town t, Worker.Job job, boolean fromLedger) {
        int radius = plugin.settings().townRadius();
        List<ActionButton> cells = new ArrayList<>();
        for (Material m : products(t, job)) {
            Integer target = t.targets.get(m); // none = no limit (smith: don't make); 0 = keep none
            int stored = t.count(m, radius);
            boolean reached = target != null && stored >= target && (target > 0 || !madeToOrder(m));
            String none = madeToOrder(m) ? "off" : "∞";
            Component label = icon(m).append(Component.text(" " + nice(m) + "  ", NamedTextColor.WHITE))
                    .append(Component.text(stored + " / " + (target != null ? target : none), reached ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            Component tip = Component.text(nice(m) + ": " + stored + " in storage, target "
                    + (target == null ? (madeToOrder(m) ? "don't make" : "no limit") : target == 0 ? (madeToOrder(m) ? "don't make" : "none (don't keep any)") : target));
            cells.add(ActionButton.create(label, tip, 180, Menus.click(plugin, (view, audience) -> {
                if (audience instanceof Player pl) org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> list(plugin, pl, t, job, fromLedger));
            }, Menus.ONCE)));
            for (int step : new int[]{-64, -16, 16, 64}) {
                cells.add(Menus.button(plugin, (step > 0 ? "+" : "−") + Math.abs(step), step > 0 ? NamedTextColor.GREEN : NamedTextColor.RED,
                        null, 34, pl -> {
                            int now = Math.max(0, t.targets.getOrDefault(m, 0) + step);
                            t.targets.put(m, now); // 0 = don't keep any (∞ removes the target)
                            plugin.towns().save();
                            list(plugin, pl, t, job, fromLedger);
                        }));
            }
            cells.add(Menus.button(plugin, none, NamedTextColor.WHITE, madeToOrder(m) ? "Don't make" : "No limit", 30, pl -> {
                t.targets.remove(m);
                plugin.towns().save();
                list(plugin, pl, t, job, fromLedger);
            }));
        }
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> {
            if (fromLedger && jobs(t).size() > 1) open(plugin, pl, t);
            else Menus.ledger(plugin, pl, t);
        });
        String title = fromLedger ? "Storage targets: " + station(job) : "What to make";
        Menus.show(p, title, List.of(), List.of(), DialogType.multiAction(cells).columns(6).exitAction(back).build());
    }

    /** The item's own (small) icon inside the text. */
    static Component icon(Material m) {
        String name = m.getKey().getKey();
        if (m == Material.SHIELD) return Component.empty(); // drawn as a 3D model, no flat texture
        if (m == Material.CROSSBOW) name = "crossbow_standby";
        switch (m) { // blocks whose picture is not called after the block
            case BAMBOO -> {
                return Component.object(net.kyori.adventure.text.object.ObjectContents.sprite(
                        net.kyori.adventure.key.Key.key("minecraft", "items"), net.kyori.adventure.key.Key.key("minecraft", "item/bamboo")));
            }
            case CACTUS, MELON, PUMPKIN -> name = name + "_side";
            default -> {
            }
        }
        if (m.isBlock() && m.isSolid()) return Component.object(net.kyori.adventure.text.object.ObjectContents.sprite(
                net.kyori.adventure.key.Key.key("minecraft", "blocks"), net.kyori.adventure.key.Key.key("minecraft", "block/" + name)));
        return Component.object(net.kyori.adventure.text.object.ObjectContents.sprite(
                net.kyori.adventure.key.Key.key("minecraft", "items"), net.kyori.adventure.key.Key.key("minecraft", "item/" + name)));
    }

    static String nice(Material m) {
        String s = FarmerJob.nice(m);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
