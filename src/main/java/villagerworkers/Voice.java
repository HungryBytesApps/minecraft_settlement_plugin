package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;

import java.util.Locale;

/**
 * Sounds settlers make: a little mumbling voice when they talk (each settler its own pitch,
 * higher for women), footsteps, and small work sounds.
 */
final class Voice {
    private Voice() {}

    /** This settler's voice pitch (the same every time). */
    static float pitch(Worker w) {
        String g = Skins.gender(w.plugin.settings(), w.skin);
        float base = "female".equals(g) ? 1.25f : "male".equals(g) ? 0.9f : 1.05f;
        return base + (Math.floorMod(w.body.getUniqueId().hashCode(), 21) - 10) / 100f;
    }

    /** Mumble along with a speech bubble: a few syllables, a question goes up, an exclamation is louder. */
    static void speak(Worker w, String text) {
        Settings s = w.plugin.settings();
        if (!s.talkSounds()) return;
        String t = text.trim().toLowerCase(Locale.ROOT);
        String sound = s.voiceSound();
        if (t.startsWith("ha") || t.contains("haha")) sound = s.laughSound();
        float pitch = pitch(w), volume = t.endsWith("!") ? 0.7f : 0.45f;
        int syllables = Math.max(1, Math.min(3, text.length() / 18 + 1));
        for (int i = 0; i < syllables; i++) {
            float p = pitch + (w.random.nextFloat() - 0.5f) * 0.12f + (t.endsWith("?") && i == syllables - 1 ? 0.15f : 0f);
            String snd = sound;
            Bukkit.getScheduler().runTaskLater(w.plugin, () -> {
                if (w.body.isValid()) w.body.getWorld().playSound(w.body.getLocation(), snd, SoundCategory.NEUTRAL, volume, p);
            }, i * 7L);
        }
    }

    /** A footstep on whatever it walks on. */
    static void step(Worker w) {
        Location l = w.body.getLocation();
        Block below = l.clone().subtract(0, 0.2, 0).getBlock();
        if (below.isPassable()) return;
        Sound step = below.getBlockData().getSoundGroup().getStepSound();
        l.getWorld().playSound(l, step, SoundCategory.NEUTRAL, 0.15f, 1f);
    }

    /** A soft tap on a block (pottering, fiddling with something). */
    static void tap(Worker w, Block b) {
        if (b == null || b.getType().isAir()) return;
        Sound hit = b.getBlockData().getSoundGroup().getHitSound();
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), hit, SoundCategory.NEUTRAL, 0.35f, 0.9f);
    }
}
