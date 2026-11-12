package settlement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fisher skins for the real fishing look (signed by Mojang, from MineSkin). In the config either a list of
 * MineSkin links (fetched once at startup, matched to the people by the skin's name, e.g. "aldric_fisher",
 * and kept in fisher-skins-cache.yml), or per person a value and signature.
 */
final class FisherSkins {
    private FisherSkins() {}

    /** Person → {value, signature}. */
    private static final Map<String, String[]> skins = new HashMap<>();

    static String[] get(SettlementPlugin plugin, String person) {
        String[] s = skins.get(person);
        if (s != null) return s;
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("fisher-skins");
        if (c != null) {
            String v = c.getString(person + ".value", ""), sig = c.getString(person + ".signature", "");
            if (v != null && !v.isBlank()) return new String[]{v.trim(), sig == null ? "" : sig.trim()};
        }
        return null;
    }

    static void load(SettlementPlugin plugin) {
        skins.clear();
        File file = new File(plugin.getDataFolder(), "fisher-skins-cache.yml");
        YamlConfiguration cache = YamlConfiguration.loadConfiguration(file);
        List<String> done = cache.getStringList("fetched");
        ConfigurationSection cs = cache.getConfigurationSection("skins");
        if (cs != null) for (String person : cs.getKeys(false)) {
            skins.put(person, new String[]{cs.getString(person + ".value", ""), cs.getString(person + ".signature", "")});
        }
        if (plugin.getConfig().isConfigurationSection("fisher-skins")) return; // values in the config itself
        List<String> todo = new ArrayList<>();
        for (String link : plugin.getConfig().getStringList("fisher-skins")) {
            String id = id(link);
            if (id != null && !done.contains(id)) todo.add(id);
        }
        if (todo.isEmpty()) return;
        String key = plugin.getConfig().getString("mineskin-api-key", "");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, String[]> found = new HashMap<>();
            List<String> fetched = new ArrayList<>();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            for (String id : todo) {
                try {
                    HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/skins/" + id))
                            .header("User-Agent", "Settlement-plugin").timeout(Duration.ofSeconds(15)).GET();
                    if (key != null && !key.isBlank()) rq.header("Authorization", "Bearer " + key.trim());
                    HttpResponse<String> resp = http.send(rq.build(), HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) {
                        plugin.getLogger().warning("MineSkin skin " + id + ": HTTP " + resp.statusCode());
                        continue;
                    }
                    JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                    JsonObject skin = root.has("skin") ? root.getAsJsonObject("skin") : root;
                    String name = str(skin, "name");
                    JsonObject tex = null;
                    if (skin.has("texture") && skin.getAsJsonObject("texture").has("data")) tex = skin.getAsJsonObject("texture").getAsJsonObject("data");
                    else if (skin.has("data") && skin.getAsJsonObject("data").has("texture")) tex = skin.getAsJsonObject("data").getAsJsonObject("texture");
                    if (tex == null) {
                        plugin.getLogger().warning("MineSkin skin " + id + ": no texture in the answer");
                        continue;
                    }
                    String person = person(name);
                    if (person == null) {
                        plugin.getLogger().warning("MineSkin skin " + id + " is called \"" + name + "\": no settler name in it (aldric, maren...)");
                        continue;
                    }
                    found.put(person, new String[]{str(tex, "value"), str(tex, "signature")});
                    fetched.add(id);
                } catch (Exception ex) {
                    plugin.getLogger().warning("MineSkin skin " + id + " could not be fetched: " + ex.getMessage());
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                skins.putAll(found);
                for (Map.Entry<String, String[]> e : found.entrySet()) {
                    cache.set("skins." + e.getKey() + ".value", e.getValue()[0]);
                    cache.set("skins." + e.getKey() + ".signature", e.getValue()[1]);
                }
                List<String> all = new ArrayList<>(done);
                all.addAll(fetched);
                cache.set("fetched", all);
                try {
                    cache.save(file);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Could not save fisher-skins-cache.yml: " + ex.getMessage());
                }
                plugin.getLogger().info("Fisher skins from MineSkin: " + found.keySet());
            });
        });
    }

    /** "https://minesk.in/e628..." or "mineskin.org/e628..." or the id itself → the id. */
    private static String id(String link) {
        if (link == null) return null;
        String s = link.trim();
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        return s.matches("[0-9a-fA-F-]{32,36}") ? s : null;
    }

    private static String person(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        for (String p : SettlerSkins.MEN) if (n.contains(p)) return p;
        for (String p : SettlerSkins.WOMEN) if (n.contains(p)) return p;
        return null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
