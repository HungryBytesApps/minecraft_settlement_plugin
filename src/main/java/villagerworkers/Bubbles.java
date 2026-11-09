package villagerworkers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Speech bubbles: a short line of text riding on top of a villager's head for a few seconds. */
final class Bubbles {
    private Bubbles() {}

    static void say(Plugin plugin, Entity v, String text, int ticks) {
        clear(v);
        TextDisplay bubble = v.getWorld().spawn(v.getLocation().add(0, v.getHeight(), 0), TextDisplay.class, d -> {
            d.text(Component.text(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setLineWidth(160);
            d.setPersistent(false);
            d.setTransformation(new Transformation(new Vector3f(0, 0.8f, 0), new Quaternionf(),
                    new Vector3f(0.8f, 0.8f, 0.8f), new Quaternionf()));
            d.getPersistentDataContainer().set(Keys.TEMP, PersistentDataType.BYTE, (byte) 1);
        });
        v.addPassenger(bubble);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bubble.isValid()) bubble.remove();
        }, ticks);
    }

    static void clear(Entity v) {
        for (Entity e : v.getPassengers()) {
            if (e instanceof TextDisplay && e.getPersistentDataContainer().has(Keys.TEMP, PersistentDataType.BYTE)) e.remove();
        }
    }
}
