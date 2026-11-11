package settlement;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.profile.PlayerTextures;

import java.util.List;
import java.util.Locale;

/**
 * The plugin's own settler skins: 8 people (4 men, 4 women), each in 9 outfits (no job, 7 jobs, guard).
 * The images live in the server resource pack: assets/settlers/textures/skins/<person>_<outfit>.png.
 * A settler is always the same person; the outfit follows the job.
 */
final class SettlerSkins {
    private SettlerSkins() {}

    static final List<String> MEN = List.of("aldric", "bram", "tobin", "edmund");
    static final List<String> WOMEN = List.of("maren", "elise", "ysolde", "hilde");

    /** The outfit for a settler: settler (no job), a job, or guard. */
    static String outfit(Worker w) {
        if (w.guard) return "guard";
        Worker.Job j = w.job();
        return j == Worker.Job.NONE ? "settler" : j.name().toLowerCase(Locale.ROOT);
    }

    static boolean female(Worker w, Settings s) {
        if (s.femaleNames().contains(w.name)) return true;
        if (s.maleNames().contains(w.name)) return false;
        String g = Skins.gender(s, w.skin);
        return g != null ? g.equals("female") : (w.body.getUniqueId().hashCode() & 1) == 1;
    }

    /** The person this settler is (always the same one): a man or woman matching the name, picked by its id. */
    static String person(Worker w, Settings s) {
        List<String> l = female(w, s) ? WOMEN : MEN;
        return l.get(Math.floorMod(w.body.getUniqueId().hashCode(), l.size()));
    }

    /** This settler's skin from the resource pack. */
    static ResolvableProfile profile(Worker w, Settings s) {
        boolean slim = female(w, s);
        Key tex = Key.key("settlers", "skins/" + person(w, s) + "_" + outfit(w));
        return ResolvableProfile.resolvableProfile()
                .skinPatch(patch -> patch.body(tex).model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC))
                .build();
    }
}
