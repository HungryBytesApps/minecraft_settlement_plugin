package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;

/**
 * Temporary campfires the herder lights under a beehive to calm the bees while it harvests. Free, can't be
 * used or broken by players, and gone again after a few seconds (also after a restart: marked in the chunk).
 */
final class Campfires implements Listener {
    private static final Set<Block> lit = new HashSet<>();

    private static NamespacedKey key(Block b) {
        return new NamespacedKey(Keys.NS, "campfire_" + b.getX() + "_" + b.getY() + "_" + b.getZ());
    }

    static boolean isTemp(Block b) {
        return lit.contains(b);
    }

    /** Light one here for {@code ticks}. */
    static void light(SettlementPlugin plugin, Block b, int ticks) {
        if (!b.getType().isAir()) return;
        b.setType(Material.CAMPFIRE, false);
        lit.add(b);
        b.getChunk().getPersistentDataContainer().set(key(b), PersistentDataType.BYTE, (byte) 1);
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), "minecraft:item.flintandsteel.use", org.bukkit.SoundCategory.BLOCKS, 0.7f, 1f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> put_out(b), ticks);
    }

    static void put_out(Block b) {
        if (!lit.remove(b)) return;
        if (b.getType() == Material.CAMPFIRE) b.setType(Material.AIR, false);
        b.getChunk().getPersistentDataContainer().remove(key(b));
    }

    /** Plugin stops: put them all out. */
    static void putOutAll() {
        for (Block b : new HashSet<>(lit)) put_out(b);
    }

    /** Left over from a crash: remove when the chunk loads. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        for (NamespacedKey k : new HashSet<>(c.getPersistentDataContainer().getKeys())) {
            if (!k.getNamespace().equals(Keys.NS) || !k.getKey().startsWith("campfire_")) continue;
            String[] p = k.getKey().substring("campfire_".length()).split("_");
            try {
                Block b = c.getWorld().getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                if (b.getType() == Material.CAMPFIRE) b.setType(Material.AIR, false);
            } catch (RuntimeException ignored) {
            }
            c.getPersistentDataContainer().remove(k);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isTemp(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && isTemp(e.getClickedBlock())) {
            e.setCancelled(true); // no cooking on it
        }
    }
}
