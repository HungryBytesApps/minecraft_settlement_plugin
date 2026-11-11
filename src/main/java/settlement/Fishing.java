package settlement;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Fisher (a barrel within 2 blocks of water): fishes next to its barrel with a rod from storage, keeps the
 * catch in the barrel (3 slots, only for the fisher; a pile of fish on the lid shows how full it is) and
 * carries it to the chests when it fills up. Fish whose storage target is reached go back into the water;
 * junk is kept only when chosen in the junk filter; treasure (in open water) is always kept.
 */
final class Fishing implements Listener {
    private final SettlementPlugin plugin;

    Fishing(SettlementPlugin plugin) {
        this.plugin = plugin;
    }

    static final List<Material> FISH = List.of(Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH);
    /** Vanilla junk (bamboo only in jungles: left out). */
    static final List<Material> JUNK = List.of(Material.LILY_PAD, Material.BOWL, Material.LEATHER, Material.LEATHER_BOOTS, Material.ROTTEN_FLESH,
            Material.STICK, Material.STRING, Material.POTION, Material.BONE, Material.INK_SAC, Material.TRIPWIRE_HOOK, Material.FISHING_ROD);
    private static final Set<Material> KEEP_BY_DEFAULT = Set.of(Material.LEATHER, Material.STRING, Material.BONE, Material.INK_SAC, Material.BOWL);
    static final int SLOTS = 3;
    private static final NamespacedKey PILE = new NamespacedKey(Keys.NS, "fish_pile");

    // ---- Where ----

    /** Water (a source with air above) within 2 blocks of the barrel. */
    static List<Block> waterNear(Block barrel) {
        List<Block> out = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    Block b = barrel.getRelative(dx, dy, dz);
                    if (b.getType() == Material.WATER && b.getRelative(BlockFace.UP).getType().isAir()) out.add(b);
                }
            }
        }
        return out;
    }

    static boolean isFishingBarrel(Block b) {
        return b.getType() == Material.BARREL && !waterNear(b).isEmpty();
    }

    /** Open water like vanilla (roughly): 5x5 of water two deep around it, nothing on top. Treasure only there. */
    static boolean open(Block water) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    Block b = water.getRelative(dx, dy, dz);
                    if (b.getType() != Material.WATER || !(b.getBlockData() instanceof Levelled l) || l.getLevel() != 0) return false;
                }
                Material above = water.getRelative(dx, 1, dz).getType();
                if (!above.isAir() && above != Material.LILY_PAD) return false;
            }
        }
        return true;
    }

    static boolean anyOpen(Block barrel) {
        for (Block w : waterNear(barrel)) if (open(w)) return true;
        return false;
    }

    // ---- What it keeps ----

    static boolean keepJunk(Town.Workplace wp, Material m) {
        Boolean v = wp.options.get("JUNK_" + m.name());
        return v != null ? v : KEEP_BY_DEFAULT.contains(m);
    }

    private static boolean fishWanted(Town t, Material fish, int radius) {
        Integer target = t.targets.get(fish);
        return target == null || t.count(fish, radius) < target; // no target: keep all
    }

    // ---- The work ----

    static Activity next(Worker w, Town t, Town.Workplace wp) {
        Block barrel = wp.loc.getBlock();
        if (!(barrel.getState(false) instanceof Barrel b)) return null;
        int radius = w.plugin.settings().townRadius();
        Inventory inv = b.getInventory();
        pile(barrel);

        // The catch it carries goes into the barrel; a full barrel goes to the chests.
        boolean carriesCatch = false;
        for (ItemStack it : w.pockets.getContents()) if (it != null && it.getType() != Material.FISHING_ROD && !Work.gear(it.getType())) carriesCatch = true;
        if (carriesCatch) {
            if (room(inv)) {
                w.workStatus = "Putting the catch in the barrel";
                return new GoAndDo(w, barrel, 2.0, "Putting the catch in the barrel", Fishing::isBarrel, bl -> {
                    if (!(bl.getState(false) instanceof Barrel br)) return;
                    Inventory bi = br.getInventory();
                    for (int i = 0; i < w.pockets.getSize(); i++) {
                        ItemStack it = w.pockets.getItem(i);
                        if (it == null || it.getType() == Material.FISHING_ROD || Work.gear(it.getType())) continue;
                        ItemStack left = add(bi, it.clone());
                        w.pockets.setItem(i, left);
                    }
                    w.savePockets();
                    t.countsChanged();
                    pile(bl);
                });
            }
            Activity d = Work.deposit(w, t, m -> m == Material.FISHING_ROD || Work.gear(m));
            if (d != null) return d;
        }
        if (full(inv)) {
            w.workStatus = "Taking the catch to storage";
            return new GoAndDo(w, barrel, 2.0, "Emptying the barrel", Fishing::isBarrel, bl -> {
                if (!(bl.getState(false) instanceof Barrel br)) return;
                Inventory bi = br.getInventory();
                for (int i = 0; i < bi.getSize(); i++) {
                    ItemStack it = bi.getItem(i);
                    if (it == null || it.getType().isAir()) continue;
                    Map<Integer, ItemStack> left = w.pockets.addItem(it.clone());
                    bi.setItem(i, left.isEmpty() ? null : left.values().iterator().next());
                }
                w.savePockets();
                t.countsChanged();
                pile(bl);
                w.carryToStorage = true;
            });
        }
        if (w.carryToStorage) {
            Activity d = Work.deposit(w, t, m -> m == Material.FISHING_ROD || Work.gear(m));
            if (d != null) return d;
            w.carryToStorage = false;
        }
        // Enough of every fish: nothing to do.
        boolean anyWanted = false;
        for (Material f : FISH) if (fishWanted(t, f, radius)) anyWanted = true;
        if (!anyWanted) {
            w.workStatus = "Enough fish in storage";
            return null;
        }
        if (w.count(Material.FISHING_ROD) == 0) {
            Activity f = Work.fetch(w, t, m -> m == Material.FISHING_ROD, 1, "Getting a fishing rod");
            if (f != null) return f;
            w.workStatus = "Needs a fishing rod";
            w.warnMissingTool("a fishing rod");
            return null;
        }
        List<Block> water = waterNear(barrel);
        if (water.isEmpty()) {
            w.workStatus = "No water next to the barrel";
            return null;
        }
        Block spot = water.get(w.random.nextInt(water.size()));
        w.workStatus = "Fishing";
        return new Cast(w, t, wp, barrel, spot);
    }

    private static boolean isBarrel(Block b) {
        return b.getType() == Material.BARREL;
    }

    /** Room in the first 3 slots? */
    private static boolean room(Inventory inv) {
        for (int i = 0; i < SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || it.getAmount() < it.getMaxStackSize()) return true;
        }
        return false;
    }

    /** Full: all 3 slots used, or a good load (so the fisher goes to the chests often). */
    private static boolean full(Inventory inv) {
        int used = 0, total = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            used++;
            total += it.getAmount();
        }
        return used >= SLOTS || total >= 16;
    }

    /** Add to the first 3 slots only; returns what's left (or null). */
    private static ItemStack add(Inventory inv, ItemStack it) {
        for (int i = 0; i < SLOTS && it.getAmount() > 0; i++) {
            ItemStack cur = inv.getItem(i);
            if (cur == null || cur.getType().isAir()) {
                inv.setItem(i, it.clone());
                return null;
            }
            if (cur.isSimilar(it) && cur.getAmount() < cur.getMaxStackSize()) {
                int n = Math.min(it.getAmount(), cur.getMaxStackSize() - cur.getAmount());
                cur.setAmount(cur.getAmount() + n);
                inv.setItem(i, cur);
                it.setAmount(it.getAmount() - n);
            }
        }
        return it.getAmount() > 0 ? it : null;
    }

    /** One cast: stand by the barrel, throw the line, wait for a bite, reel in, keep it or throw it back. */
    private static final class Cast implements Activity {
        private final Worker w;
        private final Town t;
        private final Town.Workplace wp;
        private final Block barrel, water;
        private BlockDisplay bobber;
        private int time, phase, biteAt, repath;
        private Location stand;

        Cast(Worker w, Town t, Town.Workplace wp, Block barrel, Block water) {
            this.w = w;
            this.t = t;
            this.wp = wp;
            this.barrel = barrel;
            this.water = water;
        }

        private Location bobberAt() {
            return water.getLocation().add(0.45, 0.82, 0.45);
        }

        @Override
        public boolean tick() {
            time++;
            if (phase == 0) { // walk next to the barrel, on the water side
                if (stand == null) stand = standSpot();
                if (stand == null) return true;
                if (w.body.getLocation().distanceSquared(stand) < 0.8 * 0.8 || (time > 2 && w.mover.arrived())) {
                    w.mover.stop();
                    phase = 1;
                    time = 0;
                } else if (--repath <= 0) {
                    repath = 20;
                    if (!w.mover.go(stand, 0.5, w.plugin.settings().workSpeed()) && time > 60) return true;
                }
                if (time > 600) return true;
                return false;
            }
            if (phase == 1) { // cast
                w.hold(Material.FISHING_ROD);
                w.anim.look(bobberAt());
                w.body.swingMainHand();
                w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.fishing_bobber.throw", SoundCategory.NEUTRAL, 0.6f, 0.8f);
                bobber = water.getWorld().spawn(bobberAt(), BlockDisplay.class, d -> {
                    d.setBlock(Material.RED_WOOL.createBlockData());
                    d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.12f, 0.12f, 0.12f), new AxisAngle4f()));
                    d.setTeleportDuration(10);
                    d.setPersistent(false);
                    Keys.markTemp(d);
                });
                int wait = 100 + w.random.nextInt(500); // 5 to 30 seconds, like vanilla
                if (water.getWorld().hasStorm()) wait = wait * 3 / 4;
                biteAt = wait;
                phase = 2;
                time = 0;
                return false;
            }
            if (phase == 2) { // waiting
                w.anim.look(bobberAt());
                if (bobber != null && time % 20 == 0) bobber.teleport(bobberAt().add(0, (time / 20) % 2 == 0 ? 0.03 : -0.03, 0));
                if (time >= biteAt) {
                    water.getWorld().spawnParticle(Particle.SPLASH, bobberAt(), 12, 0.15, 0.05, 0.15);
                    water.getWorld().spawnParticle(Particle.BUBBLE, bobberAt().add(0, -0.2, 0), 6, 0.1, 0.05, 0.1);
                    water.getWorld().playSound(bobberAt(), "minecraft:entity.fishing_bobber.splash", SoundCategory.NEUTRAL, 0.5f, 1f);
                    if (bobber != null) bobber.teleport(bobberAt().add(0, -0.15, 0));
                    phase = 3;
                    time = 0;
                }
                return false;
            }
            if (phase == 3 && time >= 8) { // reel in
                w.body.swingMainHand();
                w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.fishing_bobber.retrieve", SoundCategory.NEUTRAL, 0.6f, 1f);
                removeBobber();
                ItemStack caught = roll(w, open(water));
                Work.wearTool(w, "FISHING_ROD");
                int radius = w.plugin.settings().townRadius();
                Material m = caught.getType();
                boolean keep;
                if (FISH.contains(m)) keep = fishWanted(t, m, radius);
                else if (JUNK.contains(m)) keep = keepJunk(wp, m);
                else keep = true; // treasure
                if (keep) {
                    w.give(List.of(caught), w.body.getLocation());
                    w.savePockets();
                    w.body.getWorld().playSound(w.body.getLocation(), "minecraft:entity.item.pickup", SoundCategory.NEUTRAL, 0.4f, 1.4f);
                } else {
                    water.getWorld().spawnParticle(Particle.SPLASH, bobberAt(), 8, 0.1, 0.05, 0.1); // back into the water
                    water.getWorld().playSound(bobberAt(), "minecraft:entity.generic.splash", SoundCategory.NEUTRAL, 0.3f, 1.5f);
                }
                return true;
            }
            return false;
        }

        /** A walkable spot right next to the barrel, closest to the water. */
        private Location standSpot() {
            Location best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        int x = barrel.getX() + dx, y = barrel.getY() + dy, z = barrel.getZ() + dz;
                        if (!PathFinder.walkable(barrel.getWorld(), x, y, z)) continue;
                        Location l = new Location(barrel.getWorld(), x + 0.5, y, z + 0.5);
                        double d = l.distanceSquared(water.getLocation().add(0.5, 0.5, 0.5));
                        if (d < bestD) {
                            bestD = d;
                            best = l;
                        }
                    }
                }
            }
            return best;
        }

        private void removeBobber() {
            if (bobber != null) bobber.remove();
            bobber = null;
        }

        @Override
        public void stop() {
            removeBobber();
            w.mover.stop();
            w.holdTool();
        }

        @Override
        public String label() {
            return "Fishing";
        }
    }

    /** What bites, with vanilla's chances (Luck of the Sea helps; treasure only in open water). */
    static ItemStack roll(Worker w, boolean openWater) {
        Random r = w.random;
        int luck = 0;
        for (ItemStack it : w.pockets.getContents()) {
            if (it != null && it.getType() == Material.FISHING_ROD) {
                luck = it.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA);
                break;
            }
        }
        double treasure = openWater ? 0.05 + 0.021 * luck : 0, junk = Math.max(0, 0.10 - 0.019 * luck);
        double x = r.nextDouble();
        if (x < treasure) {
            switch (r.nextInt(6)) {
                case 0 -> {
                    return enchanted(new ItemStack(Material.BOW), r);
                }
                case 1 -> {
                    return enchanted(new ItemStack(Material.BOOK), r);
                }
                case 2 -> {
                    return enchanted(new ItemStack(Material.FISHING_ROD), r);
                }
                case 3 -> {
                    return new ItemStack(Material.NAME_TAG);
                }
                case 4 -> {
                    return new ItemStack(Material.NAUTILUS_SHELL);
                }
                default -> {
                    return new ItemStack(Material.SADDLE);
                }
            }
        }
        if (x < treasure + junk) {
            int[] weights = {17, 10, 10, 10, 10, 5, 5, 10, 10, 1, 10, 2};
            int sum = 0;
            for (int wgt : weights) sum += wgt;
            int pick = r.nextInt(sum);
            for (int i = 0; i < weights.length; i++) {
                pick -= weights[i];
                if (pick < 0) {
                    Material m = JUNK.get(i);
                    ItemStack it = new ItemStack(m, m == Material.INK_SAC ? 10 : 1);
                    if (m == Material.POTION && it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                        pm.setBasePotionType(org.bukkit.potion.PotionType.WATER);
                        it.setItemMeta(pm);
                    }
                    if ((m == Material.LEATHER_BOOTS || m == Material.FISHING_ROD) && it.getItemMeta() instanceof Damageable dm) {
                        dm.setDamage((int) (m.getMaxDurability() * (0.1 + r.nextDouble() * 0.8)));
                        it.setItemMeta(dm);
                    }
                    return it;
                }
            }
        }
        int f = r.nextInt(100);
        Material fish = f < 60 ? Material.COD : f < 85 ? Material.SALMON : f < 87 ? Material.TROPICAL_FISH : Material.PUFFERFISH;
        return new ItemStack(fish);
    }

    private static ItemStack enchanted(ItemStack it, Random r) {
        try {
            return Bukkit.getItemFactory().enchantWithLevels(it, 30, true, r);
        } catch (RuntimeException e) {
            return it.getType() == Material.BOOK ? new ItemStack(Material.ENCHANTED_BOOK) : it;
        }
    }

    // ---- The pile of fish on the lid ----

    private static final Map<Block, String> shown = new HashMap<>();

    /** Up to 3 items lying on the barrel (one per used slot), only redrawn when it changes. */
    static void pile(Block barrel) {
        if (!(barrel.getState(false) instanceof Barrel b)) {
            clearPile(barrel);
            return;
        }
        Inventory inv = b.getInventory();
        List<Material> items = new ArrayList<>();
        for (int i = 0; i < inv.getSize() && items.size() < 3; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) items.add(it.getType());
        }
        String sig = items.toString();
        if (sig.equals(shown.get(barrel))) return;
        clearPile(barrel);
        shown.put(barrel, sig);
        float[][] spots = {{0.32f, 0.3f, 0.6f}, {0.62f, 0.45f, 2.2f}, {0.45f, 0.68f, 4.0f}};
        for (int i = 0; i < items.size(); i++) {
            float[] s = spots[i];
            Material m = items.get(i);
            float yaw = s[2];
            barrel.getWorld().spawn(barrel.getLocation().add(s[0], 1.02 + i * 0.015, s[1]), ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(m));
                // lying flat on the lid, each turned a bit
                AxisAngle4f flat = new AxisAngle4f((float) (Math.PI / 2), 1, 0, 0);
                AxisAngle4f turn = new AxisAngle4f(yaw, 0, 1, 0);
                d.setTransformation(new Transformation(new Vector3f(), turn, new Vector3f(0.45f, 0.45f, 0.45f), flat));
                d.setPersistent(true);
                d.getPersistentDataContainer().set(PILE, PersistentDataType.BYTE, (byte) 1);
            });
        }
    }

    static void clearPile(Block barrel) {
        shown.remove(barrel);
        for (Entity e : barrel.getWorld().getNearbyEntities(barrel.getLocation().add(0.5, 1.1, 0.5), 0.7, 0.4, 0.7)) {
            if (e instanceof ItemDisplay && e.getPersistentDataContainer().has(PILE)) e.remove();
        }
    }

    // ---- Junk filter ----

    static void junkMenu(SettlementPlugin plugin, Player p, Town t, Town.Workplace wp) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Material m : JUNK) {
            boolean on = keepJunk(wp, m);
            String name = m == Material.POTION ? "Water bottle" : TargetsMenu.nice(m);
            Component label = TargetsMenu.icon(m).append(Component.text(" " + name, on ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            buttons.add(ActionButton.create(label, Component.text(on ? "Kept: click to throw back" : "Thrown back: click to keep"), 150,
                    Menus.click(plugin, (view, audience) -> {
                        if (!(audience instanceof Player pl)) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            wp.options.put("JUNK_" + m.name(), !keepJunk(wp, m));
                            plugin.towns().save();
                            junkMenu(plugin, pl, t, wp);
                        });
                    }, Menus.ONCE)));
        }
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> Menus.workplace(plugin, pl, t, wp));
        Menus.show(p, "Junk filter", List.of(Menus.line("Green: kept. Grey: thrown back into the water. Treasure is always kept.", NamedTextColor.GRAY)),
                List.of(), DialogType.multiAction(buttons).columns(3).exitAction(back).build());
    }

    // ---- Protection ----

    /** The fisher's barrel is only for the fisher (and the settlers taking the catch). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !(e.getInventory().getHolder(false) instanceof Barrel b)) return;
        Town t = plugin.towns().at(b.getLocation());
        if (t == null || t.workplace(b.getLocation()) == null || !isFishingBarrel(b.getBlock())) return;
        e.setCancelled(true);
        p.sendActionBar(Component.text("This is the fisher's barrel.", NamedTextColor.YELLOW));
    }

    /** Broken: its contents drop (vanilla), the pile goes. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() == Material.BARREL) clearPile(e.getBlock());
    }
}
