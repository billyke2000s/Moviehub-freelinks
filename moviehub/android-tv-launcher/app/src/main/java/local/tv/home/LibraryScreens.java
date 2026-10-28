package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** My List, Continue Watching and saved provider libraries. */
final class LibraryScreens {

    private static final String[] SORTS = { "Recently added", "Title A–Z", "Title Z–A" };
    private final MainActivity activity;

    LibraryScreens(MainActivity activity) {
        this.activity = activity;
    }

    private JSONArray watchlistItems() {
        try {
            return new JSONArray(activity.prefs.getString("watchlist", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    boolean isWatchlisted(JSONObject item) {
        JSONArray saved = watchlistItems();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject row = saved.optJSONObject(i);
            if (
                row != null &&
                row.optInt("id") == item.optInt("id") &&
                row.optString("media_type").equals(item.optString("media_type"))
            ) return true;
        }
        return false;
    }

    void toggleWatchlist(JSONObject item) {
        JSONArray saved = watchlistItems(),
            next = new JSONArray();
        boolean found = false;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject row = saved.optJSONObject(i);
            if (row == null) continue;
            if (
                row.optInt("id") == item.optInt("id") &&
                row.optString("media_type").equals(item.optString("media_type"))
            ) {
                found = true;
                continue;
            }
            next.put(row);
        }
        if (!found) {
            JSONObject entry = new JSONObject();
            try {
                for (String key : new String[] {
                    "id",
                    "title",
                    "name",
                    "media_type",
                    "poster_path",
                    "backdrop_path",
                    "overview",
                })
                    if (item.has(key)) entry.put(key, item.get(key));
                next.put(entry);
            } catch (Exception e) {
                Diagnostics.recovered("Copying a My List entry", e);
            }
        }
        activity.prefs.edit().putString("watchlist", next.toString()).apply();
    }

    void watchlist() {
        watchlist("All", activity.prefs.getString("watchlistSort", "Recently added"));
    }

    void watchlist(String filter, String sort) {
        activity.destination = () -> watchlist(filter, sort);
        activity.screen("My list", activity.homeScreen::home);
        activity.myListRefresh = () -> watchlist(filter, sort);
        LinearLayout tools = activity.horizontal(activity.page);
        for (String kind : new String[] { "All", "Movies", "Series" }) {
            Button tab = activity.chip(tools, kind, () -> watchlist(kind, sort));
            if (kind.equals(filter)) activity.primary(tab);
        }
        activity.chip(tools, "Sort: " + sort, () ->
            SidePanel.of(activity, "Sort My list")
                .choices(SORTS, java.util.Arrays.asList(SORTS).indexOf(sort), which -> {
                    activity.prefs.edit().putString("watchlistSort", SORTS[which]).apply();
                    watchlist(filter, SORTS[which]);
                })
                .show()
        );
        List<JSONObject> rows = new ArrayList<>();
        JSONArray saved = watchlistItems();
        for (int i = saved.length() - 1; i >= 0; i--) {
            JSONObject item = saved.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("media_type", item.has("title") ? "movie" : "tv");
            if (filter.equals("All") || filter.equals(type.equals("tv") ? "Series" : "Movies")) rows.add(
                item
            );
        }
        if (sort.startsWith("Title")) {
            rows.sort(
                Comparator.comparing(item ->
                    item.optString("title", item.optString("name", "")).toLowerCase(Locale.ROOT)
                )
            );
            if (sort.endsWith("Z–A")) Collections.reverse(rows);
        }
        activity.page.addView(
            activity.muted(
                rows.size() + (rows.size() == 1 ? " title" : " titles") + " · Hold Select for options",
                13
            )
        );
        if (rows.isEmpty()) {
            activity.emptyState(
                activity.page,
                "Your next watch starts here",
                filter.equals("All")
                    ? "Save movies and series from their details page."
                    : "No saved " + filter.toLowerCase(Locale.ROOT) + " yet.",
                "Browse " + (filter.equals("Series") ? "series" : "movies"),
                () -> library(filter.equals("Series") ? "Series" : "Movies")
            );
            return;
        }
        LinearLayout grid = null;
        for (int i = 0; i < rows.size(); i++) {
            if (i % 6 == 0) {
                grid = activity.row();
                grid.setGravity(Gravity.TOP);
                activity.page.addView(grid);
            }
            View card = activity.posters.posterCard(false);
            activity.posters.bindPoster(card, rows.get(i), false, null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(activity.dp(4), activity.dp(8), activity.dp(4), activity.dp(8));
            grid.addView(card, lp);
        }
        if (grid != null) while (grid.getChildCount() < 6)
            grid.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
    }

    void continueWatching() {
        activity.screen("Continue watching", activity.homeScreen::home);
        activity.resumeRefresh = this::continueWatching;
        activity.page.addView(activity.muted("Select to resume · Hold Select for more", 13));
        LinearLayout controls = activity.horizontal(activity.page);
        activity.chip(controls, "My list", this::watchlist);
        activity.chip(controls, "Clear history", () ->
            SidePanel.of(activity, "Clear viewing history?")
                .message("This removes saved playback progress on this device. My List is kept.")
                .action("Clear history", () -> {
                    activity.getSharedPreferences("positions", Context.MODE_PRIVATE).edit().clear().apply();
                    try {
                        activity.secrets.put("recentPlayback", "[]");
                    } catch (Exception e) {
                        activity.toast("Could not clear history");
                    }
                    continueWatching();
                })
                .action("Cancel", null)
                .show()
        );
        try {
            String stored = activity.secrets.get("recentPlayback");
            JSONArray recent = new JSONArray(stored.isEmpty() ? "[]" : stored);
            Map<String, ?> positions = activity
                .getSharedPreferences("positions", Context.MODE_PRIVATE)
                .getAll();
            Set<String> watched = activity.prefs.getStringSet("watchedEpisodes", Collections.emptySet());
            List<ContinueWatching.Entry> history = new ArrayList<>();
            for (int i = 0; i < recent.length(); i++) {
                JSONObject item = recent.getJSONObject(i);
                history.add(
                    new ContinueWatching.Entry(
                        i,
                        group(item),
                        series(item),
                        position(positions, item),
                        watched.contains(item.optString("episodeKey", "-"))
                    )
                );
            }
            List<ContinueWatching.Row> rows = ContinueWatching.rows(history);
            for (ContinueWatching.Row row : rows) {
                JSONObject item = recent.getJSONObject(row.index);
                if (row.next) nextEpisodeRow(item, recent);
                else resumeRow(item, positions, recent);
            }
            if (rows.isEmpty()) activity.emptyState(
                activity.page,
                "Pick up where you left off",
                "Movies and episodes you watch for a few minutes will appear here.",
                "Browse movies",
                () -> library("Movies")
            );
        } catch (Exception e) {
            activity.emptyState(
                activity.page,
                "History unavailable",
                "Try opening this screen again.",
                "Retry",
                this::continueWatching
            );
        }
    }

    private static boolean series(JSONObject item) {
        return item.has("episodeKey") || item.has("season") || "tv".equals(item.optString("media_type"));
    }

    /** The film or series a history record belongs to (ContinueWatching.group). */
    private static String group(JSONObject item) {
        boolean series = series(item);
        return ContinueWatching.group(
            series,
            item.optInt("tmdbId"),
            series ? item.optString("name", item.optString("title")) : item.optString("title"),
            item.optString("id")
        );
    }

    /** The saved resume point: the title's (any source), else this file's, else 0. */
    private static long position(Map<String, ?> positions, JSONObject item) {
        String titleKey = ResumePoint.titleKey(item);
        Object saved = titleKey == null ? null : positions.get(titleKey);
        if (!(saved instanceof Number)) saved = positions.get(item.optString("id"));
        return saved instanceof Number ? Math.max(0, ((Number) saved).longValue()) : 0;
    }

    private static String episodeLabel(JSONObject item) {
        return item.has("season") && item.has("episode")
            ? "S" + item.optInt("season") + " E" + item.optInt("episode")
            : "";
    }

    /** A film or episode part-way through: Resume carries on from where it stopped. */
    private void resumeRow(JSONObject item, Map<String, ?> positions, JSONArray recent) {
        long position = position(positions, item);
        long length = ResumePoint.duration(positions, item.optString("id"), ResumePoint.titleKey(item));
        String episode = episodeLabel(item);
        String title = series(item)
            ? item.optString("name", item.optString("title", "Series"))
            : item.optString("title", "Title");
        String detail =
            (episode.isEmpty() ? "" : episode + "   ·   ") +
            (length > position
                ? Math.max(1, (length - position) / 60000) + " min left"
                : position / 60000 + " min watched");
        LinearLayout copy = card(item, title, recent, () -> VodResume.start(activity, item, false), true);
        TextView line = activity.rowText(detail, 14, true, 1);
        copy.addView(line);
        if (length > 0) {
            LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, activity.dp(4));
            bar.topMargin = activity.dp(8);
            copy.addView(activity.progress((float) position / length), bar);
        }
    }

    /**
     * A series whose newest episode was finished: the row offers the next aired episode, looked
     * up in the background (as Up next does), and goes when there is none.
     */
    private void nextEpisodeRow(JSONObject item, JSONArray recent) {
        String title = item.optString("name", item.optString("title", "Series"));
        JSONObject[] next = new JSONObject[1];
        LinearLayout copy = card(
            item,
            title,
            recent,
            () -> {
                if (next[0] != null) VodResume.start(activity, next[0], false);
                else activity.toast("Still finding the next episode");
            },
            false
        );
        TextView line = activity.rowText(
            "Finished " + episodeLabel(item) + "   ·   finding the next episode…",
            14,
            true,
            1
        );
        copy.addView(line);
        View row = (View) copy.getParent();
        activity.async(
            () -> {
                try {
                    SeriesPlayback.Next found = SeriesPlayback.resolve(
                        item,
                        item.optInt("season"),
                        item.optInt("episode"),
                        path -> activity.media.tmdb(path),
                        java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                    );
                    return new Object[] { found };
                } catch (Exception e) {
                    Diagnostics.recovered("Finding the next episode for Continue watching", e);
                    return new Object[] { null, e };
                }
            },
            result -> {
                SeriesPlayback.Next found = (SeriesPlayback.Next) result[0];
                if (found == null && result.length == 1) {
                    // The last aired episode was watched: nothing to continue.
                    if (row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).removeView(row);
                    return;
                }
                if (found == null) {
                    line.setText(
                        "Finished " + episodeLabel(item) + "   ·   could not check for the next episode"
                    );
                    return;
                }
                JSONObject resume = new JSONObject(item.toString());
                resume.remove("id");
                resume.remove("url");
                resume
                    .put("season", found.season)
                    .put("episode", found.episode)
                    .put("episodeKey", found.metadata.optString("episodeKey"))
                    .put("media_type", "tv");
                next[0] = resume;
                String name = found.metadata.optString("episodeTitle", "");
                boolean fresh = ContinueWatching.isNew(
                    found.metadata.optString("air_date", ""),
                    java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                );
                // A new episode alert: it aired in the last week, so it is new since you watched.
                if (fresh) line.setTextColor(activity.accent());
                line.setText(
                    (fresh ? "New episode" : "Next") +
                        "   ·   S" +
                        found.season +
                        " E" +
                        found.episode +
                        (name.isEmpty() ? "" : "   ·   " + name)
                );
            }
        );
    }

    /** A Continue watching card: artwork, title, the Resume hint and the Hold Select panel. */
    private LinearLayout card(
        JSONObject item,
        String title,
        JSONArray recent,
        Runnable resume,
        boolean restartable
    ) {
        LinearLayout card = activity.listRow();
        card.setPadding(activity.dp(10), activity.dp(10), activity.dp(20), activity.dp(10));
        ImageView artwork = new ImageView(activity);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setClipToOutline(true);
        artwork.setBackground(activity.box(0xff1a2230));
        card.addView(artwork, new LinearLayout.LayoutParams(activity.dp(192), activity.dp(108)));
        String path = item.optString("backdrop_path", item.optString("poster_path"));
        if (path.startsWith("/")) activity.posters.loadImage(
            artwork,
            "https://image.tmdb.org/t/p/w342" + path
        );
        LinearLayout copy = activity.vertical();
        copy.setPadding(activity.dp(18), 0, activity.dp(18), 0);
        TextView name = activity.rowText(title, 19, false, 1);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        copy.addView(name);
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(activity.rowText("▶  Resume   ⋯", 15, false, 1));
        activity.styleListRow(card, false);
        card.setOnClickListener(v -> resume.run());
        Runnable more = () -> {
            SidePanel panel = SidePanel.of(activity, title).option("Resume", resume);
            if (restartable) panel.option("Start over", () -> VodResume.start(activity, item, true));
            panel
                .option("Remove from Continue watching", () -> {
                    forget(group(item), recent);
                    continueWatching();
                })
                .show();
        };
        card.setOnLongClickListener(v -> {
            more.run();
            return true;
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_MENU) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) more.run();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = activity.dp(10);
        activity.page.addView(card, lp);
        return copy;
    }

    /**
     * Removes a film or series from Continue watching: its saved progress (every source and
     * episode) and its history records, so a finished series does not come back as Next.
     * Watched-episode marks and My List are kept.
     */
    void forget(String group, JSONArray recent) {
        try {
            JSONArray kept = new JSONArray();
            for (int i = 0; i < recent.length(); i++) {
                JSONObject item = recent.getJSONObject(i);
                if (group.equals(group(item))) {
                    ResumePoint.clear(activity, item.optString("id"), ResumePoint.titleKey(item));
                } else kept.put(item);
            }
            activity.secrets.put("recentPlayback", kept.toString());
        } catch (Exception e) {
            Diagnostics.recovered("Removing from Continue watching", e);
            activity.toast("Could not remove this title");
        }
    }

    void library(String kind) {
        activity.catalogues.rows(kind);
    }

    void mediaRow(
        LinearLayout target,
        String title,
        String description,
        String action,
        Runnable run,
        Runnable more
    ) {
        mediaRow(target, title, description, action, run, more, -1);
    }

    /**
     * A list row for a saved source, cloud file or transfer: select runs {@code run}; holding Select
     * or pressing Menu runs {@code more}. {@code progress} from 0 to 1 adds a bar; -1 for none.
     */
    void mediaRow(
        LinearLayout target,
        String title,
        String description,
        String action,
        Runnable run,
        Runnable more,
        float progress
    ) {
        LinearLayout card = activity.listRow();
        card.setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(12));
        LinearLayout copy = activity.vertical();
        TextView name = activity.rowText(title, 17, false, 2);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        copy.addView(name);
        if (description != null && !description.isEmpty()) copy.addView(
            activity.rowText(description, 13, true, 1)
        );
        if (progress >= 0) {
            LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, activity.dp(4));
            bar.topMargin = activity.dp(6);
            copy.addView(activity.progress(progress), bar);
        }
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView next = activity.rowText(action + (more != null ? "   ⋯" : ""), 15, false, 1);
        LinearLayout.LayoutParams nextLayout = new LinearLayout.LayoutParams(-2, -2);
        nextLayout.leftMargin = activity.dp(16);
        card.addView(next, nextLayout);
        activity.styleListRow(card, false);
        card.setOnClickListener(v -> run.run());
        if (more != null) {
            card.setOnLongClickListener(v -> {
                more.run();
                return true;
            });
            card.setOnKeyListener((v, key, event) -> {
                if (key != android.view.KeyEvent.KEYCODE_MENU) return false;
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) more.run();
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = activity.dp(8);
        target.addView(card, lp);
    }

    void savedLibrary(String kind) {
        activity.screen("My " + kind, () -> library(kind));
        LinearLayout tools = activity.horizontal(activity.page);
        activity.chip(tools, "Browse catalogue", () -> activity.catalogues.catalogueMenu(kind));
        activity.chip(tools, "Add source", () -> activity.settingsScreens.addSource(kind, "", ""));
        activity.chip(tools, "Cloud", () -> activity.cloudScreens.cloud("", kind));
        activity.chip(tools, "Transfers", () -> activity.cloudScreens.transfers(kind));
        activity.section(activity.page, "Saved sources");
        try {
            JSONArray rows = activity.media.library();
            int found = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.getJSONObject(i);
                if (!kind.equals(item.getString("kind"))) continue;
                found++;
                String title = item.getString("title"),
                    url = item.optString("url"),
                    key = item.getString("id");
                mediaRow(
                    activity.page,
                    title,
                    "premiumize".equals(item.optString("source")) ? "Premiumize cloud" : "Saved media",
                    "Play",
                    () -> {
                        if ("premiumize".equals(item.optString("source"))) {
                            activity.async(
                                () ->
                                    activity.media.premiumize(
                                        "item/details?id=" + Network.q(item.getString("sourceId")),
                                        activity.secrets.get("premiumize")
                                    ),
                                detail -> activity.play(title, detail.getString("link"), key, false)
                            );
                        } else activity.play(title, url, key, false);
                    },
                    () ->
                        SidePanel.of(activity, "Remove " + title + "?")
                            .message(
                                "It is removed from your library on this box. The file itself is not deleted."
                            )
                            .action("Remove", () -> {
                                try {
                                    activity.media.remove(key);
                                    savedLibrary(kind);
                                } catch (Exception e) {
                                    activity.toast("Could not remove this source");
                                }
                            })
                            .action("Cancel", null)
                            .show()
                );
            }
            if (found == 0) activity.emptyState(
                activity.page,
                "Build your library",
                "Add a media link or save a video from your cloud.",
                "Open cloud",
                () -> activity.cloudScreens.cloud("", kind)
            );
        } catch (Exception e) {
            activity.emptyState(
                activity.page,
                "Library unavailable",
                "Try opening this screen again.",
                "Retry",
                () -> savedLibrary(kind)
            );
        }
    }
}
