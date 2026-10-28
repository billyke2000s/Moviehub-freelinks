package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/**
 * Downloads configured M3U and Xtream catalogues and streams them into {@link ChannelDatabase}.
 * Each source is replaced atomically: a failed or cancelled import keeps that source's previous channels.
 */
final class ChannelImporter {

    private ChannelImporter() {}

    private static final Object LOCK = new Object();
    private static volatile boolean running;
    private static volatile int progress;
    private static volatile boolean queued;

    static void markQueued() {
        queued = true;
    }

    static final class Status {

        final boolean running, queued;
        final int progress;
        final String message;
        final long lastSuccess;

        Status(boolean running, boolean queued, int progress, String message, long lastSuccess) {
            this.running = running;
            this.queued = queued;
            this.progress = progress;
            this.message = message;
            this.lastSuccess = lastSuccess;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("channelImport", Context.MODE_PRIVATE);
    }

    static Status status(Context context) {
        SharedPreferences p = prefs(context);
        return new Status(
            running,
            queued && !running,
            progress,
            p.getString("message", ""),
            p.getLong("lastSuccess", 0)
        );
    }

    /** Returns true when every configured source imported successfully. */
    static boolean run(Context context, ChannelDatabase.Cancel cancel) {
        synchronized (LOCK) {
            queued = false;
            running = true;
            progress = 0;
            Secrets secrets = new Secrets(context);
            ChannelDatabase db = ChannelDatabase.get(context);
            boolean http = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("http", false);
            List<String> configured = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            int total = 0;
            boolean anySource = false;
            String guideNote = "";
            try {
                migrateLegacy(context, db, cancel);
                String playlist = secrets.get("m3u");
                if (!playlist.isEmpty()) {
                    anySource = true;
                    configured.add("m3u");
                    try {
                        total += importM3u(db, "m3u", playlist, http, cancel, total);
                    } catch (Exception e) {
                        problems.add("M3U: " + describe(e));
                    }
                }
                String masqueradarr = secrets.get("masqueradarr");
                if (!masqueradarr.isEmpty()) {
                    anySource = true;
                    configured.add("masqueradarr");
                    try {
                        total += importM3u(db, "masqueradarr", masqueradarr, http, cancel, total);
                    } catch (Exception e) {
                        problems.add("Masqueradarr: " + describe(e));
                    }
                }
                String account = secrets.get("xtream");
                if (!account.isEmpty()) {
                    anySource = true;
                    configured.add("xtream");
                    try {
                        total += importXtream(db, new JSONObject(account), http, cancel, total);
                    } catch (Exception e) {
                        problems.add("Xtream: " + describe(e));
                    }
                }
                boolean providerConnected = anySource;
                // Pluto and the free lists first: they hold most channels and are plain downloads,
                // while each optional provider below may take minutes (ProviderBroker allows a
                // catalogue four minutes). Channels appear in the guide as each source commits.
                // Import order does not change numbering or the preferred copy (ChannelDatabase.ORDER).
                if (NativeSources.enabled(context, providerConnected)) {
                    configured.add("nativepluto");
                    anySource = true;
                    try {
                        total += NativeSources.refresh(context, db, cancel);
                    } catch (Exception e) {
                        cancel.check();
                        Diagnostics.recovered("Refreshing native Pluto channels", e);
                        problems.add("Pluto TV: regional service unavailable; cached channels kept.");
                    }
                }
                // Free lists: each its own source, so a list that fails keeps its previous channels.
                for (FreeChannels.Source free : FreeChannels.sources(context, providerConnected)) {
                    anySource = true;
                    configured.add(free.id);
                    try {
                        total += importM3u(db, free.id, free.url, http, cancel, total, free.firstGroup);
                    } catch (Exception e) {
                        problems.add(free.name + ": " + describe(e));
                    }
                }
                // Populate the saved/base channels before optional catalogues can spend minutes
                // timing out. Native provider guides remain independent and retain priority.
                Set<String> guideKeys = EpgImporter.wantedSnapshot(db);
                boolean optionalSources = EngineSources.enabled(context, providerConnected);
                for (String id : ProviderBroker.IDS)
                    optionalSources |= AdapterSources.enabled(context, id, providerConnected);
                if ((anySource || optionalSources) && !guideKeys.isEmpty()) guideNote = refreshGuide(
                    context,
                    db,
                    cancel
                );
                try {
                    ProviderUpdates.check(context, false);
                } catch (Exception e) {
                    Diagnostics.recovered("Provider package update", e);
                }
                if (EngineSources.enabled(context, providerConnected)) {
                    configured.add(EngineSources.SOURCE);
                    anySource = true;
                    try {
                        total += EngineSources.refresh(context, db, cancel);
                    } catch (Exception e) {
                        cancel.check();
                        Diagnostics.recovered("FreeLiveSports refresh", e);
                        problems.add("FreeLiveSports unavailable; cached channels kept.");
                    }
                }
                // Providers that failed this time, named once in one line grouped by the actual failure
                // (timed out, HTTP 403…): one line per provider pushed the guide's rows off the screen.
                List<String[]> unavailable = new ArrayList<>();
                android.content.SharedPreferences engine = context.getSharedPreferences("providerEngine", 0);
                for (String id : ProviderBroker.IDS)
                    if (AdapterSources.enabled(context, id, providerConnected)) {
                        configured.add(AdapterSources.source(id));
                        anySource = true;
                        try {
                            total += AdapterSources.refresh(context, id, db, cancel);
                            progress = total;
                        } catch (Exception e) {
                            cancel.check();
                            Diagnostics.recovered("Provider refresh " + id, e);
                            String failure = ProviderFailure.describe(e);
                            engine
                                .edit()
                                .putString(id + "Error", failure)
                                .apply();
                            unavailable.add(new String[] {
                                AdapterSources.sourceName(AdapterSources.source(id)),
                                failure,
                            });
                        }
                    }
                if (!unavailable.isEmpty()) problems.add(ProviderOutage.summary(unavailable));
                cancel.check();
                // Failed sources are still configured and keep their last good rows. Disabled
                // sources must disappear even when a different provider is offline.
                // A migrated legacy cache has no configured source identity yet: retain it
                // through repeated outages until a real replacement imports successfully.
                if (total == 0 && !problems.isEmpty()) configured.add("legacy");
                db.removeSourcesExcept(configured);
                // The free programme guide; a failure here never counts against the channels.
                if (
                    anySource && db.count() > 0 && !guideKeys.equals(EpgImporter.wantedSnapshot(db))
                ) guideNote = refreshGuide(context, db, cancel);
            } catch (Exception e) {
                problems.add(describe(e));
            } finally {
                running = false;
            }
            String message = !anySource
                ? "No live TV account connected"
                : problems.isEmpty()
                  ? String.format(Locale.getDefault(), "%,d channels updated", total) + guideNote
                  : String.join("\n", problems);
            SharedPreferences.Editor edit = prefs(context)
                .edit()
                .putString("message", message)
                .putLong("lastAttempt", System.currentTimeMillis());
            if (anySource && problems.isEmpty()) edit.putLong("lastSuccess", System.currentTimeMillis());
            // The app version that last finished a refresh, whatever its outcome: after an update,
            // ChannelSync refreshes once so new guide sources and fixes apply straight away.
            if (anySource) edit.putInt("refreshedVersion", BuildConfig.VERSION_CODE).putLong(
                "lastFinished",
                System.currentTimeMillis()
            );
            edit.apply();
            return problems.isEmpty();
        }
    }

    private static String refreshGuide(Context context, ChannelDatabase db, ChannelDatabase.Cancel cancel)
        throws Exception {
        try {
            EpgImporter.Result guide = EpgImporter.run(context, db, cancel);
            return guide.programmes > 0
                ? String.format(Locale.getDefault(), " · programme guide for %,d channels", guide.channels)
                : " · no programme guide matched";
        } catch (Exception e) {
            cancel.check();
            Diagnostics.recovered("Programme guide", e);
            return " · programme guide unavailable";
        }
    }

    /** Upgrade the previous UI cache without touching accounts, favourites or playback positions. */
    private static void migrateLegacy(Context context, ChannelDatabase db, ChannelDatabase.Cancel cancel)
        throws Exception {
        java.io.File legacy = context.getDatabasePath("channels.db");
        if (!legacy.exists()) return;
        if (db.count() == 0) {
            ChannelDatabase.Writer writer = db.writer("legacy", cancel, n -> progress = n);
            try (
                android.database.sqlite.SQLiteDatabase old =
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                        legacy.getPath(),
                        null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    );
                android.database.Cursor rows = old.rawQuery(
                    "SELECT name,url,category,logo,epg FROM channels ORDER BY id",
                    null
                )
            ) {
                while (rows.moveToNext())
                    writer.accept(
                        new Playlist.Channel(
                            rows.getString(0),
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4)
                        )
                    );
                cancel.check();
                writer.commit();
            } finally {
                writer.abort();
            }
        }
        context.deleteDatabase("channels.db");
    }

    private static String describe(Exception e) {
        if (
            e instanceof java.util.concurrent.CancellationException
        ) return "update was interrupted; previous channels kept";
        return "refresh failed; previous channels kept. Check the connection and account.";
    }

    private static int importM3u(
        ChannelDatabase db,
        String source,
        String url,
        boolean http,
        ChannelDatabase.Cancel cancel,
        int base
    ) throws Exception {
        return importM3u(db, source, url, http, cancel, base, null);
    }

    /**
     * Imports one playlist. With {@code firstGroup}, that group's channels come first (Free-TV's
     * "UK" group, so BBC One is channel 101); the playlist is then read twice.
     */
    private static int importM3u(
        ChannelDatabase db,
        String source,
        String url,
        boolean http,
        ChannelDatabase.Cancel cancel,
        int base,
        String firstGroup
    ) throws Exception {
        ChannelDatabase.Writer writer = db.writer(source, cancel, n -> progress = base + n);
        try {
            if (firstGroup != null) try (
                InputStream first = Network.open(url, null, Network.CATALOGUE_MAX, http)
            ) {
                Playlist.parse(new InputStreamReader(first, StandardCharsets.UTF_8), url, http, c -> {
                    if (firstGroup.equals(c.group)) writer.accept(c);
                });
            }
            try (InputStream in = Network.open(url, null, Network.CATALOGUE_MAX, http)) {
                Playlist.parse(new InputStreamReader(in, StandardCharsets.UTF_8), url, http, c -> {
                    if (firstGroup == null || !firstGroup.equals(c.group)) writer.accept(c);
                });
            }
            if (writer.count() == 0) throw new IOException("playlist has no playable channels");
            cancel.check();
            writer.commit();
            return writer.count();
        } finally {
            writer.abort();
        }
    }

    private static int importXtream(
        ChannelDatabase db,
        JSONObject account,
        boolean http,
        ChannelDatabase.Cancel cancel,
        int base
    ) throws Exception {
        String server = Playlist.networkUrl(account.getString("server"), http).replaceAll("/+$", ""),
            user = account.getString("username"),
            pass = account.getString("password"),
            format = account.optString("format", "ts");
        String api = server + "/player_api.php?username=" + Network.q(user) + "&password=" + Network.q(pass);
        Map<String, String> groups = new HashMap<>();
        Object parsed = new JSONTokener(
            Network.text(api + "&action=get_live_categories", null, http)
        ).nextValue();
        if (parsed instanceof JSONArray) {
            JSONArray categories = (JSONArray) parsed;
            for (int i = 0; i < categories.length(); i++) {
                JSONObject c = categories.optJSONObject(i);
                if (c != null) groups.put(c.optString("category_id"), c.optString("category_name"));
            }
        } else throw new IOException("account was rejected by the provider");
        ChannelDatabase.Writer writer = db.writer("xtream", cancel, n -> progress = base + n);
        try (
            InputStream in = Network.open(
                api + "&action=get_live_streams",
                null,
                Network.CATALOGUE_MAX,
                http
            );
            JsonReader json = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))
        ) {
            json.setLenient(true);
            if (json.peek() != JsonToken.BEGIN_ARRAY) throw new IOException(
                "provider did not return a channel list"
            );
            json.beginArray();
            while (json.hasNext()) {
                if (json.peek() != JsonToken.BEGIN_OBJECT) {
                    json.skipValue();
                    continue;
                }
                String name = "",
                    id = "",
                    category = "",
                    icon = "",
                    epg = "";
                json.beginObject();
                while (json.hasNext()) {
                    switch (json.nextName()) {
                        case "name":
                            name = string(json);
                            break;
                        case "stream_id":
                            id = string(json);
                            break;
                        case "category_id":
                            category = string(json);
                            break;
                        case "stream_icon":
                            icon = string(json);
                            break;
                        case "epg_channel_id":
                            epg = string(json);
                            break;
                        default:
                            json.skipValue();
                    }
                }
                json.endObject();
                if (!id.matches("[0-9]+")) continue;
                String group = groups.get(category);
                writer.accept(
                    new Playlist.Channel(
                        name.isEmpty() ? "Channel" : name,
                        ChannelQuery.xtreamUrl(server, user, pass, id, format),
                        group == null ? "" : group,
                        icon,
                        epg
                    )
                );
            }
            json.endArray();
            cancel.check();
            if (writer.count() == 0) throw new IOException("provider returned no live channels");
            cancel.check();
            writer.commit();
            return writer.count();
        } finally {
            writer.abort();
        }
    }

    private static String string(JsonReader json) throws IOException {
        switch (json.peek()) {
            case STRING:
            case NUMBER:
                return json.nextString();
            case BOOLEAN:
                return String.valueOf(json.nextBoolean());
            case NULL:
                json.nextNull();
                return "";
            default:
                json.skipValue();
                return "";
        }
    }
}
