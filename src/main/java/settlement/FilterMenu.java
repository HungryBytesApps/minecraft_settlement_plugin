package settlement;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Choosing a chest filter's items: pick a group (what the settlement's jobs make and use, what's in the
 * chest now, what's chosen), then click item icons to switch them on (green) or off.
 */
final class FilterMenu {
    private FilterMenu() {}

    /** The groups: name → items, alphabetical. */
    private static Map<String, List<Material>> groups(Town t, Block chest) {
        Map<String, List<Material>> g = new LinkedHashMap<>();
        Set<Material> inChest = EnumSet.noneOf(Material.class);
        Inventory inv = Town.inventory(chest);
        if (inv != null) for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) inChest.add(it.getType());
        put(g, "In this chest now", inChest);
        put(g, "Crops & seeds", FarmerJob.TARGET_ITEMS);
        Set<Material> food = new HashSet<>(FurnaceJob.inputs(Worker.Job.COOK));
        food.addAll(FurnaceJob.products(Worker.Job.COOK));
        put(g, "Food", food);
        put(g, "Animal products", HerderJob.PRODUCTS);
        Set<Material> fish = new HashSet<>(Fishing.FISH);
        fish.addAll(Fishing.JUNK);
        put(g, "Fish & catch", fish);
        put(g, "Brewing", Brewer.stock());
        put(g, "Wood", wood());
        Set<Material> ores = new HashSet<>(FurnaceJob.inputs(Worker.Job.SMELTER));
        ores.addAll(FurnaceJob.products(Worker.Job.SMELTER));
        put(g, "Ores & metals", ores);
        Set<Material> stone = new HashSet<>(FurnaceJob.inputs(Worker.Job.FURNACE_WORKER));
        stone.addAll(FurnaceJob.products(Worker.Job.FURNACE_WORKER));
        put(g, "Stone, glass & other", stone);
        put(g, "Weapons, tools & armor", SmithJob.PRODUCTS);
        put(g, "Fuel", List.of(Material.COAL, Material.CHARCOAL, Material.COAL_BLOCK, Material.DRIED_KELP_BLOCK, Material.BLAZE_ROD));
        // Wood only in Wood (logs are also furnace input and fuel).
        Set<Material> wood = new HashSet<>(wood());
        wood.remove(Material.APPLE); // also food
        for (Map.Entry<String, List<Material>> e : g.entrySet())
            if (!e.getKey().equals("Wood") && !e.getKey().equals("In this chest now")) e.getValue().removeIf(wood::contains);
        return g;
    }

    /** Everything wooden: logs, wood, stripped, planks, slabs, stairs, saplings, leaves, sticks, bamboo. */
    private static Set<Material> wood() {
        Set<Material> s = new HashSet<>(LumberjackJob.PRODUCTS);
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem()) continue;
            if (org.bukkit.Tag.LOGS.isTagged(m) || org.bukkit.Tag.PLANKS.isTagged(m) || org.bukkit.Tag.WOODEN_SLABS.isTagged(m)
                    || org.bukkit.Tag.WOODEN_STAIRS.isTagged(m) || org.bukkit.Tag.SAPLINGS.isTagged(m) || org.bukkit.Tag.LEAVES.isTagged(m)) s.add(m);
        }
        s.addAll(List.of(Material.STICK, Material.BAMBOO, Material.BAMBOO_BLOCK, Material.STRIPPED_BAMBOO_BLOCK, Material.MANGROVE_ROOTS,
                Material.MUDDY_MANGROVE_ROOTS, Material.MANGROVE_PROPAGULE));
        return s;
    }

    private static void put(Map<String, List<Material>> g, String name, Collection<Material> items) {
        List<Material> l = new ArrayList<>(new HashSet<>(items));
        l.removeIf(m -> m == null || m.isAir() || !m.isItem());
        l.sort(Comparator.comparing(TargetsMenu::nice));
        g.put(name, l);
    }

    static void categories(SettlementPlugin plugin, Player p, Town t, Block chest) {
        Town.Filter f = t.filter(chest);
        List<ActionButton> buttons = new ArrayList<>();
        if (!f.items.isEmpty()) {
            buttons.add(Menus.button(plugin, "Chosen (" + f.items.size() + ")", NamedTextColor.GREEN, null, 200,
                    pl -> items(plugin, pl, t, chest, "Chosen", new ArrayList<>(t.filter(chest).items))));
        }
        for (Map.Entry<String, List<Material>> e : groups(t, chest).entrySet()) {
            if (e.getValue().isEmpty()) continue;
            int on = 0;
            for (Material m : e.getValue()) if (f.items.contains(m)) on++;
            String name = e.getKey();
            List<Material> list = e.getValue();
            buttons.add(Menus.button(plugin, name + (on > 0 ? " (" + on + ")" : ""), NamedTextColor.WHITE, null, 200,
                    pl -> items(plugin, pl, t, chest, name, list)));
        }
        List<DialogBody> body = List.of(Menus.line("Filter: " + f.mode.label + ". Pick a group, then click items to choose them.", NamedTextColor.GRAY));
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> Menus.showChest(plugin, pl, t, chest));
        Menus.show(p, "Filter items", body, List.of(), DialogType.multiAction(buttons).columns(2).exitAction(back).build());
    }

    /** Item icons as toggles: green = in the filter. */
    private static void items(SettlementPlugin plugin, Player p, Town t, Block chest, String group, List<Material> list) {
        Town.Filter f = t.filter(chest);
        List<ActionButton> buttons = new ArrayList<>();
        for (Material m : list) {
            boolean on = f.items.contains(m);
            Component label = TargetsMenu.icon(m).append(Component.text(" " + TargetsMenu.nice(m), on ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            buttons.add(ActionButton.create(label, Component.text(on ? "Click to remove" : "Click to add"), 150, Menus.click(plugin, (view, audience) -> {
                if (!(audience instanceof Player pl)) return;
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    Town.Filter ef = t.editFilter(chest);
                    if (!ef.items.remove(m)) ef.items.add(m);
                    if (ef.mode == Town.FilterMode.OFF && !ef.items.isEmpty()) ef.mode = Town.FilterMode.PREFER; // choosing items switches it on
                    plugin.towns().save();
                    items(plugin, pl, t, chest, group, list);
                });
            }, Menus.ONCE)));
        }
        if (buttons.isEmpty()) buttons.add(Menus.button(plugin, "Nothing here", NamedTextColor.GRAY, null, 150, pl -> categories(plugin, pl, t, chest)));
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> categories(plugin, pl, t, chest));
        Menus.show(p, "Filter: " + group, List.of(), List.of(), DialogType.multiAction(buttons).columns(3).exitAction(back).build());
    }
}
