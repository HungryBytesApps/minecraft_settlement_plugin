package settlement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moving a settlement bell (the main one or an expansion's): "Move this bell" in its menu gives you a placeholder
 * bell. The real bell stays where it is until you place the placeholder somewhere it's allowed; then the square moves
 * there (same radius) and the old bell is gone. Settlers, workstations, chests and targets all stay. Not allowed (the
 * placeholder isn't placed, you get the reason): overlapping another settlement, a workstation with workers ending up
 * outside (move that one first), an expansion cut off. A placeholder is only ever a move: once the bell has moved
 * (or is gone), an old placeholder just disappears.
 */
final class BellMove implements Listener {

    /** On the placeholder: town id | expansion bell (Keys.ser) or "main" | the bell's spot when it was made. */
    static final NamespacedKey KEY = new NamespacedKey(Keys.NS, "move_bell");

    private final SettlementPlugin plugin;

    BellMove(SettlementPlugin plugin) {
        this.plugin = plugin;
    }

    static void start(SettlementPlugin plugin, Player p, Town t, Town.Expansion e) {
        if (!t.isOwner(p)) {
            p.sendActionBar(Component.text("Only the founder can move the bells.", NamedTextColor.RED));
            return;
        }
        Location at = e == null ? t.bell : e.bell;
        ItemStack it = new ItemStack(Material.BELL);
        it.editMeta(m -> {
            m.displayName(Component.text("Move: " + (e == null ? t.name + "'s bell" : t.name + "'s expansion bell"), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(Component.text("Place it where the bell should go.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("The bell now at " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + " moves there.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, t.id + "|" + (e == null ? "main" : Keys.ser(e.bell)) + "|" + Keys.ser(at));
            m.setEnchantmentGlintOverride(true);
        });
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        p.sendActionBar(Component.text("Place the placeholder bell where it should go. The bell stays until then.", NamedTextColor.YELLOW));
    }

    private record Target(Town town, Town.Expansion expansion) {}

    /** What this placeholder moves, or null when it's out of date (already moved, gone). */
    private Target target(ItemStack it) {
        String v = it == null || !it.hasItemMeta() ? null : it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (v == null) return null;
        String[] p = v.split("\\|", 3);
        if (p.length != 3) return null;
        Town t = plugin.towns().get(p[0]);
        if (t == null) return null;
        if (p[1].equals("main")) return Keys.ser(t.bell).equals(p[2]) ? new Target(t, null) : null;
        for (Town.Expansion x : t.expansions) if (Keys.ser(x.bell).equals(p[1]) && p[1].equals(p[2])) return new Target(t, x);
        return null;
    }

    static boolean isPlaceholder(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(KEY);
    }

    private record Approved(Location at, Target target) {}

    private final Map<UUID, Approved> approved = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        if (!isPlaceholder(it)) return;
        e.setCancelled(true); // never a plain bell; allowed below
        Player p = e.getPlayer();
        Target tg = target(it);
        if (tg == null) { // moved already, or the settlement is gone: it's used up
            it.setAmount(it.getAmount() - 1);
            p.sendActionBar(Component.text("That placeholder is out of date (the bell was moved already).", NamedTextColor.RED));
            return;
        }
        if (!tg.town().isOwner(p)) {
            p.sendActionBar(Component.text("Only the founder can move the bells.", NamedTextColor.RED));
            return;
        }
        String why = Area.checkMove(plugin, tg.town(), tg.expansion(), e.getBlockPlaced().getLocation());
        if (why != null) {
            p.sendActionBar(Component.text(why, NamedTextColor.RED));
            return;
        }
        e.setCancelled(false);
        approved.put(p.getUniqueId(), new Approved(e.getBlockPlaced().getLocation(), tg));
    }

    /** Allowed and no other plugin said no: the move happens. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlaced(BlockPlaceEvent e) {
        Approved a = approved.remove(e.getPlayer().getUniqueId());
        if (a == null || !a.at().equals(e.getBlockPlaced().getLocation())) return;
        Location to = a.at();
        Target tg = a.target();
        Town t = tg.town();
        Town.Expansion x = tg.expansion();
        Location from = x == null ? t.bell : x.bell;
        if (x == null) t.bell = to;
        else x.bell = to;
        Block old = from.getBlock();
        if (old.getType() == Material.BELL && !old.equals(e.getBlockPlaced())) old.setType(Material.AIR); // replaced by the new one
        Area.changed(plugin, t);
        plugin.borders().flash(e.getPlayer());
        to.getWorld().playSound(to, "minecraft:block.bell.use", SoundCategory.BLOCKS, 1f, 1f);
        e.getPlayer().sendActionBar(Component.text((x == null ? t.name + "'s bell" : "The expansion bell") + " moved here.", NamedTextColor.GREEN));
    }
}
