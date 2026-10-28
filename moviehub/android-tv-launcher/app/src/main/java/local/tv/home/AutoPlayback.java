package local.tv.home;

import java.util.*;
import org.json.*;

/** Optional one-click films/series. Resolve a bounded number of cached sources; never queue a transfer. */
final class AutoPlayback {

    static final String MOVIES = "autoPlayMovies",
        SERIES = "autoPlaySeries",
        QUALITY = "autoPlayQuality";
    private final MainActivity activity;

    AutoPlayback(MainActivity activity) {
        this.activity = activity;
    }

    static boolean enabled(MainActivity activity, int season) {
        return activity.prefs.getBoolean(season < 0 ? MOVIES : SERIES, true);
    }

    static int pick(JSONArray files, int season, int episode, String title) {
        List<String> paths = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            paths.add(
                file == null || file.optString("link").isEmpty()
                    ? ""
                    : file.optString("path", file.optString("name"))
            );
            sizes.add(file == null ? 0L : file.optLong("size"));
        }
        return season >= 0 && episode > 0
            ? VideoFiles.autoPick(paths, sizes, season, episode)
            : VideoFiles.recommendMovie(paths, sizes, title);
    }

    static final class Selection {

        final SourceRepository.Source source;
        final JSONArray files;
        final int file;

        Selection(SourceRepository.Source source, JSONArray files, int file) {
            this.source = source;
            this.files = files;
            this.file = file;
        }
    }

    interface Direct {
        JSONArray files(SourceRepository.Source source) throws Exception;
    }

    static Selection resolve(
        List<SourceRepository.Source> sources,
        Direct direct,
        int season,
        int episode,
        String title,
        int quality,
        boolean http,
        java.util.concurrent.atomic.AtomicBoolean cancelled
    ) {
        List<SourceRepository.Source> ready = new ArrayList<>();
        for (var source : sources) if (source.cache == SourceRepository.Cache.CACHED) ready.add(source);
        ready.sort(
            Comparator.comparingInt((SourceRepository.Source source) ->
                SourceRanking.rank(source.title, quality)
            ).reversed()
        );
        for (int i = 0; i < Math.min(5, ready.size()); i++) {
            if (cancelled.get()) return null;
            var source = ready.get(i);
            try {
                JSONArray files = direct.files(source);
                int pick = pick(files, season, episode, title);
                if (pick < 0) continue;
                Playlist.networkUrl(files.getJSONObject(pick).optString("link"), http);
                return new Selection(source, files, pick);
            } catch (Exception error) {
                Diagnostics.recovered("Automatic source selection", error);
            }
        }
        return null;
    }

    void start(
        List<SourceRepository.Source> sources,
        SourceRepository repo,
        JSONObject metadata,
        String imdb,
        int season,
        int episode,
        String title,
        String kind,
        Runnable manual
    ) {
        var status = activity.muted("Finding the best ready-to-play source…", 16);
        activity.page.addView(status);
        var choose = activity.button("Choose a source instead", () -> {});
        activity.page.addView(choose);
        // A request can be cancelled without leaving the source screen. Late results cannot start playback.
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean();
        choose.setOnClickListener(v -> {
            if (cancelled.get()) return;
            cancelled.set(true);
            activity.page.removeView(status);
            activity.page.removeView(choose);
            manual.run();
        });
        int quality = activity.prefs.getInt(QUALITY, 1080);
        boolean http = activity.http();
        activity.async(
            () -> {
                return resolve(
                    sources,
                    source -> repo.direct(source),
                    season,
                    episode,
                    title,
                    quality,
                    http,
                    cancelled
                );
            },
            selected -> {
                if (cancelled.get()) return;
                activity.page.removeView(status);
                activity.page.removeView(choose);
                if (selected == null) {
                    activity.toast("No clear playable match. Choose a source or file.");
                    manual.run();
                    return;
                }
                JSONObject file = selected.files.getJSONObject(selected.file);
                SourcesScreen.Binge.remember(imdb, season, episode, title, kind, metadata, selected.files);
                activity.play(
                    title,
                    file.getString("link"),
                    activity.id(selected.source.uri + ":" + file.optString("path", file.optString("name"))),
                    false,
                    metadata,
                    VodSources.prepare(
                        selected.source,
                        file.getString("link"),
                        selected.files,
                        sources,
                        repo,
                        season,
                        episode,
                        title,
                        quality,
                        false
                    )
                );
            }
        );
    }
}
