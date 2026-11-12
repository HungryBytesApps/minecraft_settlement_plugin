package settlement;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
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

    static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
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
    static DialogAction click(SettlementPlugin plugin, DialogActionCallback cb, ClickCallback.Options options) {
        return DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player p)) {
                cb.accept(view, audience);
                return;
            }
            Integer before = shown.get(p.getUniqueId());
            cb.accept(view, audience);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && Objects.equals(before, shown.get(p.getUniqueId()))) {
                    // Nothing new opened: close the "waiting for server" screen.
                    p.closeDialog();
                    p.closeInventory();
                    Worker.release(plugin, p); // settlers waiting for you carry on
                }
            });
        }, options);
    }

    static ActionButton button(SettlementPlugin plugin, String text, NamedTextColor color, String tip,
                                       int width, Consumer<Player> onClick) {
        return ActionButton.create(Component.text(text, color), tip == null ? null : Component.text(tip), width,
                click(plugin, (view, audience) -> {
                    if (audience instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> onClick.accept(p));
                }, ONCE));
    }

    private static ActionButton close(SettlementPlugin plugin) {
        return button(plugin, "Close", null, null, 100, pl -> {});
    }

    static DialogBody line(String text, NamedTextColor color) {
        return DialogBody.plainMessage(Component.text(text, color));
    }

    static void show(Player p, String title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
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

    static void foundTown(SettlementPlugin plugin, Player p, Block bell) {
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

    /** The bell: the settlement's rules and settings (recruiting, border, jobs, chests, defense, consumption). */
    static void bell(SettlementPlugin plugin, Player p, Town t) {
        Settings s = plugin.settings();
        List<DialogBody> body = new ArrayList<>();
        body.add(stats(t, s));
        boolean chestsOn = t.chestDefault && t.chestRules.isEmpty();

        int w = 160;
        List<ActionButton> buttons = new ArrayList<>();
        ActionButton getLedger = button(plugin, "Get a ledger", NamedTextColor.WHITE, "The book to manage your settlers, workstations and storage", w, pl -> {
            if (Ledger.has(pl)) {
                pl.sendActionBar(Component.text("You already have a Settlement Ledger.", NamedTextColor.YELLOW));
                return;
            }
            for (ItemStack left : pl.getInventory().addItem(Ledger.create()).values()) pl.getWorld().dropItem(pl.getLocation(), left);
            pl.sendActionBar(Component.text("Here's your Settlement Ledger.", NamedTextColor.GREEN));
        });
        ActionButton notify = button(plugin, "Notifications", NamedTextColor.WHITE, "Which messages you get in the chat, and whether the alarm bell rings", w,
                pl -> notifications(plugin, pl, t));
        Borders.Mode border = plugin.borders().mode(p);
        ActionButton showBorder = button(plugin, "Show border: " + border.label,
                border == Borders.Mode.OFF ? NamedTextColor.RED : border == Borders.Mode.ON ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                "Particles along the border, just for you. Click to switch: When holding ledger / On / Off", w, pl -> {
                    plugin.borders().cycle(pl);
                    bell(plugin, pl, t);
                });
        if (!t.isOwner(p)) { // members: the bell's rules are the founder's; they run things with the ledger
            body.add(line("You help run this settlement. Use the ledger to manage it.", NamedTextColor.GRAY));
            show(p, t.name, body, List.of(), DialogType.multiAction(List.of(getLedger, notify, showBorder)).columns(2).exitAction(close(plugin)).build());
            return;
        }
        buttons.add(button(plugin, "Recruit settler", NamedTextColor.GREEN, "Costs " + costText(s.recruitCostFor(t.settlers.size()))
                + ". Every new settler costs a bit more.", w, pl -> {
            plugin.recruit(pl, t);
            bell(plugin, pl, t);
        }));
        buttons.add(button(plugin, "Rename settlement", NamedTextColor.WHITE, "Give the settlement a new name", w, pl -> renameTown(plugin, pl, t)));
        buttons.add(notify);
        buttons.add(button(plugin, "Auto assign jobs: " + (t.autoJobs ? "On" : "Off"), t.autoJobs ? NamedTextColor.GREEN : NamedTextColor.RED,
                "On: a settler without a job joins the workstation with the fewest workers. Off: you choose who works where.",
                w, pl -> {
                    t.autoJobs = !t.autoJobs;
                    plugin.towns().save();
                    bell(plugin, pl, t);
                }));
        buttons.add(button(plugin, "Use all chests: " + (chestsOn ? "On" : "Off"), chestsOn ? NamedTextColor.GREEN : NamedTextColor.RED,
                "On: settlers use every chest. Off: only the chests you allow (ledger on a chest).", w, pl -> {
                    boolean now = !(t.chestDefault && t.chestRules.isEmpty());
                    t.allChests(now);
                    plugin.towns().save();
                    bell(plugin, pl, t);
                }));
        buttons.add(button(plugin, "When attacked: " + t.reaction.label,
                t.reaction == Town.Reaction.CALL_TO_ARMS ? NamedTextColor.GOLD : NamedTextColor.WHITE,
                "What workers do when monsters come: hide (take cover), or grab a weapon from storage and fight (call to arms)", w, pl -> {
                    t.reaction = t.reaction == Town.Reaction.TAKE_COVER ? Town.Reaction.CALL_TO_ARMS : Town.Reaction.TAKE_COVER;
                    plugin.towns().save();
                    bell(plugin, pl, t);
                }));
        buttons.add(button(plugin, "Settlers consumption: " + t.consumption.label,
                t.consumption == Town.Consumption.OFF ? NamedTextColor.RED : t.consumption == Town.Consumption.FOOD ? NamedTextColor.YELLOW : NamedTextColor.GREEN,
                "What hurt settlers may take from storage to heal. Off: nothing, they only heal slowly by themselves. "
                        + "Food only: food that fits their wounds. Food & Potions: guards also drink healing potions in a fight. Guards always go first.",
                w, pl -> {
                    t.consumption = t.consumption.next();
                    plugin.towns().save();
                    bell(plugin, pl, t);
                }));
        buttons.add(getLedger);
        buttons.add(showBorder);
        // On their own rows at the bottom.
        buttons.add(button(plugin, "Radius: " + t.radius + (t.expansions.isEmpty() ? "" : " (+" + t.expansions.size() + " expansions)"), NamedTextColor.WHITE,
                "Make the settlement bigger or smaller. Place more bells inside it for expansions.", 130, pl -> radius(plugin, pl, t)));
        buttons.add(button(plugin, "Members (" + t.members.size() + ")", NamedTextColor.WHITE,
                "Players who help run the settlement with the ledger (settlers, workstations, storage targets, hiring)", 130, pl -> members(plugin, pl, t)));
        buttons.add(button(plugin, "Disband settlement", NamedTextColor.RED, "Removes the settlement. All settlers drop their things and leave.", 130, pl -> disband(plugin, pl, t)));
        show(p, t.name, body, List.of(), DialogType.multiAction(buttons).columns(2).exitAction(close(plugin)).build());
    }

    // ---- Size: radius and expansions ----

    /** The main square's radius. Applied when you go back (or close). */
    static void radius(SettlementPlugin plugin, Player p, Town t) {
        List<DialogBody> body = new ArrayList<>();
        body.add(line("The settlement is a square around this bell, this many blocks to every side.", NamedTextColor.GRAY));
        body.add(line("Expansions: place a bell inside the settlement and right-click it.", NamedTextColor.GRAY));
        List<DialogInput> inputs = List.of(DialogInput.numberRange("radius", Component.text("Radius"), Town.MAIN_MIN, Town.MAIN_MAX)
                .step(1f).initial((float) t.radius).width(260).build());
        ActionButton back = ActionButton.create(Component.text("Back"), null, 100, click(plugin, (view, audience) -> {
            if (!(audience instanceof Player pl)) return;
            Float v = view.getFloat("radius");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (v != null && Math.round(v) != t.radius) {
                    int r = Math.round(v);
                    String why = Area.check(plugin, t, null, r);
                    if (why != null) pl.sendActionBar(Component.text(why, NamedTextColor.RED));
                    else {
                        t.radius = r;
                        Area.changed(plugin, t);
                        plugin.borders().flash(pl);
                        pl.sendActionBar(Component.text("Radius is now " + r + ".", NamedTextColor.GREEN));
                    }
                }
                bell(plugin, pl, t);
            });
        }, ONCE));
        show(p, "Radius", body, inputs, DialogType.notice(back));
    }

    /** A new bell inside the settlement: make it an expansion? */
    static void newExpansion(SettlementPlugin plugin, Player p, Town t, Block bell) {
        int r = Area.newExpansionRadius(plugin, t, bell);
        if (r < 0) {
            p.sendActionBar(Component.text("No room for an expansion here (another settlement is too close).", NamedTextColor.RED));
            return;
        }
        ActionButton yes = button(plugin, "Create expansion", NamedTextColor.GREEN, null, 150, pl -> {
            if (bell.getType() != Material.BELL || plugin.towns().atExpansionBell(bell) != null) return;
            Town.Expansion x = new Town.Expansion(bell.getLocation(), r);
            t.expansions.add(x);
            Area.changed(plugin, t);
            plugin.borders().flash(pl);
            expansion(plugin, pl, t, x);
        });
        show(p, "Expand " + t.name + "?", List.of(line("This bell adds a square of its own to the settlement (radius " + r
                + ", change it after). Break the bell to remove it.", NamedTextColor.GRAY)), List.of(),
                DialogType.confirmation(yes, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> {})));
    }

    /** An expansion bell: its radius (applied when you close) and remove. */
    static void expansion(SettlementPlugin plugin, Player p, Town t, Town.Expansion x) {
        List<DialogBody> body = List.of(line("An expansion of " + t.name + ": a square around this bell.", NamedTextColor.GRAY));
        List<DialogInput> inputs = List.of(DialogInput.numberRange("radius", Component.text("Radius"), Town.EXP_MIN, Town.EXP_MAX)
                .step(1f).initial((float) Math.max(Town.EXP_MIN, Math.min(Town.EXP_MAX, x.radius))).width(260).build());
        java.util.function.BiConsumer<DialogResponseView, Player> apply = (view, pl) -> {
            Float v = view.getFloat("radius");
            if (v == null || Math.round(v) == x.radius || !t.expansions.contains(x)) return;
            String why = Area.check(plugin, t, x, Math.round(v));
            if (why != null) pl.sendActionBar(Component.text(why, NamedTextColor.RED));
            else {
                x.radius = Math.round(v);
                Area.changed(plugin, t);
                plugin.borders().flash(pl);
                pl.sendActionBar(Component.text("Expansion radius is now " + x.radius + ".", NamedTextColor.GREEN));
            }
        };
        ActionButton remove = wbutton(plugin, (view, pl) -> {}, "Remove expansion", NamedTextColor.RED, "The bell stays; the square is no longer part of the settlement", 150, pl -> {
            String why = Area.checkRemove(t, x);
            if (why != null) {
                pl.sendActionBar(Component.text(why, NamedTextColor.RED));
                return;
            }
            t.expansions.remove(x);
            Area.changed(plugin, t);
            pl.sendActionBar(Component.text("Expansion removed.", NamedTextColor.YELLOW));
        });
        ActionButton done = wbutton(plugin, apply, "Done", NamedTextColor.WHITE, null, 100, pl -> {});
        show(p, "Expansion", body, inputs, DialogType.multiAction(List.of(remove)).columns(1).exitAction(done).build());
    }

    /** Members: who helps run the settlement (founder only). */
    static void members(SettlementPlugin plugin, Player p, Town t) {
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(plugin, "+ Add player", NamedTextColor.GREEN, "Pick an online player", 200, pl -> addMember(plugin, pl, t)));
        for (UUID id : new ArrayList<>(t.members)) {
            String n = org.bukkit.Bukkit.getOfflinePlayer(id).getName();
            buttons.add(button(plugin, (n == null ? id.toString().substring(0, 8) : n) + "  ✖", NamedTextColor.WHITE, "Remove as member", 200, pl -> {
                t.members.remove(id);
                plugin.towns().save();
                members(plugin, pl, t);
            }));
        }
        List<DialogBody> body = List.of(line("Members can use the ledger (settlers, workstations, storage targets, chests, hiring) and get a ledger at the bell. Not: the bell settings, dismissing settlers.", NamedTextColor.GRAY));
        show(p, "Members", body, List.of(), DialogType.multiAction(buttons).columns(1)
                .exitAction(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> bell(plugin, pl, t))).build());
    }

    private static void addMember(SettlementPlugin plugin, Player p, Town t) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Player o : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(t.owner) || t.members.contains(o.getUniqueId())) continue;
            buttons.add(button(plugin, o.getName(), NamedTextColor.WHITE, null, 200, pl -> {
                t.members.add(o.getUniqueId());
                plugin.towns().save();
                o.sendMessage(Component.text("You can now help run " + t.name + ".", NamedTextColor.GREEN));
                members(plugin, pl, t);
            }));
        }
        List<DialogBody> body = buttons.isEmpty() ? List.of(line("No other players online.", NamedTextColor.GRAY)) : List.of();
        show(p, "Add member", body, List.of(), DialogType.multiAction(buttons.isEmpty()
                        ? List.of(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> members(plugin, pl, t))) : buttons)
                .columns(1).exitAction(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> members(plugin, pl, t))).build());
    }

    /** Per player: which messages go to the chat, the alarm bell, the border. */
    static void notifications(SettlementPlugin plugin, Player p, Town t) {
        int w = 200;
        List<ActionButton> buttons = new ArrayList<>();
        for (Notify.Kind k : Notify.Kind.values()) {
            boolean on = Notify.on(p, k);
            buttons.add(button(plugin, k.label + ": " + (on ? "On" : "Off"), on ? NamedTextColor.GREEN : NamedTextColor.RED, null, w, pl -> {
                Notify.toggle(pl, k);
                notifications(plugin, pl, t);
            }));
        }
        show(p, "Notifications", List.of(), List.of(), DialogType.multiAction(buttons).columns(2)
                .exitAction(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> bell(plugin, pl, t))).build());
    }

    /** The ledger: running the settlement day to day (settlers, workstations, storage). */
    static void ledger(SettlementPlugin plugin, Player p, Town t) {
        Settings s = plugin.settings();
        List<DialogBody> body = new ArrayList<>();
        body.add(stats(t, s));

        int w = 160;
        List<ActionButton> buttons = new ArrayList<>();
        w = 120;
        buttons.add(button(plugin, "Settlers", NamedTextColor.WHITE, "All settlers: name, job, health, what they do", w, pl -> settlers(plugin, pl, t)));
        buttons.add(button(plugin, "Workstations", NamedTextColor.WHITE, "All workstations and who works there", w, pl -> workstations(plugin, pl, t)));
        buttons.add(button(plugin, "Storage targets", NamedTextColor.WHITE, "How much of each product to keep in storage", w, pl -> TargetsMenu.open(plugin, pl, t)));
        buttons.add(button(plugin, "Problems (" + t.log.size() + ")", t.log.size() > 0 ? NamedTextColor.YELLOW : NamedTextColor.WHITE,
                "What went wrong lately: missing tools and materials, full storage, places they can't reach, settlers stuck...", w,
                pl -> TownLog.open(plugin, pl, t, 0)));
        show(p, t.name, body, List.of(), DialogType.multiAction(buttons).columns(2).exitAction(close(plugin)).build());
    }

    /** All workstations: click one for its menu. */
    static void workstations(SettlementPlugin plugin, Player p, Town t) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Town.Workplace wp : t.workplaces.values()) {
            if (wp.job() == Worker.Job.NONE) continue;
            String label = station(wp.job()) + " — " + (wp.workers.isEmpty() ? "nobody" : wp.workers.size() + (wp.workers.size() == 1 ? " worker" : " workers"))
                    + (wp.enabled ? "" : " (off)");
            buttons.add(button(plugin, label, wp.enabled ? NamedTextColor.WHITE : NamedTextColor.GRAY, "At " + Keys.pos(wp.loc), 300,
                    pl -> openWorkplace(plugin, pl, t, wp, x -> workstations(plugin, x, t))));
        }
        List<DialogBody> body = buttons.isEmpty() ? List.of(line("No workstations yet. Place one in the settlement.", NamedTextColor.GRAY)) : List.of();
        show(p, "Workstations", body, List.of(), DialogType.multiAction(buttons.isEmpty()
                ? List.of(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> ledger(plugin, pl, t))) : buttons)
                .columns(1).exitAction(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> ledger(plugin, pl, t))).build());
    }

    /** "Farmer (Composter)", "Smith (Smithing Table)". */
    static String station(Worker.Job job) {
        return job.title + " (" + titleCase(FarmerJob.nice(job.block)) + ")";
    }

    static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String part : s.split(" ")) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    private static DialogBody stats(Town t, Settings s) {
        int places = 0, free = 0;
        for (Town.Workplace wp : t.workplaces.values()) {
            if (wp.job() == Worker.Job.NONE) continue; // no longer a workstation (blown up, or a barrel whose water is gone)
            places++;
            if (wp.enabled && wp.workers.isEmpty()) free++;
        }
        return line("Settlers: " + t.settlers.size() + "/" + s.maxSettlers() + "   ·   Workplaces: " + places + " (" + free + " free)", NamedTextColor.GRAY);
    }

    private static String costText(List<ItemStack> cost) {
        if (cost.isEmpty()) return "free";
        List<String> parts = new ArrayList<>();
        for (ItemStack it : cost) parts.add(it.getAmount() + " " + FarmerJob.nice(it.getType()));
        return String.join(", ", parts);
    }

    private static void renameTown(SettlementPlugin plugin, Player p, Town t) {
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

    private static void disband(SettlementPlugin plugin, Player p, Town t) {
        ActionButton yes = button(plugin, "Disband", NamedTextColor.RED, null, 100, pl -> {
            plugin.disband(t);
            pl.sendActionBar(Component.text(t.name + " is no more.", NamedTextColor.YELLOW));
        });
        show(p, "Disband " + t.name + "?", List.of(),
                List.of(), DialogType.confirmation(yes, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> bell(plugin, pl, t))));
    }

    // ---- Settlers overview ----

    /** One settler as a line: name, [job], health, what it's doing. */
    static Component row(Worker w) {
        String doing = w.doing() + (w.guard && w.unarmed() ? " — no weapon!" : "");
        if (doing.length() > 38) doing = doing.substring(0, 37) + "…";
        return Component.text(w.name + " ", NamedTextColor.WHITE)
                .append(Component.text("[" + w.title() + "]", w.guard ? NamedTextColor.GOLD : NamedTextColor.YELLOW))
                .append(Component.text("  " + w.healthText(), w.hurtBelow(50) ? NamedTextColor.RED : w.hurtBelow(100) ? NamedTextColor.GOLD : NamedTextColor.GREEN))
                .append(Component.text("  " + doing, w.guard && w.unarmed() ? NamedTextColor.RED : NamedTextColor.GRAY));
    }

    /** All settlers, one line each; each line opens that settler's menu. */
    static void settlers(SettlementPlugin plugin, Player p, Town t) {
        List<ActionButton> rows = new ArrayList<>();
        for (Map.Entry<UUID, String> e : t.settlers.entrySet()) {
            Worker w = plugin.workers().get(e.getKey());
            if (w == null) {
                rows.add(ActionButton.create(Component.text(e.getValue() + "  ", NamedTextColor.DARK_GRAY).append(Component.text("far away", NamedTextColor.DARK_GRAY)),
                        null, 380, click(plugin, (view, audience) -> {
                            if (audience instanceof Player pl) Bukkit.getScheduler().runTask(plugin, () -> settlers(plugin, pl, t));
                        }, ONCE)));
                continue;
            }
            rows.add(ActionButton.create(row(w), Component.text("Open " + w.name + "'s menu"), 380, click(plugin, (view, audience) -> {
                if (audience instanceof Player pl) Bukkit.getScheduler().runTask(plugin, () -> openSettler(plugin, pl, w, x -> settlers(plugin, x, t)));
            }, ONCE)));
        }
        List<DialogBody> body = rows.isEmpty() ? List.of(line("No settlers yet. Recruit them at the bell.", NamedTextColor.GRAY)) : List.of();
        ActionButton back = button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> ledger(plugin, pl, t));
        if (rows.isEmpty()) {
            show(p, "Settlers", body, List.of(), DialogType.notice(back));
            return;
        }
        show(p, "Settlers (" + t.settlers.size() + ")", body, List.of(), DialogType.multiAction(rows).columns(1).exitAction(back).build());
    }

    // ---- Chest ----

    private static final Map<UUID, Consumer<Player>> chestBack = new HashMap<>();

    /** A chest's menu, opened at the chest: Close. */
    static void chest(SettlementPlugin plugin, Player p, Town t, Block chest) {
        chestBack.remove(p.getUniqueId());
        showChest(plugin, p, t, chest);
    }

    /** A chest's menu, opened from another menu: Back goes there. */
    static void openChest(SettlementPlugin plugin, Player p, Town t, Block chest, Consumer<Player> back) {
        chestBack.put(p.getUniqueId(), back);
        showChest(plugin, p, t, chest);
    }

    static void showChest(SettlementPlugin plugin, Player p, Town t, Block chest) {
        java.util.UUID priv = PrivateChests.owner(chest);
        if (priv != null) { // private: only its owner can change it; settlers never use it
            boolean mine = priv.equals(p.getUniqueId());
            List<DialogBody> body = List.of(line("Private chest of " + PrivateChests.ownerName(priv)
                    + ". Only they can open it; settlers never use it.", NamedTextColor.GRAY));
            List<ActionButton> buttons = new ArrayList<>();
            if (mine) buttons.add(button(plugin, "Private: On", NamedTextColor.GREEN, "Click to make it a normal chest again", 170, pl -> {
                PrivateChests.set(chest, null);
                t.invalidate();
                showChest(plugin, pl, t, chest);
            }));
            else buttons.add(button(plugin, "OK", NamedTextColor.GRAY, null, 100, pl -> {}));
            show(p, "Chest", body, List.of(), DialogType.multiAction(buttons).columns(1).exitAction(chestExit(plugin, p)).build());
            return;
        }
        boolean[] r = t.rule(chest);
        boolean own = t.hasOwnRule(chest);
        List<DialogBody> body = List.of(line(own ? "Own rules" : "Settlement default", NamedTextColor.GRAY));
        int w = 170;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(plugin, "Settlers may put in: " + (r[0] ? "On" : "Off"), r[0] ? NamedTextColor.GREEN : NamedTextColor.RED,
                "May settlers put their harvest and other things in this chest?", w, pl -> {
                    boolean[] cur = t.rule(chest);
                    t.setRule(chest, new boolean[]{!cur[0], cur[1]});
                    plugin.towns().save();
                    showChest(plugin, pl, t, chest);
                }));
        buttons.add(button(plugin, "Settlers may take out: " + (r[1] ? "On" : "Off"), r[1] ? NamedTextColor.GREEN : NamedTextColor.RED,
                "May settlers take seeds, food, weapons and other things out of this chest?", w, pl -> {
                    boolean[] cur = t.rule(chest);
                    t.setRule(chest, new boolean[]{cur[0], !cur[1]});
                    plugin.towns().save();
                    showChest(plugin, pl, t, chest);
                }));
        Town.Filter filter = t.filter(chest);
        buttons.add(button(plugin, "Filter: " + filter.mode.label,
                filter.mode == Town.FilterMode.OFF ? NamedTextColor.RED : filter.mode == Town.FilterMode.PREFER ? NamedTextColor.YELLOW : NamedTextColor.GREEN,
                "Which items settlers put here. Prefer: the chosen items go here first, others may too. Only: nothing else goes in. Click to switch.", w, pl -> {
                    Town.Filter f = t.editFilter(chest);
                    f.mode = f.mode.next();
                    plugin.towns().save();
                    showChest(plugin, pl, t, chest);
                }));
        buttons.add(button(plugin, "Filter items (" + filter.items.size() + ")", NamedTextColor.WHITE, "Choose the items for this chest's filter", w,
                pl -> FilterMenu.categories(plugin, pl, t, chest)));
        buttons.add(button(plugin, "Private: Off", NamedTextColor.RED,
                "On: only you can open, change or break it. Settlers never use it, whatever the settlement's settings. Hoppers can't take from it.", w, pl -> {
                    PrivateChests.set(chest, pl.getUniqueId());
                    t.invalidate();
                    showChest(plugin, pl, t, chest);
                }));
        if (own) {
            buttons.add(button(plugin, "Use settlement default", NamedTextColor.WHITE, "Forget this chest's own rules", w, pl -> {
                t.setRule(chest, null);
                plugin.towns().save();
                showChest(plugin, pl, t, chest);
            }));
        }
        show(p, "Chest", body, List.of(), DialogType.multiAction(buttons).columns(1).exitAction(chestExit(plugin, p)).build());
    }

    private static ActionButton chestExit(SettlementPlugin plugin, Player p) {
        Consumer<Player> back = chestBack.get(p.getUniqueId());
        return back != null ? button(plugin, "Back", NamedTextColor.GRAY, null, 100, back) : close(plugin);
    }

    // ---- Workplace ----

    /** Where Back goes from a workplace's / settler's menu (opened from a list); none: Close. Per player. */
    private static final Map<UUID, Consumer<Player>> workplaceBack = new HashMap<>(), settlerBack = new HashMap<>();

    /** A workstation's menu, opened at the block itself: Close. */
    static void workplace(SettlementPlugin plugin, Player p, Town t, Town.Workplace wp) {
        workplaceBack.remove(p.getUniqueId());
        showWorkplace(plugin, p, t, wp);
    }

    /** A workstation's menu, opened from a list: Back goes there. */
    static void openWorkplace(SettlementPlugin plugin, Player p, Town t, Town.Workplace wp, Consumer<Player> back) {
        workplaceBack.put(p.getUniqueId(), back);
        showWorkplace(plugin, p, t, wp);
    }

    static void showWorkplace(SettlementPlugin plugin, Player p, Town t, Town.Workplace wp) {
        Settings s = plugin.settings();
        Worker.Job job = wp.job();
        List<DialogBody> body = new ArrayList<>();
        if (wp.workers.isEmpty()) body.add(line(job == Worker.Job.FISHER ? "Nobody fishes here yet: a normal barrel until then." : "Nobody works here yet.", NamedTextColor.GRAY));
        for (java.util.UUID id : wp.workers) {
            Worker wk = plugin.workers().get(id);
            String who = wk != null ? wk.name + (wk.workStatus.isEmpty() ? "" : " — " + wk.workStatus) : t.settlers.getOrDefault(id, "?") + " (far away)";
            body.add(line(who, NamedTextColor.GRAY));
        }
        if (job.usesArea) body.add(line("Works in the whole settlement.", NamedTextColor.DARK_GRAY));
        if (job == Worker.Job.HERDER) body.add(line("Adults to keep per animal: breeds up to it, slaughters the rest. Unchecked: left alone.", NamedTextColor.GRAY));
        if (job == Worker.Job.FISHER) body.add(line(Fishing.anyOpen(wp.loc.getBlock())
                ? "Open water: fish, junk and treasure." : "Not open water (needs 5x5, 2 deep, nothing on top): fish and junk, no treasure.", NamedTextColor.GRAY));
        if (job == Worker.Job.BREWER) body.add(line("Brews the potions you set targets for (Storage targets). Needs water nearby.", NamedTextColor.GRAY));

        // Sliders: applied when you click any button or close the window (no Save button).
        List<DialogInput> inputs = new ArrayList<>();
        if (job == Worker.Job.HERDER) {
            // Per kind of animal: a checkbox (look after them at all) and the adults to keep (2-20).
            for (org.bukkit.entity.EntityType kind : HerderJob.FOOD.keySet()) {
                String id = kind.name().toLowerCase(Locale.ROOT);
                inputs.add(DialogInput.bool("herding_" + id, Component.text(HerderJob.kindName(kind))).initial(wp.herding(kind)).build());
                inputs.add(DialogInput.numberRange("herd_" + id, Component.text("Adults"), 2, 20)
                        .step(1f).initial((float) wp.herdSetting(kind)).width(220).build());
            }
        }
        inputs.add(DialogInput.bool("in_use", Component.text("In use")).initial(wp.enabled).build());
        java.util.function.BiConsumer<DialogResponseView, Player> apply = (view, pl) -> {
            boolean changed = false;
            if (job == Worker.Job.HERDER) {
                for (org.bukkit.entity.EntityType kind : HerderJob.FOOD.keySet()) {
                    String id = kind.name().toLowerCase(Locale.ROOT);
                    Float h = view.getFloat("herd_" + id);
                    Boolean on = view.getBoolean("herding_" + id);
                    if (h != null) {
                        int v = Math.max(2, Math.min(20, Math.round(h)));
                        if (!Integer.valueOf(v).equals(wp.herds.get(kind.name()))) {
                            wp.herds.put(kind.name(), v);
                            changed = true;
                        }
                    }
                    if (on != null && on != wp.options.getOrDefault("HERD_" + kind.name(), true)) {
                        wp.options.put("HERD_" + kind.name(), on);
                        changed = true;
                    }
                }
            }
            Boolean use = view.getBoolean("in_use");
            if (use != null && use != wp.enabled) {
                wp.enabled = use;
                changed = true;
            }
            if (changed) plugin.towns().save();
        };

        int w = 150;
        List<ActionButton> buttons = new ArrayList<>();
        if (job == Worker.Job.HERDER) {
            boolean eggs = wp.isOn("EGGS");
            buttons.add(wbutton(plugin, apply, "Throw eggs: " + (eggs ? "On" : "Off"), eggs ? NamedTextColor.GREEN : NamedTextColor.RED,
                    "Throw eggs into the chickens' pen for chicks while there are fewer chickens than wanted, only with eggs above their storage target", w, pl -> {
                        wp.options.put("EGGS", !wp.isOn("EGGS"));
                        plugin.towns().save();
                        showWorkplace(plugin, pl, t, wp);
                    }));
            boolean bees = wp.isOn("BEES");
            buttons.add(wbutton(plugin, apply, "Bees: " + (bees ? "On" : "Off"), bees ? NamedTextColor.GREEN : NamedTextColor.RED,
                    "Harvest full beehives: honey (glass bottles) and honeycomb (shears), by storage target. Needs room for a campfire under the hive.", w, pl -> {
                        wp.options.put("BEES", !wp.isOn("BEES"));
                        plugin.towns().save();
                        showWorkplace(plugin, pl, t, wp);
                    }));
        }
        if (job == Worker.Job.FARMER) {
            FarmerJob.Compost compost = FarmerJob.compost(wp);
            buttons.add(wbutton(plugin, apply, "Make compost: " + compost.label, compost == FarmerJob.Compost.OFF ? NamedTextColor.RED : NamedTextColor.GREEN,
                    "Turns harvest into bone meal at this composter (only what the farmers harvested themselves, never things from storage). "
                            + "Off: no compost. On: keeps harvesting after the targets are reached, to make bone meal up to its storage target. "
                            + "Overflow only: just the harvest (and seeds) above the storage targets.", w, pl -> {
                        FarmerJob.setCompost(wp, FarmerJob.compost(wp).next());
                        plugin.towns().save();
                        showWorkplace(plugin, pl, t, wp);
                    }));
            boolean on = wp.isOn("BONEMEAL");
            buttons.add(wbutton(plugin, apply, "Use bone meal: " + (on ? "On" : "Off"), on ? NamedTextColor.GREEN : NamedTextColor.RED,
                    "Farmers use bone meal from storage on growing crops, only while the settlement still needs that crop", w, pl -> {
                        wp.options.put("BONEMEAL", !wp.isOn("BONEMEAL"));
                        plugin.towns().save();
                        showWorkplace(plugin, pl, t, wp);
                    }));
        }
        if (job == Worker.Job.LUMBERJACK) {
            boolean replant = wp.isOn("REPLANT");
            buttons.add(wbutton(plugin, apply, "Replant saplings: " + (replant ? "On" : "Off"), replant ? NamedTextColor.GREEN : NamedTextColor.RED,
                    "Plant a sapling where a tree was cut (saplings from the tree or from storage)", w, pl -> {
                        wp.options.put("REPLANT", !wp.isOn("REPLANT"));
                        plugin.towns().save();
                        showWorkplace(plugin, pl, t, wp);
                    }));
            boolean outside = wp.isOn("OUTSIDE");
            buttons.add(wbutton(plugin, apply, "Cut trees: " + (outside ? "Outside settlement" : "Inside settlement"), NamedTextColor.WHITE,
                    "Only real trees (logs on the ground with natural leaves), never your buildings", w, pl -> {
                        wp.options.put("OUTSIDE", !wp.isOn("OUTSIDE"));
                        plugin.towns().save();
                        showWorkplace(plugin, pl, t, wp);
                    }));
        }
        if (job == Worker.Job.FISHER) {
            int kept = 0;
            for (Material m : Fishing.JUNK) if (Fishing.keepJunk(wp, m)) kept++;
            buttons.add(wbutton(plugin, apply, "Junk filter (" + kept + " kept)", NamedTextColor.WHITE, "Which junk the fisher keeps; the rest goes back into the water", w,
                    pl -> Fishing.junkMenu(plugin, pl, t, wp)));
            // Without a fisher it's a normal barrel, with the settings of a chest.
            if (wp.workers.isEmpty()) buttons.add(wbutton(plugin, apply, "Chest settings", NamedTextColor.WHITE,
                    "Nobody fishes here, so this is a normal barrel: its storage settings", w, pl -> openChest(plugin, pl, t, wp.loc.getBlock(), x -> showWorkplace(plugin, x, t, wp))));
        }
        buttons.add(wbutton(plugin, apply, "Workers (" + wp.workers.size() + ")", NamedTextColor.GREEN, "Who works here: add or remove workers", w,
                pl -> workers(plugin, pl, t, wp)));
        Consumer<Player> back = workplaceBack.get(p.getUniqueId());
        ActionButton close = back != null ? wbutton(plugin, apply, "Back", NamedTextColor.GRAY, null, 100, back)
                : wbutton(plugin, apply, "Close", null, null, 100, pl -> {});
        show(p, job.title + "'s workplace", body, inputs, DialogType.multiAction(buttons).columns(2).exitAction(close).build());
    }

    /** A button that first applies the window's sliders. */
    private static ActionButton wbutton(SettlementPlugin plugin, java.util.function.BiConsumer<DialogResponseView, Player> apply,
                                        String text, NamedTextColor color, String tip, int width, Consumer<Player> onClick) {
        return ActionButton.create(Component.text(text, color), tip == null ? null : Component.text(tip), width,
                click(plugin, (view, audience) -> {
                    if (audience instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> {
                        apply.accept(view, p);
                        onClick.accept(p);
                    });
                }, ONCE));
    }

    /** Who works at this station: a row per worker (click: its menu) with a Remove button next to it, then "+ Add worker". */
    private static void workers(SettlementPlugin plugin, Player p, Town t, Town.Workplace wp) {
        List<ActionButton> rows = new ArrayList<>();
        for (java.util.UUID id : new ArrayList<>(wp.workers)) {
            Worker wk = plugin.workers().get(id);
            String name = wk != null ? wk.name : t.settlers.getOrDefault(id, "?");
            if (wk != null) {
                rows.add(ActionButton.create(row(wk), Component.text("Open " + name + "'s menu"), 280, click(plugin, (view, audience) -> {
                    if (audience instanceof Player pl) Bukkit.getScheduler().runTask(plugin, () -> openSettler(plugin, pl, wk, x -> workers(plugin, x, t, wp)));
                }, ONCE)));
            } else {
                rows.add(button(plugin, name + "  far away", NamedTextColor.DARK_GRAY, null, 280, pl -> workers(plugin, pl, t, wp)));
            }
            rows.add(button(plugin, "Remove", NamedTextColor.RED, "Take " + name + " off this workstation", 90, pl -> {
                Worker cur = plugin.workers().get(id);
                if (cur != null) cur.leaveWorkplace();
                wp.workers.remove(id);
                plugin.towns().save();
                pl.sendActionBar(Component.text(name + " no longer works here.", NamedTextColor.YELLOW));
                workers(plugin, pl, t, wp);
            }));
        }
        rows.add(button(plugin, "+ Add worker", NamedTextColor.GREEN, "Another settler works here too", 280, pl -> chooseWorker(plugin, pl, t, wp)));
        List<DialogBody> body = wp.workers.isEmpty() ? List.of(line("Nobody works here yet.", NamedTextColor.GRAY)) : List.of();
        show(p, "Workers: " + station(wp.job()), body, List.of(), DialogType.multiAction(rows).columns(2)
                .exitAction(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> showWorkplace(plugin, pl, t, wp))).build());
    }

    /** Add a settler: the free ones first; settlers with another job are in a separate list. */
    private static void chooseWorker(SettlementPlugin plugin, Player p, Town t, Town.Workplace wp) {
        chooseWorker(plugin, p, t, wp, false);
    }

    private static void chooseWorker(SettlementPlugin plugin, Player p, Town t, Town.Workplace wp, boolean busy) {
        List<ActionButton> buttons = new ArrayList<>();
        int others = 0;
        for (Worker w : plugin.workers().all()) {
            if (!t.id.equals(w.townId) || wp.workers.contains(w.body.getUniqueId())) continue;
            boolean free = !w.guard && w.job() == Worker.Job.NONE;
            if (!free) others++;
            if (free == busy) continue;
            String label = busy ? w.name + " (" + w.title() + ")" : w.name;
            String tip = busy ? w.name + " stops being a " + w.title().toLowerCase(Locale.ROOT) + " and works here instead" : w.name + " has no job yet";
            buttons.add(button(plugin, label, busy ? NamedTextColor.YELLOW : NamedTextColor.GREEN, tip, 200, pl -> {
                w.assign(wp);
                pl.sendActionBar(Component.text(w.name + " now works here.", NamedTextColor.GREEN));
                workers(plugin, pl, t, wp);
            }));
        }
        if (!busy && others > 0) {
            buttons.add(button(plugin, "Settlers with another job (" + others + ")", NamedTextColor.WHITE,
                    "They would leave their job to work here", 200, pl -> chooseWorker(plugin, pl, t, wp, true)));
        }
        List<DialogBody> body = new ArrayList<>();
        body.add(line(busy ? "These settlers leave their current job to work here." : "Free settlers (no job):", NamedTextColor.GRAY));
        if (buttons.isEmpty()) body.add(line(busy ? "Nobody else." : "Nobody is free.", NamedTextColor.DARK_GRAY));
        ActionButton back = button(plugin, "Back", NamedTextColor.GRAY, null, 100,
                pl -> { if (busy) chooseWorker(plugin, pl, t, wp, false); else workers(plugin, pl, t, wp); });
        if (buttons.isEmpty()) {
            show(p, "Add settler", body, List.of(), DialogType.notice(back));
            return;
        }
        show(p, busy ? "Settlers with another job" : "Add settler", body, List.of(), DialogType.multiAction(buttons).columns(1).exitAction(back).build());
    }

    // ---- Settler ----

    /** A settler's menu, opened by clicking the settler: Close. */
    static void settler(SettlementPlugin plugin, Player p, Worker w) {
        settlerBack.remove(p.getUniqueId());
        showSettler(plugin, p, w);
    }

    /** A settler's menu, opened from a list: Back goes there. */
    static void openSettler(SettlementPlugin plugin, Player p, Worker w, Consumer<Player> back) {
        settlerBack.put(p.getUniqueId(), back);
        showSettler(plugin, p, w);
    }

    private static void showSettler(SettlementPlugin plugin, Player p, Worker w) {
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
            showSettler(plugin, pl, w);
        }));
        if (w.guard) {
            buttons.add(button(plugin, "Stance: " + w.stance.label, NamedTextColor.WHITE, "Defensive: stays at its post. Patrol: walks the border.", width, pl -> {
                w.stance = w.stance.next();
                w.save();
                showSettler(plugin, pl, w);
            }));
            buttons.add(button(plugin, "Weapon: " + w.weapon.label, NamedTextColor.WHITE, "Auto: bow at range, melee up close. Melee. Archer: bow first.", width, pl -> {
                w.weapon = w.weapon.next();
                w.save();
                w.holdTool();
                showSettler(plugin, pl, w);
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
        buttons.add(button(plugin, "Inventory", NamedTextColor.WHITE, "What it wears and carries. Take things out or put things in.", width, pl -> SettlerInventory.open(pl, w)));
        if (wp != null) {
            buttons.add(button(plugin, "Leave workplace", NamedTextColor.WHITE, "Stops working here and looks for another workplace", width, pl -> {
                w.leaveWorkplace();
                showSettler(plugin, pl, w);
            }));
        }
        Town own = w.town();
        if (own == null || own.isOwner(p)) { // founder only
            buttons.add(button(plugin, "Dismiss", NamedTextColor.RED, "It drops its things, says goodbye and walks out of the settlement for good.", width, pl -> {
                plugin.dismiss(w);
                pl.sendActionBar(Component.text(w.name + " leaves the settlement.", NamedTextColor.YELLOW));
                Consumer<Player> back = settlerBack.get(pl.getUniqueId());
                if (back != null) back.accept(pl);
            }));
        }
        Consumer<Player> back = settlerBack.get(p.getUniqueId());
        show(p, w.name, body, List.of(), DialogType.multiAction(buttons).columns(2)
                .exitAction(back != null ? button(plugin, "Back", NamedTextColor.GRAY, null, 100, back) : close(plugin)).build());
    }

    private static void guardRadius(SettlementPlugin plugin, Player p, Worker w) {
        DialogInput r = DialogInput.numberRange("radius", Component.text("Guard radius"), 4, 48)
                .step(1f).initial((float) Math.max(4, Math.min(48, w.guardRadius))).width(200).build();
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 100,
                click(plugin, (view, audience) -> {
                    Float v = view.getFloat("radius");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (v != null) w.guardRadius = Math.max(4, Math.min(48, Math.round(v)));
                        w.save();
                        showSettler(plugin, pl, w);
                    });
                }, ONCE));
        show(p, "Guard radius", List.of(), List.of(r),
                DialogType.confirmation(save, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> showSettler(plugin, pl, w))));
    }

    private static void renameSettler(SettlementPlugin plugin, Player p, Worker w) {
        DialogInput name = DialogInput.text("name", Component.text("Name")).initial(w.name).maxLength(20).width(200).build();
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 100,
                click(plugin, (view, audience) -> {
                    String n = view.getText("name");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (n != null && !n.isBlank()) {
                            w.name = n.trim();
                            Town own = w.town();
                            if (own != null) {
                                own.settlers.put(w.body.getUniqueId(), w.name);
                                plugin.towns().save();
                            }
                        }
                        w.save();
                        w.applyLook();
                        showSettler(plugin, pl, w);
                    });
                }, ONCE));
        show(p, "Rename", List.of(), List.of(name),
                DialogType.confirmation(save, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> showSettler(plugin, pl, w))));
    }

    private static String nice(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- Hire a wild villager ----

    static void hire(SettlementPlugin plugin, Player p, Villager v, Town t) {
        ActionButton yes = button(plugin, "Hire", NamedTextColor.GREEN, "It becomes a settler of " + t.name, 100, pl -> plugin.hire(pl, v, t, null));
        ActionButton no = button(plugin, "Cancel", null, null, 100, pl -> {});
        show(p, "Hire villager?", List.of(), List.of(), DialogType.confirmation(yes, no));
    }
}
