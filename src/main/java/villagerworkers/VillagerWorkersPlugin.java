package villagerworkers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Mannequin;
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
    private Borders borders;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.from(getConfig());
        Keys.init(this);
        Chat.load(this);
        towns = new Towns(this);
        workers = new Workers(this);
        getServer().getPluginManager().registerEvents(new WorkerListener(this), this);
        getServer().getPluginManager().registerEvents(new SettlerInventory.Events(this), this);

        for (World w : getServer().getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) {
                if (Worker.isWorker(m)) load(m);
            }
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                if (isOldSettler(v)) convertOld(v);
            }
        }
        getServer().getScheduler().runTaskTimer(this, workers::tick, 1L, 1L);
        // Holding the ledger shows the settlement border.
        borders = new Borders(this);
        getServer().getScheduler().runTaskTimer(this, borders::tick, 20L, 10L);
        getServer().getScheduler().runTaskTimer(this, () -> Defense.tick(this), 40L, 20L);
        getLogger().info("Build " + BUILD + " enabled, " + workers.all().size() + " settlers running.");
    }

    /** Changes with every version I send, to check the right jar is installed. */
    static final String BUILD = "2026-11-10 #28";

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("VillagerWorkers build " + BUILD + ", " + workers.all().size() + " settlers running.");
            return true;
        }
        Diagnose.info(this, p);
        return true;
    }

    @Override
    public void onDisable() {
        if (workers != null) for (Worker w : workers.all()) w.stop();
        if (towns != null) towns.save();
        if (borders != null) borders.clearAll();
    }

    Settings settings() { return settings; }
    Workers workers() { return workers; }
    Towns towns() { return towns; }

    /** A settler came into the world: start it, or turn it into a villager if its settlement is gone. */
    void load(Mannequin m) {
        String townId = m.getPersistentDataContainer().get(Keys.TOWN, PersistentDataType.STRING);
        if (towns.get(townId) == null) {
            becomeVillager(m);
            return;
        }
        workers.register(m);
    }

    /** Settlers from the previous version were villagers. */
    static boolean isOldSettler(Villager v) {
        return v.getPersistentDataContainer().has(Keys.TOWN, PersistentDataType.STRING);
    }

    /** Swap an old villager settler for a Mannequin with the same name, settlement and workplace. */
    void convertOld(Villager v) {
        PersistentDataContainer pdc = v.getPersistentDataContainer();
        Town t = towns.get(pdc.get(Keys.TOWN, PersistentDataType.STRING));
        String name = pdc.getOrDefault(Keys.NAME, PersistentDataType.STRING, "Settler");
        String owner = pdc.get(Keys.OWNER, PersistentDataType.STRING);
        String workplace = pdc.get(Keys.WORKPLACE, PersistentDataType.STRING);
        for (NamespacedKey key : new NamespacedKey[]{Keys.OWNER, Keys.NAME, Keys.TOWN, Keys.WORKPLACE, Keys.BED}) pdc.remove(key);
        if (t == null) {
            v.customName(null);
            v.setCustomNameVisible(false);
            v.setAware(true);
            return;
        }
        UUID oldId = v.getUniqueId();
        List<ItemStack> carried = new java.util.ArrayList<>();
        for (ItemStack it : v.getInventory().getContents()) if (it != null && !it.getType().isAir()) carried.add(it.clone());
        Location at = v.getLocation();
        v.remove();
        t.settlers.remove(oldId);
        UUID ownerId;
        try {
            ownerId = UUID.fromString(owner);
        } catch (RuntimeException e) {
            ownerId = t.owner;
        }
        Worker w = makeSettler(at, t, ownerId, name);
        for (ItemStack it : carried) w.pockets.addItem(it);
        for (Town.Workplace wp : t.workplaces.values()) {
            if (wp.workers.remove(oldId) && workplace != null && workplace.equals(Keys.ser(wp.loc))) w.assign(wp);
        }
        w.save();
        towns.save();
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
        if (!Ledger.has(p)) {
            for (ItemStack left : p.getInventory().addItem(Ledger.create()).values()) p.getWorld().dropItem(p.getLocation(), left);
        }
        Menus.bell(this, p, t);
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
                    if (Worker.Job.forBlock(w.getType(x, y, z)) != Worker.Job.NONE) {
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
        List<ItemStack> price = settings.recruitCostFor(t.settlers.size());
        for (ItemStack cost : price) {
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
        for (ItemStack cost : price) p.getInventory().removeItem(cost.clone());
        String name = makeSettler(spawn, t, p.getUniqueId(), null).name;
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
        String given = name == null || name.isBlank() ? null : name.trim();
        Location at = v.getLocation();
        v.getWorld().playSound(at, "minecraft:entity.villager.celebrate", SoundCategory.NEUTRAL, 1f, 1f);
        v.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 15, 0.4, 0.6, 0.4);
        v.remove();
        Worker w = makeSettler(at, t, p.getUniqueId(), given);
        p.sendActionBar(Component.text(w.name + " joined " + t.name + "!", NamedTextColor.GREEN));
    }

    /** Spawn a new settler (a player-like Mannequin). */
    /** A new settler; without a name it gets one that fits its skin (and a skin that fits a given name). */
    private Worker makeSettler(Location at, Town t, UUID owner, String givenName) {
        String skin, name;
        if (givenName == null) {
            skin = Skins.skinFor(settings, "", random);
            name = Skins.nameFor(settings, skin, random);
        } else {
            name = givenName;
            skin = Skins.skinFor(settings, name, random);
        }
        Mannequin m = at.getWorld().spawn(at, Mannequin.class, nm -> {
            PersistentDataContainer pdc = nm.getPersistentDataContainer();
            pdc.set(Keys.OWNER, PersistentDataType.STRING, owner.toString());
            pdc.set(Keys.NAME, PersistentDataType.STRING, name);
            pdc.set(Keys.SKIN, PersistentDataType.STRING, skin);
            pdc.set(Keys.TOWN, PersistentDataType.STRING, t.id);
        });
        Worker w = workers.register(m);
        w.save();
        w.applyLook();
        return w;
    }

    /** Dismissed: the settler leaves and a normal villager stands in its place. */
    void dismiss(Worker w) {
        SettlerInventory.close(w);
        w.showTorch(false);
        Location at = w.body.getLocation();
        for (ItemStack it : w.pockets.getContents()) if (it != null && !it.getType().isAir()) at.getWorld().dropItemNaturally(at, it);
        w.pockets.clear();
        for (ItemStack it : w.body.getEquipment().getArmorContents()) if (it != null && !it.getType().isAir()) at.getWorld().dropItemNaturally(at, it);
        ItemStack off = w.body.getEquipment().getItemInOffHand();
        if (off.getType() == Material.SHIELD) at.getWorld().dropItemNaturally(at, off);
        forget(w);
        becomeVillager(w.body);
    }

    /** Remove a settler from its town and workplace (death or dismissal). */
    void forget(Worker w) {
        SettlerInventory.close(w);
        workers.unregister(w.body.getUniqueId());
        Town t = w.town();
        if (t != null) {
            t.settlers.remove(w.body.getUniqueId());
            for (Town.Workplace wp : t.workplaces.values()) {
                wp.workers.remove(w.body.getUniqueId());
            }
            towns.save();
        }
    }

    private void becomeVillager(Mannequin m) {
        Location at = m.getLocation();
        if (m.isInsideVehicle()) m.leaveVehicle();
        m.remove();
        at.getWorld().spawn(at, Villager.class);
        at.getWorld().spawnParticle(Particle.POOF, at.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.01);
    }

    // ---- Settlement border ----

    Borders borders() { return borders; }
}
