package villagerworkers;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

    private static ActionButton button(VillagerWorkersPlugin plugin, String text, NamedTextColor color, String tip,
                                       int width, Consumer<Player> onClick) {
        return ActionButton.create(Component.text(text, color), tip == null ? null : Component.text(tip), width,
                DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> onClick.accept(p));
                }, ONCE));
    }

    private static ActionButton close() {
        return ActionButton.create(Component.text("Close"), null, 100, null);
    }

    private static DialogBody line(String text, NamedTextColor color) {
        return DialogBody.plainMessage(Component.text(text, color));
    }

    private static void show(Player p, String title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).inputs(inputs).canCloseWithEscape(true).build())
                .type(type));
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
                DialogAction.customClick((view, audience) -> {
                    String n = view.getText("name");
                    if (audience instanceof Player pl) Bukkit.getScheduler().runTask(plugin, () -> plugin.foundTown(pl, bell, n));
                }, ONCE));
        ActionButton no = ActionButton.create(Component.text("Not now"), null, 100, null);
        show(p, "Create settlement?", List.of(
                line("This bell becomes the center of your settlement.", NamedTextColor.GRAY),
                line("Settlers live, work and store things within " + plugin.settings().townRadius() + " blocks of it.", NamedTextColor.GRAY)),
                List.of(name), DialogType.confirmation(yes, no));
    }

    // ---- Town menu ----

    static void town(VillagerWorkersPlugin plugin, Player p, Town t) {
        Settings s = plugin.settings();
        int free = 0;
        for (Town.Workplace wp : t.workplaces.values()) if (wp.enabled && wp.worker == null) free++;
        List<DialogBody> body = new ArrayList<>();
        body.add(line(t.name, NamedTextColor.GOLD));
        body.add(line("Settlers: " + t.settlers.size() + " / " + s.maxSettlers(), NamedTextColor.WHITE));
        body.add(line("Workplaces: " + t.workplaces.size() + " (" + free + " free)", NamedTextColor.WHITE));
        body.add(line("Chests: " + (t.chestDefault ? "settlers may use them" : "closed to settlers")
                + (t.chestRules.isEmpty() ? "" : " (" + t.chestRules.size() + " exceptions)"), NamedTextColor.GRAY));
        body.add(line("Recruit cost: " + costText(s.recruitCost()), NamedTextColor.GRAY));

        int w = 150;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(plugin, "Recruit settler", NamedTextColor.GREEN, "Pay " + costText(s.recruitCost()) + "; a new settler walks into the settlement",
                w, pl -> plugin.recruit(pl, t)));
        buttons.add(button(plugin, "Targets…", NamedTextColor.WHITE, "How much the settlement wants of each crop", w, pl -> targets(plugin, pl, t)));
        buttons.add(button(plugin, "All chests ON", NamedTextColor.WHITE, "Settlers may use every chest in the settlement", w, pl -> {
            t.allChests(true);
            plugin.towns().save();
            pl.sendActionBar(Component.text("Settlers may now use all chests in the settlement.", NamedTextColor.GREEN));
            town(plugin, pl, t);
        }));
        buttons.add(button(plugin, "All chests OFF", NamedTextColor.WHITE, "Settlers may use no chests (switch some on with the ledger)", w, pl -> {
            t.allChests(false);
            plugin.towns().save();
            pl.sendActionBar(Component.text("Settlers may no longer use chests in the settlement.", NamedTextColor.YELLOW));
            town(plugin, pl, t);
        }));
        buttons.add(button(plugin, "Settlers", NamedTextColor.WHITE, null, w, pl -> settlers(plugin, pl, t)));
        buttons.add(button(plugin, "Show border", NamedTextColor.WHITE, "Shows the settlement border for 10 seconds (also shown while you hold the ledger)",
                w, pl -> plugin.showBorder(pl, t)));
        buttons.add(button(plugin, "Get a ledger", NamedTextColor.WHITE, "The book to manage your settlement with", w, pl -> {
            for (ItemStack left : pl.getInventory().addItem(Ledger.create()).values()) pl.getWorld().dropItem(pl.getLocation(), left);
            pl.sendActionBar(Component.text("Here's your Settlement Ledger.", NamedTextColor.GREEN));
        }));
        buttons.add(button(plugin, "Rename settlement", NamedTextColor.WHITE, null, w, pl -> renameTown(plugin, pl, t)));
        buttons.add(button(plugin, "Disband settlement", NamedTextColor.RED, "Settlers become normal villagers again", w, pl -> disband(plugin, pl, t)));
        show(p, t.name, body, List.of(), DialogType.multiAction(buttons).columns(2).exitAction(close()).build());
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
                DialogAction.customClick((view, audience) -> {
                    String n = view.getText("name");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (n != null && !n.isBlank()) t.name = n.trim();
                        plugin.towns().save();
                        town(plugin, pl, t);
                    });
                }, ONCE));
        show(p, "Rename settlement", List.of(), List.of(name),
                DialogType.confirmation(save, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> town(plugin, pl, t))));
    }

    private static void disband(VillagerWorkersPlugin plugin, Player p, Town t) {
        ActionButton yes = button(plugin, "Disband", NamedTextColor.RED, null, 100, pl -> {
            plugin.disband(t);
            pl.sendActionBar(Component.text(t.name + " is no more.", NamedTextColor.YELLOW));
        });
        show(p, "Disband " + t.name + "?", List.of(line("All settlers become normal villagers again.", NamedTextColor.GRAY)),
                List.of(), DialogType.confirmation(yes, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> town(plugin, pl, t))));
    }

    // ---- Targets ----

    static void targets(VillagerWorkersPlugin plugin, Player p, Town t) {
        int radius = plugin.settings().townRadius();
        List<DialogInput> inputs = new ArrayList<>();
        for (Material m : FarmerJob.TARGET_ITEMS) {
            int target = t.targets.getOrDefault(m, 0);
            inputs.add(DialogInput.text(m.name().toLowerCase(Locale.ROOT), Component.text(nice(m) + "  (in storage: " + t.count(m, radius) + ")"))
                    .initial(String.valueOf(target)).maxLength(6).width(220).build());
        }
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 100,
                DialogAction.customClick((view, audience) -> {
                    Map<Material, String> values = new HashMap<>();
                    for (Material m : FarmerJob.TARGET_ITEMS) values.put(m, view.getText(m.name().toLowerCase(Locale.ROOT)));
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (Map.Entry<Material, String> e : values.entrySet()) {
                            try {
                                t.targets.put(e.getKey(), Math.max(0, Integer.parseInt(e.getValue() == null ? "0" : e.getValue().trim())));
                            } catch (NumberFormatException ignored) {
                                // keep the old value
                            }
                        }
                        plugin.towns().save();
                        pl.sendActionBar(Component.text("Targets saved.", NamedTextColor.GREEN));
                        town(plugin, pl, t);
                    });
                }, ONCE));
        show(p, "Settlement targets", List.of(line("Settlers stop producing an item once storage holds this many. 0 = no limit.", NamedTextColor.GRAY)),
                inputs, DialogType.confirmation(save, button(plugin, "Cancel", NamedTextColor.GRAY, null, 100, pl -> town(plugin, pl, t))));
    }

    // ---- Settlers overview ----

    static void settlers(VillagerWorkersPlugin plugin, Player p, Town t) {
        List<DialogBody> body = new ArrayList<>();
        body.add(line("Settlers of " + t.name + ": " + t.settlers.size(), NamedTextColor.GOLD));
        for (Map.Entry<UUID, String> e : t.settlers.entrySet()) {
            Worker w = plugin.workers().get(e.getKey());
            if (w == null) {
                body.add(line(e.getValue() + " — far away (not loaded)", NamedTextColor.DARK_GRAY));
            } else {
                body.add(line(w.name + " — " + w.job().title + " — " + w.doing(), NamedTextColor.WHITE));
            }
        }
        if (t.settlers.isEmpty()) body.add(line("Recruit settlers here, or hire a villager with the ledger.", NamedTextColor.GRAY));
        show(p, "Settlers", body, List.of(), DialogType.notice(button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> town(plugin, pl, t))));
    }

    // ---- Chest ----

    static void chest(VillagerWorkersPlugin plugin, Player p, Town t, Block chest) {
        boolean[] r = t.rule(chest);
        boolean own = t.hasOwnRule(chest);
        List<DialogBody> body = List.of(
                line("Storage in " + t.name, NamedTextColor.GOLD),
                line(own ? "This chest has its own rules." : "This chest follows the settlement default ("
                        + (t.chestDefault ? "allowed" : "not allowed") + ").", NamedTextColor.GRAY),
                line("Harvests go to an allowed chest that already holds that item, otherwise the nearest one with space.", NamedTextColor.DARK_GRAY));
        List<DialogInput> inputs = List.of(
                DialogInput.bool("store", Component.text("Settlers may put things in"), r[0], "true", "false"),
                DialogInput.bool("take", Component.text("Settlers may take things out"), r[1], "true", "false"));
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 120,
                DialogAction.customClick((view, audience) -> {
                    Boolean store = view.getBoolean("store"), take = view.getBoolean("take");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        t.setRule(chest, new boolean[]{Boolean.TRUE.equals(store), Boolean.TRUE.equals(take)});
                        plugin.towns().save();
                        pl.sendActionBar(Component.text("Chest rules saved.", NamedTextColor.GREEN));
                    });
                }, ONCE));
        ActionButton reset = button(plugin, "Use settlement default", NamedTextColor.WHITE, null, 120, pl -> {
            t.setRule(chest, null);
            plugin.towns().save();
            pl.sendActionBar(Component.text("This chest follows the settlement default again.", NamedTextColor.GREEN));
        });
        show(p, "Chest", body, inputs, DialogType.multiAction(List.of(save, reset)).columns(2).exitAction(close()).build());
    }

    // ---- Workplace ----

    static void workplace(VillagerWorkersPlugin plugin, Player p, Town t, Town.Workplace wp) {
        Settings s = plugin.settings();
        Worker worker = wp.worker == null ? null : plugin.workers().get(wp.worker);
        String who = wp.worker == null ? "nobody" : worker != null ? worker.name : t.settlers.getOrDefault(wp.worker, "?") + " (far away)";
        List<DialogBody> body = new ArrayList<>();
        body.add(line(wp.job().title + "'s workplace at " + Keys.pos(wp.loc), NamedTextColor.GOLD));
        body.add(line("Works here: " + who, NamedTextColor.WHITE));
        if (worker != null) body.add(line("Status: " + worker.workStatus, NamedTextColor.AQUA));
        body.add(line("A free settler takes an empty workplace by itself.", NamedTextColor.DARK_GRAY));
        List<DialogInput> inputs = List.of(
                DialogInput.numberRange("radius", Component.text("Work radius"), 2, s.maxRadius())
                        .step(1f).initial((float) Math.min(wp.radius, s.maxRadius())).width(200).build(),
                DialogInput.bool("enabled", Component.text("Workplace in use"), wp.enabled, "true", "false"));
        int w = 150;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, w,
                DialogAction.customClick((view, audience) -> {
                    Float radius = view.getFloat("radius");
                    Boolean enabled = view.getBoolean("enabled");
                    if (!(audience instanceof Player pl)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (radius != null) wp.radius = Math.max(2, Math.min(s.maxRadius(), Math.round(radius)));
                        if (enabled != null) wp.enabled = enabled;
                        plugin.towns().save();
                        pl.sendActionBar(Component.text("Workplace saved.", NamedTextColor.GREEN));
                        plugin.showArea(pl, wp.loc, wp.radius);
                    });
                }, ONCE)));
        buttons.add(button(plugin, "Show work area", NamedTextColor.WHITE, null, w, pl -> plugin.showArea(pl, wp.loc, wp.radius)));
        buttons.add(button(plugin, "Choose settler…", NamedTextColor.WHITE, "Pick who works here", w, pl -> chooseWorker(plugin, pl, t, wp)));
        if (wp.worker != null) {
            buttons.add(button(plugin, "Send " + (worker != null ? worker.name : "worker") + " away", NamedTextColor.RED, "Free this workplace", w, pl -> {
                Worker cur = wp.worker == null ? null : plugin.workers().get(wp.worker);
                if (cur != null) cur.leaveWorkplace();
                wp.worker = null;
                plugin.towns().save();
                workplace(plugin, pl, t, wp);
            }));
        }
        show(p, "Workplace", body, inputs, DialogType.multiAction(buttons).columns(2).exitAction(close()).build());
    }

    private static void chooseWorker(VillagerWorkersPlugin plugin, Player p, Town t, Town.Workplace wp) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Worker w : plugin.workers().all()) {
            if (!t.id.equals(w.townId)) continue;
            String current = w.job() == Worker.Job.NONE ? "no workplace" : "now " + w.job().title.toLowerCase(Locale.ROOT);
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
            show(p, "Choose settler", List.of(line("No settlers nearby (only loaded settlers are listed).", NamedTextColor.GRAY)), List.of(),
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
        body.add(line(w.name + " — " + w.job().title, NamedTextColor.GOLD));
        body.add(line("Doing: " + w.doing(), NamedTextColor.WHITE));
        body.add(line(wp == null ? "Workplace: none" : "Workplace: " + Keys.pos(wp.loc), NamedTextColor.GRAY));
        if (!w.workStatus.isEmpty()) body.add(line("Work: " + w.workStatus, NamedTextColor.AQUA));
        body.add(line("Carrying: " + carrying(w), NamedTextColor.GRAY));

        int width = 150;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(plugin, "Rename", NamedTextColor.WHITE, null, width, pl -> renameSettler(plugin, pl, w)));
        if (wp != null) {
            buttons.add(button(plugin, "Leave workplace", NamedTextColor.WHITE, "Becomes free (and may take another workplace)", width, pl -> {
                w.leaveWorkplace();
                settler(plugin, pl, w);
            }));
        }
        buttons.add(button(plugin, "Dismiss", NamedTextColor.RED, "Turn back into a normal villager", width, pl -> {
            plugin.dismiss(w);
            pl.sendActionBar(Component.text(w.name + " is a normal villager again.", NamedTextColor.YELLOW));
        }));
        show(p, w.name, body, List.of(), DialogType.multiAction(buttons).columns(2).exitAction(close()).build());
    }

    private static void renameSettler(VillagerWorkersPlugin plugin, Player p, Worker w) {
        DialogInput name = DialogInput.text("name", Component.text("Name")).initial(w.name).maxLength(20).width(200).build();
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 100,
                DialogAction.customClick((view, audience) -> {
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

    private static String carrying(Worker w) {
        Map<Material, Integer> totals = new LinkedHashMap<>();
        for (ItemStack it : w.villager.getInventory().getContents()) {
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
                DialogAction.customClick((view, audience) -> {
                    String chosen = view.getText("name");
                    if (audience instanceof Player pl) Bukkit.getScheduler().runTask(plugin, () -> plugin.hire(pl, v, t, chosen));
                }, ONCE));
        ActionButton no = ActionButton.create(Component.text("Cancel"), null, 100, null);
        Location l = v.getLocation();
        boolean far = !t.contains(l, plugin.settings().townRadius());
        show(p, "Hire villager", List.of(
                        line("This villager joins " + t.name + ". While it's a settler it doesn't trade.", NamedTextColor.GRAY),
                        line(far ? "It's far from the settlement: it will follow you when you're close." : "It will walk to the settlement by itself.", NamedTextColor.DARK_GRAY)),
                List.of(name), DialogType.confirmation(yes, no));
    }
}
