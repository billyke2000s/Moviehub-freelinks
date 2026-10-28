package local.tv.home;

import android.content.Context;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Android SQLite/session adapter for upstream async provider modules. */
final class AdapterSources {

    static final String ROOT = "https://native.moviehub.invalid/engine/";
    private static final Map<String, JSONArray> cache = new HashMap<>();
    private static final Map<String, Long> expiry = new HashMap<>();
    private static final Map<String, String> versions = new HashMap<>();
    static final String TCL_HINTS = "engineTclResolverHints";
    private static volatile boolean staleTclHints;
    private static final LinkedHashMap<String, PlayerMemo> players = new LinkedHashMap<>();

    private static final class PlayerMemo {

        int good;
        long at;
        final Set<Integer> burnt = new HashSet<>();
    }

    static synchronized void failed(String url) {
        if (url.startsWith(ROOT + "zlive/")) try {
            ProviderSessions.failed(channel(url));
        } catch (Exception e) {
            Diagnostics.recovered("Provider failure identity", e);
        }
        if (url.startsWith(ROOT + "tcl/")) staleTclHints = true;
        if (!url.startsWith(ROOT + "dlhd/")) return;
        DlhdPageCache.clear();
        PlayerMemo memo = players.get(url);
        if (memo != null && memo.good > 0) {
            memo.burnt.add(memo.good);
            memo.good = 0;
            memo.at = System.currentTimeMillis();
        }
    }

    private static synchronized JSONObject playerRow(String url, JSONObject row) throws Exception {
        JSONObject copy = new JSONObject(row.toString());
        PlayerMemo memo = players.get(url);
        if (memo == null) return copy;
        if (System.currentTimeMillis() - memo.at > 300_000) {
            players.remove(url);
            return copy;
        }
        copy.put("preferredPlayer", memo.good).put("burntPlayers", new JSONArray(memo.burnt));
        return copy;
    }

    private static synchronized void rememberPlayer(String url, int player) {
        if (player < 1 || player > 6) return;
        PlayerMemo memo = players.get(url);
        if (memo == null) {
            memo = new PlayerMemo();
            if (players.size() >= 256) players.remove(players.keySet().iterator().next());
            players.put(url, memo);
        }
        memo.good = player;
        memo.burnt.remove(player);
        memo.at = System.currentTimeMillis();
    }

    static final String[] LABELS = {
        "STIRR",
        "TCL TV+",
        "Xumo Play",
        "LG Channels (US only)",
        "Whale TV+",
        "Vizio WatchFree+",
        "Roku Channel (US only)",
        "Plex",
        "DaddyLive",
        "Tubi",
        "Samsung TV Plus (GB)",
        "Local Now (US only)",
        "ZLive",
        "FreeShot (UK sports)",
    };

    /** Map upstream labels into the shared TV-guide sections; see {@link ChannelCategory}. */
    static String guideCategory(String category, String name) {
        return ChannelCategory.of(category, name);
    }

    /** The provider's name for a channel source, shown with each copy in the Source list. */
    static String sourceName(String source) {
        if (source == null) return "";
        switch (source) {
            case "xtream":
                return "Xtream";
            case "m3u":
                return "M3U";
            case "masqueradarr":
                return "Masqueradarr";
            case "fta":
                return "Free-TV";
            case "free":
                return "iptv-org";
            case "nativepluto":
                return "Pluto TV";
            case "legacy":
                return "Saved list";
            case EngineSources.SOURCE:
                return "FreeLiveSports";
            default:
                for (int i = 0; i < ProviderBroker.IDS.length; i++) if (
                    source.equals(source(ProviderBroker.IDS[i]))
                ) return LABELS[i];
                for (String[] list : FreeChannels.EXTRA)
                    if (list.length == 3 && list[0].equals(source)) return list[1];
                return source;
        }
    }

    static String sourceName(ChannelDatabase.Row row) {
        String provider = sourceName(row.source);
        return row.sourceLabel.isEmpty() ? provider : provider + " · " + row.sourceLabel;
    }

    private AdapterSources() {}

    static String source(String id) {
        return "engine" + id;
    }

    static String pref(String id) {
        return "engineProvider_" + id;
    }

    /**
     * A provider imports when it is turned on in Settings → Provider engine. That switch is its
     * own: the free-list choice (Settings → Free channels) only decides the public M3U lists, so an
     * account or "only without a TV provider" no longer hides a provider the viewer turned on.
     */
    static boolean enabled(Context c, String id, boolean connected) {
        android.content.SharedPreferences ui = c.getSharedPreferences("ui", 0);
        if (ProviderOutage.hidden(ui.getBoolean(ProviderOutage.HIDE_US_ONLY, false), id)) return false;
        return ui.getBoolean(pref(id), false);
    }

    static synchronized void invalidate() {
        expiry.clear();
        cache.clear();
        versions.clear();
    }

    static boolean locator(String url) {
        if (url == null || !url.startsWith(ROOT)) return false;
        String tail = url.substring(ROOT.length());
        int slash = tail.indexOf('/');
        return slash > 0 && ProviderBroker.supported(tail.substring(0, slash));
    }

    static String provider(String url) throws IOException {
        if (!locator(url)) throw new IOException("Invalid provider locator");
        return url.substring(ROOT.length(), url.indexOf('/', ROOT.length()));
    }

    static String channel(String url) throws IOException {
        String id = provider(url);
        String tail = url.substring((ROOT + id + "/").length());
        if (!tail.matches("[A-Za-z0-9_-]{1,700}")) throw new IOException("Invalid provider channel");
        try {
            return new String(Base64.getUrlDecoder().decode(tail), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Invalid provider identity");
        }
    }

    static String locator(String id, String channel) {
        return (
            ROOT +
            id +
            "/" +
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(channel.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }

    /** The in-memory catalogue for the current module, or null. Never contacts the provider. */
    private static JSONArray cachedCatalogue(String id, String module) {
        synchronized (AdapterSources.class) {
            if (
                cache.containsKey(id) &&
                System.currentTimeMillis() < expiry.getOrDefault(id, 0L) &&
                module.equals(versions.get(id))
            ) return cache.get(id);
        }
        return null;
    }

    private static JSONArray catalogue(Context c, String id, NativeHttp http) throws Exception {
        String module = ProviderPackages.module(c, id);
        JSONArray cached = cachedCatalogue(id, module);
        if (cached != null) return cached;
        Object value = ProviderBroker.call(c, id, "catalogue", null, http);
        if (!(value instanceof JSONArray)) throw new IOException("Invalid catalogue result");
        JSONArray rows = (JSONArray) value;
        if (id.equals("zlive")) rows = ZliveSources.expand(rows);
        if (rows.length() == 0 || rows.length() > 5000) throw new IOException("Invalid catalogue size");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String key = row.getString("channelId");
            if (
                key.isEmpty() ||
                key.length() > 400 ||
                !seen.add(key) ||
                row.getString("name").trim().isEmpty()
            ) throw new IOException("Invalid provider row");
        }
        if (id.equals("tcl")) try {
            saveTclHints(c, module, rows);
        } catch (Exception e) {
            Diagnostics.recovered("Saving TCL resolver hints", e);
        }
        if (id.equals("zlive")) ZliveSources.save(c, module, rows);
        synchronized (AdapterSources.class) {
            cache.put(id, rows);
            expiry.put(id, System.currentTimeMillis() + 600_000);
            versions.put(id, module);
        }
        return rows;
    }

    static void saveTclHints(Context c, String module, JSONArray rows) throws Exception {
        JSONObject hints = new JSONObject();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i),
                hint = new JSONObject().put("channelId", row.getString("channelId"));
            for (String field : Arrays.asList("source", "media"))
                if (row.has(field)) hint.put(field, row.get(field));
            hints.put(row.getString("channelId"), hint);
        }
        String saved = new JSONObject()
            .put("module", ProviderCodec.call("sha256", module))
            .put("at", System.currentTimeMillis())
            .put("rows", hints)
            .toString();
        if (saved.length() > 2 * 1024 * 1024) throw new IOException("Resolver hint limit");
        new Secrets(c).put(TCL_HINTS, saved);
        staleTclHints = false;
    }

    static JSONObject resolverRow(Context c, String id, String wanted, NativeHttp http) throws Exception {
        http.check();
        if (id.equals("zlive")) {
            JSONObject hint = ZliveSources.hint(c, ProviderPackages.module(c, id), wanted);
            if (hint != null) return hint;
            // Old stored IDs omitted auto-/evt-. Match them against a catalogue already in memory,
            // but never download the catalogue just to start a channel (cold starts stay fast).
            JSONArray current = cachedCatalogue(id, ProviderPackages.module(c, id));
            if (current != null) for (int i = 0; i < current.length(); i++) {
                JSONObject candidate = current.getJSONObject(i);
                String key = candidate.getString("channelId");
                if (
                    wanted.equals(key) ||
                    wanted.equals(key.replaceFirst("^auto-", "").replaceFirst("^evt-", ""))
                ) return candidate;
            }
            // The resolver passes the raw channel ID unchanged; source variants need their row.
            if (!wanted.contains(ZliveSources.VARIANT)) return new JSONObject().put("channelId", wanted);
        }
        // These upstream resolvers accept the provider's own channel id; no catalogue hop is needed.
        // Plex is not one: it plays by a compound id whose server prefix changes, so its (cached)
        // catalogue row is looked up.
        if (
            Arrays.asList(
                "stirr",
                "xumo",
                "roku",
                "dlhd",
                "tubi",
                "samsung",
                "localnow",
                "freeshot"
            ).contains(id)
        ) return new JSONObject().put("channelId", wanted);
        if (id.equals("tcl") && !staleTclHints) try {
            String saved = new Secrets(c).get(TCL_HINTS);
            if (!saved.isEmpty()) {
                JSONObject envelope = new JSONObject(saved);
                long age = System.currentTimeMillis() - envelope.getLong("at");
                if (
                    age >= 0 &&
                    age < 14L * 86400_000 &&
                    envelope
                        .getString("module")
                        .equals(ProviderCodec.call("sha256", ProviderPackages.module(c, id)))
                ) {
                    JSONObject hint = envelope.getJSONObject("rows").optJSONObject(wanted);
                    if (hint != null) return hint;
                }
            }
        } catch (Exception e) {
            Diagnostics.recovered("Reading TCL resolver hints", e);
        }
        JSONArray rows = catalogue(c, id, http);
        for (int i = 0; i < rows.length(); i++) {
            http.check();
            JSONObject row = rows.getJSONObject(i);
            if (wanted.equals(row.getString("channelId"))) return row;
        }
        throw new IOException("Provider channel unavailable");
    }

    static EngineSources.Stream resolve(Context c, String url, NativeHttp http) throws Exception {
        String id = provider(url),
            wanted = channel(url);
        JSONObject row = resolverRow(c, id, wanted, http);
        JSONObject resolved = (JSONObject) ProviderBroker.call(
            c,
            id,
            "resolve",
            id.equals("dlhd") ? playerRow(url, row) : row,
            http
        );
        if (id.equals("zlive")) {
            resolved.put("segmentContainers", true);
            JSONObject supplied = row.optJSONObject("sourceHeaders");
            if (supplied != null) for (Iterator<String> keys = supplied.keys(); keys.hasNext(); ) {
                String key = keys.next();
                resolved.getJSONObject("headers").put(key, supplied.getString(key));
            }
        }
        EngineSources.Stream stream = new EngineSources.Stream(resolved);
        if (id.equals("dlhd")) rememberPlayer(url, resolved.optInt("playerIndex", 0));
        return stream;
    }

    static int refresh(Context c, String id, ChannelDatabase db, ChannelDatabase.Cancel cancel)
        throws Exception {
        synchronized (AdapterSources.class) {
            expiry.remove(id);
        }
        NativeHttp http = new NativeHttp();
        http.externalCancel = cancel;
        cancel.check();
        JSONArray rows = catalogue(c, id, http);
        cancel.check();
        int previous = c.getSharedPreferences("providerEngine", 0).getInt(id + "Count", 0);
        if (id.equals("zlive") && previous >= 20 && rows.length() * 2 < previous) throw new IOException(
            "Partial provider catalogue rejected"
        );
        ChannelDatabase.Writer writer = db.writer(source(id), cancel, n -> {});
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String channel = row.getString("channelId");
                String logo = "";
                try {
                    logo = Playlist.networkUrl(row.optString("logo"), false);
                } catch (Exception ignored) {}
                writer.accept(
                    new Playlist.Channel(
                        row.getString("name"),
                        locator(id, channel),
                        guideCategory(row.optString("category", ""), row.getString("name")),
                        logo,
                        source(id) + ":" + row.optString("guideChannelId", channel),
                        row.optString("sourceLabel", "")
                    )
                );
            }
            cancel.check();
            writer.commit();
        } finally {
            writer.abort();
        }
        // Programme data comes only from the cached combined guide.
        c.getSharedPreferences("providerEngine", 0)
            .edit()
            .putInt(id + "Count", rows.length())
            .putLong(id + "Refresh", System.currentTimeMillis())
            .remove(id + "Error")
            .apply();
        return rows.length();
    }
}
