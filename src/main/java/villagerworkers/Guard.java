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
    private int time, gearCheckAt = 20, warnAt, calmUntil;
    private Location patrolTo;
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
            if (Combat.eat(w)) return false;
            if (t.mayEat) {
                Activity food = Defense.fetchFood(w, t);
                if (food != null) {
                    sub = food;
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
                            Combat.gearUp(w, b, wantsBow(), arrowsWanted(), t.guardPotions ? 2 : 0);
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
        if (t.guardPotions && w.body.getHealth() < Combat.maxHealth(w.body) * 0.35 && now >= w.potionReady) {
            if (Combat.drinkPotion(w)) w.potionReady = now + 600;
        }
        ItemStack bow = Combat.bow(w), melee = Combat.bestMelee(w);
        boolean arrows = w.count(Material.ARROW) > 0;
        double d = w.body.getLocation().distance(target.getLocation());
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
                    Social.LookAround.glance(w);
                }
            }
            case ESCORT -> {
                Player owner = Bukkit.getPlayer(w.owner);
                if (owner != null && t.contains(owner.getLocation(), w.plugin.settings().townRadius())) {
                    label = "Escorting " + owner.getName();
                    if (time % 20 == 0 && owner.getLocation().distanceSquared(w.body.getLocation()) > 4 * 4) {
                        w.mover.go(owner.getLocation(), 2.5, w.plugin.settings().workSpeed());
                    }
                    return;
                }
                patrol(speed);
            }
            case PATROL -> patrol(speed);
        }
    }

    private void patrol(double speed) {
        label = "On patrol";
        if (w.mover.moving()) return;
        if (patrolTo != null && w.body.getLocation().distanceSquared(patrolTo) > 2 * 2 && !w.mover.failed() && time % 40 != 0) return;
        World world = t.bell.getWorld();
        int r = w.plugin.settings().townRadius();
        for (int tries = 0; tries < 10; tries++) {
            double a = w.random.nextDouble() * Math.PI * 2;
            double dist = w.random.nextInt(10) < 7 ? r - 6 - w.random.nextInt(6) : w.random.nextInt(Math.max(1, r - 6));
            int x = (int) Math.floor(t.bell.getX() + Math.cos(a) * dist), z = (int) Math.floor(t.bell.getZ() + Math.sin(a) * dist);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = t.bell.getBlockY() + 8; y >= t.bell.getBlockY() - 12; y--) {
                if (PathFinder.walkable(world, x, y, z)) {
                    patrolTo = new Location(world, x + 0.5, y, z + 0.5);
                    if (w.mover.go(patrolTo, 1.5, speed)) return;
                    break;
                }
            }
        }
    }

    private LivingEntity findTarget() {
        if (w.lastAttacker != null && w.lastAttacker.isValid() && !w.lastAttacker.isDead()
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
        for (LivingEntity h : t.hostiles) {
            if (!h.isValid() || !inRange(h)) continue;
            double d = h.getLocation().distanceSquared(from);
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        if (best == null) {
            for (Entity e : w.body.getNearbyEntities(12, 6, 12)) {
                if (e instanceof LivingEntity le && Combat.hostile(e) && inRange(le)) {
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
            if (!Combat.hasUpgrade(w, b, wantsBow(), arrowsWanted(), t.guardPotions ? 2 : 0)) continue;
            double d = b.getLocation().distanceSquared(w.body.getLocation());
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    @Override
    public void stop() {
        if (sub != null) sub.stop();
        w.mover.stop();
        w.holdTool();
    }

    @Override
    public String label() {
        return sub != null ? sub.label() : label;
    }
}
