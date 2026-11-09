package villagerworkers;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.profile.PlayerTextures;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Skins for settlers: built-in Minecraft skins (wide or slim) or a real player's skin. */
final class Skins {
    private Skins() {}

    /** Pick one from the config: "zuri:slim", "steve:wide" or "player:Name". */
    static String pick(List<String> options, Random random) {
        String s = options.get(random.nextInt(options.size())).trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("player:") || s.toLowerCase(Locale.ROOT).startsWith("texture:")) return s;
        return s.toLowerCase(Locale.ROOT) + (random.nextBoolean() ? ":slim" : ":wide");
    }

    static ResolvableProfile profile(String spec) {
        if (spec.toLowerCase(Locale.ROOT).startsWith("player:")) {
            return ResolvableProfile.resolvableProfile().name(spec.substring(7)).build();
        }
        if (spec.toLowerCase(Locale.ROOT).startsWith("texture:")) {
            // Your own skin from the server resource pack: "texture:namespace:path/to/skin" (+ ":slim").
            String[] p = spec.split(":");
            boolean slim = p.length > 3 && p[3].equals("slim");
            String ns = p.length > 2 ? p[1] : "minecraft", path = p.length > 2 ? p[2] : p[1];
            return ResolvableProfile.resolvableProfile()
                    .skinPatch(patch -> patch.body(Key.key(ns, path))
                            .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC))
                    .build();
        }
        String[] p = spec.split(":");
        boolean slim = p.length > 1 && p[1].equals("slim");
        String body = "entity/player/" + (slim ? "slim" : "wide") + "/" + p[0];
        return ResolvableProfile.resolvableProfile()
                .skinPatch(patch -> patch.body(Key.key("minecraft", body))
                        .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC))
                .build();
    }
}
