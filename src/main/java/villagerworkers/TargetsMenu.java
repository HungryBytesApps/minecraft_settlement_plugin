package villagerworkers;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Storage targets: one row per product with its item icon, what's in storage and a number field.
 * Empty = no limit (keep producing).
 */
final class TargetsMenu {
    private TargetsMenu() {}

    /** Everything settlers can produce, in menu order. New jobs add their products here. */
    static List<Material> products() {
        return FarmerJob.TARGET_ITEMS;
    }

    static void open(VillagerWorkersPlugin plugin, Player p, Town t) {
        int radius = plugin.settings().townRadius();
        List<DialogInput> inputs = new ArrayList<>();
        for (Material m : products()) {
            int target = t.targets.getOrDefault(m, 0);
            Component label = icon(m)
                    .append(Component.text(" " + nice(m), NamedTextColor.WHITE))
                    .append(Component.text("  (" + t.count(m, radius) + " in storage)", NamedTextColor.GRAY));
            inputs.add(DialogInput.text(key(m), label)
                    .initial(target > 0 ? String.valueOf(target) : "").maxLength(6).width(80).build());
        }
        List<DialogBody> body = List.of(Menus.line("Empty = no limit", NamedTextColor.GRAY));
        ActionButton save = ActionButton.create(Component.text("Save", NamedTextColor.GREEN), null, 100,
                Menus.click(plugin, (view, audience) -> {
                    Map<Material, String> values = new HashMap<>();
                    for (Material m : products()) values.put(m, view.getText(key(m)));
                    if (!(audience instanceof Player pl)) return;
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                        for (Map.Entry<Material, String> e : values.entrySet()) {
                            String v = e.getValue() == null ? "" : e.getValue().trim();
                            if (v.isEmpty()) {
                                t.targets.remove(e.getKey());
                                continue;
                            }
                            try {
                                t.targets.put(e.getKey(), Math.max(0, Integer.parseInt(v)));
                            } catch (NumberFormatException ignored) {
                                // not a number: keep the old value
                            }
                        }
                        plugin.towns().save();
                        pl.sendActionBar(Component.text("Storage targets saved.", NamedTextColor.GREEN));
                        Menus.ledger(plugin, pl, t);
                    });
                }, Menus.ONCE));
        ActionButton back = Menus.button(plugin, "Back", NamedTextColor.GRAY, null, 100, pl -> Menus.ledger(plugin, pl, t));
        Menus.show(p, "Storage targets", body, inputs, DialogType.confirmation(save, back));
    }

    /** The item's own icon inside the text (uses the player's resource pack). */
    private static Component icon(Material m) {
        // Since 1.21.11 item textures live in their own "items" atlas.
        return Component.object(ObjectContents.sprite(Key.key("minecraft", "items"), Key.key("minecraft", "item/" + m.getKey().getKey())));
    }

    private static String key(Material m) {
        return m.name().toLowerCase(Locale.ROOT);
    }

    private static String nice(Material m) {
        String s = FarmerJob.nice(m);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
