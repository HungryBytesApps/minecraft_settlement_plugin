package settlement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Private chests: only the player who made it private can open, change or break it. Settlers never use it
 * (whatever the bell or ledger say), hoppers can't pull from it, explosions don't break it.
 * Stored on the chest itself, so it stays private even without a settlement.
 */
final class PrivateChests implements Listener {
    static final NamespacedKey KEY = new NamespacedKey(Keys.NS, "private_owner");

    /** Who owns this private chest (either half of a double chest), or null. */
    static UUID owner(Block b) {
        for (Block part : Town.halves(b)) {
            if (part.getState(false) instanceof TileState ts) {
                String s = ts.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
                if (s != null) {
                    try {
                        return UUID.fromString(s);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return null;
    }

    /** Make private for this player (null: not private), both halves of a double chest. */
    static void set(Block b, UUID owner) {
        for (Block part : Town.halves(b)) {
            BlockState st = part.getState();
            if (!(st instanceof TileState ts)) continue;
            if (owner == null) ts.getPersistentDataContainer().remove(KEY);
            else ts.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, owner.toString());
            ts.update();
        }
        marker(b);
    }

    static final NamespacedKey MARK = new NamespacedKey(Keys.NS, "private_mark");

    /** A small key floating above a private chest (removed when it isn't private any more). */
    static void marker(Block b) {
        java.util.List<Block> parts = Town.halves(b);
        for (Block part : parts) {
            for (org.bukkit.entity.ItemDisplay d : part.getWorld().getNearbyEntitiesByType(org.bukkit.entity.ItemDisplay.class,
                    part.getLocation().add(0.5, 1, 0.5), 1.2)) {
                if (d.getPersistentDataContainer().has(MARK)) d.remove();
            }
        }
        if (owner(b) == null || !Town.isStorage(b)) return;
        double x = 0, z = 0;
        for (Block part : parts) {
            x += part.getX() + 0.5;
            z += part.getZ() + 0.5;
        }
        Location at = new Location(b.getWorld(), x / parts.size(), b.getY() + 1.25, z / parts.size());
        b.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setItemStack(new org.bukkit.inventory.ItemStack(org.bukkit.Material.TRIAL_KEY));
            d.setBillboard(org.bukkit.entity.Display.Billboard.VERTICAL);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                    new org.joml.AxisAngle4f(), new org.joml.Vector3f(0.4f, 0.4f, 0.4f), new org.joml.AxisAngle4f()));
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        });
    }

    static String ownerName(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "someone" : n;
    }

    private static Block blockOf(Inventory inv) {
        InventoryHolder h = inv.getHolder(false);
        if (h instanceof DoubleChest dc && dc.getLeftSide(false) instanceof BlockState l) return l.getBlock();
        if (h instanceof BlockState bs) return bs.getBlock();
        Location l = inv.getLocation();
        return l == null ? null : l.getBlock();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Block b = blockOf(e.getInventory());
        if (b == null || !Town.isStorage(b)) return;
        UUID o = owner(b);
        if (o != null && !o.equals(p.getUniqueId()) && !p.hasPermission("settlement.admin")) { // ops may look inside
            e.setCancelled(true);
            p.sendActionBar(Component.text("This chest is private (" + ownerName(o) + ").", NamedTextColor.RED));
        } else if (o != null) {
            marker(b); // (private chests made before the key existed get one too)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!Town.isStorage(e.getBlock())) return;
        UUID o = owner(e.getBlock());
        if (o != null && !o.equals(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("This chest is private (" + ownerName(o) + ").", NamedTextColor.RED));
        } else if (o != null) { // the owner breaks it: the key goes (or moves over the half that's left)
            java.util.List<Block> parts = Town.halves(e.getBlock());
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Block part : parts) {
                    for (org.bukkit.entity.ItemDisplay d : part.getWorld().getNearbyEntitiesByType(org.bukkit.entity.ItemDisplay.class,
                            part.getLocation().add(0.5, 1, 0.5), 1.2)) if (d.getPersistentDataContainer().has(MARK)) d.remove();
                }
                for (Block part : parts) {
                    if (Town.isStorage(part)) {
                        marker(part);
                        break;
                    }
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> Town.isStorage(b) && owner(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> Town.isStorage(b) && owner(b) != null);
    }

    private final org.bukkit.plugin.Plugin plugin;

    PrivateChests(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * A chest placed next to a private chest: only its owner can make it one big (private) chest.
     * Anyone else's chest is placed as if they were sneaking: on its own, not joined.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        Block placed = e.getBlockPlaced();
        if (!(placed.getBlockData() instanceof org.bukkit.block.data.type.Chest data)
                || data.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) return;
        // The chest it would join: left/right of the way it faces.
        org.bukkit.block.BlockFace facing = data.getFacing();
        org.bukkit.block.BlockFace side = data.getType() == org.bukkit.block.data.type.Chest.Type.LEFT
                ? rotateClockwise(facing) : rotateClockwise(facing).getOppositeFace();
        Block other = placed.getRelative(side);
        UUID o = owner(other);
        if (o == null) return;
        UUID placer = e.getPlayer().getUniqueId();
        if (o.equals(placer)) {
            Bukkit.getScheduler().runTask(plugin, () -> set(placed, placer)); // bigger private chest
            return;
        }
        data.setType(org.bukkit.block.data.type.Chest.Type.SINGLE); // as if sneaking
        placed.setBlockData(data, false);
        if (other.getBlockData() instanceof org.bukkit.block.data.type.Chest od && od.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
            od.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
            other.setBlockData(od, false);
        }
    }

    private static org.bukkit.block.BlockFace rotateClockwise(org.bukkit.block.BlockFace f) {
        return switch (f) {
            case NORTH -> org.bukkit.block.BlockFace.EAST;
            case EAST -> org.bukkit.block.BlockFace.SOUTH;
            case SOUTH -> org.bukkit.block.BlockFace.WEST;
            default -> org.bukkit.block.BlockFace.NORTH;
        };
    }

    /** Hoppers can't take things out of a private chest. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        Block b = blockOf(e.getSource());
        if (b != null && Town.isStorage(b) && owner(b) != null) e.setCancelled(true);
    }
}
