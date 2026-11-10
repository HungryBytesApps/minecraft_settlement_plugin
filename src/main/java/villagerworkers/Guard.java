package villagerworkers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Guard duty: stands at its post (defensive), walks rounds (patrol) or follows the owner (escort),
 * and fights hostile mobs. Restocks gear/arrows from storage after a fight (archers: when out),
 * eats when hurt, drinks healing potions in a fight if the settlement allows it.
 */
final class Guard implements Activity {
    enum Stance {
        DEFENSIVE("Defensive"), PATROL("Patrol"), ESCORT("Escort");

        final String label;

        Stance(String label) {
            this.label = label;
        }

        Stance next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    enum Weapon {
        AUTO("Auto"), MELEE("Melee"), RANGED("Archer");

        final String label;

        Weapon(String label) {
            this.label = label;
        }

        Weapon next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final Worker w;
    private final Town t;
    private LivingEntity target;
    private Activity sub;           // a trip to a chest (gear, arrows, food)
    private boolean arrowRun;       // archer fetching arrows mid-fight
    private int lootAt = 100;
    private int time, gearCheckAt = 20, warnAt, calmUntil;
    private Location patrolTo;
    private LivingEntity chasing;
    private double closest;
    private int progressAt;
    private final java.util.Map<java.util.UUID, Integer> ignored = new java.util.HashMap<>(); // out of reach for now
    private boolean patrolPause;
    private int patrolWait, patrolGiveUp, patrolDir = 1;
    private String label = "On guard";

    Guard(Worker w, Town t) {
        this.w = w;
        this.t = t;
    }

    @Override
    public boolean tick() {
        time++;
        if (w.town() != t) return true;
        int now = Bukkit.getCurrentTick();
        if (target != null && (!target.isValid() || target.isDead() || !inRange(target))) {
            target = null;
            gearCheckAt = time + 40; // restock after the fight
            lootAt = time + 40;      // and pick up what they dropped
            Combat.raiseShield(w, false);
        }
        if (target != null && leftBehind()) { // escorting: the owner ran far away from the fight, go with them
            target = null;
            chasing = null;
            w.mover.stop();
            Combat.raiseShield(w, false);
        }
        if (target == null && time % 10 == 0 && time >= calmUntil) target = findTarget();

        if (target != null) {
            if (sub != null) {
                if (arrowRun) return runSub(); // archers fetch arrows as soon as they run out
                sub.stop();                    // anything else waits until after the fight
                sub = null;
            }
            fight(now);
            return false;
        }
        if (sub != null) return runSub();

        // Not fighting: heal up, restock, then do the rounds.
        if (time % 40 == 0 && hurt()) {
            if (t.mayEat()) {
                if (Combat.eat(w)) return false;
                Activity food = Defense.fetchFood(w, t);
                if (food != null) {
                    sub = food;
                    return false;
                }
            }
        }
        // After a fight: pick up what the monsters dropped, and bring a good load of it to storage.
        if (time >= lootAt && !t.alarm) {
            lootAt = time + 100;
            Activity pick = Work.collectDrops(w, t, Guard::loot, 16);
            if (pick != null) {
                sub = pick;
                lootAt = time + 10;
                return false;
            }
            if (Work.needsDeposit(w, Work::gear, false) || (Work.needsDeposit(w, Work::gear, true) && w.random.nextInt(4) == 0)) {
                Activity d = Work.deposit(w, t, Work::gear);
                if (d != null) {
                    sub = d;
                    return false;
                }
            }
        }
        if (time >= gearCheckAt) {
            gearCheckAt = time + 600;
            Block chest = gearChest();
            if (chest != null) {
                sub = new GoAndDo(w, chest, 2.0, "Getting gear", b -> true,
                        b -> {
                            Combat.gearUp(w, b, wantsBow(), arrowsWanted(), t.guardPotions() ? 2 : 0);
                            w.holdTool();
                            w.save();
                        });
                return false;
            }
            if (noWeapon() && now >= warnAt) {
                warnAt = now + 6000;
                Player owner = Bukkit.getPlayer(w.owner);
                if (owner != null) owner.sendMessage(Component.text("⚔ Guard " + w.name + " has no weapon. Put one in a chest settlers may take from.", NamedTextColor.YELLOW));
            }
        }
        rounds();
        return false;
    }

    private boolean runSub() {
        if (sub.tick()) {
            sub.stop();
            sub = null;
            arrowRun = false;
            w.holdTool();
        }
        return false;
    }

    private void fight(int now) {
        label = "Fighting";
        if (t.guardPotions() && w.body.getHealth() < Combat.maxHealth(w.body) * 0.35 && now >= w.potionReady) {
            if (Combat.drinkPotion(w)) w.potionReady = now + 600;
        }
        ItemStack bow = Combat.bow(w), melee = Combat.bestMelee(w);
        boolean arrows = w.count(Material.ARROW) > 0;
        double d = w.body.getLocation().distance(target.getLocation());
        // Can't get to it (down a cliff, in deep water)? Shoot it if possible, else leave it be for a while.
        if (target != chasing) {
            chasing = target;
            closest = d;
            progressAt = time;
        } else if (d < closest - 0.5 || d <= 3.5) {
            closest = d;
            progressAt = time;
        }
        boolean stuck = d > 3 && time - progressAt > 100;
        if (stuck && bow != null && arrows && w.body.hasLineOfSight(target)) {
            Combat.raiseShield(w, false);
            w.body.getEquipment().setItemInMainHand(bow.clone());
            Combat.shoot(w, target, bow);
            return;
        }
        if (stuck) {
            ignored.put(target.getUniqueId(), time + 600);
            t.unreachable.put(target.getUniqueId(), now + 1200); // and no alarm for it
            target = null;
            chasing = null;
            w.mover.stop();
            Combat.raiseShield(w, false);
            return;
        }
        // Shield: up against archers while closing in, and up close between strikes. Down to shoot or strike.
        if (Combat.hasShield(w)) {
            boolean ranged = target instanceof org.bukkit.entity.AbstractSkeleton || target instanceof org.bukkit.entity.Pillager
                    || target instanceof org.bukkit.entity.Witch || target instanceof org.bukkit.entity.Blaze || target instanceof org.bukkit.entity.Ghast
                    || target instanceof org.bukkit.entity.Creeper;
            boolean shooting = bow != null && arrows && (w.weapon == Weapon.RANGED || (w.weapon == Weapon.AUTO && (d > 5 || melee == null)));
            boolean waiting = now < w.attackReady - 3;
            Combat.raiseShield(w, !shooting && ((ranged && d > 3) || (d < 4.5 && waiting)));
        }
        // Up high (a spider on a ceiling or wall): go below it, into the room, and shoot it down.
        double up = target.getLocation().getY() - w.body.getLocation().getY();
        if (up > 2.5 && bow != null && arrows) {
            if (w.body.hasLineOfSight(target) && d <= 16) {
                w.mover.stop();
                w.body.getEquipment().setItemInMainHand(bow.clone());
                Combat.raiseShield(w, false);
                Combat.shoot(w, target, bow);
                progressAt = time; // shooting counts as getting somewhere
            } else if (!w.mover.moving() && time % 20 == 0) {
                Location below = floorBelow(target.getLocation());
                if (below != null) w.mover.go(below, 3.0, w.plugin.settings().workSpeed());
            }
            label = "Shooting down a " + target.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            return;
        }
        // Creepers: never let them get close. Shoot from a distance; with only a sword: hit (it gets knocked
        // back) and step back at once, and back off whenever it starts to hiss.
        if (target instanceof org.bukkit.entity.Creeper creeper) {
            boolean hissing = creeper.getFuseTicks() > 0;
            if (bow != null && arrows) {
                if (d < 6 || (hissing && d < 9)) retreatFrom(creeper, hissing ? 9 : 7);
                else {
                    w.body.getEquipment().setItemInMainHand(bow.clone());
                    Combat.shoot(w, creeper, bow);
                    if (w.body.hasLineOfSight(creeper)) progressAt = time;
                }
                return;
            }
            if (melee != null) {
                // Hit once (knocked far back), then step back; when it hisses: run until well out of range.
                if (hissing) {
                    if (d < 9) retreatFrom(creeper, 9);
                    else { w.mover.stop(); w.anim.look(creeper.getEyeLocation()); label = "Waiting for the creeper to calm down"; progressAt = time; }
                } else if (now < w.attackReady - 5) {
                    if (d < 5) retreatFrom(creeper, 5);
                } else Combat.melee(w, creeper, melee);
                return;
            }
        }
        switch (w.weapon) {
            case RANGED -> {
                if (bow != null && arrows) Combat.shoot(w, target, bow);
                else if (bow != null) restockArrowsNow();
                else if (melee != null) Combat.melee(w, target, melee);
                else noWeaponNow();
            }
            case MELEE -> {
                if (melee != null) Combat.melee(w, target, melee);
                else if (bow != null && arrows) Combat.shoot(w, target, bow);
                else noWeaponNow();
            }
            case AUTO -> {
                if (bow != null && arrows && (d > 5 || melee == null)) Combat.shoot(w, target, bow);
                else if (melee != null) Combat.melee(w, target, melee);
                else if (bow != null) restockArrowsNow();
                else noWeaponNow();
            }
        }
    }

    /** The floor under a spot (where you can stand), up to 12 blocks down. */
    private Location floorBelow(Location l) {
        World world = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ();
        for (int y = l.getBlockY(); y >= l.getBlockY() - 12; y--) {
            if (PathFinder.walkable(world, x, y, z)) return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    /** Step back from something, a few blocks straight away from it. */
    private void retreatFrom(LivingEntity e, double dist) {
        Location me = w.body.getLocation();
        org.bukkit.util.Vector away = me.toVector().subtract(e.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new org.bukkit.util.Vector(1, 0, 0);
        away.normalize().multiply(dist);
        Location to = me.clone().add(away);
        int top = to.getWorld().getHighestBlockYAt(to.getBlockX(), to.getBlockZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        if (Math.abs(top - me.getY()) <= 3) to.setY(top);
        else to.setY(me.getY());
        double fast = w.plugin.settings().workSpeed() * (e instanceof org.bukkit.entity.Creeper c && c.getFuseTicks() > 0 ? 2.0 : 1.4);
        if (!w.mover.moving() || time % 10 == 0) w.mover.go(to, 1.0, fast);
        w.anim.look(e.getEyeLocation());
        label = "Keeping away from a creeper";
        progressAt = time; // backing off on purpose is not being stuck
        closest = me.distance(e.getLocation());
    }

    private void restockArrowsNow() {
        Block chest = t.takeChest(Material.ARROW, w.body.getLocation(), w.plugin.settings().townRadius());
        if (chest == null) {
            ItemStack melee = Combat.bestMelee(w);
            if (melee != null) Combat.melee(w, target, melee);
            else {
                label = "Out of arrows!";
                target = null;
                calmUntil = time + 100;
                w.mover.stop();
            }
            return;
        }
        arrowRun = true;
        sub = new GoAndDo(w, chest, 2.0, "Getting arrows", b -> true,
                b -> Combat.gearUp(w, b, true, Math.max(1, arrowsWanted()), 0));
    }

    private boolean noWeapon() {
        return w.unarmed();
    }

    private void noWeaponNow() {
        label = "No weapon!";
        target = null;
        calmUntil = time + 100; // keep out of the fight for a while
        w.mover.stop();
    }

    /** Defensive: stand at the post. Patrol: walk rounds (mostly along the border). Escort: follow the owner. */
    private void rounds() {
        double speed = w.plugin.settings().walkSpeed();
        if (noWeapon()) {
            label = "No weapon!";
            Location home = w.post != null ? w.post : t.bell;
            if (home.getWorld() == w.body.getWorld() && !w.mover.moving() && time % 40 == 0
                    && w.body.getLocation().distanceSquared(home) > 3 * 3) w.mover.go(home.clone().add(0.5, 0, 0.5), 2, speed);
            return;
        }
        switch (w.stance) {
            case DEFENSIVE -> {
                label = "Guarding the post";
                Location post = w.post != null ? w.post : t.bell;
                if (post.getWorld() != w.body.getWorld()) return;
                Location stand = post.clone().add(0.5, 0, 0.5);
                if (w.body.getLocation().distanceSquared(stand) > 1.5 * 1.5) {
                    if (!w.mover.moving() && time % 20 == 0) w.mover.go(stand, 0.6, speed);
                } else if (time % 60 == 0) {
                    w.anim.glance();
                }
            }
            case ESCORT -> {
                Player owner = Bukkit.getPlayer(w.owner);
                if (owner != null && t.contains(owner.getLocation(), w.plugin.settings().townRadius())) {
                    label = "Escorting " + owner.getName();
                    double far = owner.getLocation().distance(w.body.getLocation());
                    if (far > 4 && (time % 20 == 0 || (far > 10 && time % 10 == 0))) {
                        // The further behind, the faster: up to running to catch up.
                        double sp = w.plugin.settings().workSpeed() * (far > 16 ? 2.0 : far > 8 ? 1.5 : 1.0);
                        w.mover.go(owner.getLocation(), 2.5, sp);
                    }
                    return;
                }
                patrol(speed);
            }
            case PATROL -> patrol(speed);
        }
    }

    /**
     * Patrol: walk a round along the inside of the border (now and then a stretch through the middle),
     * a good distance at a time, then stop and look around for a bit before walking on.
     */
    private void patrol(double speed) {
        label = "On patrol";
        if (w.mover.moving()) return;
        if (time < patrolWait) return;
        boolean there = patrolTo != null && w.body.getLocation().distanceSquared(patrolTo) <= 3 * 3;
        if (patrolTo != null && !there && !w.mover.failed() && time < patrolGiveUp) return; // still on the way
        if (there && patrolPause) { // arrived: a short look around first
            patrolPause = false;
            patrolWait = time + 40 + w.random.nextInt(60);
            w.anim.glance();
            return;
        }
        World world = t.bell.getWorld();
        int r = w.plugin.settings().townRadius();
        Location me = w.body.getLocation();
        double cur = Math.atan2(me.getZ() - t.bell.getZ(), me.getX() - t.bell.getX());
        for (int tries = 0; tries < 12; tries++) {
            boolean middle = w.random.nextInt(10) < 2;
            // Along the border: onwards in the same direction, 25-50 degrees further.
            double a = middle ? w.random.nextDouble() * Math.PI * 2 : cur + patrolDir * Math.toRadians(25 + w.random.nextInt(25));
            double dist = middle ? w.random.nextInt(Math.max(1, r / 2)) : r - 5 - w.random.nextInt(4);
            // The settlement is a square: along the border means along its sides.
            double k = middle ? 1 : Math.max(Math.abs(Math.cos(a)), Math.abs(Math.sin(a)));
            int x = (int) Math.floor(t.bell.getX() + Math.cos(a) * dist / k), z = (int) Math.floor(t.bell.getZ() + Math.sin(a) * dist / k);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int top = world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            for (int y = top; y >= top - 3; y--) {
                if (!PathFinder.walkable(world, x, y, z)) continue;
                Location to = new Location(world, x + 0.5, y, z + 0.5);
                if (to.distanceSquared(me) < 8 * 8) break; // too close: pick something further
                if (w.mover.go(to, 1.5, speed)) {
                    patrolTo = to;
                    patrolPause = true;
                    patrolGiveUp = time + 900;
                    return;
                }
                break;
            }
        }
        patrolDir = -patrolDir; // nothing that way: turn around
        patrolWait = time + 40;
    }

    private LivingEntity findTarget() {
        if (w.lastAttacker != null && w.lastAttacker.isValid() && !w.lastAttacker.isDead() && !ignored.containsKey(w.lastAttacker.getUniqueId())
                && w.lastAttacker.getLocation().distanceSquared(w.body.getLocation()) <= 32 * 32) return w.lastAttacker;
        Location from = switch (w.stance) {
            case DEFENSIVE -> w.post != null ? w.post : t.bell;
            case ESCORT -> {
                Player owner = Bukkit.getPlayer(w.owner);
                yield owner != null && owner.getWorld() == w.body.getWorld() ? owner.getLocation() : w.body.getLocation();
            }
            case PATROL -> w.body.getLocation();
        };
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        ignored.values().removeIf(until -> time > until);
        for (LivingEntity h : t.hostiles) {
            if (!h.isValid() || !inRange(h) || ignored.containsKey(h.getUniqueId())) continue;
            double d = h.getLocation().distanceSquared(from);
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        if (best == null) {
            for (Entity e : w.body.getNearbyEntities(12, 6, 12)) {
                if (e instanceof LivingEntity le && Combat.hostile(e) && inRange(le) && !ignored.containsKey(le.getUniqueId())) {
                    double d = e.getLocation().distanceSquared(from);
                    if (d < bestD) {
                        bestD = d;
                        best = le;
                    }
                }
            }
        }
        return best;
    }

    private boolean inRange(LivingEntity e) {
        if (e.getWorld() != w.body.getWorld()) return false;
        if (e.equals(w.lastAttacker)) return true;
        return switch (w.stance) {
            case DEFENSIVE -> {
                Location post = w.post != null ? w.post : t.bell;
                double r = w.guardRadius;
                yield post.getWorld() == e.getWorld() && e.getLocation().distanceSquared(post) <= r * r;
            }
            case PATROL, ESCORT -> t.contains(e.getLocation(), w.plugin.settings().townRadius() + 8);
        };
    }

    /** Escort: the owner fled far from this fight (and the monster isn't after them). */
    private boolean leftBehind() {
        if (w.stance != Stance.ESCORT || target.equals(w.lastAttacker) && target.getLocation().distanceSquared(w.body.getLocation()) < 4 * 4) return false;
        Player owner = Bukkit.getPlayer(w.owner);
        if (owner == null || owner.getWorld() != w.body.getWorld()) return false;
        Location o = owner.getLocation();
        return o.distanceSquared(w.body.getLocation()) > 28 * 28 && o.distanceSquared(target.getLocation()) > 20 * 20;
    }

    private boolean hurt() {
        return w.body.getHealth() < Combat.maxHealth(w.body) * w.plugin.settings().eatBelowPercent() / 100.0;
    }

    private boolean wantsBow() {
        return w.weapon != Weapon.MELEE;
    }

    private int arrowsWanted() {
        return wantsBow() ? w.plugin.settings().arrowsToCarry() : 0;
    }

    /** A chest with better gear, a bow, arrows or potions (only chests settlers may take from). */
    private Block gearChest() {
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (Block b : t.containers(w.plugin.settings().townRadius())) {
            if (!t.rule(b)[1]) continue;
            if (!Combat.hasUpgrade(w, b, wantsBow(), arrowsWanted(), t.guardPotions() ? 2 : 0)) continue;
            double d = b.getLocation().distanceSquared(w.body.getLocation());
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /** What monsters drop that guards pick up (and bring to storage; arrows they keep). */
    static boolean loot(Material m) {
        return switch (m) {
            case ROTTEN_FLESH, BONE, ARROW, STRING, SPIDER_EYE, GUNPOWDER, ENDER_PEARL, SLIME_BALL, PHANTOM_MEMBRANE,
                 BLAZE_ROD, MAGMA_CREAM, GHAST_TEAR, PRISMARINE_SHARD, IRON_INGOT, COPPER_INGOT, GOLD_NUGGET, EMERALD,
                 GLASS_BOTTLE, GLOWSTONE_DUST, REDSTONE, SUGAR, STICK, TRIDENT, NAUTILUS_SHELL, RABBIT_HIDE, FEATHER -> true;
            default -> false;
        };
    }

    /** Not fighting or fetching anything (may look at a player). */
    boolean idle() {
        return target == null && sub == null;
    }

    @Override
    public void stop() {
        if (sub != null) sub.stop();
        w.mover.stop();
        Combat.raiseShield(w, false);
        w.showTorch(false);
        w.holdTool();
    }

    @Override
    public String label() {
        return sub != null ? sub.label() : label;
    }
}
