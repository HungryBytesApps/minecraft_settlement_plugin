package settlement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where a settler died: a skull and some bones on the ground (glowing, and on the locator bar) holding its
 * things. Right-click: its things in a chest window. Emptied: the grave is gone. Nothing on it: no grave.
 * Graves are kept in graves.yml; what you see of them is put back when their chunk loads.
 */
final class Graves implements Listener {
    private final SettlementPlugin plugin;

    Graves(SettlementPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        load();
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) show(c);
    }

    private static Graves instance;

    /** One grave: where, whose, what. */
    private static final class Grave implements InventoryHolder {
        final String id;
        final Location at;
        final String name;
        final String town;
        Inventory inv;
        final List<Entity> shown = new ArrayList<>();

        Grave(String id, Location at, String name, String town) {
            this.id = id;
            this.at = at;
            this.name = name;
            this.town = town;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final Map<String, Grave> graves = new HashMap<>();

    // ---- Making one ----

    /** Its pockets and what it wore into a grave where it lies. False: it had nothing (no grave). */
    static boolean bury(SettlementPlugin plugin, Worker w, Location at) {
        SettlerInventory.close(w);
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : w.pockets.getContents()) if (it != null && !it.getType().isAir()) items.add(it.clone());
        w.pockets.clear();
        org.bukkit.inventory.EntityEquipment eq = w.body.getEquipment();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFF_HAND}) {
            ItemStack it = eq.getItem(slot);
            if (it != null && !it.getType().isAir()) items.add(it.clone());
            eq.setItem(slot, null);
        }
        if (items.isEmpty() || instance == null) return false;
        Location ground = groundAt(at);
        Town t = w.town();
        Grave g = new Grave(UUID.randomUUID().toString(), ground, w.name, t == null ? null : t.id);
        g.inv = inventory(g, items);
        instance.graves.put(g.id, g);
        instance.save();
        if (ground.isChunkLoaded()) instance.spawn(g);
        return true;
    }

    private static Location groundAt(Location l) {
        Location g = l.getBlock().getLocation().add(0.5, 0, 0.5);
        for (int i = 0; i < 6 && g.getBlockY() > g.getWorld().getMinHeight() && g.clone().add(0, -1, 0).getBlock().isPassable(); i++) g.add(0, -1, 0);
        return g;
    }

    private static Inventory inventory(Grave g, List<ItemStack> items) {
        int size = Math.min(54, Math.max(9, (items.size() + 8) / 9 * 9));
        Inventory inv = Bukkit.createInventory(g, size, Component.text(g.name + "'s things"));
        for (ItemStack it : items) {
            for (ItemStack left : inv.addItem(it).values()) g.at.getWorld().dropItemNaturally(g.at, left); // more than 54 stacks
        }
        return inv;
    }

    // ---- What you see ----

    private void spawn(Grave g) {
        for (Entity e : g.shown) if (e.isValid()) return; // already there
        g.shown.clear();
        World w = g.at.getWorld();
        Location base = g.at.clone();
        // The skull, lying a little tilted, and two bones flat on the ground.
        g.shown.add(item(base.clone().add(0, 0.18, 0), Material.SKELETON_SKULL, 0.55f, new AxisAngle4f((float) Math.toRadians(-15), 1, 0, 0),
                new AxisAngle4f((float) Math.toRadians(25), 0, 1, 0)));
        g.shown.add(item(base.clone().add(0.35, 0.03, 0.1), Material.BONE, 0.5f, new AxisAngle4f((float) Math.toRadians(90), 1, 0, 0),
                new AxisAngle4f((float) Math.toRadians(40), 0, 0, 1)));
        g.shown.add(item(base.clone().add(-0.3, 0.03, -0.25), Material.BONE, 0.45f, new AxisAngle4f((float) Math.toRadians(90), 1, 0, 0),
                new AxisAngle4f((float) Math.toRadians(-70), 0, 0, 1)));
        // To right-click it.
        g.shown.add(w.spawn(base, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(0.5f);
            i.setResponsive(true);
            i.setPersistent(false);
            i.getPersistentDataContainer().set(GRAVE, org.bukkit.persistence.PersistentDataType.STRING, g.id);
        }));
        // On the locator bar: a tiny armor stand in the ground under it (out of sight).
        g.shown.add(w.spawn(base.clone().add(0, -1.2, 0), ArmorStand.class, a -> {
            a.setMarker(true);
            a.setSmall(true);
            a.setBasePlate(false);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
            Keys.markTemp(a);
            Downed.mark(a, true);
            a.setGlowing(false);
        }));
    }

    private ItemDisplay item(Location at, Material m, float scale, AxisAngle4f left, AxisAngle4f right) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(m));
            d.setTransformation(new Transformation(new Vector3f(), left, new Vector3f(scale, scale, scale), right));
            d.setGlowing(true);
            d.setPersistent(false);
            Keys.markTemp(d);
        });
    }

    private void unspawn(Grave g) {
        for (Entity e : g.shown) e.remove();
        g.shown.clear();
    }

    private void show(Chunk c) {
        for (Grave g : graves.values()) {
            if (g.at.getWorld() == c.getWorld() && (g.at.getBlockX() >> 4) == c.getX() && (g.at.getBlockZ() >> 4) == c.getZ()) spawn(g);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!graves.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getChunk().isLoaded()) show(e.getChunk());
        });
    }

    // ---- Opening and emptying ----

    private static final org.bukkit.NamespacedKey GRAVE = new org.bukkit.NamespacedKey(Keys.NS, "grave");

    @EventHandler(ignoreCancelled = true)
    public void onClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction i)) return;
        String id = i.getPersistentDataContainer().get(GRAVE, org.bukkit.persistence.PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Grave g = graves.get(id);
        if (g == null) {
            i.remove();
            return;
        }
        Player p = e.getPlayer();
        Town t = g.town == null ? null : plugin.towns().get(g.town);
        if (t != null && !t.canManage(p) && !p.hasPermission("settlement.admin")) {
            p.sendActionBar(Component.text("Here lies " + g.name + ".", NamedTextColor.GRAY));
            return;
        }
        p.openInventory(g.inv);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Grave g) || !graves.containsKey(g.id)) return;
        if (g.inv.isEmpty() && g.inv.getViewers().size() <= 1) { // everything taken: the grave is gone
            graves.remove(g.id);
            unspawn(g);
            g.at.getWorld().spawnParticle(org.bukkit.Particle.POOF, g.at.clone().add(0, 0.3, 0), 8, 0.2, 0.1, 0.2, 0.01);
        }
        save();
    }

    /** Server stopping: what you see of the graves goes (it comes back with the chunks). */
    static void hideAll() {
        if (instance == null) return;
        for (Grave g : instance.graves.values()) instance.unspawn(g);
        instance.save();
    }

    // ---- Saving ----

    private File file() {
        return new File(plugin.getDataFolder(), "graves.yml");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Grave g : graves.values()) {
            String k = "graves." + g.id;
            y.set(k + ".world", g.at.getWorld().getName());
            y.set(k + ".x", g.at.getX());
            y.set(k + ".y", g.at.getY());
            y.set(k + ".z", g.at.getZ());
            y.set(k + ".name", g.name);
            if (g.town != null) y.set(k + ".town", g.town);
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack it : g.inv.getContents()) if (it != null && !it.getType().isAir()) items.add(it);
            y.set(k + ".items", items);
        }
        try {
            y.save(file());
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not save graves.yml: " + ex.getMessage());
        }
    }

    private void load() {
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection s = y.getConfigurationSection("graves");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection g = s.getConfigurationSection(id);
            if (g == null) continue;
            World w = Bukkit.getWorld(g.getString("world", ""));
            if (w == null) continue;
            List<ItemStack> items = new ArrayList<>();
            for (Object o : g.getList("items", List.of())) if (o instanceof ItemStack it) items.add(it);
            if (items.isEmpty()) continue;
            Grave gr = new Grave(id, new Location(w, g.getDouble("x"), g.getDouble("y"), g.getDouble("z")), g.getString("name", "A settler"), g.getString("town"));
            gr.inv = inventory(gr, items);
            graves.put(id, gr);
        }
    }
}
