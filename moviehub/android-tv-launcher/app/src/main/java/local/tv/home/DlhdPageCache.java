package local.tv.home;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.regex.*;
import org.json.JSONObject;

/** Short-lived public mirror pages only. Never caches embeds, tokens, playlists or media. */
final class DlhdPageCache {

    static final long TTL_MS = 30_000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final LinkedHashMap<String, Entry> pages = new LinkedHashMap<>();
    private static int bytes;

    private static final class Entry {

        final String response;
        final long at;

        Entry(String response, long at) {
            this.response = response;
            this.at = at;
        }
    }

    private DlhdPageCache() {}

    static void clear() {
        synchronized (pages) {
            pages.clear();
            bytes = 0;
        }
    }

    private static boolean eligible(JSONObject request) {
        try {
            URI uri = URI.create(request.getString("url"));
            if (
                !"https".equals(uri.getScheme()) ||
                uri.getUserInfo() != null ||
                !"GET".equals(request.getString("method")) ||
                !request.optString("body").isEmpty()
            ) return false;
            JSONObject headers = request.getJSONObject("headers");
            for (java.util.Iterator<String> keys = headers.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (key.equalsIgnoreCase("Cookie") || key.equalsIgnoreCase("Authorization")) return false;
            }
            return (
                uri.getRawFragment() == null &&
                ((uri.getPath().matches("/(stream|cast|watch|plus|casting|player)/stream-[0-9]+\\.php") &&
                    uri.getRawQuery() == null) ||
                    (uri.getPath().equals("/watch.php") &&
                        uri.getRawQuery() != null &&
                        uri.getRawQuery().matches("id=[0-9]+")))
            );
        } catch (Exception e) {
            return false;
        }
    }

    /** Preserve exactly the literal inputs used by upstream findEmbedUrls/listPlayerPages. */
    static JSONObject compact(JSONObject response) throws Exception {
        String body = response.optString("text");
        if (response.optInt("status") != 200 || body.contains("/api/playlist-token")) return response;
        StringBuilder useful = new StringBuilder();
        String[] patterns = {
            "https?://[^\"'\\s)]+/premiumtv/[a-z0-9]+\\.php\\?id=\\d+",
            "<iframe[^>]*\\bsrc=[\"'][^\"']+[\"']",
            "data-url=[\"'][^\"']*?(/[a-z]+/stream-\\d+\\.php)[\"']",
        };
        for (String pattern : patterns) {
            Matcher matches = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(body);
            while (matches.find()) {
                useful.append(matches.group());
                if (pattern.startsWith("<iframe")) useful.append('>');
                useful.append('\n');
            }
        }
        if (useful.length() == 0 || useful.length() >= body.length()) return response;
        return new JSONObject(response.toString()).put("text", useful.toString());
    }

    static JSONObject request(String module, JSONObject request, ProviderBroker.Transport network)
        throws Exception {
        return request(module, request, network, true);
    }

    static JSONObject request(
        String module,
        JSONObject request,
        ProviderBroker.Transport network,
        boolean compactPages
    ) throws Exception {
        return requestAt(module, request, network, System.nanoTime() / 1_000_000, compactPages);
    }

    static JSONObject requestAt(String module, JSONObject request, ProviderBroker.Transport network, long now)
        throws Exception {
        return requestAt(module, request, network, now, true);
    }

    static JSONObject requestAt(
        String module,
        JSONObject request,
        ProviderBroker.Transport network,
        long now,
        boolean compactPages
    ) throws Exception {
        if (!eligible(request)) return network.request(request);
        String key = module + "\n" + compactPages + "\n" + request.toString();
        synchronized (pages) {
            Entry entry = pages.get(key);
            if (entry != null && now >= entry.at && now - entry.at < TTL_MS) return new JSONObject(
                entry.response
            );
            if (entry != null) bytes -= pages.remove(key).response.length() * 2;
        }
        JSONObject response = network.request(request);
        if (compactPages) response = compact(response);
        String body = response.optString("text");
        // A challenge, redirect or empty page is never remembered as a usable player page.
        if (
            response.optInt("status") != 200 || !(body.contains("<iframe") || body.contains("data-url="))
        ) return response;
        String encoded = response.toString();
        if (encoded.length() * 2 > MAX_BYTES) return response;
        synchronized (pages) {
            Entry old = pages.remove(key);
            if (old != null) bytes -= old.response.length() * 2;
            while (!pages.isEmpty() && (pages.size() >= 8 || bytes + encoded.length() * 2 > MAX_BYTES)) {
                bytes -= pages.remove(pages.keySet().iterator().next()).response.length() * 2;
            }
            pages.put(key, new Entry(encoded, now));
            bytes += encoded.length() * 2;
        }
        return response;
    }
}
