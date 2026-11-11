package settlement;

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
        NONE("Settler", Material.AIR, null, false),
        FARMER("Farmer", Material.IRON_HOE, Material.COMPOSTER, true),
        COOK("Cook", Material.AIR, Material.SMOKER, false),
        SMELTER("Smelter", Material.AIR, Material.BLAST_FURNACE, false),
        FURNACE_WORKER("Furnace worker", Material.AIR, Material.FURNACE, false),
        HERDER("Herder", Material.SHEARS, Material.LOOM, true),
        LUMBERJACK("Lumberjack", Material.IRON_AXE, Material.FLETCHING_TABLE, true),
        SMITH("Smith", Material.AIR, Material.SMITHING_TABLE, false),
        BREWER("Brewer", Material.AIR, Material.BREWING_STAND, false),
        FISHER("Fisher", Material.FISHING_ROD, Material.BARREL, false);

        final String title;
        final Material tool;   // the kind of tool it holds (only when it really has one)
        final Material block;  // its workplace block
        final boolean usesArea; // works in an area around the workplace

        Job(String title, Material tool, Material block, boolean usesArea) {
            this.title = title;
            this.tool = tool;
            this.block = block;
            this.usesArea = usesArea;
        }

        /** Which job a workplace block stands for. */
        static Job forBlock(Material m) {
            if (m == Material.BARREL) return NONE; // only a barrel by water is a workplace: see of(Block)
            for (Job j : values()) if (j.block == m) return j;
            return NONE;
        }

        /** The job this block is a workplace for (a barrel only when there's water next to it). */
        static Job of(org.bukkit.block.Block b) {
            if (b.getType() == Material.BARREL) return Fishing.isFishingBarrel(b) ? FISHER : NONE;
            return forBlock(b.getType());
        }
    }

    final SettlementPlugin plugin;
    final Mannequin body;
    final Mover mover;
    final Animator anim;
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
    int attackReady, chaseRepath, potionReady, lastHurt = -10000, drawStart, shieldSince;
    boolean drawing, shieldUp;
    org.bukkit.entity.LivingEntity lastAttacker;
    private int regenTimer, healthShown = -1, defenseCheck = 20, foodCheck = 100;

    int errorLoggedUntil;
    /** Talking to a player (menu open): waits for them, then carries on. */
    org.bukkit.entity.Player pausedFor;
    int pauseUntil;
    /** Farmer: planting round (after a harvest round), and how much it harvested this round. */
    boolean planting;
    int harvestRound;
    /** Don't look for shelter from the weather again before this tick (nothing found, or work called). */
    int shelterAfter;
    /** What it harvested itself (only that may go into the composter; never things taken from storage). */
    final java.util.Map<Material, Integer> harvested = new java.util.HashMap<>();
    long ticks;               // diagnostics: how often tick() ran
    int lastClickSeen = -1;   // diagnostics: tick of the last right-click event
    Activity activity;
    String workStatus = "";
    int storageFullUntil;
    private int decisionCooldown;
    private int pickupCooldown;
    private int saveCooldown = 200;
    private int workCheck = 40;
    private final Map<Block, Integer> skipped = new HashMap<>();

    Worker(SettlementPlugin plugin, Mannequin body) {
        this.plugin = plugin;
        this.body = body;
        this.mover = new Mover(this);
        this.anim = new Animator(this);
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
            String st = pdc.getOrDefault(Keys.STANCE, PersistentDataType.STRING, "DEFENSIVE");
            stance = Guard.Stance.valueOf(st);
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
        if (townId == null) pdc.remove(Keys.TOWN);
        else pdc.set(Keys.TOWN, PersistentDataType.STRING, townId);
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
        Settings st = plugin.settings();
        // No skin yet, or a man's skin with a woman's name (or the other way around): pick one that fits the name.
        String g = Skins.gender(st, skin);
        boolean mismatch = ("male".equals(g) && st.femaleNames().contains(name)) || ("female".equals(g) && st.maleNames().contains(name))
                || (skin != null && "male".equals(g) && skin.endsWith(":slim")) || (skin != null && "female".equals(g) && skin.endsWith(":wide"));
        if (skin == null || mismatch) {
            skin = Skins.skinFor(st, name, random);
            body.getPersistentDataContainer().set(Keys.SKIN, PersistentDataType.STRING, skin);
        }
        // Skin: a job skin from the config, else the plugin's own settler skins (person + outfit), else the settler's own.
        String jobSkin = Skins.forJob(plugin.settings(), title(), body.getUniqueId());
        io.papermc.paper.datacomponent.item.ResolvableProfile own = jobSkin == null && plugin.getConfig().getBoolean("settler-skins", true)
                ? SettlerSkins.profile(this, st) : null;
        body.setProfile(own != null ? own : Skins.profile(jobSkin != null ? jobSkin : skin));
        Job job = job();
        body.setCustomNameVisible(plugin.settings().showNames());
        healthShown = -1;
        shownTag = null;
        updateDescription();
        holdTool();
    }

    void setSkin(String spec) {
        skin = spec;
        body.getPersistentDataContainer().set(Keys.SKIN, PersistentDataType.STRING, spec);
        body.setProfile(Skins.profile(spec));
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
    /** Above the head: the job (and health when hurt); under it: what it's doing. No names. */
    private void updateDescription() {
        int hp = (int) Math.ceil(body.getHealth());
        String doing = doing();
        String tag = hp + "|" + doing + "|" + title() + "|" + name;
        if (tag.equals(shownTag)) return;
        shownTag = tag;
        healthShown = hp;
        boolean hurt = body.getHealth() < Combat.maxHealth(body) - 0.01;
        boolean bad = hurtBelow(50);
        if (!plugin.settings().showNames() && !bad) {
            body.customName(null);
            body.setDescription(null);
            return;
        }
        Component top = Component.text(name + " ", NamedTextColor.WHITE).append(Component.text("[" + title() + "]", NamedTextColor.GOLD));
        if (hurt) top = top.append(Component.text("  " + healthText(), bad ? NamedTextColor.RED : NamedTextColor.GOLD));
        body.customName(top);
        body.setCustomNameVisible(true);
        body.setDescription(Component.text(doing, NamedTextColor.GRAY));
    }

    private String shownTag;

    /** Put the job's tool (or a guard's weapon) in hand (after reading, playing music...). */
    void holdTool() {
        if (guard || borrowedWeapon != null) {
            ItemStack wpn = guard && weapon == Guard.Weapon.RANGED ? Combat.bow(this) : null;
            if (wpn == null) wpn = Combat.bestMelee(this);
            if (wpn == null) wpn = Combat.bow(this);
            body.getEquipment().setItemInMainHand(wpn == null ? null : wpn.clone());
            return;
        }
        // Only a tool it really carries (a farmer with a hoe from storage), nothing made up.
        Material kind = job().tool;
        ItemStack held = null;
        if (!kind.isAir()) {
            String suffix = kind.name().contains("_") ? kind.name().substring(kind.name().lastIndexOf('_')) : kind.name();
            for (ItemStack it : pockets.getContents()) {
                if (it != null && it.getType().name().endsWith(suffix)) {
                    held = it.asOne();
                    break;
                }
            }
        }
        body.getEquipment().setItemInMainHand(held);
    }

    // ---- Noticing players ----

    private org.bukkit.entity.Player watcher;
    private int watchUntil, watchCheck, lookingSince = -1;

    /** Is a player looking at this settler (and it's looking back)? */
    boolean watched() {
        return watcher != null && Bukkit.getCurrentTick() < watchUntil;
    }

    /** A player close by looks at the settler: after a moment it looks back at them. */
    private void noticePlayers() {
        if (--watchCheck > 0) return;
        watchCheck = 5;
        boolean busy = mover.moving() || activity instanceof Defense.Reaction
                || (activity instanceof Guard g && !g.idle()) || body.isInsideVehicle() && !(activity != null && activity.freeTime());
        if (busy) {
            watcher = null;
            lookingSince = -1;
            return;
        }
        org.bukkit.entity.Player found = null;
        for (org.bukkit.entity.Player p : body.getWorld().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (p.getLocation().distanceSquared(body.getLocation()) > 12 * 12) continue;
            if (body.equals(p.getTargetEntity(12))) {
                found = p;
                break;
            }
        }
        int now = Bukkit.getCurrentTick();
        if (found != null) {
            if (lookingSince < 0 || found != watcher) lookingSince = now;
            watcher = found;
            if (now - lookingSince >= 30) watchUntil = now + 40; // looked for 1.5 s: look back
        } else if (!watched()) {
            lookingSince = -1;
        }
        if (watched() && watcher != null && watcher.isValid() && watcher.getWorld() == body.getWorld()) {
            anim.look(watcher.getEyeLocation());
        } else if (found == null) {
            watcher = null;
        }
    }

    void hold(Material m) {
        body.getEquipment().setItemInMainHand(m == null ? null : new ItemStack(m));
    }

    // ---- Workplace ----

    void assign(Town.Workplace wp) {
        leaveWorkplace();
        guard = false;
        wp.workers.add(body.getUniqueId());
        workplace = wp.loc;
        save();
        plugin.towns().save();
        applyLook();
    }

    void leaveWorkplace() {
        Town.Workplace wp = workplace();
        if (wp != null && wp.workers.remove(body.getUniqueId())) plugin.towns().save();
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
        String what = m == null ? "its goods" : FarmerJob.nice(m);
        Town t = town();
        for (UUID id : t != null ? t.people() : List.of(owner)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !Notify.on(p, Notify.Kind.STORAGE)) continue;
            p.sendMessage(Component.text("📦 " + name + " has no room in storage for " + what + ". Add a chest or make space.", NamedTextColor.YELLOW));
            p.sendActionBar(Component.text(name + ": no room in storage!", NamedTextColor.YELLOW));
        }
    }

    private int toolWarnAt;

    /** Tell the owner (at most every 5 minutes) that this settler needs a tool that isn't in storage. */
    void warnMissingTool(String what) {
        int now = Bukkit.getCurrentTick();
        if (now < toolWarnAt) return;
        toolWarnAt = now + 6000;
        Notify.send(town(), owner, Notify.Kind.TOOLS, "🔧 " + name + " [" + title() + "] needs " + what + ". Put one in a chest settlers may take from.", NamedTextColor.YELLOW);
    }

    /** A player talks to it: stop, turn to them, wait (at most this long, or until the menu closes), then carry on. */
    void pauseFor(org.bukkit.entity.Player p, int ticks) {
        if (activity instanceof Defense.Reaction || (activity instanceof Guard g && !g.idle())) return; // not in a fight
        pausedFor = p;
        pauseUntil = Bukkit.getCurrentTick() + ticks;
        mover.stop();
        if (activity != null) { // pick the work up again afterwards (fresh, so nothing gets stuck half-way)
            activity.stop();
            activity = null;
        }
        anim.look(p.getEyeLocation());
    }

    /** The player closed the menu: everyone waiting for them carries on. */
    static void release(SettlementPlugin plugin, org.bukkit.entity.Player p) {
        for (Worker w : plugin.workers().all()) if (w.pausedFor != null && w.pausedFor.equals(p)) w.pausedFor = null;
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
        if (workplace != null && (wp == null || !wp.enabled || wp.job() == Job.NONE || !wp.workers.contains(body.getUniqueId()))) {
            leaveWorkplace(); // gone, switched off, or you sent it away
            wp = null;
        }
        if (wp == null && t.autoJobs) {
            Town.Workplace free = t.freeWorkplace(body.getLocation());
            if (free != null) {
                assign(free);
                Bubbles.say(plugin, body, "I'll be the new " + free.job().title.toLowerCase(Locale.ROOT) + "!", 60);
            }
        }
    }

    // ---- Thinking ----

    void tick() {
        ticks++;
        // Someone is talking to it (its menu is open): stand still and look at them.
        if (pausedFor != null) {
            int nowT = Bukkit.getCurrentTick();
            if (pausedFor.isOnline() && pausedFor.getWorld() == body.getWorld() && nowT < pauseUntil
                    && pausedFor.getLocation().distanceSquared(body.getLocation()) < 10 * 10) {
                mover.stop();
                if (ticks % 10 == 0) anim.look(pausedFor.getEyeLocation());
                anim.tick();
                return;
            }
            pausedFor = null;
        }
        // Someone is looking in its pockets: stand still and look at them.
        org.bukkit.entity.Player looking = SettlerInventory.viewer(this);
        if (looking != null) {
            mover.stop();
            if (ticks % 10 == 0) anim.look(looking.getEyeLocation());
            anim.tick();
            return;
        }
        if (activity != null) {
            boolean done;
            try {
                done = activity.tick();
            } catch (RuntimeException ex) {
                int now = Bukkit.getCurrentTick();
                if (now >= errorLoggedUntil) { // full error, at most once a minute per settler
                    errorLoggedUntil = now + 1200;
                    plugin.getLogger().log(java.util.logging.Level.WARNING, name + " (" + activity.label() + ") ran into an error", ex);
                }
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
        anim.tick();
        healthTick();
        noticePlayers();
        if (--defenseCheck <= 0) {
            defenseCheck = 20;
            Town t = town();
            if (t != null && !guard && !(activity instanceof Defense.Reaction)) {
                Activity r = Defense.react(this, t);
                if (r == null) r = Shelter.check(this, t); // rain / thunder
                if (r != null) start(r);
            }
        }
        if (activity != null && activity.freeTime() && --workCheck <= 0) {
            workCheck = 40;
            Activity work = workToDo();
            if (work != null) start(work); // back to work
        }
        if ((job() == Job.FARMER || job() == Job.HERDER || job() == Job.LUMBERJACK) && --pickupCooldown <= 0) {
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
        if (!t.mayEat()) return; // Settlers consumption: Off (they only heal slowly by themselves)
        if (Combat.eat(this)) return;
        if (Defense.guardNeedsFood(this, t)) return; // guards eat first
        Activity food = Defense.fetchFood(this, t);
        if (food != null) start(food);
    }

    /** Fisher: emptied the barrel, now taking the catch to the chests. */
    boolean carryToStorage;

    /** Just recruited or hired: wait at the bell for instructions until this tick (or until it gets a job). */
    int newcomerUntil;

    private void decide() {
        Town t = town();
        if (t == null) {
            workStatus = "Has no settlement";
            start(new Social.LookAround(this, 100));
            return;
        }
        int townR = plugin.settings().townRadius();
        boolean outside = !t.contains(body.getLocation(), townR + 2);
        if (guard) {
            workStatus = "";
            if (outside) start(new Social.GoHome(this, t));
            else start(new Guard(this, t));
            return;
        }
        checkWorkplace(t);
        if (job() == Job.NONE && Bukkit.getCurrentTick() < newcomerUntil) {
            workStatus = "";
            start(new Social.WaitAtBell(this, t));
            return;
        }
        boolean night = isNight();
        if (night && (!plugin.settings().workAtNight() || job() == Job.NONE)) {
            workStatus = job() == Job.NONE ? "" : "Done for the day";
            if (outside) start(new Social.GoHome(this, t));
            else start(Social.evening(this, t));
            return;
        }
        // Work first (a lumberjack may work outside the settlement); only then walk back or take free time.
        if (job() != Job.NONE) {
            Activity a = jobNext(t);
            if (a != null) {
                start(a);
                return;
            }
        } else {
            workStatus = "No workplace";
        }
        if (outside) {
            start(new Social.GoHome(this, t)); // just recruited or hired, or done outside: back to the settlement
            return;
        }
        start(Social.idle(this));
    }

    /** The next thing to do for its job (or null: nothing to do now). */
    private Activity jobNext(Town t) {
        Town.Workplace wp = workplace();
        if (wp == null) return null;
        return switch (job()) {
            case FARMER -> FarmerJob.next(this, t, wp);
            case COOK, SMELTER, FURNACE_WORKER -> FurnaceJob.next(this, t, wp);
            case HERDER -> HerderJob.next(this, t, wp);
            case LUMBERJACK -> LumberjackJob.next(this, t, wp);
            case SMITH -> SmithJob.next(this, t, wp);
            case BREWER -> Brewer.next(this, t, wp);
            case FISHER -> Fishing.next(this, t, wp);
            case NONE -> null;
        };
    }

    /** Work that can be done right now (day, or night if allowed), or null. */
    Activity workToDo() {
        Town t = town();
        if (t == null || job() == Job.NONE) return null;
        if (isNight() && !plugin.settings().workAtNight()) return null;
        return jobNext(t);
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
            Material type = stack.getType();
            boolean want = switch (job()) {
                case FARMER -> FarmerJob.wanted(type);
                case HERDER -> HerderJob.wanted(type);
                case LUMBERJACK -> LumberjackJob.wanted(type);
                default -> false;
            };
            if (!want) continue;

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
        Town t = town();
        if (t != null) return t.canManage(p);
        return p.getUniqueId().equals(owner) || p.hasPermission("settlement.admin");
    }
}
