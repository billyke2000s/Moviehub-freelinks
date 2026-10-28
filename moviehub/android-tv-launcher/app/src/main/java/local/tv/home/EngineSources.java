package local.tv.home;

import android.content.Context;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Standalone JS provider integration. Channels use stable encrypted locators, never expiring URLs. */
final class EngineSources {

    static final String PREF = "engineFreeLiveSports";
    static final String SOURCE = "enginefls";
    static final String ROOT = "https://native.moviehub.invalid/engine/freelivesports/";
    private static volatile JSONArray cached;
    private static volatile long expires;
    private static volatile String cachedModule;

    /** Device fixture only; never assigned by app code. */
    static String testCatalogue;

    private EngineSources() {}

    static void invalidate() {
        expires = 0;
        cached = null;
        cachedModule = null;
        AdapterSources.invalidate();
    }

    /** On when turned on in Settings → Provider engine, whatever the free-list choice; see AdapterSources. */
    static boolean enabled(Context c, boolean connected) {
        return c.getSharedPreferences("ui", 0).getBoolean(PREF, false);
    }

    static boolean locator(String url) {
        return url != null && (url.startsWith(ROOT) || AdapterSources.locator(url));
    }

    static String id(String url) {
        if (
            url == null ||
            !url.startsWith(ROOT) ||
            !url.substring(ROOT.length()).matches("[A-Za-z0-9:_-]{1,160}")
        ) {
            throw new IllegalArgumentException("Invalid provider channel");
        }
        return url.substring(ROOT.length());
    }

    private static JSONArray catalogue(Context c, NativeHttp http) throws Exception {
        String module = ProviderPackages.module(c);
        JSONArray rows = cached;
        if (rows != null && System.currentTimeMillis() < expires && module.equals(cachedModule)) return rows;
        JSONObject metadata = new JSONObject(ProviderBridge.call(c, module, "metadata", ""));
        String address = metadata.getString("url");
        java.net.URI uri = java.net.URI.create(Playlist.networkUrl(address, false));
        if (
            !"epg.unreel.me".equals(uri.getHost()) ||
            uri.getPort() != -1 ||
            !uri.getPath().startsWith("/v2/sites/freelivesports/live-channels/public/")
        ) {
            throw new IOException("Provider catalogue capability rejected");
        }
        Map<String, String> headers = new HashMap<>();
        JSONObject supplied = metadata.getJSONObject("headers");
        for (String key : Arrays.asList("User-Agent", "Accept", "Origin", "Referer")) {
            if (supplied.has(key)) headers.put(key, supplied.getString(key));
        }
        String raw = testCatalogue == null ? http.getHeaders(address, headers) : testCatalogue;
        rows = new JSONArray(ProviderBridge.call(c, module, "catalogue", raw));
        if (rows.length() == 0 || rows.length() > 5000) throw new IOException("Invalid provider catalogue");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String channel = row.getString("channelId");
            id(ROOT + channel);
            if (!seen.add(channel) || row.getString("name").trim().isEmpty()) throw new IOException(
                "Invalid provider row"
            );
            // Validate the URL after JS macro expansion, when brackets have become legal query data.
            String master = row.getString("streamUrl");
            if (!master.startsWith("https://") || master.length() > 16384) throw new IOException(
                "Unsupported provider stream"
            );
        }
        cachedModule = module;
        cached = rows;
        expires = System.currentTimeMillis() + 600_000;
        return rows;
    }

    static final class Stream {

        final String url, userAgent, referer;
        final long expiresAt;
        final JSONObject headers;
        final JSONObject playlistSession;
        final boolean segmentContainers;
        final boolean boundedRanges;

        Stream(JSONObject value) throws Exception {
            playlistSession = value.optJSONObject("playlistSession");
            segmentContainers = value.optBoolean("segmentContainers", false);
            boundedRanges = value.optBoolean("boundedRanges", false);
            expiresAt = value.optLong("expiresAtMs", 0);
            url = ProviderUrls.playback(value.getString("url"));
            headers = value.getJSONObject("headers");
            String host = java.net.URI.create(url).getHost();
            if (
                host == null || host.equalsIgnoreCase("localhost") || host.endsWith(".local")
            ) throw new IOException("Local playback target rejected");
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(host)) {
                if (
                    address.isAnyLocalAddress() ||
                    address.isLoopbackAddress() ||
                    address.isLinkLocalAddress() ||
                    address.isSiteLocalAddress() ||
                    address.isMulticastAddress() ||
                    (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)
                ) throw new IOException("Private playback target rejected");
            }
            userAgent = header(value.optString("userAgent"));
            referer = header(value.optString("referer"));
        }

        private static String header(String value) throws IOException {
            if (
                value.length() > 1024 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
            ) throw new IOException("Invalid playback header");
            return value;
        }
    }

    static Stream resolve(Context c, String locator, NativeHttp http) throws Exception {
        if (AdapterSources.locator(locator)) return AdapterSources.resolve(c, locator, http);
        String wanted = id(locator);
        JSONArray rows = catalogue(c, http);
        for (int i = 0; i < rows.length(); i++) {
            http.check();
            JSONObject row = rows.getJSONObject(i);
            if (wanted.equals(row.getString("channelId"))) {
                return new Stream(
                    new JSONObject(
                        ProviderBridge.call(
                            c,
                            ProviderPackages.module(c),
                            "resolve",
                            row.getString("streamUrl")
                        )
                    )
                );
            }
        }
        throw new IOException("Provider channel unavailable");
    }

    static int refresh(Context c, ChannelDatabase db, ChannelDatabase.Cancel cancel) throws Exception {
        expires = 0;
        cancel.check();
        JSONArray rows = catalogue(c, new NativeHttp());
        cancel.check();
        ChannelDatabase.Writer writer = db.writer(SOURCE, cancel, n -> {});
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                writer.accept(
                    new Playlist.Channel(
                        row.getString("name"),
                        ROOT + row.getString("channelId"),
                        // FreeLiveSports is a sports service (upstream files every channel as Sports).
                        ChannelCategory.of("Sports", row.getString("name")),
                        logo(row.optString("logo")),
                        SOURCE + ":" + row.getString("channelId")
                    )
                );
            }
            cancel.check();
            writer.commit();
        } finally {
            writer.abort();
        }
        // The server-generated guide owns programme data; catalogues only update channels.
        c.getSharedPreferences("providerEngine", 0)
            .edit()
            .putInt("flsCount", rows.length())
            .putLong("flsRefresh", System.currentTimeMillis())
            .apply();
        return rows.length();
    }

    private static String logo(String value) {
        try {
            return Playlist.networkUrl(value, false);
        } catch (Exception e) {
            return "";
        }
    }
}
