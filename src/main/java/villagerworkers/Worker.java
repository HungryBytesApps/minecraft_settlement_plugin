package villagerworkers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** A settler: a villager that belongs to a town, works at a workplace and lives its own life. */
final class Worker {
    enum Job {
        NONE("Settler"),
        FARMER("Farmer");

        final String title;

        Job(String title) {
            this.title = title;
        }

        /** Which job a workplace block stands for. */
        static Job forBlock(Material m) {
            return m == Material.COMPOSTER ? FARMER : NONE;
        }
    }

    final VillagerWorkersPlugin plugin;
    final Villager villager;
    final Mover mover;
    final Random random = new Random();

    UUID owner;
    String name;
    String townId;
    Location workplace;
    Location bed;

    Activity activity;
    String workStatus = "";
    int storageFullUntil;
    private int decisionCooldown;
    private int pickupCooldown;
    private final Map<Block, Integer> skipped = new HashMap<>();

    Worker(VillagerWorkersPlugin plugin, Villager villager) {
        this.plugin = plugin;
        this.villager = villager;
        this.mover = new Mover(this);
        load();
        decisionCooldown = 10 + random.nextInt(30); // don't all think on the same tick
    }

    static boolean isWorker(Entity e) {
        return e instanceof Villager v && v.getPersistentDataContainer().has(Keys.TOWN, PersistentDataType.STRING);
    }

    // ---- Stored data ----

    private void load() {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        try {
            owner = UUID.fromString(pdc.getOrDefault(Keys.OWNER, PersistentDataType.STRING, ""));
        } catch (IllegalArgumentException e) {
            owner = new UUID(0, 0);
        }
        name = pdc.getOrDefault(Keys.NAME, PersistentDataType.STRING, "Settler");
        townId = pdc.get(Keys.TOWN, PersistentDataType.STRING);
        workplace = Keys.deser(pdc.get(Keys.WORKPLACE, PersistentDataType.STRING));
        bed = Keys.deser(pdc.get(Keys.BED, PersistentDataType.STRING));
    }

    void save() {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        pdc.set(Keys.OWNER, PersistentDataType.STRING, owner.toString());
        pdc.set(Keys.NAME, PersistentDataType.STRING, name);
        pdc.set(Keys.TOWN, PersistentDataType.STRING, townId);
        setLoc(pdc, Keys.WORKPLACE, workplace);
        setLoc(pdc, Keys.BED, bed);
        Town t = town();
        if (t != null && !name.equals(t.settlers.put(villager.getUniqueId(), name))) plugin.towns().save();
    }

    private static void setLoc(PersistentDataContainer pdc, org.bukkit.NamespacedKey key, Location l) {
        String s = Keys.ser(l);
        if (s == null) pdc.remove(key);
        else pdc.set(key, PersistentDataType.STRING, s);
    }

    Town town() {
        return plugin.towns().get(townId);
    }

    Town.Workplace workplace() {
        Town t = town();
        return t == null || workplace == null ? null : t.workplace(workplace);
    }

    Job job() {
        Town.Workplace wp = workplace();
        return wp == null ? Job.NONE : wp.job();
    }

    /** Vanilla AI off (the plugin drives the settler), name tag, outfit. */
    void applyLook() {
        villager.setAware(false);
        villager.setRemoveWhenFarAway(false);
        villager.setPersistent(true);
        Job job = job();
        if (plugin.settings().showNames()) {
            villager.customName(Component.text(name, NamedTextColor.WHITE)
                    .append(Component.text(" (" + job.title + ")", NamedTextColor.GRAY)));
            villager.setCustomNameVisible(true);
        } else {
            villager.customName(null);
            villager.setCustomNameVisible(false);
        }
        if (job == Job.FARMER && villager.getProfession() != Villager.Profession.FARMER) {
            villager.setProfession(Villager.Profession.FARMER);
        }
    }

    // ---- Workplace ----

    void assign(Town.Workplace wp) {
        leaveWorkplace();
        wp.worker = villager.getUniqueId();
        workplace = wp.loc;
        save();
        plugin.towns().save();
        applyLook();
    }

    void leaveWorkplace() {
        Town.Workplace wp = workplace();
        if (wp != null && villager.getUniqueId().equals(wp.worker)) {
            wp.worker = null;
            plugin.towns().save();
        }
        if (workplace != null) {
            workplace = null;
            save();
            applyLook();
            if (activity != null) start(new Social.LookAround(this, 10));
        }
    }

    /** Keep the workplace valid; claim a free one if we have none. */
    private void checkWorkplace(Town t) {
        Town.Workplace wp = workplace();
        if (workplace != null && (wp == null || !wp.enabled || wp.job() == Job.NONE
                || (wp.worker != null && !wp.worker.equals(villager.getUniqueId())))) {
            leaveWorkplace();
            wp = null;
        }
        if (wp != null && wp.worker == null) {
            wp.worker = villager.getUniqueId();
            plugin.towns().save();
        }
        if (wp == null) {
            Town.Workplace free = t.freeWorkplace(villager.getLocation());
            if (free != null) {
                assign(free);
                Bubbles.say(plugin, villager, "I'll be the new " + free.job().title.toLowerCase(Locale.ROOT) + "!", 60);
            }
        }
    }

    // ---- Thinking ----

    void tick() {
        if (activity != null) {
            boolean done;
            try {
                done = activity.tick();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning(name + ": " + ex);
                done = true;
            }
            if (done) {
                Activity a = activity;
                activity = null;
                a.stop();
                decisionCooldown = 5;
            }
        }
        mover.tick();
        if (job() == Job.FARMER && --pickupCooldown <= 0) {
            pickupCooldown = 10;
            pickUpItems();
        }
        if (activity == null && --decisionCooldown <= 0) {
            decisionCooldown = 10;
            decide();
        }
    }

    private void decide() {
        Town t = town();
        if (t == null) {
            workStatus = "Has no settlement";
            start(new Social.LookAround(this, 100));
            return;
        }
        // Far from town (just recruited or hired): walk there, or follow the owner.
        if (!t.contains(villager.getLocation(), plugin.settings().socialRadius() + 12)) {
            start(new Social.GoHome(this, t));
            return;
        }
        Settings s = plugin.settings();
        boolean night = isNight();
        if (night && s.sleepAtNight()) {
            Activity sleep = Social.sleep(this);
            if (sleep != null) {
                start(sleep);
                return;
            }
        }
        checkWorkplace(t);
        Job job = job();
        if (job == Job.FARMER) {
            if (night && !s.workAtNight()) {
                workStatus = "Done for the day";
            } else {
                Activity a = FarmerJob.next(this, t, workplace());
                if (a != null) {
                    start(a);
                    return;
                }
            }
        } else {
            workStatus = "No workplace (place a composter in the settlement)";
        }
        start(Social.idle(this));
    }

    void start(Activity a) {
        if (a == null) return;
        if (activity != null) {
            Activity old = activity;
            activity = null;
            old.stop();
        }
        activity = a;
    }

    /** Stop everything (unload, dismiss, plugin disable). */
    void stop() {
        if (activity != null) {
            Activity a = activity;
            activity = null;
            a.stop();
        }
        mover.stop();
        mover.closeDoors(true);
        Bubbles.clear(villager);
        if (villager.isSleeping()) villager.wakeup();
    }

    String doing() {
        if (activity != null) return activity.label();
        return "Standing around";
    }

    boolean isNight() {
        long t = villager.getWorld().getTime();
        return t >= 12500 && t < 23400;
    }

    // ---- Helpers ----

    void skip(Block b, int ticks) {
        skipped.put(b, Bukkit.getCurrentTick() + ticks);
    }

    boolean isSkipped(Block b) {
        Integer until = skipped.get(b);
        if (until == null) return false;
        if (Bukkit.getCurrentTick() >= until) {
            skipped.remove(b);
            return false;
        }
        return true;
    }

    /** Where free time happens: around the town bell. */
    Location center() {
        Town t = town();
        if (t != null && t.bell.getWorld() == villager.getWorld()) return t.bell;
        return villager.getLocation();
    }

    /** Puts items in the settler's pockets; whatever doesn't fit is dropped. */
    void give(Collection<ItemStack> items, Location dropAt) {
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            for (ItemStack left : villager.getInventory().addItem(it).values()) {
                villager.getWorld().dropItemNaturally(dropAt, left);
            }
        }
    }

    private void pickUpItems() {
        for (Entity e : villager.getNearbyEntities(1.5, 1.0, 1.5)) {
            if (!(e instanceof Item item) || item.getPickupDelay() > 0) continue;
            ItemStack stack = item.getItemStack();
            if (!FarmerJob.wanted(stack.getType())) continue;
            Map<Integer, ItemStack> left = villager.getInventory().addItem(stack.clone());
            if (left.isEmpty()) {
                item.remove();
            } else {
                ItemStack rest = left.values().iterator().next();
                if (rest.getAmount() == stack.getAmount()) continue;
                item.setItemStack(rest);
            }
            villager.getWorld().playSound(villager.getLocation(), "minecraft:entity.item.pickup", SoundCategory.NEUTRAL, 0.3f, 1.6f);
        }
    }

    int count(Material m) {
        int n = 0;
        for (ItemStack it : villager.getInventory().getContents()) {
            if (it != null && it.getType() == m) n += it.getAmount();
        }
        return n;
    }

    boolean take(Material m, int amount) {
        if (count(m) < amount) return false;
        villager.getInventory().removeItem(new ItemStack(m, amount));
        return true;
    }

    boolean canManage(Player p) {
        return p.getUniqueId().equals(owner) || p.hasPermission("villagerworkers.admin");
    }
}
