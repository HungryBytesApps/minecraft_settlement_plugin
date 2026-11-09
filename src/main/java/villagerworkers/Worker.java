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
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** A settler: a player-like Mannequin that belongs to a settlement, works and lives its own life. */
final class Worker {
    enum Job {
        NONE("Settler", Material.AIR),
        FARMER("Farmer", Material.IRON_HOE);

        final String title;
        final Material tool;

        Job(String title, Material tool) {
            this.title = title;
            this.tool = tool;
        }

        /** Which job a workplace block stands for. */
        static Job forBlock(Material m) {
            return m == Material.COMPOSTER ? FARMER : NONE;
        }
    }

    final VillagerWorkersPlugin plugin;
    final Mannequin body;
    final Mover mover;
    final Random random = new Random();
    /** What the settler carries (saved on the entity). */
    final Inventory pockets = Bukkit.createInventory(null, 9);

    UUID owner;
    String name;
    String townId;
    Location workplace;
    String skin;

    // Guard settings (saved on the entity)
    boolean guard;
    Guard.Stance stance = Guard.Stance.DEFENSIVE;
    Guard.Weapon weapon = Guard.Weapon.AUTO;
    Location post;
    int guardRadius;

    // Combat (runtime)
    int attackReady, chaseRepath, potionReady, lastHurt = -10000;
    org.bukkit.entity.LivingEntity lastAttacker;
    private int regenTimer, healthShown = -1, defenseCheck = 20, foodCheck = 100;

    int errorLoggedUntil;
    Activity activity;
    String workStatus = "";
    int storageFullUntil;
    private int decisionCooldown;
    private int pickupCooldown;
    private int saveCooldown = 200;
    private int workCheck = 40;
    private final Map<Block, Integer> skipped = new HashMap<>();

    Worker(VillagerWorkersPlugin plugin, Mannequin body) {
        this.plugin = plugin;
        this.body = body;
        this.mover = new Mover(this);
        load();
        decisionCooldown = 10 + random.nextInt(30); // don't all think on the same tick
    }

    static boolean isWorker(Entity e) {
        return e instanceof Mannequin m && m.getPersistentDataContainer().has(Keys.TOWN, PersistentDataType.STRING);
    }

    // ---- Stored data ----

    private void load() {
        PersistentDataContainer pdc = body.getPersistentDataContainer();
        try {
            owner = UUID.fromString(pdc.getOrDefault(Keys.OWNER, PersistentDataType.STRING, ""));
        } catch (IllegalArgumentException e) {
            owner = new UUID(0, 0);
        }
        name = pdc.getOrDefault(Keys.NAME, PersistentDataType.STRING, "Settler");
        townId = pdc.get(Keys.TOWN, PersistentDataType.STRING);
        workplace = Keys.deser(pdc.get(Keys.WORKPLACE, PersistentDataType.STRING));
        skin = pdc.get(Keys.SKIN, PersistentDataType.STRING);
        guard = "guard".equals(pdc.get(Keys.ROLE, PersistentDataType.STRING));
        try {
            stance = Guard.Stance.valueOf(pdc.getOrDefault(Keys.STANCE, PersistentDataType.STRING, "DEFENSIVE"));
        } catch (IllegalArgumentException ignored) {
        }
        try {
            weapon = Guard.Weapon.valueOf(pdc.getOrDefault(Keys.WEAPON, PersistentDataType.STRING, "AUTO"));
        } catch (IllegalArgumentException ignored) {
        }
        post = Keys.deser(pdc.get(Keys.POST, PersistentDataType.STRING));
        guardRadius = pdc.getOrDefault(Keys.GUARD_RADIUS, PersistentDataType.INTEGER, plugin.settings().guardRadius());
        List<byte[]> items = pdc.get(Keys.POCKETS, PersistentDataType.LIST.byteArrays());
        if (items != null) {
            for (byte[] b : items) {
                try {
                    pockets.addItem(ItemStack.deserializeBytes(b));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    void save() {
        PersistentDataContainer pdc = body.getPersistentDataContainer();
        pdc.set(Keys.OWNER, PersistentDataType.STRING, owner.toString());
        pdc.set(Keys.NAME, PersistentDataType.STRING, name);
        pdc.set(Keys.TOWN, PersistentDataType.STRING, townId);
        if (skin != null) pdc.set(Keys.SKIN, PersistentDataType.STRING, skin);
        String wp = Keys.ser(workplace);
        if (wp == null) pdc.remove(Keys.WORKPLACE);
        else pdc.set(Keys.WORKPLACE, PersistentDataType.STRING, wp);
        pdc.set(Keys.ROLE, PersistentDataType.STRING, guard ? "guard" : "worker");
        pdc.set(Keys.STANCE, PersistentDataType.STRING, stance.name());
        pdc.set(Keys.WEAPON, PersistentDataType.STRING, weapon.name());
        pdc.set(Keys.GUARD_RADIUS, PersistentDataType.INTEGER, guardRadius);
        String ps = Keys.ser(post);
        if (ps == null) pdc.remove(Keys.POST);
        else pdc.set(Keys.POST, PersistentDataType.STRING, ps);
        savePockets();
        Town t = town();
        if (t != null && !name.equals(t.settlers.put(body.getUniqueId(), name))) plugin.towns().save();
    }

    void savePockets() {
        List<byte[]> items = new ArrayList<>();
        for (ItemStack it : pockets.getContents()) {
            if (it != null && !it.getType().isAir()) items.add(it.serializeAsBytes());
        }
        body.getPersistentDataContainer().set(Keys.POCKETS, PersistentDataType.LIST.byteArrays(), items);
    }

    Town town() {
        return plugin.towns().get(townId);
    }

    Town.Workplace workplace() {
        Town t = town();
        return t == null || workplace == null ? null : t.workplace(workplace);
    }

    Job job() {
        if (guard) return Job.NONE;
        Town.Workplace wp = workplace();
        return wp == null ? Job.NONE : wp.job();
    }

    /** Skin, name, job under the name, tool in hand. */
    void applyLook() {
        body.setInvulnerable(false); // mobs can hurt settlers (players can't, see WorkerListener)
        body.setRemoveWhenFarAway(false);
        body.setPersistent(true);
        if (skin == null) {
            skin = Skins.pick(plugin.settings().skins(), random);
            body.getPersistentDataContainer().set(Keys.SKIN, PersistentDataType.STRING, skin);
        }
        body.setProfile(Skins.profile(skin));
        Job job = job();
        body.customName(Component.text(name, NamedTextColor.WHITE));
        body.setCustomNameVisible(plugin.settings().showNames());
        healthShown = -1;
        updateDescription();
        holdTool();
    }

    String title() {
        return guard ? "Guard" : job().title;
    }

    String healthText() {
        return "❤ " + (int) Math.ceil(body.getHealth()) + "/" + (int) Combat.maxHealth(body);
    }

    boolean hurtBelow(int percent) {
        return body.getHealth() < Combat.maxHealth(body) * percent / 100.0;
    }

    /** Job under the name, plus health when hurt (red when badly hurt). */
    private void updateDescription() {
        int hp = (int) Math.ceil(body.getHealth());
        if (hp == healthShown) return;
        healthShown = hp;
        boolean hurt = body.getHealth() < Combat.maxHealth(body) - 0.01;
        boolean bad = hurtBelow(50);
        if (!plugin.settings().showNames() && !bad) {
            body.setDescription(null);
            return;
        }
        Component d = Component.text(title(), NamedTextColor.GRAY);
        if (hurt) d = d.append(Component.text("  " + healthText(), bad ? NamedTextColor.RED : NamedTextColor.GOLD));
        body.setDescription(d);
    }

    /** Put the job's tool (or a guard's weapon) in hand (after reading, playing music...). */
    void holdTool() {
        if (guard || borrowedWeapon != null) {
            ItemStack wpn = guard && weapon == Guard.Weapon.RANGED ? Combat.bow(this) : null;
            if (wpn == null) wpn = Combat.bestMelee(this);
            if (wpn == null) wpn = Combat.bow(this);
            body.getEquipment().setItemInMainHand(wpn == null ? null : wpn.clone());
            return;
        }
        Material tool = job().tool;
        body.getEquipment().setItemInMainHand(tool.isAir() ? null : new ItemStack(tool));
    }

    void hold(Material m) {
        body.getEquipment().setItemInMainHand(m == null ? null : new ItemStack(m));
    }

    // ---- Workplace ----

    void assign(Town.Workplace wp) {
        leaveWorkplace();
        guard = false;
        wp.worker = body.getUniqueId();
        workplace = wp.loc;
        save();
        plugin.towns().save();
        applyLook();
    }

    void leaveWorkplace() {
        Town.Workplace wp = workplace();
        if (wp != null && body.getUniqueId().equals(wp.worker)) {
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

    /** Worker ↔ guard. A guard leaves its workplace. */
    void setGuard(boolean g) {
        if (g == guard) return;
        if (g) leaveWorkplace();
        guard = g;
        save();
        applyLook();
        if (activity != null) start(new Social.LookAround(this, 10));
    }

    private int storageWarnAt;

    /** Tell the owner (at most every 5 minutes) that there's no room for what this settler made. */
    void warnStorageFull(Material m) {
        int now = Bukkit.getCurrentTick();
        if (now < storageWarnAt) return;
        storageWarnAt = now + 6000;
        Player p = Bukkit.getPlayer(owner);
        if (p == null) return;
        String what = m == null ? "its goods" : FarmerJob.nice(m);
        p.sendMessage(Component.text("📦 " + name + " has no room in storage for " + what + ". Add a chest or make space.", NamedTextColor.YELLOW));
        p.sendActionBar(Component.text(name + ": no room in storage!", NamedTextColor.YELLOW));
    }

    /** Guard without anything to fight with. */
    boolean unarmed() {
        return Combat.bestMelee(this) == null && (Combat.bow(this) == null || count(Material.ARROW) == 0);
    }

    /** Weapon taken from storage when called to arms (returned after the fight). */
    ItemStack borrowedWeapon;
    Block borrowedFrom;

    /** Keep the workplace valid; claim a free one if we have none. */
    private void checkWorkplace(Town t) {
        if (guard) return;
        Town.Workplace wp = workplace();
        if (workplace != null && (wp == null || !wp.enabled || wp.job() == Job.NONE
                || (wp.worker != null && !wp.worker.equals(body.getUniqueId())))) {
            leaveWorkplace();
            wp = null;
        }
        if (wp != null && wp.worker == null) {
            wp.worker = body.getUniqueId();
            plugin.towns().save();
        }
        if (wp == null) {
            Town.Workplace free = t.freeWorkplace(body.getLocation());
            if (free != null) {
                assign(free);
                Bubbles.say(plugin, body, "I'll be the new " + free.job().title.toLowerCase(Locale.ROOT) + "!", 60);
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
        healthTick();
        if (--defenseCheck <= 0) {
            defenseCheck = 20;
            Town t = town();
            if (t != null && !guard && !(activity instanceof Defense.Reaction)) {
                Activity r = Defense.react(this, t);
                if (r != null) start(r);
            }
        }
        if (activity != null && activity.freeTime() && --workCheck <= 0) {
            workCheck = 40;
            Activity work = workToDo();
            if (work != null) start(work); // back to work
        }
        if (job() == Job.FARMER && --pickupCooldown <= 0) {
            pickupCooldown = 10;
            pickUpItems();
        }
        if (--saveCooldown <= 0) {
            saveCooldown = 200;
            savePockets();
        }
        if (activity == null && --decisionCooldown <= 0) {
            decisionCooldown = 10;
            decide();
        }
    }

    /** Slow regeneration out of combat, eating when hurt, and the health shown under the name. */
    private void healthTick() {
        int now = Bukkit.getCurrentTick();
        if (++regenTimer >= plugin.settings().regenSeconds() * 20) {
            regenTimer = 0;
            if (now - lastHurt > 100 && !body.isDead()) Combat.heal(body, 1);
        }
        if (now % 10 == 0) updateDescription();
        if (guard || --foodCheck > 0) return;
        foodCheck = 100;
        Town t = town();
        if (t == null || t.alarm || !hurtBelow(plugin.settings().eatBelowPercent())) return;
        if (activity != null && !activity.freeTime() && !(activity instanceof Social.LookAround)) return;
        if (Combat.eat(this)) return;
        if (!t.mayEat || Defense.guardNeedsFood(this, t)) return; // guards eat first
        Activity food = Defense.fetchFood(this, t);
        if (food != null) start(food);
    }

    private void decide() {
        Town t = town();
        if (t == null) {
            workStatus = "Has no settlement";
            start(new Social.LookAround(this, 100));
            return;
        }
        // Far away (just recruited or hired): walk to the settlement, or follow the owner.
        if (!t.contains(body.getLocation(), plugin.settings().socialRadius() + 12)) {
            start(new Social.GoHome(this, t));
            return;
        }
        if (guard) {
            workStatus = "";
            start(new Guard(this, t));
            return;
        }
        checkWorkplace(t);
        boolean night = isNight();
        if (night) {
            workStatus = job() == Job.NONE ? "" : "Done for the day";
            if (!plugin.settings().workAtNight() || job() == Job.NONE) {
                start(Social.evening(this, t));
                return;
            }
        }
        if (job() == Job.FARMER) {
            Activity a = FarmerJob.next(this, t, workplace());
            if (a != null) {
                start(a);
                return;
            }
        } else {
            workStatus = "No workplace";
        }
        start(Social.idle(this));
    }

    /** Work that can be done right now (day, or night if allowed), or null. */
    private Activity workToDo() {
        Town t = town();
        if (t == null || job() != Job.FARMER) return null;
        if (isNight() && !plugin.settings().workAtNight()) return null;
        return FarmerJob.next(this, t, workplace());
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
        Bubbles.clear(body);
        holdTool();
        savePockets();
    }

    String doing() {
        if (activity != null) return activity.label();
        return "Standing around";
    }

    boolean isNight() {
        long t = body.getWorld().getTime();
        return t >= 12500 && t < 23400;
    }

    /** Free for a chat, music, etc. */
    boolean available() {
        return activity == null || activity.interruptible();
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

    /** Where free time happens: around the settlement bell. */
    Location center() {
        Town t = town();
        if (t != null && t.bell.getWorld() == body.getWorld()) return t.bell;
        return body.getLocation();
    }

    /** Puts items in the settler's pockets; whatever doesn't fit is dropped. */
    void give(Collection<ItemStack> items, Location dropAt) {
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            for (ItemStack left : pockets.addItem(it).values()) body.getWorld().dropItemNaturally(dropAt, left);
        }
    }

    private void pickUpItems() {
        for (Entity e : body.getNearbyEntities(1.5, 1.0, 1.5)) {
            if (!(e instanceof Item item) || item.getPickupDelay() > 0) continue;
            ItemStack stack = item.getItemStack();
            if (!FarmerJob.wanted(stack.getType())) continue;
            Map<Integer, ItemStack> left = pockets.addItem(stack.clone());
            if (left.isEmpty()) {
                item.remove();
            } else {
                ItemStack rest = left.values().iterator().next();
                if (rest.getAmount() == stack.getAmount()) continue;
                item.setItemStack(rest);
            }
            body.getWorld().playSound(body.getLocation(), "minecraft:entity.item.pickup", SoundCategory.NEUTRAL, 0.3f, 1.6f);
        }
    }

    int count(Material m) {
        int n = 0;
        for (ItemStack it : pockets.getContents()) {
            if (it != null && it.getType() == m) n += it.getAmount();
        }
        return n;
    }

    boolean take(Material m, int amount) {
        if (count(m) < amount) return false;
        pockets.removeItem(new ItemStack(m, amount));
        return true;
    }

    boolean canManage(Player p) {
        return p.getUniqueId().equals(owner) || p.hasPermission("villagerworkers.admin");
    }
}
