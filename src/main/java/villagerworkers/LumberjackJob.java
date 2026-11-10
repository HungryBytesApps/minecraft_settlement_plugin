package villagerworkers;

import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Predicate;

/**
 * Lumberjack (fletching table): fells the trees in its work area with an axe from storage, log by
 * log, replants a sapling, picks up what falls from the leaves and brings the wood to storage.
 * Only real trees (logs on dirt with natural leaves), so it never chops your buildings.
 */
final class LumberjackJob {
    private LumberjackJob() {}

    static boolean wanted(Material m) {
        return Tag.LOGS.isTagged(m) || Tag.SAPLINGS.isTagged(m) || m == Material.APPLE || m == Material.STICK
                || m == Material.MANGROVE_PROPAGULE;
    }

    /** Products for storage targets: the logs of each tree kind. */
    static final List<Material> PRODUCTS = List.of(Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
            Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG, Material.PALE_OAK_LOG,
            Material.APPLE, Material.STICK);

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        Predicate<Material> keep = m -> Tag.SAPLINGS.isTagged(m) || m == Material.MANGROVE_PROPAGULE || m.name().endsWith("_AXE") || Work.gear(m);
        if (Work.needsDeposit(w, keep, false)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        // Saplings, apples and sticks from the leaves: pick them up.
        Activity pick = Work.collectDrops(w, t, LumberjackJob::wanted, 20);
        if (pick != null) return pick;
        // Replant spots where a tree stood.
        int radius = w.plugin.settings().townRadius();
        boolean outside = wp.isOn("OUTSIDE");
        Block spot = !wp.isOn("REPLANT") ? null : Work.nearestOf(w, Replant.all(), b -> b.getType().isAir() && plantable(b.getRelative(BlockFace.DOWN))
                && b.getWorld() == t.world() && b.getLocation().distanceSquared(t.bell) <= (radius + 30.0) * (radius + 30.0));
        if (spot != null) {
            Material sapling = Replant.wanted(spot);
            if (w.count(sapling) > 0) {
                return new GoAndDo(w, spot, 1.8, "Planting a sapling", b -> b.getType().isAir(), b -> {
                    if (!w.take(sapling, 1)) return;
                    b.setType(sapling);
                    Replant.done(b);
                    b.getWorld().playSound(b.getLocation(), "minecraft:block.grass.place", SoundCategory.BLOCKS, 0.8f, 1f);
                });
            }
            Activity f = Work.fetch(w, t, m -> m == sapling, 8, "Getting saplings");
            if (f != null) return f;
            Replant.done(spot); // none anywhere: forget it
        }
        // Real trees only (a log on the ground with natural leaves), inside or just outside the settlement.
        Block base = Work.nearestOf(w, Fields.trees(t, radius, outside), b -> isTreeBase(b) && t.wants(b.getType(), radius));
        if (base != null) {
            Work.claim(w, base);
            if (!Work.hasTool(w, "_AXE")) {
                Activity f = Work.fetchTool(w, t, "_AXE", "an axe");
                if (f != null) return f;
                w.workStatus = "Needs an axe (none in storage)";
                return null;
            }
            List<Block> logs = tree(base);
            if (logs != null) {
                w.workStatus = "Felling a tree";
                return new Chop(w, base, logs, wp.isOn("REPLANT"));
            }
            w.skip(base, 6000);
        }
        if (Work.needsDeposit(w, keep, true)) {
            Activity d = Work.deposit(w, t, keep);
            if (d != null) return d;
        }
        w.workStatus = base == null ? (outside ? "No trees just outside the settlement" : "No trees in the settlement") : "The settlement has enough wood";
        return null;
    }

    private static boolean plantable(Block b) {
        return Tag.DIRT.isTagged(b.getType()) || b.getType() == Material.GRASS_BLOCK;
    }

    /** The lowest log of a tree: a log standing on dirt. */
    private static boolean isTreeBase(Block b) {
        Material m = b.getType();
        return Tag.LOGS.isTagged(m) && !m.name().startsWith("STRIPPED_") && plantable(b.getRelative(BlockFace.DOWN));
    }

    /** All logs of the tree, top first. Null if it's not a real tree (no natural leaves) or too big. */
    static List<Block> tree(Block base) {
        Material kind = base.getType();
        List<Block> logs = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> todo = new ArrayDeque<>();
        todo.add(base);
        seen.add(base);
        boolean leaves = false;
        while (!todo.isEmpty()) {
            Block b = todo.poll();
            logs.add(b);
            if (logs.size() > 200) return null;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = b.getRelative(dx, dy, dz);
                        if (n.getBlockData() instanceof Leaves l && !l.isPersistent()) leaves = true;
                        if (n.getType() == kind && n.getY() >= base.getY() && Math.abs(n.getX() - base.getX()) <= 6
                                && Math.abs(n.getZ() - base.getZ()) <= 6 && seen.add(n)) todo.add(n);
                    }
                }
            }
        }
        if (!leaves) return null;
        logs.sort((a, b2) -> Integer.compare(b2.getY(), a.getY()));
        return logs;
    }

    static Material saplingFor(Material log) {
        if (log == Material.MANGROVE_LOG) return Material.MANGROVE_PROPAGULE;
        return Material.matchMaterial(log.name().replace("_LOG", "_SAPLING"));
    }

    /** Where trees were felled and what sapling goes there. */
    static final class Replant {
        private static final Map<Block, Material> spots = new HashMap<>();

        static Material wanted(Block b) {
            return spots.get(b);
        }

        static void add(Block b, Material sapling) {
            if (sapling != null) spots.put(b, sapling);
        }

        static void done(Block b) {
            spots.remove(b);
        }

        static List<Block> all() {
            return new ArrayList<>(spots.keySet());
        }
    }

    /** Walk to the tree, then chop the logs one by one (top first), axe swinging. */
    static final class Chop implements Activity {
        private final Worker w;
        private final Block base;
        private final List<Block> logs;
        private final Material kind;
        private int phase, time, wait, hits;
        private Block current;
        private final boolean replant;
        private static final int HITS = 5; // swings per log

        Chop(Worker w, Block base, List<Block> logs, boolean replant) {
            this.replant = replant;
            this.w = w;
            this.base = base;
            this.logs = new ArrayList<>(logs);
            this.kind = base.getType();
        }

        @Override
        public boolean tick() {
            time++;
            if (phase == 0) {
                if (!w.mover.go(base.getLocation().add(0.5, 0, 0.5), 2.0, w.plugin.settings().workSpeed())) {
                    w.skip(base, 2400);
                    return true;
                }
                phase = 1;
                return false;
            }
            if (phase == 1) {
                Location me = w.body.getLocation();
                double dx = base.getX() + 0.5 - me.getX(), dz = base.getZ() + 0.5 - me.getZ();
                if (dx * dx + dz * dz <= 2.6 * 2.6 || w.mover.arrived()) {
                    w.mover.stop();
                    phase = 2;
                } else if (w.mover.failed() || time > 600) {
                    w.skip(base, 2400);
                    return true;
                }
                return false;
            }
            // Chopping: axe in hand, swing after swing, cracks growing in the log, then it breaks.
            if (current == null) {
                while (!logs.isEmpty() && logs.get(0).getType() != kind) logs.remove(0);
                if (logs.isEmpty()) {
                    if (replant) Replant.add(base, saplingFor(kind));
                    return true;
                }
                if (!Work.hasTool(w, "_AXE")) return true; // the axe broke
                w.holdTool();
                current = logs.remove(0);
                hits = 0;
            }
            if (--wait > 0) return false;
            wait = 5;
            w.anim.look(current.getLocation().add(0.5, 0.5, 0.5));
            w.body.swingMainHand();
            hits++;
            float progress = Math.min(0.9f, hits / (float) HITS);
            for (org.bukkit.entity.Player p : current.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(current.getLocation()) < 48 * 48) p.sendBlockDamage(current.getLocation(), progress, w.body.getEntityId());
            }
            current.getWorld().playSound(current.getLocation(), "minecraft:block.wood.hit", SoundCategory.BLOCKS, 0.7f, 0.9f + w.random.nextFloat() * 0.2f);
            if (hits < HITS) return false;
            Block log = current;
            current = null;
            for (org.bukkit.entity.Player p : log.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(log.getLocation()) < 48 * 48) p.sendBlockDamage(log.getLocation(), 0f, w.body.getEntityId());
            }
            log.getWorld().playEffect(log.getLocation(), Effect.STEP_SOUND, log.getBlockData());
            log.setType(Material.AIR);
            w.give(List.of(new ItemStack(kind)), w.body.getLocation());
            Work.wearTool(w, "_AXE");
            return false;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return "Felling a tree";
        }
    }
}
