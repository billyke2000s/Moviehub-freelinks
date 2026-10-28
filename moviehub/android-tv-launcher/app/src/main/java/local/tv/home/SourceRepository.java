package local.tv.home;

import java.util.*;
import org.json.*;

final class SourceRepository {

    enum Cache {
        CACHED,
        NOT_CACHED,
        UNKNOWN,
    }

    static final class Source {

        final String title, uri;
        Cache cache = Cache.UNKNOWN;
        final JSONArray files;
        final JSONObject headers;
        EngineSources.Stream stream;
        String ranking;
        double probeMbps;
        long responseMs = Long.MAX_VALUE;
        /** The quality was read from the file or stream itself, not taken from the website. */
        boolean confirmed;
        /** A streaming playlist (HLS/DASH), whose player may choose a lower step than offered. */
        boolean playlist;
        /** Free sources: the website searched, and the quality it claimed for this link. */
        String site = "",
            claimed = "";

        Source(String title, String uri) {
            this(title, uri, null, new JSONObject());
        }

        Source(String title, String uri, JSONArray files, JSONObject headers) {
            this.title = title;
            this.uri = uri;
            this.files = files;
            this.headers = headers;
            if (files != null) cache = Cache.CACHED;
        }
    }

    private final Secrets secrets;

    SourceRepository(Secrets secrets) {
        this.secrets = secrets;
    }

    List<Source> torrentio(String imdb, int season, int episode) throws Exception {
        if (!imdb.matches("tt[0-9]+")) throw new IllegalArgumentException("IMDb ID unavailable");
        String type = season < 0 ? "movie" : "series",
            id = season < 0 ? imdb : imdb + ":" + season + ":" + episode;
        List<String> providers = new ArrayList<>();
        providers.add("https://torrentio.strem.fun");
        for (String manifest : DefaultSourceProviders.backups())
            providers.add(manifest.replaceAll("/manifest\\.json$", "").replaceAll("/+$", ""));
        List<Source> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Exception failure = null;
        for (String provider : providers) {
            try {
                List<Source> batch = new ArrayList<>();
                long started = Timings.start();
                String body = Network.text(provider + "/stream/" + type + "/" + id + ".json", null, false);
                Timings.record("Finding stream sources", started);
                JSONArray streams = new JSONObject(body).getJSONArray("streams");
                for (int i = 0; i < streams.length() && batch.size() < 200; i++) {
                    JSONObject stream = streams.getJSONObject(i);
                    String hash = stream.optString("infoHash"),
                        uri = stream.optString("url");
                    if (hash.matches("[a-fA-F0-9]{40}")) uri = "magnet:?xt=urn:btih:" + hash;
                    else if (!uri.startsWith("magnet:?")) continue;
                    if (!uri.matches(".*(?:urn:btih:|urn%3Abtih%3A)[a-fA-F0-9]{40}.*")) continue;
                    if (seen.add(uri)) batch.add(
                        new Source(
                            stream.optString("name", "Source") +
                                "\n" +
                                stream.optString("title", stream.optString("description", "")),
                            uri
                        )
                    );
                }
                if (!batch.isEmpty()) {
                    try {
                        check(batch);
                    } catch (Exception e) {
                        for (Source source : batch) source.cache = Cache.UNKNOWN;
                    }
                    result.addAll(batch);
                    for (Source source : batch) if (source.cache == Cache.CACHED) return result;
                }
            } catch (Exception e) {
                failure = e;
            }
        }
        if (failure != null && result.isEmpty()) throw new Exception("Source providers unavailable", failure);
        return result;
    }

    List<Source> check(List<Source> items) throws Exception {
        StringBuilder form = new StringBuilder();
        for (Source source : items) {
            if (form.length() > 0) form.append('&');
            form.append("items%5B%5D=").append(Network.q(source.uri));
        }
        if (items.isEmpty()) return items;
        long started = Timings.start();
        JSONObject reply = post("cache/check", form.toString());
        Timings.record("Premiumize cache check", started);
        JSONArray statuses = reply.getJSONArray("response");
        if (statuses.length() != items.size()) throw new Exception("Incomplete cache response");
        for (int i = 0; i < items.size(); i++) {
            Object state = statuses.get(i);
            items.get(i).cache =
                state instanceof Boolean
                    ? (Boolean) state
                        ? Cache.CACHED
                        : Cache.NOT_CACHED
                    : Cache.UNKNOWN;
        }
        return items;
    }

    JSONObject post(String endpoint, String body) throws Exception {
        JSONObject reply = new JSONObject(
            Network.post("https://www.premiumize.me/api/" + endpoint, secrets.get("premiumize"), body)
        );
        if (!"success".equals(reply.optString("status"))) throw new Exception("Premiumize request failed");
        return reply;
    }

    JSONArray direct(Source source) throws Exception {
        if (source.files != null) return source.files;
        long started = Timings.start();
        JSONArray files = post("transfer/directdl", "src=" + Network.q(source.uri)).getJSONArray("content");
        Timings.record("Premiumize file link", started);
        return files;
    }

    String queue(Source source) throws Exception {
        return post("transfer/create", "src=" + Network.q(source.uri)).getString("id");
    }
}
