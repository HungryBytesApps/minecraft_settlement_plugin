package settlement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** The Settlement Ledger: a book to manage your town with. */
final class Ledger {
    private Ledger() {}

    static ItemStack create() {
        ItemStack book = new ItemStack(Material.BOOK);
        book.editMeta(meta -> {
            meta.displayName(Component.text("Settlement Ledger", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(lore("Right-click to manage your settlement")));
            meta.setEnchantmentGlintOverride(false); // glows when there are new problems
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(Keys.LEDGER, PersistentDataType.BYTE, (byte) 1);
        });
        return book;
    }

    /**
     * The ledger shows new problems in the settlement's log: it glows, and its name says how many
     * ("Settlement Ledger ⚠ 3"). Called every 2 seconds; only changes the item when something changed.
     */
    static void refresh(SettlementPlugin plugin) {
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (!is(it)) continue;
                Town t = plugin.ownTown(p);
                int n = t == null ? 0 : t.log.unseen;
                String want = n > 0 ? "Settlement Ledger ⚠ " + n : "Settlement Ledger";
                Component name = it.getItemMeta().displayName();
                String have = name == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name);
                if (!have.equals(want)) {
                    it.editMeta(meta -> {
                        meta.displayName(n > 0 ? Component.text("Settlement Ledger", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                                .append(Component.text(" ⚠ " + n, NamedTextColor.RED))
                                : Component.text("Settlement Ledger", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                        meta.lore(n > 0 ? List.of(lore("Right-click to manage your settlement"), lore(n + " new problem" + (n == 1 ? "" : "s") + ": see Problems"))
                                : List.of(lore("Right-click to manage your settlement")));
                        meta.setEnchantmentGlintOverride(n > 0);
                    });
                    inv.setItem(i, it);
                }
                break; // one ledger per player
            }
        }
    }

    private static Component lore(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    /** Does the player already carry a ledger? */
    static boolean has(org.bukkit.entity.Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (is(it)) return true;
        return is(p.getItemOnCursor());
    }

    static boolean is(ItemStack item) {
        return item != null && item.getType() == Material.BOOK && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(Keys.LEDGER, PersistentDataType.BYTE);
    }
}
