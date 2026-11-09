package villagerworkers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage targets as a chest window: one row per job, its products as items showing what's in
 * storage and the target. Click to change, nothing to type.
 */
final class TargetsMenu implements InventoryHolder {
    /** A job and the products it makes (one row each). New jobs add a row here. */
    private record Row(String job, Material icon, List<Material> products) {}

    private static final List<Row> ROWS = List.of(
            new Row("Farmer", Material.IRON_HOE, FarmerJob.TARGET_ITEMS));

    private final VillagerWorkersPlugin plugin;
    private final Town t;
    private final Inventory inv;
    private final Map<Integer, Material> slots = new HashMap<>();

    private TargetsMenu(VillagerWorkersPlugin plugin, Town t) {
        this.plugin = plugin;
        this.t = t;
        this.inv = Bukkit.createInventory(this, Math.max(27, 9 * (ROWS.size() + 2)), Component.text("Storage targets"));
        fill();
    }

    static void open(VillagerWorkersPlugin plugin, Player p, Town t) {
        Menus.markShown(p);
        p.openInventory(new TargetsMenu(plugin, t).inv);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    private void fill() {
        int radius = plugin.settings().townRadius();
        for (int r = 0; r < ROWS.size(); r++) {
            Row row = ROWS.get(r);
            inv.setItem(r * 9, named(new ItemStack(row.icon()), Component.text(row.job(), NamedTextColor.GOLD), List.of()));
            for (int i = 0; i < row.products().size() && i < 8; i++) {
                int slot = r * 9 + 1 + i;
                slots.put(slot, row.products().get(i));
                draw(slot, radius);
            }
        }
        int back = inv.getSize() - 1;
        inv.setItem(back, named(new ItemStack(Material.ARROW), Component.text("Back", NamedTextColor.GRAY), List.of()));
    }

    private void draw(int slot, int radius) {
        Material m = slots.get(slot);
        int target = t.targets.getOrDefault(m, 0);
        int stored = t.count(m, radius);
        boolean reached = target > 0 && stored >= target;
        List<Component> lore = new ArrayList<>();
        lore.add(gray("In storage: " + stored));
        lore.add(target > 0
                ? Component.text("Target: " + target, reached ? NamedTextColor.GREEN : NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                : gray("Target: no limit"));
        lore.add(Component.empty());
        lore.add(Component.text("Left / right-click: +16 / −16", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Shift: ±64  ·  Q: no limit", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        String name = FarmerJob.nice(m);
        ItemStack it = named(new ItemStack(m),
                Component.text(Character.toUpperCase(name.charAt(0)) + name.substring(1), NamedTextColor.WHITE), lore);
        if (reached) {
            ItemMeta meta = it.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
        }
        inv.setItem(slot, it);
    }

    private static Component gray(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack named(ItemStack it, Component name, List<Component> lore) {
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private void click(Player p, int slot, ClickType type) {
        if (slot == inv.getSize() - 1) {
            Bukkit.getScheduler().runTask(plugin, () -> Menus.ledger(plugin, p, t));
            return;
        }
        Material m = slots.get(slot);
        if (m == null) return;
        int cur = t.targets.getOrDefault(m, 0);
        int next = switch (type) {
            case LEFT -> cur + 16;
            case RIGHT -> cur - 16;
            case SHIFT_LEFT -> cur + 64;
            case SHIFT_RIGHT -> cur - 64;
            case DROP, CONTROL_DROP -> 0;
            default -> cur;
        };
        next = Math.max(0, Math.min(100000, next));
        if (next == cur) return;
        t.targets.put(m, next);
        draw(slot, plugin.settings().townRadius());
        p.playSound(p.getLocation(), "minecraft:ui.button.click", 0.4f, next > cur ? 1.4f : 1.0f);
    }

    /** Clicks and closing for all targets windows. */
    static final class Events implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder(false) instanceof TargetsMenu menu)) return;
            e.setCancelled(true);
            if (e.getClickedInventory() == menu.inv && e.getWhoClicked() instanceof Player p) menu.click(p, e.getSlot(), e.getClick());
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder(false) instanceof TargetsMenu) e.setCancelled(true);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (e.getInventory().getHolder(false) instanceof TargetsMenu menu) menu.plugin.towns().save();
        }
    }
}
