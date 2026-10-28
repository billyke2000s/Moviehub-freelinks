package local.tv.home;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.json.*;

/** Continue within a pack, or discover the next aired episode across season boundaries. */
final class SeriesPlayback {

    private final MainActivity activity;
    private static volatile Pending pending;

    private static final class Pending {

        final String key;
        final long created = System.currentTimeMillis();
        final java.util.concurrent.FutureTask<Next> result;

        Pending(String key, java.util.concurrent.FutureTask<Next> result) {
            this.key = key;
            this.result = result;
        }
    }

    private static String key(JSONObject metadata) {
        return (
            metadata.optInt("tmdbId", metadata.optInt("id")) +
            ":" +
            metadata.optInt("season", -1) +
            ":" +
            metadata.optInt("episode", -1)
        );
    }

    /** Fetch the small next-episode record while the current episode plays. */
    static void prefetch(MainActivity activity, JSONObject metadata) {
        if (metadata == null || !metadata.has("episodeKey")) return;
        try {
            JSONObject snapshot = new JSONObject(metadata.toString());
            var task = new java.util.concurrent.FutureTask<Next>(() ->
                resolve(
                    snapshot,
                    snapshot.optInt("season", -1),
                    snapshot.optInt("episode", -1),
                    path -> activity.media.tmdb(path),
                    LocalDate.now(ZoneOffset.UTC).toString()
                )
            );
            pending = new Pending(key(snapshot), task);
            activity.io.execute(task);
        } catch (Exception error) {
            Diagnostics.recovered("Preparing the next episode", error);
        }
    }

    private Next lookup(JSONObject current, int season, int episode) throws Exception {
        Pending cached = pending;
        if (
            cached != null &&
            cached.result.isDone() &&
            cached.key.equals(key(current)) &&
            System.currentTimeMillis() - cached.created < 30 * 60 * 1000L
        ) {
            try {
                return cached.result.get();
            } catch (Exception error) {
                if (pending == cached) pending = null;
                throw error;
            }
        }
        return resolve(
            current,
            season,
            episode,
            path -> activity.media.tmdb(path),
            LocalDate.now(ZoneOffset.UTC).toString()
        );
    }

    SeriesPlayback(MainActivity activity) {
        this.activity = activity;
    }

    static String imdb(JSONObject metadata) {
        JSONObject external = metadata.optJSONObject("external_ids");
        return metadata.optString("imdb_id", external == null ? "" : external.optString("imdb_id"));
    }

    /** Continue Watching can restore an episode after the app process was restarted. */
    static void restore(JSONObject metadata) {
        if (metadata == null || !metadata.has("episodeKey")) return;
        JSONObject previous = SourcesScreen.Binge.metadata;
        if (
            previous != null && metadata.optString("episodeKey").equals(previous.optString("episodeKey"))
        ) return;
        SourcesScreen.Binge.remember(
            imdb(metadata),
            metadata.optInt("season", -1),
            metadata.optInt("episode", -1),
            metadata.optString("name", "Series"),
            "Series",
            metadata,
            null
        );
    }

    interface Metadata {
        JSONObject get(String path) throws Exception;
    }

    static final class Next {

        final int season, episode;
        final JSONObject metadata;

        Next(int season, int episode, JSONObject metadata) {
            this.season = season;
            this.episode = episode;
            this.metadata = metadata;
        }
    }

    static Next resolve(JSONObject current, int season, int episode, Metadata tmdb, String today)
        throws Exception {
        int id = current.optInt("tmdbId", current.optInt("id"));
        if (id <= 0) throw new IllegalArgumentException("Series metadata unavailable");
        if (imdb(current).isEmpty() || !current.has("name")) {
            JSONObject details = tmdb.get("tv/" + id + "?append_to_response=external_ids,alternative_titles");
            current = new JSONObject(current.toString());
            current.put("name", details.optString("name", "Series"));
            for (String field : new String[] { "original_name", "alternative_titles", "first_air_date" }) {
                if (details.has(field)) current.put(field, details.get(field));
            }
            if (details.has("external_ids")) current.put("external_ids", details.get("external_ids"));
        }
        JSONArray episodes = tmdb.get("tv/" + id + "/season/" + season).getJSONArray("episodes");
        boolean foundCurrent = false;
        for (int i = 0; i < episodes.length(); i++) if (
            episodes.getJSONObject(i).optInt("episode_number") == episode
        ) foundCurrent = true;
        if (!foundCurrent) throw new IllegalArgumentException(
            "Current episode is missing from the catalogue"
        );
        int next = nextNumber(episodes, episode, today);
        if (next > 0) return prepare(current, season, next, episodes, id);
        // A future/unknown next episode in this season is a stop, not a reason to skip to another season.
        for (int i = 0; i < episodes.length(); i++) if (
            episodes.getJSONObject(i).optInt("episode_number") > episode
        ) return null;
        JSONObject details = tmdb.get("tv/" + id);
        JSONArray seasons = details.optJSONArray("seasons");
        List<Integer> numbers = new ArrayList<>();
        if (seasons != null) for (int i = 0; i < seasons.length(); i++) {
            JSONObject item = seasons.optJSONObject(i);
            if (item != null && item.optInt("episode_count") > 0) numbers.add(item.optInt("season_number"));
        }
        for (int number : EpisodeOrder.laterSeasons(numbers, season)) {
            episodes = tmdb.get("tv/" + id + "/season/" + number).getJSONArray("episodes");
            if (episodes.length() == 0) continue;
            next = nextNumber(episodes, 0, today);
            return next > 0 ? prepare(current, number, next, episodes, id) : null;
        }
        return null;
    }

    private static int nextNumber(JSONArray episodes, int after, String today) throws JSONException {
        List<EpisodeOrder.Episode> items = new ArrayList<>();
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject episode = episodes.getJSONObject(i);
            items.add(
                new EpisodeOrder.Episode(
                    episode.optInt("episode_number"),
                    episode.optString("air_date", null)
                )
            );
        }
        return EpisodeOrder.next(items, after, today);
    }

    private static Next prepare(JSONObject current, int season, int episode, JSONArray episodes, int id)
        throws JSONException {
        JSONObject metadata = new JSONObject(current.toString());
        metadata.put("id", id).put("tmdbId", id);
        metadata
            .put("season", season)
            .put("episode", episode)
            .put("episodeKey", id + ":" + season + ":" + episode);
        metadata.remove("episodeTitle");
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject item = episodes.getJSONObject(i);
            if (item.optInt("episode_number") == episode) {
                metadata.put("episodeTitle", item.optString("name"));
                metadata.put("air_date", item.optString("air_date", ""));
            }
        }
        return new Next(season, episode, metadata);
    }

    void next() {
        String imdb = SourcesScreen.Binge.imdb;
        int season = SourcesScreen.Binge.season,
            episode = SourcesScreen.Binge.episode;
        JSONObject current = SourcesScreen.Binge.metadata;
        JSONArray pack = SourcesScreen.Binge.files;
        if (imdb == null || season < 0 || episode <= 0 || current == null) return;
        String kind = SourcesScreen.Binge.kind;
        String title = current.optString("name", "Series");
        activity.screen("Up next", () ->
            activity.seriesScreens.seasons(current.optInt("tmdbId", current.optInt("id")), title, season)
        );
        activity.page.addView(activity.muted("Finding the next episode…", 16));
        activity.async(
            () -> lookup(current, season, episode),
            next -> {
                activity.page.removeAllViews();
                if (next == null) {
                    activity.emptyState(
                        activity.page,
                        "You're up to date",
                        "No further aired episode is available in the catalogue.",
                        "View episodes",
                        () ->
                            activity.seriesScreens.seasons(
                                current.optInt("tmdbId", current.optInt("id")),
                                title,
                                season
                            )
                    );
                    return;
                }
                String label =
                    next.metadata.optString("name", title) + " · S" + next.season + " E" + next.episode;
                if (!next.metadata.optString("episodeTitle").isEmpty()) label +=
                    " · " + next.metadata.optString("episodeTitle");
                final String nextTitle = label;
                int pick = pack == null ? -1 : AutoPlayback.pick(pack, next.season, next.episode, title);
                if (pick >= 0) {
                    JSONObject file = pack.getJSONObject(pick);
                    SourcesScreen.Binge.remember(
                        imdb,
                        next.season,
                        next.episode,
                        title,
                        kind,
                        next.metadata,
                        pack
                    );
                    activity.play(
                        nextTitle,
                        file.getString("link"),
                        activity.id(file.getString("link")),
                        false,
                        next.metadata
                    );
                    return;
                }
                String nextImdb = imdb(next.metadata);
                if (nextImdb.isEmpty()) nextImdb = imdb;
                final String sourceImdb = nextImdb;
                Runnable search = () -> {
                    activity.playbackTitle = next.metadata;
                    activity.sources.torrentSources(sourceImdb, next.season, next.episode, nextTitle, kind);
                };
                if (AutoPlayback.enabled(activity, next.season)) {
                    activity.toast("Searching for the next episode…");
                    search.run();
                } else {
                    activity.pageTitle(
                        activity.page,
                        "Next episode not in this pack",
                        nextTitle,
                        "Search other sources to continue watching."
                    );
                    activity.page.addView(activity.button("Search for the next episode", search));
                }
            }
        );
    }
}
