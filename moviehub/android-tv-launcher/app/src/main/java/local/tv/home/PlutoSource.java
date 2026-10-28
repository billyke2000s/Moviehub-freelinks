package local.tv.home;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.json.*;

/** Anonymous, regional Pluto API. No region spoofing, ad stripping, DRM or account credentials. */
final class PlutoSource {

    static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0";
    static final String ROOT = "https://native.moviehub.invalid/pluto/";
    private static final String CLIENT = UUID.randomUUID().toString();

    interface Http {
        String get(String url, String bearer) throws Exception;
    }

    static final class Session {

        final String token, channels, stitcher, params, country;
        final long expires;

        Session(JSONObject boot, long now) throws Exception {
            token = boot.getString("sessionToken");
            JSONObject servers = boot.getJSONObject("servers");
            channels = endpoint(servers.getString("channels"));
            stitcher = endpoint(servers.getString("stitcher"));
            params = boot.getString("stitcherParams");
            String region = boot.getJSONObject("session").getString("countryCode").toLowerCase(Locale.ROOT);
            country = region.equals("gb") ? "uk" : region;
            if (token.isEmpty() || !country.matches("[a-z]{2}")) throw new IOException("Invalid session");
            expires = now + Math.min(600, Math.max(1, boot.optInt("refreshInSec", 600))) * 1000L;
        }
    }

    private static volatile Session cached;
    private static volatile Catalogue catalogue;

    private static final class Catalogue {

        final Session session;
        final List<Playlist.Channel> channels;

        Catalogue(Session session, List<Playlist.Channel> channels) {
            this.session = session;
            this.channels = Collections.unmodifiableList(channels);
        }
    }

    static void invalidate() {
        cached = null;
        catalogue = null;
    }

    static Session session(Http http) throws Exception {
        Session current = cached;
        long now = System.currentTimeMillis();
        if (current != null && current.expires > now) return current;
        String url =
            "https://boot.pluto.tv/v4/start?appName=web&appVersion=9.0.0" +
            "&deviceVersion=145.0&deviceModel=web&deviceMake=firefox&deviceType=web" +
            "&clientModelNumber=1.0.0&serverSideAds=true&clientID=" +
            CLIENT;
        current = new Session(new JSONObject(http.get(url, null)), now);
        cached = current;
        return current;
    }

    /** Only provider-returned Pluto endpoints may receive its anonymous bearer token. */
    static String endpoint(String url) {
        URI uri = URI.create(Playlist.networkUrl(url, false));
        if (
            !uri.getHost().endsWith(".pluto.tv") ||
            uri.getPort() != -1 ||
            uri.getQuery() != null ||
            uri.getFragment() != null ||
            !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
        ) throw new IllegalArgumentException("Invalid provider endpoint");
        return url.replaceAll("/+$", "");
    }

    static boolean nativeUrl(String url) {
        return url != null && url.startsWith(ROOT);
    }

    static String channelId(String url) {
        if (
            !nativeUrl(url) || !url.substring(ROOT.length()).matches("[0-9a-f]{24}")
        ) throw new IllegalArgumentException("Invalid native channel");
        return url.substring(ROOT.length());
    }

    static List<Playlist.Channel> catalogue(Http http) throws Exception {
        Session session = session(http);
        return catalogue(session, http);
    }

    private static List<Playlist.Channel> catalogue(Session session, Http http) throws Exception {
        Catalogue current = catalogue;
        if (current != null && current.session == session) return current.channels;
        List<Playlist.Channel> channels = parseCatalogue(
            http.get(session.channels + "/v2/guide/channels", session.token),
            session.country
        );
        catalogue = new Catalogue(session, channels);
        return channels;
    }

    static List<Playlist.Channel> parseCatalogue(String raw, String country) throws Exception {
        JSONObject response = new JSONObject(raw);
        JSONArray rows = response.getJSONArray("data");
        if (rows.length() > 5000) throw new IOException("Catalogue too large");
        if (
            response.optJSONObject("meta") != null &&
            response.getJSONObject("meta").optInt("totalCount", rows.length()) > rows.length()
        ) throw new IOException("Incomplete catalogue");
        List<Playlist.Channel> channels = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (
                row == null || row.optBoolean("plutoOfficeOnly") || !row.optBoolean("isStitched", true)
            ) continue;
            String id = row.optString("id"),
                name = row.optString("name").trim();
            if (!id.matches("[0-9a-f]{24}") || name.isEmpty() || !seen.add(id)) continue;
            String logo = "";
            JSONArray images = row.optJSONArray("images");
            if (images != null) for (int j = 0; j < images.length(); j++) {
                JSONObject image = images.optJSONObject(j);
                if (image != null && "colorLogoPNG".equals(image.optString("type"))) {
                    try {
                        logo = Playlist.networkUrl(image.optString("url"), false);
                    } catch (IllegalArgumentException ignored) {}
                    break;
                }
            }
            channels.add(
                new Playlist.Channel(name, ROOT + id, "Pluto TV", logo, "pluto:" + id + "." + country)
            );
        }
        if (channels.isEmpty()) throw new IOException("No regional channels available");
        return channels;
    }

    static String resolve(String locator, Http http) throws Exception {
        String id = channelId(locator);
        Session session = session(http);
        // Validate membership in the current regional catalogue; never use a foreign-region ID.
        boolean available = false;
        for (Playlist.Channel channel : catalogue(session, http))
            if (channel.url.equals(locator)) {
                available = true;
                break;
            }
        if (!available) throw new IOException("Channel unavailable in this region");
        return stream(session, id);
    }

    static String stream(Session session, String id) {
        if (!id.matches("[0-9a-f]{24}")) throw new IllegalArgumentException("Invalid channel id");
        return (
            session.stitcher +
            "/v2/stitch/hls/channel/" +
            id +
            "/master.m3u8?" +
            session.params +
            "&jwt=" +
            Network.q(session.token) +
            "&masterJWTPassthrough=true"
        );
    }

    /** A day of listings in batches of 20 channels, from the same regional session. */
}
