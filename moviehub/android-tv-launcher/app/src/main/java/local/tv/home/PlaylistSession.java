package local.tv.home;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import org.json.JSONObject;

/** Anonymous, in-memory playlist tokens scoped to one discovered CDN and channel path. */
final class PlaylistSession {

    interface Fetch {
        JSONObject token(URI endpoint, JSONObject body) throws Exception;
    }

    private final URI endpoint;
    private final String origin, prefix, slug;
    private final boolean freeShot;
    private String token;
    private long expiresAt;

    PlaylistSession(String address, JSONObject value) throws Exception {
        URI stream = URI.create(ProviderUrls.playback(address));
        endpoint = URI.create(ProviderUrls.playback(value.getString("endpoint")));
        slug = value.getString("slug");
        String type = value.optString("type", "dlhd");
        if (!type.equals("dlhd") && !type.equals("freeshot")) throw new IOException(
            "Unknown playlist session"
        );
        freeShot = type.equals("freeshot");
        if (!slug.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Invalid playlist scope");
        origin = "https://" + stream.getRawAuthority();
        prefix = "/" + slug + "/";
        boolean validEndpoint = freeShot
            ? "wideiptv.top".equals(endpoint.getHost()) &&
              endpoint.getPort() == -1 &&
              "/api/refresh_token.php".equals(endpoint.getRawPath()) &&
              endpoint.getRawQuery() == null &&
              stream.getHost().endsWith(".bluetier.top") &&
              (stream.getPort() == -1 || stream.getPort() == 443)
            : sameOrigin(endpoint) &&
              "/api/playlist-token".equals(endpoint.getRawPath()) &&
              ("slug=" + slug).equals(endpoint.getRawQuery());
        if (
            !validEndpoint || endpoint.getRawFragment() != null || !stream.getRawPath().startsWith(prefix)
        ) throw new IOException("Invalid playlist session endpoint");
        token = validToken(value.getString("token"));
        expiresAt = Math.min(value.getLong("expiresAtMs"), System.currentTimeMillis() + 600_000);
    }

    private boolean sameOrigin(URI uri) {
        return origin.equalsIgnoreCase(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    boolean applies(URI uri, boolean playlist) {
        return (playlist || freeShot) && sameOrigin(uri) && uri.getRawPath().startsWith(prefix);
    }

    synchronized URI authorize(URI uri, boolean playlist, boolean force, Fetch fetch) throws Exception {
        if (!applies(uri, playlist)) return uri;
        if (force || System.currentTimeMillis() + 5000 >= expiresAt) {
            JSONObject body = freeShot
                ? new JSONObject().put("channel", slug).put("current_token", token)
                : null;
            JSONObject value = fetch.token(endpoint, body);
            if (freeShot && !value.optBoolean("success")) throw new IOException("Playlist renewal refused");
            String next = validToken(value.getString("token"));
            long ttl = Math.max(20, Math.min(600, value.optLong(freeShot ? "expires_in" : "ttl", 120)));
            token = next;
            expiresAt = System.currentTimeMillis() + ttl * 1000;
        }
        String clean = withoutToken(uri).toString();
        return URI.create(
            clean +
                (clean.contains("?") ? "&" : "?") +
                (freeShot ? "token=" : "t=") +
                URLEncoder.encode(token, "UTF-8")
        );
    }

    static URI withoutToken(URI uri) {
        StringBuilder query = new StringBuilder();
        if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
            String key = pair.split("=", 2)[0];
            if (key.equals("t") || key.equals("token") || key.equals("_") || pair.isEmpty()) continue;
            if (query.length() > 0) query.append('&');
            query.append(pair);
        }
        return URI.create(
            uri.getScheme() +
                "://" +
                uri.getRawAuthority() +
                uri.getRawPath() +
                (query.length() == 0 ? "" : "?" + query)
        );
    }

    private String validToken(String value) throws IOException {
        if (
            !value.matches(freeShot ? "[A-Za-z0-9+/=._~-]{1,4096}" : "[A-Za-z0-9._~-]{1,4096}")
        ) throw new IOException("Invalid playlist token");
        return value;
    }
}
