package villagerworkers;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** All dialog windows: town, targets, chest, workplace, settler, hiring, founding a town. */
final class Menus {
    private Menus() {}

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(10)).build();

    /** Times a dialog was shown per player: tells whether a click opened a new window. */
    private static final Map<UUID, Integer> shown = new java.util.concurrent.ConcurrentHashMap<>();

    /** Something else (an inventory) replaced the dialog: don't close it after the click. */
    static void markShown(Player p) {
        shown.merge(p.getUniqueId(), 1, Integer::sum);
    }

    /**
     * Dialogs wait for the server after a click, so a new window replaces the old one directly
     * (no flicker, the mouse stays put). A click that opens nothing new closes the dialog.
     */
    private static DialogAction click(VillagerWorkersPlugin plugin, DialogActionCallback cb, ClickCallback.Options options) {
        return click(plugin, (view, audience) -> {
            if (!(audience instanceof Player p)) {
                cb.accept(view, audience);
                return;
            }
            Integer before = shown.get(p.getUniqueId());
            cb.accept(view, audience);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && Objects.equals(before, shown.get(p.getUniqueId()))) p.closeDialog();
            });
        }, options);
    }

    private static ActionButton button(VillagerWorkersPlugin plugin, String text, NamedTextColor color, String tip,
                                       int width, Consumer<Player> onClick) {
        return ActionButton.create(Component.text(text, color), tip == null ? null : Component.text(tip), width,
                click(plugin, (view, audience) -> {
                    if (audience instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> onClick.accept(p));
                }, ONCE));
    }

    private static ActionButton close(VillagerWorkersPlugin plugin) {
        return button(plugin, "Close", null, null, 100, pl -> {});
    }

    private static DialogBody line(String text, NamedTextColor color) {
        return DialogBody.plainMessage(Component.text(text, color));
    }

    private static void show(Player p, String title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).inputs(inputs).canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE).build())
                .type(type));
        markShown(p);
        p.showDialog(d);
    }

    private static String nice(Material m) {
        String s = FarmerJob.nice(m);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- Found a town ----

    static void foundTown(VillagerWorkersPlugin plugin, Player p, Block bell) {
        DialogInput name = DialogInput.text("name", Component.text("Settlement name")).initial(p.getName() + "'s Settlement").maxLength(32).width(220).build();
        ActionButton yes = ActionButton.create(Component.text("Create settlement", NamedTextColor.GREEN), null, 100,
                click(plugin, (view, audience) -> {
                    String n = view.getText("name");
                    if (audience instanceof Player pl) Bukkit.getScheduler().runTask(plugin, () -> plugin.foundTown(pl, bell, n));
                }, ONCE));
        ActionButton no = button(plugin, "Not now", null, null, 100, pl -> {});
        show(p, "Create settlement?", List.of(),
                List.of(name), DialogType.confirmation(yes, no));
    }

    // ---- Town menu ----

    /** Right-clicking the bell: stats, recruiting, border, ledger, rename, disband. */
    static void bell(VillagerWorkersPlugin plugin, Player p, Town t) {
        Settings s = plugin.settings();
        List<DialogBody> body = new ArrayList<>();
        body.add(stats(t, s));

        int w = 150;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(plugin, "Recruit settler", NamedTextColor.GREEN, "Costs " + costText(s.recruitCost()),
                w, pl -> plugin.recruit(pl, t)));
        Borders.Mode border = plugin.borders().mode(p);
        buttons.add(button(plugin, "Show border: " + border.label,
                border == Borders.Mode.OFF ? NamedTextColor.RED : NamedTextColor.GREEN, "Click to switch: When holding ledger / On / Off", w, pl -> {
                    plugin.borders().cycle(pl);
                    bell(plugin, pl, t);
                }));
        buttons.add(button(plugin, "Get a ledger", NamedTextColor.WHITE, "The book to manage your settlement", w, pl -> {
            if (Ledger.has(pl)) {
                pl.sendActionBar(Component.text("You already have a Settlement Ledger.", NamedTextColor.YELLOW));
                return;
            }
            for (ItemStack left : pl.getInventory().addItem(Ledger.create()).values()) pl.getWorld().dropItem(pl.getLocation(), left);
            pl.sendActionBar(Component.text("Here's your Settlement Ledger.", NamedTextColor.GREEN));
        }));
        buttons.add(button(plugin, "Rename settlement", NamedTextColor.WHITE, "Give the settlement a new name", w, pl -> renameTown(plugin, pl, t)));
        buttons.add(button(plugin, "Tutorial", NamedTextColor.AQUA, "How everything works", w, pl -> tutorial(plugin, pl, t, true)));
        buttons.add(button(plugin, "Disband settlement", NamedTextColor.RED, "Removes the settlement. Settlers become villagers again.", w, pl -> disband(plugin, pl, t)));
        show(p, t.name, body, List.of(), DialogType.multiAction(buttons).columns(2).exitAction(close(plugin)).build());
    }

    /** The ledger's own menu: stats, targets, chests, settlers. */
    static void ledger(VillagerWorkersPlugin plugin, Player p, Town t) {
        Settings s = plugin.settings();
        boolean on = t.chestDefault && t.chestRules.isEmpty();
        List<DialogBody> body = new ArrayList<>();
        body.add(stats(t, s));

        int w = 150;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(plugin, "Storage targets…", NamedTextColor.WHITE, "How much of each product to keep in storage", w, pl -> TargetsMenu.open(plugin, pl, t)));
        buttons.add(button(plugin, "Use all chests: " + (on ? "On" : "Off"), on ? NamedTextColor.GREEN : NamedTextColor.RED, "On: settlers use every chest. Off: only the chests you allow with the ledger.", w, pl -> {
            boolean now = !(t.chestDefault && t.chestRules.isEmpty());
            t.allChests(now);
            plugin.towns().save();
            pl.sendActionBar(Component.text(now ? "Settlers may now use all chests in the settlement."
                    : "Settlers may no longer use chests in the settlement.", now ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            ledger(plugin, pl, t);
        }));
        buttons.add(button(plugin, "Settlers", NamedTextColor.WHITE, "All settlers: job, what they do, health", w, pl -> settlers(plugin, pl, t)));
        buttons.add(button(plugin, "When attacked: " + t.reaction.label,
                t.reaction == Town.Reaction.CALL_TO_ARMS ? NamedTextColor.GOLD : NamedTextColor.WHITE, "What workers do when monsters come: hide, or grab a weapon and fight", w, pl -> {
                    t.reaction = t.reaction == Town.Reaction.TAKE_COVER ? Town.Reaction.CALL_TO_ARMS : Town.Reaction.TAKE_COVER;
                    plugin.towns().save();
                    ledger(plugin, pl, t);
                }));
        buttons.add(button(plugin, "Settlers may eat: " + (t.mayEat ? "On" : "Off"), t.mayEat ? NamedTextColor.GREEN : NamedTextColor.RED, "Hurt settlers eat food from storage. Guards first.", w, pl -> {
            t.mayEat = !t.mayEat;
            plugin.towns().save();
            ledger(plugin, pl, t);
        }));
        buttons.add(button(plugin, "Guards use potions: " + (t.guardPotions ? "On" : "Off"), t.guardPotions ? NamedTextColor.GREEN : NamedTextColor.RED, "Guards take healing potions from storage and drink them in a fight", w, pl -> {
            t.guardPotions = !t.guardPotions;
            plugin.towns().save();
            ledger(plugin, pl, t);
        }));
        buttons.add(button(plugin, "Tutorial", NamedTextColor.AQUA, "How everything works", w, pl -> tutorial(plugin, pl, t, false)));
        show(p, t.name, body, List.of(), DialogType.multiAction(buttons).columns(2).exitAction(close(plugin)).build());
    }

    // ---- Tutorial ----

    private static void tutorial(VillagerWorkersPlugin plugin, Player p, Town t, boolean fromBell) {
        Settings s = plugin.settings();
        List<DialogBody> body = new ArrayList<>();
        head(body, "Settlement");
        text(body, "Your settlement is everything within " + s.townRadius() + " blocks of this bell. Settlers live, work and sleep here.");
        text(body, "Bell: recruit settlers (" + costText(s.recruitCost()) + "), show the border, get a ledger, rename or disband.");
        text(body, "Show border: an orange band marks the settlement, green bands mark work areas. When holding ledger / On / Off.");
        head(body, "Settlement Ledger");
        text(body, "One per player. Right-click with it:");
        text(body, "• a chest or barrel: may settlers put things in / take things out?");
        text(body, "• a composter: its work radius, who works there, on/off.");
        text(body, "• a settler: rename, inventory, worker or guard, guard settings, leave workplace, dismiss.");
        text(body, "• a wild villager: hire it. It walks to the settlement, or follows you if it's far away.");
        text(body, "• anything else: storage targets, chests, settlers, defense settings.");
        head(body, "Settlers");
        text(body, "A free settler takes an empty workplace by itself. A composter is a farm: the farmer harvests, replants and plants the fields around it.");
        text(body, "They fetch seeds from chests they may take from, and bring the harvest to a chest that already has that item (or the nearest one with room). No room left: they keep it in their pockets and you get a message.");
        text(body, "When they're not working they take walks, chat, read, make music together and visit each other at work.");
        text(body, "In the evening they get together at the spot with the most seats close together (stairs, slabs, cushions, extra cosy near a campfire), or at the bell.");
        head(body, "Guards and defense");
        text(body, "Make a settler a guard in its menu. Guards take the best weapons, armor, bows and arrows (" + s.arrowsToCarry()
                + ") from chests settlers may take from. Without a weapon there, you get a warning.");
        text(body, "Stance: Defensive guards stay at their post (Set post here) and fight mobs within their radius (default " + s.guardRadius()
                + "). Patrol guards walk along the border and through the settlement. Escort guards follow you inside the settlement.");
        text(body, "Weapon: Auto (bow at range, melee up close), Melee or Archer. Archers fetch arrows as soon as they run out; others refill after the fight.");
        text(body, "Hostile mobs in the settlement ring the bell. Other settlers then take cover, or with Call to arms grab a weapon from storage and fight (and bring it back after).");
        text(body, "Everyone can be hurt and die, but never by players. They heal slowly, and eat food from storage when hurt (Settlers may eat). Guards eat first.");
        text(body, "Guards use potions: guards carry and drink healing potions from storage in a fight. Off by default.");
        text(body, "When they die, they drop what they carried and wore.");
        head(body, "Storage and targets");
        text(body, "Use all chests: On lets settlers use every chest; Off only the ones you allow with the ledger.");
        text(body, "Storage targets: settlers stop making a product once the chests hold that many (no limit by default). Farmers keep replanting.");
        ActionButton back = button(plugin, "Back", NamedTextColor.GRAY, null, 100,
                pl -> { if (fromBell) bell(plugin, pl, t); else ledger(plugin, pl, t); });
        show(p, "Tutorial", body, List.of(), DialogType.notice(back));
    }

    private static void head(List<DialogBody> body, String text) {
        body.add(DialogBody.plainMessage(Component.text(text, NamedTextColor.GOLD), 300));
    }

    private static void text(List<DialogBody> body, String text) {
        body.add(DialogBody.plainMessage(Component.text(text, NamedTextColor.GRAY), 300));
    }

    private static DialogBody stats(Town t, Settings s) {
        return line("Settlers: " + t.settlers.size() + "/" + s.maxSettlers() + "   ·   Workplaces: " + t.workplaces.size()
                + " (" + freeWorkplaces(t) + " free)", NamedTextColor.GRAY);
    }

    private static int freeWorkplaces(Town t) {
        int free = 0;
        for (Town.Workplace wp : t.workplaces.values()) if (wp.enabled && wp.worker == null) free++;
        return free;
    }

    private static String costText(List<ItemStack> cost) {
        if (cost.isEmpty()) return "free";
        List<String> parts = new ArrayList<>();
        for (ItemStack it : cost) parts.add(it.getAmount() + " " + FarmerJob.nice(it.getType()));
        return String.join(", ", parts);
    }

    private static void renameTown(VillagerWorkersPlugin plugin, Player p, Town t) {
        DialogInput name = DialogInput.text("name", Component.text("Settlement name")).initial(t.name).maxLength(32).width(220).build();
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 100,
                click(plugin, (view, audience) -> {
                    String n = view.getText("name");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (n != null && !n.isBlank()) t.name = n.trim();
                        plugin.towns().save();
                        bell(plugin, pl, t);
                    });
                }, ONCE));
        show(p, "Rename settlement", List.of(), List.of(name),
                DialogType.confirmation(save, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> bell(plugin, pl, t))));
    }

    private static void disband(VillagerWorkersPlugin plugin, Player p, Town t) {
        ActionButton yes = button(plugin, "Disband", NamedTextColor.RED, null, 100, pl -> {
            plugin.disband(t);
            pl.sendActionBar(Component.text(t.name + " is no more.", NamedTextColor.YELLOW));
        });
        show(p, "Disband " + t.name + "?", List.of(),
                List.of(), DialogType.confirmation(yes, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> bell(plugin, pl, t))));
    }

    // ---- Settlers overview ----

    static void settlers(VillagerWorkersPlugin plugin, Player p, Town t) {
        List<DialogBody> body = new ArrayList<>();
        for (Map.Entry<UUID, String> e : t.settlers.entrySet()) {
            Worker w = plugin.workers().get(e.getKey());
            if (w == null) {
                body.add(line(e.getValue() + " — far away (not loaded)", NamedTextColor.DARK_GRAY));
            } else {
                Component c = Component.text(w.name + " — " + w.title() + " — " + w.doing() + "  ", NamedTextColor.WHITE)
                        .append(Component.text(w.healthText(), w.hurtBelow(50) ? NamedTextColor.RED : NamedTextColor.GRAY));
                if (w.guard && w.unarmed()) c = c.append(Component.text("  no weapon!", NamedTextColor.RED));
                body.add(DialogBody.plainMessage(c, 300));
            }
        }
        show(p, "Settlers (" + t.settlers.size() + ")", body, List.of(), DialogType.notice(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> ledger(plugin, pl, t))));
    }

    // ---- Chest ----

    static void chest(VillagerWorkersPlugin plugin, Player p, Town t, Block chest) {
        boolean[] r = t.rule(chest);
        boolean own = t.hasOwnRule(chest);
        List<DialogBody> body = List.of(
                line(own ? "Own rules" : "Settlement default", NamedTextColor.GRAY));
        List<DialogInput> inputs = List.of(
                DialogInput.bool("store", Component.text("Settlers may put things in"), r[0], "true", "false"),
                DialogInput.bool("take", Component.text("Settlers may take things out"), r[1], "true", "false"));
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 120,
                click(plugin, (view, audience) -> {
                    Boolean store = view.getBoolean("store"), take = view.getBoolean("take");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        t.setRule(chest, new boolean[]{Boolean.TRUE.equals(store), Boolean.TRUE.equals(take)});
                        plugin.towns().save();
                        pl.sendActionBar(Component.text("Chest rules saved.", NamedTextColor.GREEN));
                    });
                }, ONCE));
        ActionButton reset = button(plugin, "Use settlement default", NamedTextColor.WHITE, "Forget this chest's own rules", 120, pl -> {
            t.setRule(chest, null);
            plugin.towns().save();
            pl.sendActionBar(Component.text("This chest follows the settlement default again.", NamedTextColor.GREEN));
        });
        show(p, "Chest", body, inputs, DialogType.multiAction(List.of(save, reset)).columns(2).exitAction(close(plugin)).build());
    }

    // ---- Workplace ----

    static void workplace(VillagerWorkersPlugin plugin, Player p, Town t, Town.Workplace wp) {
        Settings s = plugin.settings();
        Worker worker = wp.worker == null ? null : plugin.workers().get(wp.worker);
        String who = wp.worker == null ? "nobody" : worker != null ? worker.name : t.settlers.getOrDefault(wp.worker, "?") + " (far away)";
        List<DialogBody> body = new ArrayList<>();
        body.add(line("Works here: " + who + (worker != null && !worker.workStatus.isEmpty() ? " — " + worker.workStatus : ""), NamedTextColor.GRAY));
        List<DialogInput> inputs = List.of(
                DialogInput.numberRange("radius", Component.text("Work radius"), 2, s.maxRadius())
                        .step(1f).initial((float) Math.min(wp.radius, s.maxRadius())).width(200).build(),
                DialogInput.bool("enabled", Component.text("Workplace in use"), wp.enabled, "true", "false"));
        int w = 150;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, w,
                click(plugin, (view, audience) -> {
                    Float radius = view.getFloat("radius");
                    Boolean enabled = view.getBoolean("enabled");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (radius != null) wp.radius = Math.max(2, Math.min(s.maxRadius(), Math.round(radius)));
                        if (enabled != null) wp.enabled = enabled;
                        plugin.towns().save();
                        pl.sendActionBar(Component.text("Workplace saved.", NamedTextColor.GREEN));
                        plugin.borders().flash(pl);
                    });
                }, ONCE)));
        buttons.add(button(plugin, "Show work area", NamedTextColor.WHITE, "Shows the work area for 10 seconds",
                w, pl -> plugin.borders().flash(pl)));
        buttons.add(button(plugin, "Choose settler…", NamedTextColor.WHITE, "Pick who works here", w, pl -> chooseWorker(plugin, pl, t, wp)));
        if (wp.worker != null) {
            buttons.add(button(plugin, "Send " + (worker != null ? worker.name : "worker") + " away", NamedTextColor.RED, "This settler stops working here", w, pl -> {
                Worker cur = wp.worker == null ? null : plugin.workers().get(wp.worker);
                if (cur != null) cur.leaveWorkplace();
                wp.worker = null;
                plugin.towns().save();
                workplace(plugin, pl, t, wp);
            }));
        }
        show(p, wp.job().title + "'s workplace", body, inputs, DialogType.multiAction(buttons).columns(2).exitAction(close(plugin)).build());
    }

    private static void chooseWorker(VillagerWorkersPlugin plugin, Player p, Town t, Town.Workplace wp) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Worker w : plugin.workers().all()) {
            if (!t.id.equals(w.townId)) continue;
            String current = w.guard ? "now guard" : w.job() == Worker.Job.NONE ? "no workplace" : "now " + w.job().title.toLowerCase(Locale.ROOT);
            buttons.add(button(plugin, w.name + " (" + current + ")", NamedTextColor.WHITE, null, 200, pl -> {
                Worker prev = wp.worker == null ? null : plugin.workers().get(wp.worker);
                if (prev != null && prev != w) prev.leaveWorkplace();
                wp.worker = null;
                w.assign(wp);
                pl.sendActionBar(Component.text(w.name + " now works here.", NamedTextColor.GREEN));
                workplace(plugin, pl, t, wp);
            }));
        }
        if (buttons.isEmpty()) {
            show(p, "Choose settler", List.of(line("No settlers nearby.", NamedTextColor.GRAY)), List.of(),
                    DialogType.notice(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> workplace(plugin, pl, t, wp))));
            return;
        }
        show(p, "Choose settler", List.of(), List.of(), DialogType.multiAction(buttons).columns(1)
                .exitAction(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> workplace(plugin, pl, t, wp))).build());
    }

    // ---- Settler ----

    static void settler(VillagerWorkersPlugin plugin, Player p, Worker w) {
        Town.Workplace wp = w.workplace();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(w.title() + " — " + w.doing() + "   ", NamedTextColor.GRAY)
                .append(Component.text(w.healthText(), w.hurtBelow(50) ? NamedTextColor.RED : NamedTextColor.GRAY))));
        if (w.guard && w.unarmed()) body.add(line("No weapon in storage!", NamedTextColor.RED));

        int width = 150;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(plugin, "Rename", NamedTextColor.WHITE, "Give this settler a new name", width, pl -> renameSettler(plugin, pl, w)));
        buttons.add(button(plugin, "Role: " + (w.guard ? "Guard" : "Worker"), w.guard ? NamedTextColor.GOLD : NamedTextColor.WHITE, "Workers work, guards defend the settlement", width, pl -> {
            w.setGuard(!w.guard);
            settler(plugin, pl, w);
        }));
        if (w.guard) {
            buttons.add(button(plugin, "Stance: " + w.stance.label, NamedTextColor.WHITE, "Defensive: stays at its post. Patrol: walks the border. Escort: follows you.", width, pl -> {
                w.stance = w.stance.next();
                w.save();
                settler(plugin, pl, w);
            }));
            buttons.add(button(plugin, "Weapon: " + w.weapon.label, NamedTextColor.WHITE, "Auto: bow at range, melee up close. Melee. Archer: bow first.", width, pl -> {
                w.weapon = w.weapon.next();
                w.save();
                w.holdTool();
                settler(plugin, pl, w);
            }));
            if (w.stance == Guard.Stance.DEFENSIVE) {
                buttons.add(button(plugin, "Set post here", NamedTextColor.WHITE, "This guard will stand where you stand now", width, pl -> {
                    Town t = w.town();
                    if (t == null || !t.contains(pl.getLocation(), plugin.settings().townRadius())) {
                        pl.sendActionBar(Component.text("Stand inside the settlement.", NamedTextColor.YELLOW));
                        return;
                    }
                    w.post = pl.getLocation().getBlock().getLocation();
                    w.save();
                    pl.sendActionBar(Component.text(w.name + " guards this spot now.", NamedTextColor.GREEN));
                }));
                buttons.add(button(plugin, "Guard radius: " + w.guardRadius, NamedTextColor.WHITE, "How far from its post it fights", width, pl -> guardRadius(plugin, pl, w)));
            }
        }
        buttons.add(button(plugin, "Inventory", NamedTextColor.WHITE, "What it wears and carries", width, pl -> inventory(plugin, pl, w)));
        if (wp != null) {
            buttons.add(button(plugin, "Leave workplace", NamedTextColor.WHITE, "Stops working here and looks for another workplace", width, pl -> {
                w.leaveWorkplace();
                settler(plugin, pl, w);
            }));
        }
        buttons.add(button(plugin, "Dismiss", NamedTextColor.RED, "Turns it back into a villager. It drops its things.", width, pl -> {
            plugin.dismiss(w);
            pl.sendActionBar(Component.text(w.name + " is a normal villager again.", NamedTextColor.YELLOW));
        }));
        show(p, w.name, body, List.of(), DialogType.multiAction(buttons).columns(2).exitAction(close(plugin)).build());
    }

    private static void guardRadius(VillagerWorkersPlugin plugin, Player p, Worker w) {
        DialogInput r = DialogInput.numberRange("radius", Component.text("Guard radius"), 4, 48)
                .step(1f).initial((float) Math.max(4, Math.min(48, w.guardRadius))).width(200).build();
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 100,
                click(plugin, (view, audience) -> {
                    Float v = view.getFloat("radius");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (v != null) w.guardRadius = Math.max(4, Math.min(48, Math.round(v)));
                        w.save();
                        settler(plugin, pl, w);
                    });
                }, ONCE));
        show(p, "Guard radius", List.of(), List.of(r),
                DialogType.confirmation(save, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> settler(plugin, pl, w))));
    }

    private static void renameSettler(VillagerWorkersPlugin plugin, Player p, Worker w) {
        DialogInput name = DialogInput.text("name", Component.text("Name")).initial(w.name).maxLength(20).width(200).build();
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 100,
                click(plugin, (view, audience) -> {
                    String n = view.getText("name");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (n != null && !n.isBlank()) w.name = n.trim();
                        w.save();
                        w.applyLook();
                        settler(plugin, pl, w);
                    });
                }, ONCE));
        show(p, "Rename", List.of(), List.of(name),
                DialogType.confirmation(save, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> settler(plugin, pl, w))));
    }

    /** What a settler wears and carries. */
    private static void inventory(VillagerWorkersPlugin plugin, Player p, Worker w) {
        List<String> armor = new ArrayList<>();
        for (ItemStack it : w.body.getEquipment().getArmorContents()) {
            if (it != null && !it.getType().isAir()) armor.add(FarmerJob.nice(it.getType()));
        }
        Collections.reverse(armor); // helmet first
        List<String> weapons = new ArrayList<>();
        for (ItemStack it : w.pockets.getContents()) {
            if (it != null && (Combat.isBow(it.getType()) || Combat.damage(it.getType()) >= 2.5)) weapons.add(FarmerJob.nice(it.getType()));
        }
        int used = 0;
        for (ItemStack it : w.pockets.getContents()) if (it != null && !it.getType().isAir()) used++;
        List<DialogBody> body = new ArrayList<>();
        body.add(line("Armor: " + (armor.isEmpty() ? "none" : String.join(", ", armor)), NamedTextColor.WHITE));
        body.add(line("Weapons: " + (weapons.isEmpty() ? "none" : String.join(", ", weapons)), NamedTextColor.WHITE));
        body.add(DialogBody.plainMessage(Component.text("Pockets (" + used + "/" + w.pockets.getSize() + "): " + carrying(w), NamedTextColor.WHITE), 300));
        show(p, w.name + "'s inventory", body, List.of(),
                DialogType.notice(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> settler(plugin, pl, w))));
    }

    private static String carrying(Worker w) {
        Map<Material, Integer> totals = new LinkedHashMap<>();
        for (ItemStack it : w.pockets.getContents()) {
            if (it != null && !it.getType().isAir()) totals.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        if (totals.isEmpty()) return "nothing";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : totals.entrySet()) parts.add(e.getValue() + " " + FarmerJob.nice(e.getKey()));
        return String.join(", ", parts);
    }

    // ---- Hire a wild villager ----

    static void hire(VillagerWorkersPlugin plugin, Player p, Villager v, Town t) {
        List<String> names = plugin.settings().names();
        String suggestion = names.get(new Random().nextInt(names.size()));
        DialogInput name = DialogInput.text("name", Component.text("Name")).initial(suggestion).maxLength(20).width(200).build();
        ActionButton yes = ActionButton.create(Component.text("Hire", NamedTextColor.GREEN), null, 100,
                click(plugin, (view, audience) -> {
                    String chosen = view.getText("name");
                    if (audience instanceof Player pl) Bukkit.getScheduler().runTask(plugin, () -> plugin.hire(pl, v, t, chosen));
                }, ONCE));
        ActionButton no = button(plugin, "Cancel", null, null, 100, pl -> {});
        show(p, "Hire villager?", List.of(),
                List.of(name), DialogType.confirmation(yes, no));
    }
}
