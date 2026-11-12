package settlement;

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
            case FISHER -> out.addAll(Fishing.FISH);
            case COOK, SMELTER, FURNACE_WORKER -> {
                for (Town.Workplace wp : t.workplaces.values()) if (wp.job() == job) out.addAll(FurnaceJob.recipes(wp).values());
                if (job == Worker.Job.COOK) out.addAll(Cook.FOOD.keySet());
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

    /** Smith and furnace products: made to order (no target or 0 = don't make). */
    private static boolean madeToOrder(Material m) {
        return SmithJob.PRODUCTS.contains(m) || FurnaceJob.furnaceProduct(m) || Cook.FOOD.containsKey(m);
    }

    /** From the ledger: pick a workstation (or straight to its list if there's only one kind). */
    static void open(SettlementPlugin plugin, Player p, Town t) {
        List<Worker.Job> jobs = jobs(t);
        if (jobs.isEmpty()) jobs = List.of(Worker.Job.FARMER);
        if (jobs.size() == 1) {
            if (jobs.get(0) == Worker.Job.BREWER) Brewer.targets(plugin, p, t);
            else list(plugin, p, t, jobs.get(0), true);
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Worker.Job job : jobs) {
            buttons.add(Menus.button(plugin, station(job), NamedTextColor.WHITE, null, 200, pl -> {
                if (job == Worker.Job.BREWER) Brewer.targets(plugin, pl, t);
                else list(plugin, pl, t, job, true);
            }));
        }
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> Menus.ledger(plugin, pl, t));
        Menus.show(p, "Storage targets", List.of(), List.of(), DialogType.multiAction(buttons).columns(2).exitAction(back).build());
    }

    /** Back from a workstation's targets: to the workstation picker, or to the ledger when there's only one kind. */
    static void back(SettlementPlugin plugin, Player p, Town t) {
        if (jobs(t).size() > 1) open(plugin, p, t);
        else Menus.ledger(plugin, p, t);
    }

    /** One workstation's products, a row each: icon, name, in storage / target, then − and + right after it. */
    private static void list(SettlementPlugin plugin, Player p, Town t, Worker.Job job, boolean fromLedger) {
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
        if (name.endsWith("_wood")) name = name.substring(0, name.length() - 5) + "_log";         // bark all round: the log's picture
        else if (name.endsWith("_hyphae")) name = name.substring(0, name.length() - 7) + "_stem";
        switch (m) { // blocks whose picture is not called after the block
            case BAMBOO -> {
                return Component.object(net.kyori.adventure.text.object.ObjectContents.sprite(
                        net.kyori.adventure.key.Key.key("minecraft", "items"), net.kyori.adventure.key.Key.key("minecraft", "item/bamboo")));
            }
            case LILY_PAD, TRIPWIRE_HOOK -> { // flat items that use the block's picture
                return Component.object(net.kyori.adventure.text.object.ObjectContents.sprite(
                        net.kyori.adventure.key.Key.key("minecraft", "blocks"), net.kyori.adventure.key.Key.key("minecraft", "block/" + name)));
            }
            case CACTUS, MELON, PUMPKIN, BASALT, ANCIENT_DEBRIS, QUARTZ_BLOCK -> name = name + "_side";
            case DRIED_KELP_BLOCK -> name = "dried_kelp_side";
            case SMOOTH_QUARTZ -> name = "quartz_block_bottom";
            case SMOOTH_SANDSTONE -> name = "sandstone_top";
            case SMOOTH_RED_SANDSTONE -> name = "red_sandstone_top";
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
