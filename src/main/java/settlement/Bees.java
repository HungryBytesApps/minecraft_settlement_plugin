package settlement;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The herder's bees: a full beehive or bee nest gives honey (with a glass bottle) or honeycomb (with shears).
 * It first lights a campfire under the hive for a moment so the bees stay calm; a hive without room for one
 * (within 5 blocks below, nothing in between) is left alone, with a warning.
 */
final class Bees {
    private Bees() {}

    private static final Map<Block, Integer> warned = new HashMap<>();

    static boolean full(Block b) {
        return b.getBlockData() instanceof Beehive h && h.getHoneyLevel() >= h.getMaximumHoneyLevel();
    }

    /** Where a campfire can go: the lowest free spot under the hive (up to 5 down) with solid ground. Null: no room. */
    static Block smokeSpot(Block hive) {
        Block spot = null;
        for (int dy = 1; dy <= 5; dy++) {
            Block b = hive.getRelative(BlockFace.DOWN, dy);
            if (b.getType() == Material.CAMPFIRE || b.getType() == Material.SOUL_CAMPFIRE) return b; // one is there already
            if (!b.getType().isAir()) break;
            spot = b;
            if (b.getRelative(BlockFace.DOWN).getType().isSolid()) return spot;
        }
        return null;
    }

    static Activity next(Worker w, Town t) {
        int radius = w.plugin.settings().townRadius();
        boolean honey = t.wants(Material.HONEY_BOTTLE, radius), comb = t.wants(Material.HONEYCOMB, radius);
        if (!honey && !comb) return null;
        Block hive = Work.nearestOf(w, Fields.hives(t, radius), b -> full(b) && !w.isSkipped(b));
        if (hive == null) return null;
        Block spot = smokeSpot(hive);
        if (spot == null) {
            w.skip(hive, 6000);
            int now = Bukkit.getCurrentTick();
            Integer last = warned.get(hive);
            if (last == null || now - last > 12000) {
                warned.put(hive, now);
                Notify.send(t, t.owner, Notify.Kind.BEES, "🐝 " + w.name + " can't harvest the beehive at " + hive.getX() + ", " + hive.getY() + ", "
                        + hive.getZ() + ": no room under it for a campfire.", net.kyori.adventure.text.format.NamedTextColor.YELLOW);
            }
            return null;
        }
        // Honey needs a glass bottle (made from glass, or asked for), honeycomb needs shears.
        boolean useBottle = honey;
        if (useBottle) {
            Supply.Result got = Supply.obtain(w, t, Material.GLASS_BOTTLE, 1);
            if (got.act() != null) return got.act();
            if (got.missing() != null) {
                useBottle = false;
                if (!comb) {
                    w.workStatus = "Needs glass bottles for honey";
                    return null;
                }
            }
        }
        if (!useBottle && w.count(Material.SHEARS) == 0) {
            Activity f = Work.fetch(w, t, m -> m == Material.SHEARS, 1, "Getting shears");
            if (f != null) return f;
            w.workStatus = "Needs shears for honeycomb";
            return null;
        }
        boolean bottle = useBottle;
        Work.claim(w, hive);
        w.workStatus = "Harvesting " + (bottle ? "honey" : "honeycomb");
        return new GoAndDo(w, hive, 2.5, "Harvesting " + (bottle ? "honey" : "honeycomb"), Bees::full, b -> {
            if (!(b.getBlockData() instanceof Beehive h)) return;
            if (bottle) {
                if (!w.take(Material.GLASS_BOTTLE, 1)) return;
                w.give(List.of(new ItemStack(Material.HONEY_BOTTLE)), b.getLocation().add(0.5, 0.5, 0.5));
                b.getWorld().playSound(b.getLocation(), "minecraft:item.bottle.fill", SoundCategory.BLOCKS, 0.8f, 1f);
            } else {
                Work.wearTool(w, "SHEARS");
                w.give(List.of(new ItemStack(Material.HONEYCOMB, 3)), b.getLocation().add(0.5, 0.5, 0.5));
                b.getWorld().playSound(b.getLocation(), "minecraft:block.beehive.shear", SoundCategory.BLOCKS, 0.8f, 1f);
            }
            h.setHoneyLevel(0);
            b.setBlockData(h);
        }).onArrive(b -> {
            if (spot.getType().isAir()) Campfires.light(w.plugin, spot, 100); // calm the bees first
        }).working(40, "minecraft:block.beehive.work");
    }
}
