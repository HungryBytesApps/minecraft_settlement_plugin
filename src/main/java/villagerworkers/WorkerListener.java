package villagerworkers;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

final class WorkerListener implements Listener {
    private static final List<String> GREETINGS = List.of("Hello!", "Hi there!", "Hm?", "Busy, busy!", "Nice to see you!", "Hrrm.");

    private final VillagerWorkersPlugin plugin;

    WorkerListener(VillagerWorkersPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- Blocks: bell, ledger on chests / composters / anything ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        boolean ledger = Ledger.is(e.getItem());
        Block b = e.getClickedBlock();

        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && b != null) {
            if (b.getType() == Material.BELL) {
                // The bell still rings; this just opens the town menu (or offers to found a town).
                Town t = plugin.towns().atBell(b);
                if (t != null) {
                    if (canManage(p, t)) Menus.town(plugin, p, t);
                    else p.sendActionBar(Component.text("The bell of " + t.name + ".", NamedTextColor.GRAY));
                } else if (plugin.canFoundTown(p, b, false)) {
                    Menus.foundTown(plugin, p, b);
                }
                return;
            }
            if (!ledger) return;
            Town t = plugin.towns().at(b.getLocation());
            if (Town.isStorage(b) || b.getType() == Material.COMPOSTER) {
                e.setCancelled(true);
                if (t == null) {
                    p.sendActionBar(Component.text("This isn't in a settlement.", NamedTextColor.RED));
                    return;
                }
                if (!canManage(p, t)) {
                    p.sendActionBar(Component.text("This belongs to " + t.name + ".", NamedTextColor.RED));
                    return;
                }
                if (b.getType() == Material.COMPOSTER) {
                    Town.Workplace wp = t.workplace(b.getLocation());
                    if (wp == null) {
                        wp = t.addWorkplace(b.getLocation(), plugin.settings().defaultRadius());
                        plugin.towns().save();
                    }
                    Menus.workplace(plugin, p, t, wp);
                } else {
                    Menus.chest(plugin, p, t, b);
                }
                return;
            }
        }
        if (ledger && (e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_AIR)) {
            e.setCancelled(true);
            Town t = plugin.ownTown(p);
            if (t == null) p.sendActionBar(Component.text("Right-click a bell to create a settlement first.", NamedTextColor.YELLOW));
            else Menus.town(plugin, p, t);
        }
    }

    /** A composter placed in town becomes a workplace; chests change what's in storage. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Town t = plugin.towns().at(b.getLocation());
        if (t == null) return;
        if (b.getType() == Material.COMPOSTER) {
            t.addWorkplace(b.getLocation(), plugin.settings().defaultRadius());
            plugin.towns().save();
            if (canManage(e.getPlayer(), t)) {
                e.getPlayer().sendActionBar(Component.text("New farm workplace in " + t.name + ".", NamedTextColor.GREEN));
            }
        } else if (Town.isStorage(b)) {
            t.invalidate();
        }
    }

    /** The town bell can't be broken while the town exists. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreakBell(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.BELL) return;
        Town t = plugin.towns().atBell(b);
        if (t != null) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("This is the bell of " + t.name + ". Disband the settlement first.", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Town t = plugin.towns().at(b.getLocation());
        if (t == null) return;
        if (b.getType() == Material.COMPOSTER) {
            Town.Workplace wp = t.workplace(b.getLocation());
            if (wp != null) {
                Worker w = wp.worker == null ? null : plugin.workers().get(wp.worker);
                t.workplaces.remove(Keys.ser(b.getLocation()));
                plugin.towns().save();
                if (w != null) Bukkit.getScheduler().runTask(plugin, w::leaveWorkplace);
            }
        } else if (Town.isStorage(b)) {
            t.setRule(b, null);
            t.invalidate();
        }
    }

    // ---- Villagers ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onClickVillager(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Villager v)) return;
        Player p = e.getPlayer();
        boolean ledger = Ledger.is(p.getInventory().getItemInMainHand());
        Worker w = plugin.workers().get(v.getUniqueId());
        if (w != null) {
            e.setCancelled(true);
            if (ledger && w.canManage(p)) {
                Menus.settler(plugin, p, w);
            } else {
                Bubbles.say(plugin, v, GREETINGS.get(w.random.nextInt(GREETINGS.size())), 40);
                Mover.lookAt(v, p.getEyeLocation());
                v.getWorld().playSound(v.getLocation(), "minecraft:entity.villager.ambient", SoundCategory.NEUTRAL, 0.8f, 1f);
            }
            return;
        }
        if (ledger && v.isAdult() && !Worker.isWorker(v)) {
            e.setCancelled(true);
            Town t = plugin.ownTown(p);
            if (t == null) p.sendActionBar(Component.text("Right-click a bell to create a settlement first.", NamedTextColor.YELLOW));
            else Menus.hire(plugin, p, v, t);
        }
    }

    /** Settlers don't trample farmland. */
    @EventHandler(ignoreCancelled = true)
    public void onTrample(EntityInteractEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && plugin.workers().get(e.getEntity().getUniqueId()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Worker w = plugin.workers().get(e.getEntity().getUniqueId());
        if (w == null) return;
        String name = w.name;
        plugin.forget(w);
        Player owner = Bukkit.getPlayer(w.owner);
        if (owner != null) owner.sendMessage(Component.text("Your settler " + name + " has died.", NamedTextColor.RED));
    }

    @EventHandler
    public void onAdd(EntityAddToWorldEvent e) {
        Entity en = e.getEntity();
        // Leftover speech bubbles (normally never saved).
        if (en instanceof TextDisplay && en.getPersistentDataContainer().has(Keys.TEMP, PersistentDataType.BYTE)) {
            Bukkit.getScheduler().runTask(plugin, en::remove);
            return;
        }
        if (Worker.isWorker(en)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (en.isValid()) plugin.load((Villager) en);
            });
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent e) {
        if (Worker.isWorker(e.getEntity())) plugin.workers().unregister(e.getEntity().getUniqueId());
    }

    private static boolean canManage(Player p, Town t) {
        return p.getUniqueId().equals(t.owner) || p.hasPermission("villagerworkers.admin");
    }
}
