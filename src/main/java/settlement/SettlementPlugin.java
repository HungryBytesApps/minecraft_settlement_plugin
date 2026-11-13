package settlement;

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

public final class SettlementPlugin extends JavaPlugin {
    private Settings settings;
    private Workers workers;
    private Towns towns;
    private Borders borders;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.from(getConfig());
        Town.BASE = settings.townRadius();
        Town.MAIN_MIN = getConfig().getInt("settlement-radius-min", 16);
        Town.MAIN_MAX = Math.max(Town.MAIN_MIN, getConfig().getInt("settlement-radius-max", 64));
        Town.EXP_MIN = getConfig().getInt("expansion-radius-min", 4);
        Town.EXP_MAX = Math.max(Town.EXP_MIN, getConfig().getInt("expansion-radius-max", 24));
        Keys.init(this);
        LEAVING = new org.bukkit.NamespacedKey(Keys.NS, "leaving");
        Chat.load(this);
        towns = new Towns(this);
        workers = new Workers(this);
        getServer().getPluginManager().registerEvents(new WorkerListener(this), this);
        getServer().getPluginManager().registerEvents(new SettlerInventory.Events(this), this);
        getServer().getPluginManager().registerEvents(new PrivateChests(this), this);
        getServer().getPluginManager().registerEvents(new Campfires(), this);
        getServer().getPluginManager().registerEvents(new Fishing(this), this);
        getServer().getPluginManager().registerEvents(new Graves(this), this); // where settlers died: their things
        FisherSkins.load(this); // the fisher skins from MineSkin (once, then from the cache)
        if (Fishing.realLook()) FakeFisher.listen(this); // clicks on the fake fisher reach the settler

        for (World w : getServer().getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) {
                if (Worker.isWorker(m)) load(m);
            }
        }
        getServer().getScheduler().runTaskTimer(this, workers::tick, 1L, 1L);
        // Holding the ledger shows the settlement border.
        borders = new Borders(this);
        getServer().getScheduler().runTaskTimer(this, () -> guarded("Border", borders::tick), 20L, 10L);
        getServer().getScheduler().runTaskTimer(this, () -> guarded("Defense", () -> Defense.tick(this)), 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> guarded("Ledger", () -> Ledger.refresh(this)), 40L, 40L); // new problems on the ledger
        for (Town t : towns.all()) keepLoaded(t, true);
        getLogger().info("Build " + BUILD + " enabled, " + workers.all().size() + " settlers running.");
    }

    /**
     * keep-settlements-loaded: hold the settlement's chunks loaded (a chunk ticket), so it keeps working
     * while nobody is near. Off: let go of them.
     */
    void keepLoaded(Town t, boolean on) {
        org.bukkit.World w = t.world();
        if (w == null) return;
        int[] box = t.box(0);
        int n = 0;
        for (int x = box[0] >> 4; x <= box[1] >> 4; x++) {
            for (int z = box[2] >> 4; z <= box[3] >> 4; z++) {
                if (on && !settings.keepLoaded()) continue;
                if (on && !t.touchesChunk(x, z)) continue;
                if (on) w.addPluginChunkTicket(x, z, this);
                else w.removePluginChunkTicket(x, z, this);
                n++;
            }
        }
        if (on && n > 0) getLogger().info("Keeping " + n + " chunks of " + t.name + " loaded.");
    }

    /** After a settlement changed shape: drop all chunk tickets and set them again for every settlement. */
    void reloadChunks() {
        for (org.bukkit.World w : getServer().getWorlds()) w.removePluginChunkTickets(this);
        for (Town t : towns.all()) keepLoaded(t, true);
    }

    /** Changes with every version I send, to check the right jar is installed. */
    static final String BUILD = "2026-11-13 #102";

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Settlement build " + BUILD + ", " + workers.all().size() + " settlers running.");
            return true;
        }
        Diagnose.info(this, p);
        return true;
    }

    private final java.util.Map<String, Integer> errorLoggedAt = new java.util.HashMap<>();

    /** Run a timer job; an error is logged with its stack trace at most once a minute. */
    private void guarded(String what, Runnable job) {
        try {
            job.run();
        } catch (RuntimeException ex) {
            int now = getServer().getCurrentTick();
            Integer last = errorLoggedAt.get(what);
            if (last != null && now - last < 1200) return;
            errorLoggedAt.put(what, now);
            getLogger().log(java.util.logging.Level.WARNING, what + " ran into an error", ex);
        }
    }

    @Override
    public void onDisable() {
        Graves.hideAll();
        if (Fishing.realLook()) {
            FakeFisher.removeAll(); // fishers back for everyone
            FakeFisher.unlisten();
        }
        if (workers != null) for (Worker w : workers.all()) w.stop();
        if (towns != null) towns.save();
        if (borders != null) borders.clearAll();
        Campfires.putOutAll();
    }

    Settings settings() { return settings; }
    Workers workers() { return workers; }
    Towns towns() { return towns; }

    /** A settler came into the world: start it, or turn it into a villager if its settlement is gone. */
    void load(Mannequin m) {
        if (m.getPersistentDataContainer().has(LEAVING)) { // was walking away when the server stopped
            m.remove();
            return;
        }
        String townId = m.getPersistentDataContainer().get(Keys.TOWN, PersistentDataType.STRING);
        workers.register(m);
        if (towns.get(townId) == null) {
            // Its settlement was disbanded while it was far away: it drops its things and leaves too.
            Worker w = workers.get(m.getUniqueId());
            if (w != null) getServer().getScheduler().runTask(this, () -> {
                if (w.body.isValid()) dismiss(w);
            });
        }
    }

    /** The nearest settlement this player owns or helps run. */
    Town ownTown(Player p) {
        List<Town> mine = new java.util.ArrayList<>();
        for (Town t : towns.all()) if (t.owner.equals(p.getUniqueId()) || t.members.contains(p.getUniqueId())) mine.add(t);
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
        if (!p.hasPermission("settlement.admin") && towns.ownedBy(p.getUniqueId()).size() >= settings.maxTowns()) return false;
        if (towns.at(bell.getLocation()) != null) return false; // inside a settlement: that's an expansion
        if (Area.newSettlementRadius(this, bell) < 0) {
            if (tell) p.sendActionBar(Component.text("Too close to another settlement.", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    void foundTown(Player p, Block bell, String name) {
        if (bell.getType() != Material.BELL || towns.atBell(bell) != null || !canFoundTown(p, bell, true)) return;
        if (name == null || name.isBlank()) name = p.getName() + "'s Settlement";
        int r = Area.newSettlementRadius(this, bell);
        Town t = towns.create(p.getUniqueId(), name.trim(), bell.getLocation());
        t.radius = r; // smaller when a neighbour is close
        towns.save();
        keepLoaded(t, true);
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
        int by = t.bell.getBlockY();
        int[] box = t.box(0);
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                if (!t.inside(x + 0.5, z + 0.5, 0)) continue;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(w.getMinHeight(), by - 12); y <= Math.min(w.getMaxHeight() - 1, by + 12); y++) {
                    if (Worker.Job.of(w.getBlockAt(x, y, z)) != Worker.Job.NONE) {
                        Location l = new Location(w, x, y, z);
                        t.addWorkplace(l, settings.defaultRadius());
                    }
                }
            }
        }
        towns.save();
    }

    void disband(Town t) {
        for (Worker w : workers.all()) if (t.id.equals(w.townId)) dismiss(w);
        towns.remove(t);
        reloadChunks(); // let go of its chunks (but not of ones a neighbouring settlement shares)
    }

    // ---- Settlers ----

    private boolean hasRoom(Player p, Town t) {
        if (t.settlers.size() < settings.maxSettlers() || p.hasPermission("settlement.admin")) return true;
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
        String name = makeSettler(spawn, t, t.owner, null).name;
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
        Worker w = makeSettler(at, t, t.owner, given);
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
        // First waits at the bell for instructions (config: newcomer-wait-seconds).
        w.newcomerUntil = Bukkit.getCurrentTick() + Math.max(0, getConfig().getInt("newcomer-wait-seconds", 180)) * 20;
        w.save();
        w.applyLook();
        return w;
    }

    /** Dismissed: the settler leaves and a normal villager stands in its place. */
    /** Dismissed: leaves its things behind, says goodbye, walks out of the settlement and is gone. */
    void dismiss(Worker w) {
        SettlerInventory.close(w);
        Location at = w.body.getLocation();
        for (ItemStack it : w.pockets.getContents()) if (it != null && !it.getType().isAir()) at.getWorld().dropItemNaturally(at, it);
        w.pockets.clear();
        w.savePockets();
        org.bukkit.inventory.EntityEquipment eq = w.body.getEquipment();
        for (ItemStack it : eq.getArmorContents()) if (it != null && !it.getType().isAir()) at.getWorld().dropItemNaturally(at, it);
        ItemStack off = eq.getItemInOffHand();
        if (off.getType() == Material.SHIELD) at.getWorld().dropItemNaturally(at, off);
        eq.setArmorContents(new ItemStack[4]);
        eq.setItemInOffHand(null);
        eq.setItemInMainHand(null);
        Town t = w.town();
        Location exit = exitPoint(t, at);
        // First out of its job (that still saves it under its settlement), then off the settlement's books:
        // no longer part of the settlement, but still walking (and gone once out).
        w.leaveWorkplace();
        w.guard = false;
        w.townId = null;
        if (t != null) {
            t.settlers.remove(w.body.getUniqueId());
            for (Town.Workplace wp : t.workplaces.values()) wp.workers.remove(w.body.getUniqueId());
            towns.save();
        }
        w.leaving = true;
        w.body.getPersistentDataContainer().set(LEAVING, PersistentDataType.BYTE, (byte) 1);
        w.save();
        w.applyLook();
        w.start(new Leave(w, exit));
    }

    /** A point outside the border, straight away from the bell. */
    private Location exitPoint(Town t, Location from) {
        if (t == null || t.bell.getWorld() != from.getWorld()) return from.clone().add(20, 0, 0);
        double dx = from.getX() - t.bell.getX(), dz = from.getZ() - t.bell.getZ();
        double len = Math.max(Math.abs(dx), Math.abs(dz)); // the settlement is a square
        if (len < 1) {
            double a = random.nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dz = Math.sin(a);
            len = 1;
        }
        // Walk outwards from where it stands until 6 blocks past the border (the settlement can have any shape).
        double ux = dx / Math.hypot(dx, dz), uz = dz / Math.hypot(dx, dz);
        double px = from.getX(), pz = from.getZ();
        for (int i = 0; i < 400 && t.inside(px, pz, 6); i++) {
            px += ux;
            pz += uz;
        }
        int x = (int) Math.floor(px), z = (int) Math.floor(pz);
        int y = from.getWorld().getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        return new Location(from.getWorld(), x + 0.5, y, z + 0.5);
    }

    static org.bukkit.NamespacedKey LEAVING;

    /** Walking out of the settlement; gone once outside (or after a while). */
    private final class Leave implements Activity {
        private final Worker w;
        private final Location exit;
        private int time;

        Leave(Worker w, Location exit) {
            this.w = w;
            this.exit = exit;
            Bubbles.say(SettlementPlugin.this, w.body, "Goodbye!", 60);
            Voice.speak(w, "Goodbye!");
        }

        @Override
        public boolean tick() {
            time++;
            Location me = w.body.getLocation();
            double dx = me.getX() - exit.getX(), dz = me.getZ() - exit.getZ();
            if ((dx * dx + dz * dz) < 4 * 4 || time > 1800) {
                me.getWorld().spawnParticle(org.bukkit.Particle.POOF, me.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
                workers.unregister(w.body.getUniqueId());
                w.body.remove();
                return true;
            }
            if (!w.mover.moving() && time % 40 == 1) {
                // Far away: walk towards it bit by bit (paths are limited in length).
                Location step = exit;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 40) {
                    double f = 40 / dist;
                    int x = (int) Math.floor(me.getX() - dx * f), z = (int) Math.floor(me.getZ() - dz * f);
                    step = new Location(me.getWorld(), x + 0.5, me.getWorld().getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z + 0.5);
                }
                if (!w.mover.go(step, 3, settings.walkSpeed()) && time > 200) {
                    workers.unregister(w.body.getUniqueId());
                    w.body.remove(); // can't find the way out: just go
                    return true;
                }
            }
            return false;
        }

        @Override
        public void stop() {
            w.mover.stop();
        }

        @Override
        public String label() {
            return "Leaving the settlement";
        }
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

    // ---- Settlement border ----

    Borders borders() { return borders; }
}
