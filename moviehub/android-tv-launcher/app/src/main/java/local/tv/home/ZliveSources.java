package local.tv.home;

import android.content.Context;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.json.*;

/** Only advertised, supported public resolver routes become independently selectable copies. */
final class ZliveSources {

    static final String HINTS = "engineZliveSourceHints";
    static final String VARIANT = "--source--";

    private ZliveSources() {}

    private static String slug(JSONObject source, String channel) {
        // A labelled website option alone is not proof of an externally selectable route.
        String explicit = source.optString("resolverSlug", "");
        if (explicit.isEmpty() && source.optString("mode").equals("timstream")) explicit = source.optString(
            "slug",
            ""
        );
        if (explicit.matches("[A-Za-z0-9_-]{1,160}")) return explicit;
        String address = source.optString(
            "resolverUrl",
            source.optString("url", source.optString("key", ""))
        );
        try {
            URI uri = URI.create(address);
            if (
                "https".equals(uri.getScheme()) &&
                "iptv.zlive.st".equals(uri.getHost()) &&
                uri.getPort() == -1 &&
                uri.getUserInfo() == null &&
                uri.getQuery() == null &&
                uri.getFragment() == null &&
                uri.getPath().matches("/[A-Za-z0-9_-]{1,160}")
            ) return uri.getPath().substring(1);
        } catch (Exception ignored) {}
        return address.equals(channel) ? channel : "";
    }

    static JSONArray expand(JSONArray channels) throws Exception {
        JSONArray expanded = new JSONArray();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            String base = channel.getString("channelId");
            JSONArray sources = channel.optJSONArray("sources");
            Set<String> seen = new HashSet<>();
            int added = 0;
            if (sources != null) for (int j = 0; j < Math.min(32, sources.length()); j++) {
                JSONObject source = sources.optJSONObject(j);
                if (source == null || source.has("drm") || source.has("authorization")) continue;
                String resolver = slug(source, base);
                if (resolver.isEmpty()) continue;
                String identity = resolver.equals("skysportsf1-uk") ? "sky-sports-f1" : resolver;
                if (!seen.add(identity)) continue;
                JSONObject row = new JSONObject(channel.toString());
                row.remove("sources");
                String id =
                    added++ == 0
                        ? base
                        : base + VARIANT + ProviderCodec.call("sha256", identity).substring(0, 16);
                String label = source.optString("label", "").replaceAll("[\\p{Cntrl}]", " ").trim();
                if (label.isEmpty() || label.contains("://")) label = "Server " + added;
                if (label.length() > 80) label = label.substring(0, 80);
                row.put("channelId", id).put("resolverSlug", resolver).put("sourceLabel", label);
                row.put("guideChannelId", guideId(base, channel.optString("region")));
                JSONObject headers = new JSONObject(),
                    supplied = source.optJSONObject("headers");
                if (supplied != null) for (String key : Arrays.asList(
                    "User-Agent",
                    "Referer",
                    "Origin",
                    "Accept",
                    "Accept-Language"
                )) {
                    String value = supplied.optString(key, "");
                    if (
                        !value.isEmpty() &&
                        value.length() <= 1024 &&
                        !value.contains("\r") &&
                        !value.contains("\n")
                    ) headers.put(key, value);
                }
                row.put("sourceHeaders", headers);
                expanded.put(row);
            }
            if (added == 0) {
                JSONObject row = new JSONObject(channel.toString());
                row.remove("sources");
                row.put("resolverSlug", channel.optString("resolverSlug", base)).put(
                    "sourceLabel",
                    sources != null && sources.length() > 0 ? "Website-selected" : "Primary"
                );
                row.put("guideChannelId", guideId(base, channel.optString("region")));
                row.put("siteOnlySourceCount", sources == null ? 0 : sources.length());
                expanded.put(row);
            }
        }
        return expanded;
    }

    private static String guideId(String base, String region) {
        String country = region.trim().toLowerCase(Locale.ROOT);
        if (country.equals("gb") || country.equals("united kingdom")) country = "uk";
        if (country.equals("usa") || country.equals("united states")) country = "us";
        return country.matches("[a-z]{2}") ? base + "." + country : base;
    }

    static void save(Context context, String module, JSONArray rows) throws Exception {
        JSONObject map = new JSONObject();
        for (int i = 0; i < rows.length(); i++) map.put(
            rows.getJSONObject(i).getString("channelId"),
            rows.getJSONObject(i)
        );
        String value = new JSONObject()
            .put("module", ProviderCodec.call("sha256", module))
            .put("at", System.currentTimeMillis())
            .put("rows", map)
            .toString();
        if (value.length() > 2 * 1024 * 1024) throw new IOException("ZLive source hint limit");
        new Secrets(context).put(HINTS, value);
    }

    static JSONObject hint(Context context, String module, String id) {
        try {
            String saved = new Secrets(context).get(HINTS);
            if (saved.isEmpty()) return null;
            JSONObject value = new JSONObject(saved);
            long age = System.currentTimeMillis() - value.getLong("at");
            if (
                age < 0 ||
                age > 2 * 3600_000 ||
                !ProviderCodec.call("sha256", module).equals(value.getString("module"))
            ) return null;
            return value.getJSONObject("rows").optJSONObject(id);
        } catch (Exception e) {
            Diagnostics.recovered("Reading ZLive source hints", e);
            return null;
        }
    }
}
