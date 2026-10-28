package local.tv.home;

import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Seasons and episodes for a series on one page, as streaming apps show them: the series artwork
 * and title, season buttons, and the chosen season's episodes below. Includes watched-episode marks.
 */
final class SeriesScreens {

    private final MainActivity activity;

    SeriesScreens(MainActivity activity) {
        this.activity = activity;
    }

    void seasons(int tmdbId, String title) {
        seasons(tmdbId, title, -1);
    }

    /** Opens the series page on {@code initialSeason} (or the season you are watching, when -1). */
    void seasons(int tmdbId, String title, int initialSeason) {
        JSONObject series = activity.playbackTitle;
        activity.screen("", () -> {
            if (series != null) activity.posters.tmdbTitle(series);
            else activity.libraryScreens.library("Series");
        });
        TextView loading = activity.muted("Loading seasons…", 16);
        activity.page.addView(loading);
        activity.async(
            () -> activity.media.tmdb("tv/" + tmdbId + "?append_to_response=external_ids,alternative_titles"),
            details -> {
                activity.page.removeView(loading);
                details.put("media_type", "tv");
                activity.playbackTitle = details;
                renderSeasons(details, initialSeason);
            }
        );
    }

    void renderSeasons(JSONObject details) throws Exception {
        renderSeasons(details, -1);
    }

    void renderSeasons(JSONObject details, int initialSeason) throws Exception {
        String title = details.optString("name", "Series");
        int id = details.optInt("id");
        String imdb =
            details.optJSONObject("external_ids") == null
                ? ""
                : details.getJSONObject("external_ids").optString("imdb_id");
        activity.posters.listBackdrop(details);
        activity.pageTitle(activity.page, "Series", title, activity.posters.titleMetadata(details));
        activity.section(activity.page, "Choose a season");
        JSONArray seasons = details.optJSONArray("seasons");
        if (seasons == null || seasons.length() == 0) {
            activity.emptyState(
                activity.page,
                "No seasons yet",
                "Season information is not available for this series.",
                null,
                null
            );
            return;
        }
        LinearLayout pills = activity.horizontal(activity.page);
        LinearLayout summary = activity.vertical();
        activity.page.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout list = activity.vertical();
        list.setClipChildren(false);
        activity.page.addView(list, new LinearLayout.LayoutParams(-1, -2));

        List<Button> buttons = new ArrayList<>();
        int[] shown = { Integer.MIN_VALUE };
        int start = startingSeason(id, seasons, initialSeason);
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject season = seasons.getJSONObject(i);
            int number = season.optInt("season_number");
            Button pill = activity.chip(pills, season.optString("name", "Season " + number), () -> {});
            buttons.add(pill);
            pill.setOnClickListener(v -> {
                if (shown[0] == number) return;
                shown[0] = number;
                for (Button other : buttons) {
                    other.setSelected(other == v);
                    activity.styleFocus(other, other.hasFocus());
                }
                showSeason(season, details, id, imdb, title, summary, list, shown);
            });
            if (number == start) {
                shown[0] = number;
                pill.setSelected(true);
                activity.styleFocus(pill, false);
                showSeason(season, details, id, imdb, title, summary, list, shown);
                pill.post(pill::requestFocus);
            }
        }
    }

    /** The season to open: the one asked for, else the latest one you watched, else season 1. */
    private int startingSeason(int id, JSONArray seasons, int initialSeason) {
        Set<String> watched = activity.prefs.getStringSet("watchedEpisodes", Collections.emptySet());
        int latestWatched = -1,
            first = Integer.MIN_VALUE,
            firstRegular = Integer.MIN_VALUE;
        boolean hasInitial = false;
        for (int i = 0; i < seasons.length(); i++) {
            int number =
                seasons.optJSONObject(i) == null ? 0 : seasons.optJSONObject(i).optInt("season_number");
            if (first == Integer.MIN_VALUE) first = number;
            if (number >= 1 && firstRegular == Integer.MIN_VALUE) firstRegular = number;
            if (number == initialSeason) hasInitial = true;
        }
        for (String key : watched) {
            String[] parts = key.split(":");
            if (parts.length == 3 && parts[0].equals(String.valueOf(id))) try {
                latestWatched = Math.max(latestWatched, Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {}
        }
        if (hasInitial) return initialSeason;
        if (latestWatched >= 0) for (int i = 0; i < seasons.length(); i++) if (
            seasons.optJSONObject(i) != null &&
            seasons.optJSONObject(i).optInt("season_number") == latestWatched
        ) return latestWatched;
        return firstRegular != Integer.MIN_VALUE ? firstRegular : first;
    }

    private void showSeason(
        JSONObject season,
        JSONObject series,
        int id,
        String imdb,
        String title,
        LinearLayout summary,
        LinearLayout list,
        int[] shown
    ) {
        int number = season.optInt("season_number");
        summary.removeAllViews();
        String date = season.optString("air_date", "");
        String facts =
            season.optInt("episode_count") +
            " episodes" +
            (date.length() >= 4 ? "  ·  " + date.substring(0, 4) : "") +
            "  ·  Hold Select on an episode to mark it watched";
        summary.addView(activity.muted(facts, 14));
        String overview = season.optString("overview", "");
        if (!overview.isEmpty()) {
            TextView about = activity.muted(overview, 14);
            about.setMaxLines(2);
            about.setEllipsize(android.text.TextUtils.TruncateAt.END);
            summary.addView(about);
        }
        list.removeAllViews();
        list.addView(activity.muted("Loading episodes…", 16));
        activity.async(
            () -> activity.media.tmdb("tv/" + id + "/season/" + number).getJSONArray("episodes"),
            items -> {
                if (shown[0] != number) return; // another season was chosen meanwhile
                list.removeAllViews();
                renderEpisodes(list, items, series, id, imdb, number, title);
            }
        );
    }

    private String episodeKey(int id, int season, int episode) {
        return id + ":" + season + ":" + episode;
    }

    /** Adds the episodes to the current page. */
    void renderEpisodes(
        JSONArray episodes,
        JSONObject series,
        int tmdbId,
        String imdb,
        int season,
        String title
    ) throws Exception {
        renderEpisodes(activity.page, episodes, series, tmdbId, imdb, season, title);
    }

    void renderEpisodes(
        LinearLayout target,
        JSONArray episodes,
        JSONObject series,
        int tmdbId,
        String imdb,
        int season,
        String title
    ) throws Exception {
        if (episodes.length() == 0) {
            activity.emptyState(
                target,
                "No episodes yet",
                "Episode information is not available for this season.",
                null,
                null
            );
            return;
        }
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject episode = episodes.getJSONObject(i);
            int number = episode.optInt("episode_number");
            String key = episodeKey(tmdbId, season, number),
                name = episode.optString("name", "Episode " + number);
            Set<String> watched = activity.prefs.getStringSet("watchedEpisodes", Collections.emptySet());
            LinearLayout card = activity.listRow();
            card.setPadding(activity.dp(10), activity.dp(10), activity.dp(20), activity.dp(10));
            ImageView still = new ImageView(activity);
            still.setScaleType(ImageView.ScaleType.CENTER_CROP);
            still.setClipToOutline(true);
            still.setBackground(activity.box(0xff1a2230));
            card.addView(still, new LinearLayout.LayoutParams(activity.dp(192), activity.dp(108)));
            String path = episode.optString("still_path");
            if (path.startsWith("/")) activity.posters.loadImage(
                still,
                "https://image.tmdb.org/t/p/w342" + path
            );
            LinearLayout copy = activity.vertical();
            copy.setPadding(activity.dp(18), 0, 0, 0);
            TextView label = activity.rowText(
                (watched.contains(key) ? "✓  " : "") + number + " · " + name,
                19,
                false,
                1
            );
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            copy.addView(label);
            String date = episode.optString("air_date", "");
            int runtime = episode.optInt("runtime");
            copy.addView(
                activity.rowText(
                    (runtime > 0 ? runtime + " min" : "") +
                        (date.isEmpty() ? "" : (runtime > 0 ? "   ·   " : "") + date),
                    13,
                    true,
                    1
                )
            );
            copy.addView(
                activity.rowText(episode.optString("overview", "No synopsis available."), 14, true, 2)
            );
            card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            activity.styleListRow(card, false);
            card.setOnClickListener(v -> {
                try {
                    JSONObject metadata =
                        series == null
                            ? new JSONObject().put("id", tmdbId).put("media_type", "tv")
                            : new JSONObject(series.toString());
                    metadata.put("season", season).put("episode", number).put("episodeKey", key);
                    activity.playbackTitle = metadata;
                } catch (JSONException e) {
                    Diagnostics.recovered("Preparing episode metadata", e);
                }
                activity.sources.torrentSources(
                    imdb,
                    season,
                    number,
                    title + " · S" + season + " E" + number + " · " + name,
                    "Series"
                );
            });
            card.setOnLongClickListener(v -> {
                Set<String> values = new HashSet<>(
                    activity.prefs.getStringSet("watchedEpisodes", Collections.emptySet())
                );
                if (!values.add(key)) values.remove(key);
                activity.prefs.edit().putStringSet("watchedEpisodes", values).apply();
                label.setText((values.contains(key) ? "✓  " : "") + number + " · " + name);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = activity.dp(10);
            target.addView(card, lp);
        }
    }
}
