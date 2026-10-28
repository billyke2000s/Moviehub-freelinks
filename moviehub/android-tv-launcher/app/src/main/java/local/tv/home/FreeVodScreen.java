package local.tv.home;

import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Progressive movie/episode results share the existing source cards and VOD recovery. */
final class FreeVodScreen {

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    FreeVodScreen(MainActivity activity) {
        this.activity = activity;
    }

    void start(String imdb, int season, int episode, String title, String kind, JSONObject metadata) {
        try {
            JSONObject info = metadata == null ? new JSONObject() : metadata;
            String searchTitle = info.optString(season < 0 ? "title" : "name", title);
            JSONObject query = new JSONObject()
                .put("imdb", imdb)
                .put("tmdb", info.optInt("tmdbId", info.optInt("id")))
                .put("title", searchTitle)
                .put("localTitle", searchTitle)
                .put("aliases", aliases(info, season < 0))
                .put("year", info.optString(season < 0 ? "release_date" : "first_air_date").split("-")[0])
                .put("season", season)
                .put("episode", episode)
                .put("premiered", info.optString("air_date", info.optString("first_air_date")))
                .put("episodeTitle", info.optString("episodeTitle"))
                .put("http", activity.http());
            SourceRepository repo = new SourceRepository(activity.secrets);
            VodSearch search = new VodSearch(
                activity,
                query,
                repo,
                activity.secrets.has("premiumize"),
                activity.prefs.getInt(AutoPlayback.QUALITY, 1080)
            );
            TextView status = activity.muted(
                "Finding streams… choosing the best available in 3 to 8 seconds.",
                16
            );
            activity.page.addView(status);
            LinearLayout results = activity.vertical();
            activity.page.addView(results);
            boolean[] automatic = { AutoPlayback.enabled(activity, season) };
            Button choose = activity.button("Choose a source instead", () -> automatic[0] = false);
            activity.page.addView(choose);
            activity.page.addView(
                activity.button("Provider search details", () ->
                    SidePanel.of(activity, "Provider search details").message(search.report()).show()
                )
            );
            final int generation = activity.generation;
            Runnable refresh = new Runnable() {
                int count = -1;
                final Map<String, Button> displayed = new LinkedHashMap<>();
                boolean handedOff;

                public void run() {
                    if (
                        activity.isDestroyed() || activity.isFinishing() || activity.generation != generation
                    ) {
                        if (!handedOff) search.close();
                        return;
                    }
                    SourceRepository.Source best = search.selected();
                    if (automatic[0] && best != null) {
                        handedOff = true;
                        play(best, search, repo, metadata, imdb, season, episode, title, kind, false);
                        return;
                    }
                    List<SourceRepository.Source> found = search.snapshot();
                    if (count != found.size()) {
                        count = found.size();

                        Map<String, List<SourceRepository.Source>> groups = new LinkedHashMap<>();
                        for (var source : found)
                            groups
                                .computeIfAbsent(SourcesScreen.provider(source.title), key ->
                                    new ArrayList<>()
                                )
                                .add(source);
                        for (var group : groups.entrySet()) {
                            String provider = group.getKey();
                            Button row = displayed.get(provider);
                            if (row == null) {
                                row = activity.button(provider, () -> {
                                    automatic[0] = false;
                                    List<SourceRepository.Source> choices = new ArrayList<>();
                                    for (var source : search.snapshot())
                                        if (
                                            provider.equals(SourcesScreen.provider(source.title))
                                        ) choices.add(source);
                                    SidePanel panel = SidePanel.of(activity, provider);
                                    for (int i = 0; i < choices.size(); i++) {
                                        var source = choices.get(i);
                                        String host = java.net.URI.create(
                                            source.files.optJSONObject(0).optString("link")
                                        ).getHost();
                                        panel.option(
                                            SourcesScreen.badge(source) + " · " + host + " · Link " + (i + 1),
                                            () -> {
                                                handedOff = true;
                                                play(
                                                    source,
                                                    search,
                                                    repo,
                                                    metadata,
                                                    imdb,
                                                    season,
                                                    episode,
                                                    title,
                                                    kind,
                                                    true
                                                );
                                            }
                                        );
                                    }
                                    panel.show();
                                });
                                displayed.put(provider, row);
                                results.addView(row);
                            }
                            int links = group.getValue().size();
                            row.setText(provider + " · " + links + (links == 1 ? " link" : " links"));
                        }
                    }
                    boolean done = search.finished();
                    status.setText(
                        count > 0
                            ? displayed.size() +
                                  " providers · " +
                                  count +
                                  " links found · " +
                                  search.progress() +
                                  (done ? "" : " · searching for backups…")
                            : done
                              ? "No playable stream found · " +
                                search.progress() +
                                ". See provider search details."
                              : "No stream yet · " + search.progress() + " · still searching…"
                    );
                    // Even an immediate complete response waits for the original selection window.
                    if (!done || (automatic[0] && best == null && count > 0)) handler.postDelayed(this, 100);
                    else if (count == 0) search.close();
                }
            };
            handler.post(refresh);
        } catch (Exception error) {
            Diagnostics.recovered("Starting source search", error);
            activity.toast("Could not start the source search");
        }
    }

    static JSONArray aliases(JSONObject metadata, boolean movie) {
        Set<String> titles = new LinkedHashSet<>();
        String original = metadata.optString(movie ? "original_title" : "original_name");
        if (!original.trim().isEmpty()) titles.add(original);
        JSONObject alternatives = metadata.optJSONObject("alternative_titles");
        if (alternatives != null) {
            JSONArray rows = alternatives.optJSONArray(movie ? "titles" : "results");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String name = row.optString("title").trim();
                if (!name.isEmpty()) titles.add(name);
            }
        }
        return new JSONArray(titles);
    }

    private void play(
        SourceRepository.Source selected,
        VodSearch search,
        SourceRepository repo,
        JSONObject metadata,
        String imdb,
        int season,
        int episode,
        String title,
        String kind,
        boolean pinned
    ) {
        try {
            int pick = AutoPlayback.pick(selected.files, season, episode, title);
            if (pick < 0) throw new IllegalArgumentException("No exact playable file");
            String link = selected.files.getJSONObject(pick).getString("link");
            SourcesScreen.Binge.remember(imdb, season, episode, title, kind, metadata, selected.files);
            VodSources.Session session = VodSources.progressive(
                selected,
                link,
                search,
                repo,
                season,
                episode,
                title,
                pinned
            );
            activity.play(title, link, activity.id(selected.uri), false, metadata, session);
        } catch (Exception error) {
            Diagnostics.recovered("Opening free source", error);
            activity.toast("Could not open that source");
            search.close();
        }
    }
}
