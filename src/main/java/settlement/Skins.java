package settlement;

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
        String low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith("player:") || low.startsWith("texture:") || low.startsWith("url:") || low.startsWith("value:")) return s;
        return s.toLowerCase(Locale.ROOT) + (random.nextBoolean() ? ":slim" : ":wide");
    }

    /** The skin for a job from job-skins (the same one for the same settler), or null. */
    static String forJob(Settings s, String job, java.util.UUID id) {
        List<String> l = s.jobSkins().get(job.toLowerCase(Locale.ROOT).replace(' ', '-'));
        if (l == null || l.isEmpty()) return null;
        return l.get(Math.floorMod(id.hashCode(), l.size())).trim();
    }

    /** "male", "female" or null (unknown, e.g. a player's skin). */
    static String gender(Settings s, String spec) {
        if (spec == null) return null;
        return s.skinGender().get(spec.split(":")[0].toLowerCase(Locale.ROOT));
    }

    /** A random name that fits the skin. */
    static String nameFor(Settings s, String skin, Random r) {
        String g = gender(s, skin);
        List<String> l = "male".equals(g) ? s.maleNames() : "female".equals(g) ? s.femaleNames() : s.names();
        if (l.isEmpty()) l = s.names();
        return l.get(r.nextInt(l.size()));
    }

    /** A random skin from the config that fits the name (slim arms for women, wide for men). */
    static String skinFor(Settings s, String name, Random r) {
        String g = s.maleNames().contains(name) ? "male" : s.femaleNames().contains(name) ? "female" : null;
        List<String> fit = new java.util.ArrayList<>();
        for (String o : s.skins()) {
            String og = s.skinGender().get(o.trim().split(":")[0].toLowerCase(Locale.ROOT));
            if (g == null || g.equals(og)) fit.add(o);
        }
        if (fit.isEmpty()) fit = s.skins();
        String pick = fit.get(r.nextInt(fit.size())).trim();
        String low = pick.toLowerCase(Locale.ROOT);
        if (low.startsWith("player:") || low.startsWith("texture:") || low.startsWith("url:") || low.startsWith("value:") || pick.contains(":")) return pick;
        String og = s.skinGender().get(low);
        boolean slim = "female".equals(og) || (og == null && r.nextBoolean()); // women slim, men wide
        return low + (slim ? ":slim" : ":wide");
    }

    static ResolvableProfile profile(String spec) {
        if (spec.toLowerCase(Locale.ROOT).startsWith("player:")) {
            return ResolvableProfile.resolvableProfile().name(spec.substring(7)).build();
        }
        String low = spec.toLowerCase(Locale.ROOT);
        if (low.startsWith("url:") || low.startsWith("value:")) {
            // A skin hosted by Minecraft: "url:http://textures.minecraft.net/texture/<id>" (+ ":slim"),
            // or "value:<texture value>" as MineSkin and other sites give it.
            String value;
            if (low.startsWith("value:")) {
                value = spec.substring(6).trim();
            } else {
                String rest = spec.substring(4).trim();
                boolean slim = rest.endsWith(":slim");
                if (slim) rest = rest.substring(0, rest.length() - 5);
                String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + rest + "\"" + (slim ? ",\"metadata\":{\"model\":\"slim\"}" : "") + "}}}";
                value = java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return ResolvableProfile.resolvableProfile()
                    .addProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", value))
                    .build();
        }
        if (low.startsWith("texture:")) {
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
