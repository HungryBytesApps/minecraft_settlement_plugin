package villagerworkers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Random;
import java.util.UUID;

public final class VillagerWorkersPlugin extends JavaPlugin {
    private Settings settings;
    private Workers workers;
    private Towns towns;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.from(getConfig());
        Keys.init(this);
        towns = new Towns(this);
        workers = new Workers(this);
        getServer().getPluginManager().registerEvents(new WorkerListener(this), this);

        for (World w : getServer().getWorlds()) {
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                if (Worker.isWorker(v)) load(v);
            }
        }
        getServer().getScheduler().runTaskTimer(this, workers::tick, 1L, 1L);
        // Holding the ledger shows the nearby settlement border.
        getServer().getScheduler().runTaskTimer(this, this::ledgerBorders, 20L, 10L);
    }

    @Override
    public void onDisable() {
        if (workers != null) for (Worker w : workers.all()) w.stop();
        if (towns != null) towns.save();
    }

    Settings settings() { return settings; }
    Workers workers() { return workers; }
    Towns towns() { return towns; }

    /** A settler came into the world: start it, or release it if its town is gone. */
    void load(Villager v) {
        String townId = v.getPersistentDataContainer().get(Keys.TOWN, PersistentDataType.STRING);
        if (towns.get(townId) == null) {
            strip(v);
            return;
        }
        workers.register(v);
    }

    Town ownTown(Player p) {
        List<Town> mine = towns.ownedBy(p.getUniqueId());
        if (mine.isEmpty()) return null;
        Town best = mine.get(0);
        for (Town t : mine) {
            if (t.bell.getWorld() == p.getWorld() && (best.bell.getWorld() != p.getWorld()
                    || t.bell.distanceSquared(p.getLocation()) < best.bell.distanceSquared(p.getLocation()))) best = t;
        }
        return best;
    }

    // ---- Towns ----

    boolean canFoundTown(Player p, Block bell, boolean tell) {
        if (!p.hasPermission("villagerworkers.admin") && towns.ownedBy(p.getUniqueId()).size() >= settings.maxTowns()) return false;
        Town other = towns.at(bell.getLocation());
        if (other != null && other.bell.distanceSquared(bell.getLocation()) < (double) settings.townRadius() * settings.townRadius()) {
            if (tell) p.sendActionBar(Component.text("Too close to " + other.name + ".", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    void foundTown(Player p, Block bell, String name) {
        if (bell.getType() != Material.BELL || towns.atBell(bell) != null || !canFoundTown(p, bell, true)) return;
        if (name == null || name.isBlank()) name = p.getName() + "'s Settlement";
        Town t = towns.create(p.getUniqueId(), name.trim(), bell.getLocation());
        registerComposters(t);
        bell.getWorld().playSound(bell.getLocation(), "minecraft:ui.toast.challenge_complete", SoundCategory.BLOCKS, 0.6f, 1.2f);
        p.sendActionBar(Component.text(t.name + " has been created! Here's your Settlement Ledger.", NamedTextColor.GREEN));
        for (ItemStack left : p.getInventory().addItem(Ledger.create()).values()) p.getWorld().dropItem(p.getLocation(), left);
        Menus.town(this, p, t);
    }

    /** Composters that were already there become workplaces. */
    private void registerComposters(Town t) {
        World w = t.bell.getWorld();
        int r = settings.townRadius();
        int bx = t.bell.getBlockX(), by = t.bell.getBlockY(), bz = t.bell.getBlockZ();
        for (int x = bx - r; x <= bx + r; x++) {
            for (int z = bz - r; z <= bz + r; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(w.getMinHeight(), by - 12); y <= Math.min(w.getMaxHeight() - 1, by + 12); y++) {
                    if (w.getType(x, y, z) == Material.COMPOSTER) {
                        Location l = new Location(w, x, y, z);
                        if (t.contains(l, r)) t.addWorkplace(l, settings.defaultRadius());
                    }
                }
            }
        }
        towns.save();
    }

    void disband(Town t) {
        for (Worker w : workers.all()) if (t.id.equals(w.townId)) dismiss(w);
        towns.remove(t);
    }

    // ---- Settlers ----

    private boolean hasRoom(Player p, Town t) {
        if (t.settlers.size() < settings.maxSettlers() || p.hasPermission("villagerworkers.admin")) return true;
        p.sendActionBar(Component.text(t.name + " already has " + settings.maxSettlers() + " settlers.", NamedTextColor.RED));
        return false;
    }

    /** Pay the price; a new settler appears some distance away and walks into town. */
    void recruit(Player p, Town t) {
        if (!hasRoom(p, t)) return;
        for (ItemStack cost : settings.recruitCost()) {
            if (!p.getInventory().containsAtLeast(cost, cost.getAmount())) {
                p.sendActionBar(Component.text("You need " + cost.getAmount() + " " + FarmerJob.nice(cost.getType()) + ".", NamedTextColor.RED));
                return;
            }
        }
        Location spawn = arrivalSpot(t);
        if (spawn == null) {
            p.sendActionBar(Component.text("No room for a new settler to arrive.", NamedTextColor.RED));
            return;
        }
        for (ItemStack cost : settings.recruitCost()) p.getInventory().removeItem(cost.clone());
        String name = settings.names().get(random.nextInt(settings.names().size()));
        Villager v = spawn.getWorld().spawn(spawn, Villager.class, nv -> nv.setProfession(Villager.Profession.NONE));
        makeSettler(v, t, p.getUniqueId(), name);
        p.sendActionBar(Component.text(name + " heard about " + t.name + " and is on the way!", NamedTextColor.GREEN));
    }

    /** A walkable spot 18–26 blocks from the bell (so the settler visibly walks in). */
    private Location arrivalSpot(Town t) {
        World w = t.bell.getWorld();
        for (int tries = 0; tries < 30; tries++) {
            double angle = random.nextDouble() * Math.PI * 2, dist = 18 + random.nextInt(9);
            int x = t.bell.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = t.bell.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            for (int y = t.bell.getBlockY() + 8; y >= t.bell.getBlockY() - 8; y--) {
                if (PathFinder.walkable(w, x, y, z)) {
                    Location l = new Location(w, x + 0.5, y, z + 0.5);
                    if (PathFinder.find(l, t.bell.clone().add(0.5, -1, 0.5), 4) != null) return l;
                    break;
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    int x = t.bell.getBlockX() + dx, y = t.bell.getBlockY() + dy, z = t.bell.getBlockZ() + dz;
                    if (PathFinder.walkable(w, x, y, z)) return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    void hire(Player p, Villager v, Town t, String name) {
        if (!v.isValid() || Worker.isWorker(v) || towns.get(t.id) == null || !hasRoom(p, t)) return;
        if (name == null || name.isBlank()) name = settings.names().get(random.nextInt(settings.names().size()));
        makeSettler(v, t, p.getUniqueId(), name.trim());
        v.getWorld().playSound(v.getLocation(), "minecraft:entity.villager.celebrate", SoundCategory.NEUTRAL, 1f, 1f);
        p.sendActionBar(Component.text(name.trim() + " joined " + t.name + "!", NamedTextColor.GREEN));
    }

    private void makeSettler(Villager v, Town t, UUID owner, String name) {
        PersistentDataContainer pdc = v.getPersistentDataContainer();
        pdc.set(Keys.OWNER, PersistentDataType.STRING, owner.toString());
        pdc.set(Keys.NAME, PersistentDataType.STRING, name);
        pdc.set(Keys.TOWN, PersistentDataType.STRING, t.id);
        Worker w = workers.register(v);
        w.save();
        w.applyLook();
    }

    void dismiss(Worker w) {
        forget(w);
        strip(w.villager);
    }

    /** Remove a settler from its town and workplace (death or dismissal). */
    void forget(Worker w) {
        workers.unregister(w.villager.getUniqueId());
        Town t = w.town();
        if (t != null) {
            t.settlers.remove(w.villager.getUniqueId());
            for (Town.Workplace wp : t.workplaces.values()) {
                if (w.villager.getUniqueId().equals(wp.worker)) wp.worker = null;
            }
            towns.save();
        }
        Social.releaseBed(w);
    }

    /** Back to a normal villager. */
    private void strip(Villager v) {
        PersistentDataContainer pdc = v.getPersistentDataContainer();
        for (NamespacedKey key : new NamespacedKey[]{Keys.OWNER, Keys.NAME, Keys.TOWN, Keys.WORKPLACE, Keys.BED}) pdc.remove(key);
        v.customName(null);
        v.setCustomNameVisible(false);
        v.setAware(true);
    }

    // ---- Settlement border ----

    private static final Particle.DustOptions BORDER = new Particle.DustOptions(Color.fromRGB(255, 170, 40), 1.6f);

    /** The whole border for 10 seconds. */
    void showBorder(Player p, Town t) {
        final BukkitTask[] task = new BukkitTask[1];
        final int[] runs = {0};
        task[0] = getServer().getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline() || runs[0]++ >= 20) {
                task[0].cancel();
                return;
            }
            drawBorder(p, t, Double.MAX_VALUE);
        }, 0L, 10L);
    }

    private void ledgerBorders() {
        for (Player p : getServer().getOnlinePlayers()) {
            if (!Ledger.is(p.getInventory().getItemInMainHand())) continue;
            for (Town t : towns.all()) {
                if (t.bell.getWorld() != p.getWorld()) continue;
                double reach = settings.townRadius() + 24;
                if (t.bell.distanceSquared(p.getLocation()) <= reach * reach) drawBorder(p, t, 24);
            }
        }
    }

    /** Particles on the ground along the border circle, within {@code range} blocks of the player. */
    private void drawBorder(Player p, Town t, double range) {
        World w = t.bell.getWorld();
        if (w != p.getWorld()) return;
        int r = settings.townRadius();
        double cx = t.bell.getBlockX() + 0.5, cz = t.bell.getBlockZ() + 0.5;
        Location me = p.getLocation();
        int points = (int) Math.ceil(2 * Math.PI * r);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
            double dx = x - me.getX(), dz = z - me.getZ();
            if (dx * dx + dz * dz > range * range) continue;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            double y = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1.2;
            p.spawnParticle(Particle.DUST, x, y, z, 1, BORDER);
        }
    }

    // ---- Show area ----

    /** Outlines an area with particles for 6 seconds, only for this player. */
    void showArea(Player p, Location c, int r) {
        Particle.DustOptions green = new Particle.DustOptions(Color.LIME, 1.2f);
        final BukkitTask[] task = new BukkitTask[1];
        final int[] runs = {0};
        task[0] = getServer().getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline() || runs[0]++ >= 12 || p.getWorld() != c.getWorld()) {
                task[0].cancel();
                return;
            }
            double y = c.getBlockY() + 0.2;
            for (int i = -r; i <= r + 1; i++) {
                p.spawnParticle(Particle.DUST, c.getBlockX() + i, y, c.getBlockZ() - r, 1, green);
                p.spawnParticle(Particle.DUST, c.getBlockX() + i, y, c.getBlockZ() + r + 1, 1, green);
                p.spawnParticle(Particle.DUST, c.getBlockX() - r, y, c.getBlockZ() + i, 1, green);
                p.spawnParticle(Particle.DUST, c.getBlockX() + r + 1, y, c.getBlockZ() + i, 1, green);
            }
        }, 0L, 10L);
    }
}
