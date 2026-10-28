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

/** The full-screen details page for a movie or series. */
final class TitleDetails {

    private final MainActivity activity;

    TitleDetails(MainActivity activity) {
        this.activity = activity;
    }

    /** Poster size in the "More like this" row, in dp (2:3). */
    static final int RELATED_WIDTH = 120,
        RELATED_HEIGHT = 180;

    /**
     * Streaming-app style details page: full-screen artwork with the title, facts, synopsis and
     * buttons on the left, then "More like this" posters, the collection and the cast below.
     */
    void renderDetails(JSONObject item) throws Exception {
        activity.playbackTitle = item;
        String title = item.optString("title", item.optString("name", "Title")),
            type = item.optString("media_type", "movie");
        int tmdbId = item.optInt("id");
        String kind = type.equals("tv") ? "Series" : "Movies";

        BackdropView backdrop = activity.fullBleedBackdrop();
        String art = item.optString("backdrop_path");
        if (!art.startsWith("/")) art = item.optString("poster_path");
        if (art.startsWith("/")) activity.posters.loadImage(
            backdrop,
            "https://image.tmdb.org/t/p/w1280" + art
        );

        // The title block fills most of the first screen and sits at its bottom, over the artwork.
        LinearLayout hero = activity.vertical();
        hero.setGravity(Gravity.BOTTOM);
        hero.setPadding(activity.dp(4), 0, 0, activity.dp(12));
        int heroHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.58f);
        activity.page.addView(hero, new LinearLayout.LayoutParams(-1, heroHeight));
        LinearLayout info = activity.vertical();
        int width = Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.55f);
        hero.addView(info, new LinearLayout.LayoutParams(width, -2));

        List<String> eyebrow = new ArrayList<>();
        eyebrow.add(type.equals("tv") ? "SERIES" : "MOVIE");
        JSONArray genres = item.optJSONArray("genres");
        if (genres != null) for (int i = 0; i < Math.min(3, genres.length()); i++) eyebrow.add(
            genres.getJSONObject(i).optString("name").toUpperCase(Locale.ROOT)
        );
        TextView kicker = activity.compactText(String.join("  ·  ", eyebrow), 12);
        kicker.setLetterSpacing(.12f);
        kicker.setTextColor(0xffc4cede);
        info.addView(kicker);
        TextView heading = activity.compactText(title, 36);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setShadowLayer(activity.dp(8), 0, activity.dp(1), 0xcc000000);
        info.addView(heading);
        TextView facts = activity.compactText(activity.posters.titleMetadata(item), 15);
        facts.setTextColor(0xffdfe5ee);
        info.addView(facts);
        TextView synopsis = activity.compactText(item.optString("overview", "No synopsis available."), 16);
        synopsis.setMaxLines(4);
        synopsis.setEllipsize(android.text.TextUtils.TruncateAt.END);
        synopsis.setLineSpacing(activity.dp(2), 1f);
        synopsis.setShadowLayer(activity.dp(4), 0, activity.dp(1), 0xcc000000);
        LinearLayout.LayoutParams synopsisLayout = new LinearLayout.LayoutParams(-1, -2);
        synopsisLayout.topMargin = activity.dp(6);
        info.addView(synopsis, synopsisLayout);

        LinearLayout actions = activity.row();
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        actions.setPadding(0, activity.dp(14), 0, activity.dp(4));
        JSONObject recent = activity.posters.recentForTitle(item);
        Button resume =
            recent == null
                ? null
                : action(actions, "▶  Resume", true, () -> VodResume.start(activity, recent, false));
        Button play = action(
            actions,
            type.equals("tv") ? "Episodes" : AutoPlayback.enabled(activity, -1) ? "▶  Play" : "Find sources",
            resume == null,
            () -> {
                ForYou.noteInterest(activity.prefs, type, tmdbId);
                activity.playbackTitle = item;
                if (type.equals("tv")) activity.seriesScreens.seasons(tmdbId, title);
                else {
                    JSONObject external = item.optJSONObject("external_ids");
                    String imdb =
                        external == null
                            ? item.optString("imdb_id")
                            : external.optString("imdb_id", item.optString("imdb_id"));
                    activity.sources.torrentSources(imdb, -1, -1, title, kind);
                }
            }
        );
        Button saved = action(
            actions,
            activity.libraryScreens.isWatchlisted(item) ? "✓ My list" : "+ My list",
            false,
            () -> {}
        );
        saved.setOnClickListener(v -> {
            activity.libraryScreens.toggleWatchlist(item);
            saved.setText(activity.libraryScreens.isWatchlisted(item) ? "✓ My list" : "+ My list");
        });
        RatingActions.buttons(activity, actions, type, tmdbId);
        String trailer = trailerKey(item);
        if (!trailer.isEmpty()) action(actions, "Trailer", false, () -> {
            try {
                activity.startActivity(
                    new Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.youtube.com/watch?v=" + trailer)
                    )
                );
            } catch (Exception e) {
                activity.toast("No app can open this trailer");
            }
        });
        info.addView(actions);

        if (activity.secrets.has("tmdb")) relatedRow(type, tmdbId);
        JSONObject collection = item.optJSONObject("belongs_to_collection");
        if (collection != null) {
            LinearLayout part = activity.row();
            part.setPadding(0, activity.dp(6), 0, activity.dp(6));
            action(part, "Part of " + collection.optString("name", "a collection"), false, () ->
                activity.catalogues.browse(
                    kind,
                    collection.optString("name", "Collection"),
                    "collection/" + collection.optInt("id"),
                    1
                )
            );
            activity.page.addView(part);
        }
        castRow(item, kind, type);
        activity.page.addView(new View(activity), new LinearLayout.LayoutParams(-1, activity.dp(24)));
        if (resume != null) resume.requestFocus();
        else play.requestFocus();
    }

    private Button action(LinearLayout parent, String label, boolean primary, Runnable run) {
        Button button = activity.button(label, run);
        if (primary) activity.primary(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, activity.dp(38));
        lp.rightMargin = activity.dp(12);
        parent.addView(button, lp);
        return button;
    }

    private String trailerKey(JSONObject item) throws JSONException {
        JSONArray videos =
            item.optJSONObject("videos") == null
                ? null
                : item.getJSONObject("videos").optJSONArray("results");
        String trailer = "";
        if (videos != null) for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.getJSONObject(i);
            if (
                video.optString("site").equals("YouTube") &&
                video.optString("type").equals("Trailer") &&
                video.optString("key").matches("[A-Za-z0-9_-]{11}")
            ) {
                trailer = video.getString("key");
                if (video.optBoolean("official")) break;
            }
        }
        return trailer;
    }

    private TextView rowTitle(String text) {
        TextView title = activity.compactText(text, 18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(activity.dp(4), activity.dp(16), 0, activity.dp(4));
        return title;
    }

    /** A row of recommended posters. Hidden if TMDB has none. */
    private void relatedRow(String type, int tmdbId) {
        LinearLayout section = activity.vertical();
        section.addView(rowTitle("More like this"));
        PosterFeed feed = new PosterFeed(
            activity,
            true,
            type,
            activity.io,
            n -> {
                JSONObject page = activity.media.tmdb(type + "/" + tmdbId + "/recommendations?page=" + n);
                JSONArray results = page.optJSONArray("results");
                if (n == 1 && (results == null || results.length() == 0)) activity.runOnUiThread(() ->
                    section.setVisibility(View.GONE)
                );
                return page;
            },
            new PosterFeed.Cards() {
                public View create(ViewGroup parent) {
                    View card = activity.posters.posterCard(true);
                    int frame = activity.dp(3);
                    card.setPadding(frame, frame, frame, frame);
                    return card;
                }

                public void bind(View card, JSONObject related) {
                    activity.posters.bindPoster(card, related, true, null);
                }
            }
        );
        feed.setCardSize(RELATED_WIDTH, RELATED_HEIGHT);
        feed.setClipToPadding(false);
        feed.setPadding(0, activity.dp(4), activity.dp(24), activity.dp(4));
        activity.feeds.add(feed);
        section.addView(feed, new LinearLayout.LayoutParams(-1, activity.dp(RELATED_HEIGHT + 16)));
        activity.page.addView(section);
        feed.start();
    }

    /** Cast as round photos with names; selecting one browses their other titles. */
    private void castRow(JSONObject item, String kind, String type) throws JSONException {
        JSONObject credits = item.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        if (cast == null || cast.length() == 0) return;
        activity.page.addView(rowTitle("Cast"));
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout people = activity.row();
        people.setClipChildren(false);
        people.setPadding(0, activity.dp(6), activity.dp(24), activity.dp(6));
        scroll.addView(people);
        activity.page.addView(scroll);
        for (int i = 0; i < Math.min(15, cast.length()); i++) {
            JSONObject person = cast.getJSONObject(i);
            people.addView(
                activity.catalogueSearch.personCard(person, () ->
                    activity.catalogues.browse(
                        kind,
                        person.optString("name"),
                        "person/" + person.optInt("id") + "/" + type + "_credits",
                        1
                    )
                )
            );
        }
    }
}
