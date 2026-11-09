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
import org.bukkit.entity.Display;
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
                    if (canManage(p, t)) Menus.bell(plugin, p, t);
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
            else Menus.ledger(plugin, p, t);
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
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        boolean ledger = Ledger.is(p.getInventory().getItemInMainHand());
        Worker w = plugin.workers().get(e.getRightClicked().getUniqueId());
        if (w != null) {
            e.setCancelled(true);
            if (ledger && w.canManage(p)) {
                Menus.settler(plugin, p, w);
            } else {
                Bubbles.say(plugin, w.body, GREETINGS.get(w.random.nextInt(GREETINGS.size())), 40);
                if (!w.body.isInsideVehicle()) Mover.lookAt(w.body, p.getEyeLocation());
                w.body.swingMainHand();
            }
            return;
        }
        if (!(e.getRightClicked() instanceof Villager v)) return;
        if (ledger && v.isAdult() && !VillagerWorkersPlugin.isOldSettler(v)) {
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

    // ---- One ledger per player ----

    @EventHandler(ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && Ledger.is(e.getItem().getItemStack()) && Ledger.has(p)) e.setCancelled(true);
    }

    /** Moving a ledger from a chest (or anywhere else) into your inventory when you already have one. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() == null) return;
        boolean ownInv = e.getClickedInventory().equals(p.getInventory());
        org.bukkit.inventory.ItemStack moving = null;
        if (!ownInv && (e.isShiftClick() || e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY)) {
            moving = e.getCurrentItem(); // from a chest straight into your inventory
        } else if (ownInv && !e.isShiftClick()) {
            moving = e.getCursor();      // putting a ledger from the cursor into your inventory
        }
        if (Ledger.is(moving) && countLedgers(p) >= 1) {
            e.setCancelled(true);
            p.sendActionBar(Component.text("You can only carry one Settlement Ledger.", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !Ledger.is(e.getOldCursor()) || countLedgers(p) < 1) return;
        int top = e.getView().getTopInventory().getSize();
        for (int slot : e.getRawSlots()) {
            if (slot >= top) {
                e.setCancelled(true);
                return;
            }
        }
    }

    private static int countLedgers(Player p) {
        int n = 0;
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) if (Ledger.is(it)) n += it.getAmount();
        return n;
    }

    // ---- Combat ----

    /** Who's really behind this damage (the shooter of an arrow, etc.). */
    private static Entity source(Entity damager) {
        if (damager instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity shooter) return shooter;
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() != null) return tnt.getSource();
        if (damager instanceof org.bukkit.entity.AreaEffectCloud cloud && cloud.getSource() instanceof Entity src) return src;
        return damager;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        Entity src = source(e.getDamager());
        boolean victimSettler = Worker.isWorker(victim);
        boolean bySettler = Worker.isWorker(src);
        // Players can't hurt settlers; settlers don't hurt each other, players or animals.
        if (victimSettler && (src instanceof Player || bySettler)) {
            e.setCancelled(true);
            if (e.getDamager() instanceof org.bukkit.entity.Projectile pr && !(pr instanceof org.bukkit.entity.Trident)) pr.remove();
            return;
        }
        if (bySettler && !Combat.hostile(victim)) {
            e.setCancelled(true);
            if (e.getDamager() instanceof org.bukkit.entity.Arrow a) a.remove();
            return;
        }
        if (victimSettler) {
            Worker w = plugin.workers().get(victim.getUniqueId());
            if (w == null) return;
            w.lastHurt = Bukkit.getCurrentTick();
            if (src instanceof org.bukkit.entity.LivingEntity le && Combat.hostile(le)) w.lastAttacker = le;
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAnyDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!Worker.isWorker(e.getEntity())) return;
        Worker w = plugin.workers().get(e.getEntity().getUniqueId());
        if (w != null) w.lastHurt = Bukkit.getCurrentTick();
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Worker w = plugin.workers().get(e.getEntity().getUniqueId());
        if (w == null) return;
        // Drop what they carried and wore (the item in hand is only a copy).
        e.getDrops().clear();
        e.setDroppedExp(0);
        for (org.bukkit.inventory.ItemStack it : w.pockets.getContents()) {
            if (it != null && !it.getType().isAir()) e.getDrops().add(it.clone());
        }
        w.pockets.clear();
        org.bukkit.inventory.EntityEquipment eq = w.body.getEquipment();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            org.bukkit.inventory.ItemStack it = eq.getItem(slot);
            if (it != null && !it.getType().isAir()) e.getDrops().add(it.clone());
        }
        String name = w.name;
        plugin.forget(w);
        Player owner = Bukkit.getPlayer(w.owner);
        if (owner != null) owner.sendMessage(Component.text("✝ " + (w.guard ? "Guard " : "Settler ") + name + " has died. Their things lie where they fell.", NamedTextColor.RED));
    }

    @EventHandler
    public void onAdd(EntityAddToWorldEvent e) {
        Entity en = e.getEntity();
        // Leftover speech bubbles / seats (normally never saved).
        if ((en instanceof Display || Seats.isSeatEntity(en)) && Keys.isLeftover(en)) {
            Bukkit.getScheduler().runTask(plugin, en::remove);
            return;
        }
        if (Worker.isWorker(en)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (en.isValid()) plugin.load((org.bukkit.entity.Mannequin) en);
            });
        } else if (en instanceof Villager v && VillagerWorkersPlugin.isOldSettler(v)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (v.isValid()) plugin.convertOld(v);
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
