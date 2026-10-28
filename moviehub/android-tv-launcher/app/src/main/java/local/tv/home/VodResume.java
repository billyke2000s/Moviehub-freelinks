package local.tv.home;

import org.json.JSONObject;

/** Resume a title/episode using fresh provider results; a saved stream URL is never its identity. */
final class VodResume {

    private VodResume() {}

    interface Metadata {
        JSONObject get(String path) throws Exception;
    }

    static JSONObject refresh(JSONObject recent, Metadata metadata) throws Exception {
        int tmdb = recent.optInt("tmdbId");
        if (tmdb <= 0) throw new IllegalArgumentException("Title identity unavailable");
        boolean series = recent.has("season") || "tv".equals(recent.optString("media_type"));
        JSONObject title = metadata.get(
            (series ? "tv/" : "movie/") + tmdb + "?append_to_response=external_ids,alternative_titles"
        );
        title.put("tmdbId", tmdb).put("media_type", series ? "tv" : "movie");
        if (series) {
            int season = recent.optInt("season", -1),
                episode = recent.optInt("episode", -1);
            if (season < 0 || episode < 1) throw new IllegalArgumentException("Episode identity unavailable");
            JSONObject detail = metadata.get("tv/" + tmdb + "/season/" + season + "/episode/" + episode);
            title
                .put("season", season)
                .put("episode", episode)
                .put("episodeKey", tmdb + ":" + season + ":" + episode)
                .put("episodeTitle", detail.optString("name"))
                .put("air_date", detail.optString("air_date"));
        }
        return title;
    }

    static void start(MainActivity activity, JSONObject recent, boolean restart) {
        if (restart) ResumePoint.clear(activity, recent.optString("id"), ResumePoint.titleKey(recent));
        if (recent.optInt("tmdbId") <= 0) {
            // A user-opened cloud/direct file has no title to rediscover.
            activity.play(
                recent.optString("title", "Video"),
                recent.optString("url"),
                recent.optString("id"),
                false,
                recent
            );
            return;
        }
        activity.screen("Finding a fresh source", activity.libraryScreens::continueWatching);
        activity.page.addView(activity.muted("Refreshing this title and searching providers…", 16));
        activity.async(
            () -> refresh(recent, activity.media::tmdb),
            title -> {
                JSONObject external = title.optJSONObject("external_ids");
                String imdb = title.optString(
                    "imdb_id",
                    external == null ? "" : external.optString("imdb_id")
                );
                boolean series = title.has("episode");
                activity.playbackTitle = title;
                String label = series
                    ? title.optString("name") +
                      String.format(
                          java.util.Locale.ROOT,
                          " S%02dE%02d",
                          title.getInt("season"),
                          title.getInt("episode")
                      )
                    : title.optString("title");
                activity.sources.torrentSources(
                    imdb,
                    series ? title.getInt("season") : -1,
                    series ? title.getInt("episode") : -1,
                    label,
                    series ? "Series" : "Movies"
                );
            }
        );
    }
}
