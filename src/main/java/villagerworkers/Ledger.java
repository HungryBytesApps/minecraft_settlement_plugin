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
            meta.lore(List.of(
                    lore("Right-click a chest: storage rules"),
                    lore("Right-click a composter: workplace"),
                    lore("Right-click a settler: settler menu"),
                    lore("Right-click a villager: hire it"),
                    lore("Right-click anything else: settlement menu")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(Keys.LEDGER, PersistentDataType.BYTE, (byte) 1);
        });
        return book;
    }

    private static Component lore(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    static boolean is(ItemStack item) {
        return item != null && item.getType() == Material.BOOK && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(Keys.LEDGER, PersistentDataType.BYTE);
    }
}
