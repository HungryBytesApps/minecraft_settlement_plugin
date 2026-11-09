package villagerworkers;

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
            meta.setEnchantmentGlintOverride(true);
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(Keys.LEDGER, PersistentDataType.BYTE, (byte) 1);
        });
        return book;
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
