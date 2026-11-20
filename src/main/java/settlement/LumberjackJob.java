package settlement;

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
        int needSticks = sticksNeeded(w, t);
        Predicate<Material> keep = m -> Tag.SAPLINGS.isTagged(m) || m == Material.MANGROVE_PROPAGULE || m.name().endsWith("_AXE") || Work.gear(m)
                || (needSticks > 0 && Tag.PLANKS.isTagged(m)); // planks it fetched for sticks
        // Saplings for replanting: 16 of a kind is plenty, the rest goes to storage.
        java.util.function.ToIntFunction<Material> limit = m -> Tag.SAPLINGS.isTagged(m) || m == Material.MANGROVE_PROPAGULE ? 16 : Integer.MAX_VALUE;
        if (Work.needsDeposit(w, keep, limit, false)) {
            Activity d = Work.deposit(w, t, keep, limit);
            if (d != null) return d;
        }
        if (org.bukkit.Bukkit.getCurrentTick() < w.storageFullUntil || w.pockets.firstEmpty() < 0) { // storage full: keep what it has, stop
            w.workStatus = "Storage is full";
            return null;
        }
        // Saplings, apples and sticks from the leaves: pick them up.
        Activity pick = Work.collectDrops(w, t, LumberjackJob::wanted, 20);
        if (pick != null) return pick;
        // Replant spots where a tree stood.
        int radius = w.plugin.settings().townRadius();
        boolean outside = wp.isOn("OUTSIDE");
        Block spot = !wp.isOn("REPLANT") ? null : Work.nearestOf(w, Replant.all(), b -> b.getType().isAir() && !w.isSkipped(b) && plantable(b.getRelative(BlockFace.DOWN))
                && b.getWorld() == t.world() && t.inside(b.getX() + 0.5, b.getZ() + 0.5, radius - Town.BASE + 30));
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
            if (Replant.old(spot)) Replant.done(spot); // none anywhere for 5 minutes: forget it
            else w.skip(spot, 600); // the leaves may still drop some
        }
        // Sticks below their storage target: made at its fletching table from planks (or spare logs).
        if (needSticks > 0) {
            Activity a = sticks(w, t, wp, needSticks, radius);
            if (a != null) return a;
        }
        // Real trees only (a log on the ground with natural leaves), inside or just outside the settlement.
        Block base = Work.nearestOf(w, Fields.trees(t, radius, outside), b -> isTreeBase(b) && t.wants(b.getType(), radius, w));
        if (base != null) {
            Work.claim(w, base);
            if (!Work.hasTool(w, "_AXE")) {
                Activity f = Work.fetchTool(w, t, "_AXE", "an axe");
                if (f != null) return f;
                w.workStatus = "Needs an axe (none in storage)";
                w.warnMissingTool("an axe");
                return null;
            }
            List<Block> logs = tree(base);
            if (logs != null) {
                w.workStatus = "Felling a tree";
                return new Chop(w, base, logs, wp.isOn("REPLANT"));
            }
            w.skip(base, 6000);
        }
        if (Work.needsDeposit(w, keep, limit, true)) {
            Activity d = Work.deposit(w, t, keep, limit);
            if (d != null) return d;
        }
        boolean trees = Work.nearestOf(w, Fields.trees(t, radius, outside), LumberjackJob::isTreeBase) != null;
        if (trees) w.workStatus = "The settlement has enough wood";
        else if (!Fields.saplings(t, radius, outside).isEmpty()) w.workStatus = "Waiting for the saplings to grow";
        else w.workStatus = outside ? "No trees just outside the settlement" : "No trees in the settlement";
        return null;
    }

    /** How many sticks the storage target still wants (0: none, or no target set). */
    private static int sticksNeeded(Worker w, Town t) {
        Integer target = t.targets.get(Material.STICK);
        if (target == null || target <= 0) return 0;
        return Math.max(0, target - t.count(Material.STICK, w.plugin.settings().townRadius()) - w.count(Material.STICK));
    }

    /** The planks a log (or stem, wood, bamboo block) makes, or null. */
    static Material planksOf(Material log) {
        if (log == Material.BAMBOO_BLOCK || log == Material.STRIPPED_BAMBOO_BLOCK) return Material.BAMBOO_PLANKS;
        String n = log.name().replace("STRIPPED_", "");
        for (String end : new String[]{"_LOG", "_WOOD", "_STEM", "_HYPHAE"})
            if (n.endsWith(end)) return Material.matchMaterial(n.substring(0, n.length() - end.length()) + "_PLANKS");
        return null;
    }

    /** What's in storage above its own target (planks and logs aren't used below their targets). */
    private static int spare(Town t, Material m, int radius) {
        Integer own = t.targets.get(m);
        return t.count(m, radius) - (own == null ? 0 : own);
    }

    private static int carriedPlanks(Worker w) {
        int n = 0;
        for (ItemStack it : w.pockets.getContents()) if (it != null && Tag.PLANKS.isTagged(it.getType())) n += it.getAmount();
        return n;
    }

    /** Make sticks: planks (or logs, first into planks) at the fletching table, 2 planks for 4 sticks. */
    private static Activity sticks(Worker w, Town t, Town.Workplace wp, int need, int radius) {
        int planksNeeded = (need + 3) / 4 * 2;
        int carried = carriedPlanks(w);
        boolean logs = false;
        for (ItemStack it : w.pockets.getContents())
            if (it != null && Tag.LOGS.isTagged(it.getType()) && planksOf(it.getType()) != null && spare(t, it.getType(), radius) + w.count(it.getType()) > 0) logs = true;
        if (carried >= 2 || (carried < planksNeeded && logs)) {
            Block table = wp.loc.getBlock();
            if (w.isSkipped(table)) return null;
            w.workStatus = "Making sticks";
            return new GoAndDo(w, table, 2.0, "Making sticks", b -> b.getType() == Material.FLETCHING_TABLE, b -> {
                int want = (sticksNeeded(w, t) + 3) / 4 * 2; // planks to use
                if (want <= 0) return;
                // Short of planks: a log it carries becomes 4 planks first.
                while (carriedPlanks(w) < want) {
                    Material log = null;
                    for (ItemStack it : w.pockets.getContents())
                        if (it != null && Tag.LOGS.isTagged(it.getType()) && planksOf(it.getType()) != null
                                && spare(t, it.getType(), radius) + w.count(it.getType()) > 0) { // (not logs its own target still needs)
                            log = it.getType();
                            break;
                        }
                    if (log == null || !w.take(log, 1)) break;
                    for (ItemStack left : w.pockets.addItem(new ItemStack(planksOf(log), 4)).values())
                        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 1, 0.5), left);
                }
                int use = Math.min(want, carriedPlanks(w)) / 2 * 2;
                if (use < 2) return;
                int left = use;
                for (int i = 0; i < w.pockets.getSize() && left > 0; i++) {
                    ItemStack it = w.pockets.getItem(i);
                    if (it == null || !Tag.PLANKS.isTagged(it.getType())) continue;
                    int n = Math.min(left, it.getAmount());
                    it.setAmount(it.getAmount() - n);
                    w.pockets.setItem(i, it.getAmount() > 0 ? it : null);
                    left -= n;
                }
                for (ItemStack rest : w.pockets.addItem(new ItemStack(Material.STICK, use * 2)).values())
                    b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 1, 0.5), rest);
                w.savePockets();
                b.getWorld().playSound(b.getLocation(), "minecraft:ui.cartography_table.take_result", SoundCategory.BLOCKS, 0.6f, 1.2f);
            });
        }
        // Planks from storage (spare ones), or else spare logs.
        int want = Math.min(64, planksNeeded - carried);
        Activity f = Work.fetch(w, t, m -> Tag.PLANKS.isTagged(m) && spare(t, m, radius) > 0, want, "Getting planks for sticks");
        if (f != null) return f;
        f = Work.fetch(w, t, m -> Tag.LOGS.isTagged(m) && planksOf(m) != null && spare(t, m, radius) > 0, Math.max(1, (want + 3) / 4), "Getting wood for sticks");
        if (f != null) return f;
        return null;
    }

    private static boolean plantable(Block b) {
        return Tag.DIRT.isTagged(b.getType()) || b.getType() == Material.GRASS_BLOCK;
    }

    /** The lowest log of a tree: a log standing on dirt. */
    private static boolean isTreeBase(Block b) {
        Material m = b.getType();
        if (!Tag.LOGS.isTagged(m) || m.name().startsWith("STRIPPED_") || m.name().endsWith("_WOOD") || m.name().endsWith("_HYPHAE")) return false;
        if (b.getBlockData() instanceof org.bukkit.block.data.Orientable o && o.getAxis() != org.bukkit.Axis.Y) return false; // lying: a build
        return plantable(b.getRelative(BlockFace.DOWN));
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
                        if (built(n.getType())) return null; // planks, glass, a door... next to it: someone's building, not a tree
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

    private static boolean built(Material m) {
        return Tag.PLANKS.isTagged(m) || Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.STAIRS.isTagged(m) || Tag.SLABS.isTagged(m)
                || Tag.FENCES.isTagged(m) || Tag.WOOL.isTagged(m) || Tag.BEDS.isTagged(m) || m.name().contains("GLASS") || m.name().endsWith("BRICKS");
    }

    /** Natural leaves around the trunk at feet and head height: in the way of getting to it. Nearest first. */
    static List<Block> leavesAround(Block base) {
        List<Block> out = new ArrayList<>();
        for (int dy = 0; dy <= 1; dy++)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    Block b = base.getRelative(dx, dy, dz);
                    if (b.getBlockData() instanceof Leaves l && !l.isPersistent()) out.add(b);
                }
        out.sort(Comparator.comparingInt(b -> Math.abs(b.getX() - base.getX()) + Math.abs(b.getZ() - base.getZ()) + b.getY()));
        return out;
    }

    static Material saplingFor(Material log) {
        if (log == Material.MANGROVE_LOG) return Material.MANGROVE_PROPAGULE;
        return Material.matchMaterial(log.name().replace("_LOG", "_SAPLING"));
    }

    /** Where trees were felled and what sapling goes there. */
    static final class Replant {
        private static final Map<Block, Material> spots = new HashMap<>();
        private static final Map<Block, Integer> since = new HashMap<>();

        /** Waited 5 minutes for a sapling (the leaves drop them as they decay): forget it. */
        static boolean old(Block b) {
            Integer s = since.get(b);
            return s == null || org.bukkit.Bukkit.getCurrentTick() - s > 6000;
        }

        static Material wanted(Block b) {
            return spots.get(b);
        }

        static void add(Block b, Material sapling) {
            if (sapling != null) {
                spots.put(b, sapling);
                since.put(b, org.bukkit.Bukkit.getCurrentTick());
            }
        }

        static void done(Block b) {
            spots.remove(b);
            since.remove(b);
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
        private final List<Block> bases = new ArrayList<>();
        private boolean cleared; // leaves in the way around the trunk: cut first
        private final List<Block> leaves = new ArrayList<>();
        private final boolean replant;
        private static final int HITS = 5; // swings per log

        Chop(Worker w, Block base, List<Block> logs, boolean replant) {
            this.replant = replant;
            this.w = w;
            this.base = base;
            this.logs = new ArrayList<>(logs);
            for (Block b : logs) if (b.getY() == base.getY()) bases.add(b);
            if (bases.isEmpty()) bases.add(base);
            this.kind = base.getType();
        }

        @Override
        public boolean tick() {
            time++;
            if (phase == 0 && !cleared) {
                cleared = true;
                leaves.addAll(leavesAround(base));
                if (!leaves.isEmpty()) {
                    if (!w.mover.go(base.getLocation().add(0.5, 0, 0.5), 4.5, w.plugin.settings().workSpeed())) {
                        w.skip(base, 2400);
                        return true;
                    }
                    phase = 3;
                    time = 0;
                    return false;
                }
            }
            if (phase == 3) { // walking up to the leaves
                Location me = w.body.getLocation();
                double dx = base.getX() + 0.5 - me.getX(), dz = base.getZ() + 0.5 - me.getZ();
                if (dx * dx + dz * dz <= 4.8 * 4.8 || w.mover.arrived()) {
                    w.mover.stop();
                    phase = 4;
                    Location eye = w.body.getEyeLocation();
                    leaves.sort(Comparator.comparingDouble(b -> b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(eye)));
                } else if (w.mover.failed() || time > 600) {
                    w.skip(base, 2400);
                    return true;
                }
                return false;
            }
            if (phase == 4) { // cutting the leaves away, a few swings each (they drop saplings and sticks as usual)
                if (--wait > 0) return false;
                wait = 4;
                while (!leaves.isEmpty() && (!(leaves.get(0).getBlockData() instanceof Leaves)
                        || leaves.get(0).getLocation().add(0.5, 0.5, 0.5).distanceSquared(w.body.getEyeLocation()) > 5.5 * 5.5)) leaves.remove(0);
                if (leaves.isEmpty()) {
                    phase = 0;
                    time = 0;
                    return false;
                }
                Block leaf = leaves.get(0);
                w.anim.look(leaf.getLocation().add(0.5, 0.5, 0.5));
                w.body.swingMainHand();
                if (++hits < 2) return false;
                hits = 0;
                leaves.remove(0);
                leaf.getWorld().playEffect(leaf.getLocation(), Effect.DESTROY_BLOCK, leaf.getBlockData());
                leaf.breakNaturally();
                return false;
            }
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
                    if (replant) for (Block b : bases) Replant.add(b, saplingFor(kind)); // a 2x2 tree: all four
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
            if (log.getType() != kind) return false; // someone else took it meanwhile
            log.getWorld().playEffect(log.getLocation(), Effect.DESTROY_BLOCK, log.getBlockData());
            log.setType(Material.AIR);
            w.give(List.of(new ItemStack(kind)), w.body.getLocation());
            Work.wearTool(w, "_AXE");
            return false;
        }

        @Override
        public void stop() {
            w.mover.stop();
            if (current != null) for (org.bukkit.entity.Player p : current.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(current.getLocation()) < 48 * 48) p.sendBlockDamage(current.getLocation(), 0f, w.body.getEntityId());
            }
        }

        @Override
        public String label() {
            return "Felling a tree";
        }
    }
}
