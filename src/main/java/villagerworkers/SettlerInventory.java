package villagerworkers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A settler's inventory as a chest window: armor on top, pockets below. Take things out or put
 * things in. The settler stops and waits while you look in its pockets.
 */
final class SettlerInventory implements InventoryHolder {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final int POCKETS = 9;
    private static final Map<UUID, SettlerInventory> open = new HashMap<>();

    private final Worker w;
    private final Inventory inv;

    private SettlerInventory(Worker w) {
        this.w = w;
        this.inv = Bukkit.createInventory(this, 18, Component.text(w.name));
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(m -> m.displayName(Component.text(" ")));
        for (int i = 5; i < 9; i++) inv.setItem(i, filler);
        ItemStack label = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        label.editMeta(m -> m.displayName(Component.text("← Armor, shield   ·   Pockets ↓", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        inv.setItem(5, label);
        load();
    }

    /** Is someone looking in this settler's pockets? (It waits.) */
    static Player viewer(Worker w) {
        SettlerInventory si = open.get(w.body.getUniqueId());
        if (si == null) return null;
        for (var h : si.inv.getViewers()) if (h instanceof Player p) return p;
        open.remove(w.body.getUniqueId());
        return null;
    }

    static void open(Player p, Worker w) {
        w.showTorch(false); // the real shield, not the torch
        SettlerInventory si = open.computeIfAbsent(w.body.getUniqueId(), k -> new SettlerInventory(w));
        si.load();
        Menus.markShown(p);
        p.openInventory(si.inv);
    }

    /** Close it (settler died or left). */
    static void close(Worker w) {
        SettlerInventory si = open.remove(w.body.getUniqueId());
        if (si != null) for (var h : new java.util.ArrayList<>(si.inv.getViewers())) h.closeInventory();
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    private void load() {
        EntityEquipment eq = w.body.getEquipment();
        for (int i = 0; i < 4; i++) {
            ItemStack it = eq.getItem(ARMOR[i]);
            inv.setItem(i, it == null || it.getType().isAir() ? null : it.clone());
        }
        ItemStack off = eq.getItemInOffHand();
        inv.setItem(4, off.getType() == Material.SHIELD ? off.clone() : null);
        for (int i = 0; i < POCKETS; i++) {
            ItemStack it = w.pockets.getItem(i);
            inv.setItem(9 + i, it == null ? null : it.clone());
        }
    }

    /** Window → settler. */
    private void store() {
        EntityEquipment eq = w.body.getEquipment();
        for (int i = 0; i < 4; i++) {
            ItemStack it = inv.getItem(i);
            eq.setItem(ARMOR[i], it == null ? null : it.clone());
        }
        ItemStack shield = inv.getItem(4);
        eq.setItemInOffHand(shield == null ? null : shield.clone());
        for (int i = 0; i < POCKETS; i++) {
            ItemStack it = inv.getItem(9 + i);
            w.pockets.setItem(i, it == null ? null : it.clone());
        }
        w.savePockets();
        w.holdTool();
    }

    private static boolean fits(int slot, ItemStack it) {
        if (it == null || it.getType().isAir()) return true;
        if (slot < 4) return Combat.armorSlot(it.getType()) == ARMOR[slot];
        if (slot == 4) return it.getType() == Material.SHIELD;
        return slot >= 9;
    }

    /** Clicks and closing for all settler inventory windows. */
    static final class Events implements Listener {
        private final VillagerWorkersPlugin plugin;

        Events(VillagerWorkersPlugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder(false) instanceof SettlerInventory si)) return;
            if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(true);
                return;
            }
            boolean top = e.getClickedInventory() == si.inv;
            if (top) {
                int slot = e.getSlot();
                if (slot >= 5 && slot < 9) {
                    e.setCancelled(true);
                    return;
                }
                ItemStack placing = e.getAction() == InventoryAction.HOTBAR_SWAP && e.getHotbarButton() >= 0
                        ? e.getWhoClicked().getInventory().getItem(e.getHotbarButton()) : e.getCursor();
                if (!fits(slot, placing)) {
                    e.setCancelled(true);
                    return;
                }
            } else if (e.isShiftClick() && e.getCurrentItem() != null) {
                // From your inventory: armor to its slot if free, the rest into the pockets.
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem().clone();
                for (int i = 0; i < 5 && it.getAmount() > 0; i++) {
                    if (fits(i, it) && si.inv.getItem(i) == null) {
                        si.inv.setItem(i, it.asOne());
                        it.subtract();
                    }
                }
                for (int i = 9; i < 18 && it.getAmount() > 0; i++) {
                    ItemStack cur = si.inv.getItem(i);
                    if (cur == null) {
                        si.inv.setItem(i, it.clone());
                        it.setAmount(0);
                    } else if (cur.isSimilar(it) && cur.getAmount() < cur.getMaxStackSize()) {
                        int n = Math.min(it.getAmount(), cur.getMaxStackSize() - cur.getAmount());
                        cur.setAmount(cur.getAmount() + n);
                        it.setAmount(it.getAmount() - n);
                    }
                }
                e.setCurrentItem(it.getAmount() > 0 ? it : null);
            }
            Bukkit.getScheduler().runTask(plugin, si::store);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (!(e.getView().getTopInventory().getHolder(false) instanceof SettlerInventory si)) return;
            for (int raw : e.getRawSlots()) {
                if (raw < 18 && !fits(raw, e.getOldCursor())) {
                    e.setCancelled(true);
                    return;
                }
                if (raw >= 5 && raw < 9) {
                    e.setCancelled(true);
                    return;
                }
            }
            Bukkit.getScheduler().runTask(plugin, si::store);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (e.getInventory().getHolder(false) instanceof SettlerInventory si) {
                si.store();
                si.w.save();
            }
        }
    }
}
