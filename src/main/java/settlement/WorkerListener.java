package settlement;

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

    private final SettlementPlugin plugin;

    WorkerListener(SettlementPlugin plugin) {
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
                Town ex = t == null ? plugin.towns().atExpansionBell(b) : null;
                Town in = t == null && ex == null ? plugin.towns().at(b.getLocation()) : null;
                if (t != null) {
                    if (canManage(p, t)) Menus.bell(plugin, p, t);
                    else p.sendActionBar(Component.text("The bell of " + t.name + ".", NamedTextColor.GRAY));
                } else if (ex != null) { // an expansion bell
                    if (ex.isOwner(p)) Menus.expansion(plugin, p, ex, ex.expansionAt(b));
                    else if (canManage(p, ex)) Menus.bell(plugin, p, ex);
                    else p.sendActionBar(Component.text("A bell of " + ex.name + ".", NamedTextColor.GRAY));
                } else if (in != null) { // a new bell inside a settlement: offer an expansion
                    if (in.isOwner(p)) Menus.newExpansion(plugin, p, in, b);
                    else p.sendActionBar(Component.text("This bell is in " + in.name + ".", NamedTextColor.GRAY));
                } else if (plugin.canFoundTown(p, b, false)) {
                    Menus.foundTown(plugin, p, b);
                } else if (Area.newSettlementRadius(plugin, b) < 0) {
                    p.sendActionBar(Component.text("Too close to another settlement for a new one.", NamedTextColor.RED));
                }
                return;
            }
            if (!ledger) return;
            Town t = plugin.towns().at(b.getLocation());
            if (Town.isStorage(b) || Worker.Job.forBlock(b.getType()) != Worker.Job.NONE) {
                e.setCancelled(true);
                if (t == null) {
                    p.sendActionBar(Component.text("This isn't in a settlement.", NamedTextColor.RED));
                    return;
                }
                if (!canManage(p, t)) {
                    p.sendActionBar(Component.text("This belongs to " + t.name + ".", NamedTextColor.RED));
                    return;
                }
                if (Worker.Job.forBlock(b.getType()) != Worker.Job.NONE) {
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
            // Clicking a settler also sends a "use item" click right after: don't open the ledger over its menu.
            Integer last = lastClick.get(p.getUniqueId());
            if (last != null && Bukkit.getCurrentTick() - last < 5) return;
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
        if (Worker.Job.forBlock(b.getType()) != Worker.Job.NONE) {
            t.addWorkplace(b.getLocation(), plugin.settings().defaultRadius());
            plugin.towns().save();
            if (canManage(e.getPlayer(), t)) {
                e.getPlayer().sendActionBar(Component.text("New " + Worker.Job.forBlock(b.getType()).title.toLowerCase(java.util.Locale.ROOT) + "'s workplace in " + t.name + ".", NamedTextColor.GREEN));
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
            return;
        }
        Town ex = plugin.towns().atExpansionBell(b);
        if (ex != null) { // breaking an expansion bell removes the expansion (founder only, if nothing falls outside)
            Town.Expansion x = ex.expansionAt(b);
            String why = !ex.isOwner(e.getPlayer()) ? "Only the founder can remove an expansion." : Area.checkRemove(ex, x);
            if (why != null) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar(Component.text(why, NamedTextColor.RED));
                return;
            }
            ex.expansions.remove(x);
            Area.changed(plugin, ex);
            e.getPlayer().sendActionBar(Component.text("Expansion of " + ex.name + " removed.", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Town t = plugin.towns().at(b.getLocation());
        if (t == null) return;
        if (Worker.Job.forBlock(b.getType()) != Worker.Job.NONE) {
            Town.Workplace wp = t.workplace(b.getLocation());
            if (wp != null) {
                List<Worker> staff = new java.util.ArrayList<>();
                for (java.util.UUID id : wp.workers) {
                    Worker w = plugin.workers().get(id);
                    if (w != null) staff.add(w);
                }
                t.workplaces.remove(Keys.ser(b.getLocation()));
                plugin.towns().save();
                for (Worker w : staff) Bukkit.getScheduler().runTask(plugin, w::leaveWorkplace);
            }
        } else if (Town.isStorage(b)) {
            t.setRule(b, null);
            t.invalidate();
        }
    }

    // ---- Villagers ----

    private final java.util.Map<java.util.UUID, Integer> lastClick = new java.util.HashMap<>();

    /** Player-like entities (settlers) get an "interact at" click; handle it the same way. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClickAt(org.bukkit.event.player.PlayerInteractAtEntityEvent e) {
        if (Worker.isWorker(e.getRightClicked())) onClickVillager(e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClickVillager(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (Worker.isWorker(e.getRightClicked())) {
            e.setCancelled(true);
            Worker seen = plugin.workers().get(e.getRightClicked().getUniqueId());
            if (seen != null) seen.lastClickSeen = Bukkit.getCurrentTick();
            // Both click events can come for one click: handle it once.
            int now = Bukkit.getCurrentTick();
            Integer last = lastClick.put(p.getUniqueId(), now);
            if (last != null && now - last < 4) return;
        }
        boolean ledger = Ledger.is(p.getInventory().getItemInMainHand());
        Worker w = plugin.workers().get(e.getRightClicked().getUniqueId());
        if (w == null && e.getRightClicked() instanceof org.bukkit.entity.Mannequin m && Worker.isWorker(m)) {
            plugin.load(m); // somehow not running: start it now
            w = plugin.workers().get(m.getUniqueId());
        }
        if (w != null) {
            e.setCancelled(true);
            if (w.canManage(p)) { // your own settler: its menu (no ledger needed)
                w.pauseFor(p, 1200); // waits while you're in its menu
                Menus.settler(plugin, p, w);
            } else {
                w.pauseFor(p, 60);
                String hi = GREETINGS.get(w.random.nextInt(GREETINGS.size()));
                Bubbles.say(plugin, w.body, hi, 40);
                Voice.speak(w, hi);
                w.anim.look(p.getEyeLocation());
            }
            return;
        }
        if (!(e.getRightClicked() instanceof Villager v)) return;
        if (ledger && v.isAdult()) {
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
        if (bySettler && !Combat.hostile(victim) && !HerderJob.mayKill(victim)) {
            e.setCancelled(true);
            if (e.getDamager() instanceof org.bukkit.entity.Arrow a) a.remove();
            return;
        }
        if (victimSettler) {
            Worker w = plugin.workers().get(victim.getUniqueId());
            if (w == null) return;
            if (Combat.blocks(w, e.getDamager())) { // blocked with the shield
                e.setCancelled(true);
                org.bukkit.inventory.ItemStack shield = w.body.getEquipment().getItemInOffHand();
                Work.wear(w, shield);
                w.body.getEquipment().setItemInOffHand(shield.getAmount() > 0 ? shield : null);
                victim.getWorld().playSound(victim.getLocation(), "minecraft:item.shield.block", org.bukkit.SoundCategory.NEUTRAL, 1f, 0.9f + w.random.nextFloat() * 0.2f);
                if (e.getDamager() instanceof org.bukkit.entity.Projectile pr) pr.remove();
                if (src instanceof org.bukkit.entity.LivingEntity le && Combat.hostile(le)) w.lastAttacker = le;
                return;
            }
            w.lastHurt = Bukkit.getCurrentTick();
            if (src instanceof org.bukkit.entity.LivingEntity le && Combat.hostile(le)) w.lastAttacker = le;
        }
    }

    /** Settlers move by themselves (not by physics): no damage from bumping into blocks or falling. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onMoveDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!Worker.isWorker(e.getEntity())) return;
        switch (e.getCause()) {
            case SUFFOCATION, FALL, CRAMMING, FLY_INTO_WALL -> {
                e.setCancelled(true);
                if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.SUFFOCATION) {
                    e.getEntity().teleport(e.getEntity().getLocation().add(0, 1, 0),
                            io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS); // out of the block
                }
            }
            default -> {
            }
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
        SettlerInventory.close(w); // someone looking in its pockets: finish that first
        // Drop what they carried and wore (the item in hand is only a copy).
        e.getDrops().clear();
        e.setDroppedExp(0);
        for (org.bukkit.inventory.ItemStack it : w.pockets.getContents()) {
            if (it != null && !it.getType().isAir()) e.getDrops().add(it.clone());
        }
        w.pockets.clear();
        org.bukkit.inventory.EntityEquipment eq = w.body.getEquipment();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFF_HAND}) {
            org.bukkit.inventory.ItemStack it = eq.getItem(slot);
            if (it != null && !it.getType().isAir()) e.getDrops().add(it.clone());
        }
        String name = w.name;
        plugin.forget(w);
        Notify.send(w.town(), w.owner, Notify.Kind.DEATH, "✝ " + (w.guard ? "Guard " : "Settler ") + name + " has died. Their things lie where they fell.", NamedTextColor.RED);
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
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent e) {
        if (Worker.isWorker(e.getEntity())) plugin.workers().unregister(e.getEntity().getUniqueId());
    }

    private static boolean canManage(Player p, Town t) {
        return t.canManage(p);
    }
}
