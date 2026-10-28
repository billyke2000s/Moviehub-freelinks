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

/** Poster cards and poster feeds shared by Home, catalogues and libraries. */
final class PosterCards {

    private final MainActivity activity;

    PosterCards(MainActivity activity) {
        this.activity = activity;
    }

    View posterCard(boolean compact) {
        LinearLayout card = activity.vertical();
        card.setBackground(activity.box(0xff171d27));
        card.setClipToOutline(true);
        card.setPadding(
            activity.dp(compact ? 0 : 6),
            activity.dp(compact ? 0 : 6),
            activity.dp(compact ? 0 : 6),
            activity.dp(compact ? 0 : 6)
        );
        ImageView image = compact
            ? new ImageView(activity)
            : new ImageView(activity) {
                  @Override
                  protected void onMeasure(int widthSpec, int heightSpec) {
                      int width = View.MeasureSpec.getSize(widthSpec);
                      setMeasuredDimension(width, Math.round(width * 1.5f));
                  }
              };
        image.setScaleType(compact ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        card.addView(image, new LinearLayout.LayoutParams(-1, compact ? -1 : -2));
        if (!compact) {
            PosterTitle title = new PosterTitle(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                -1,
                Math.max(activity.dp(42), (int) Math.ceil(title.getPaint().getFontSpacing() * 2))
            );
            lp.topMargin = activity.dp(4);
            card.addView(title, lp);
        }
        activity.focus(card);
        return card;
    }

    void bindPoster(View view, JSONObject item, boolean compact, Runnable preview) {
        LinearLayout card = (LinearLayout) view;
        ImageView image = (ImageView) card.getChildAt(0);
        String name = item.optString("title", item.optString("name", "Title"));
        image.setTag(null);
        image.setImageDrawable(null);
        String art = item.optString("poster_path");
        if (art.startsWith("/")) loadImage(image, "https://image.tmdb.org/t/p/w342" + art);
        card.setContentDescription(name + " · " + titleMetadata(item));
        card.setOnLongClickListener(v -> {
            posterActions(v, item);
            return true;
        });
        card.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) posterActions(v, item);
                return true;
            }
            return false;
        });
        PosterTitle title = compact ? null : (PosterTitle) card.getChildAt(1);
        if (title != null) {
            title.setText(name);
            title.cardFocus(card.hasFocus());
        }
        card.setOnFocusChangeListener((v, on) -> {
            activity.styleFocus(v, on);
            if (title != null) title.cardFocus(on);
            if (on && preview != null) preview.run();
        });
        activity.styleFocus(card, card.hasFocus());
        card.setOnClickListener(v -> tmdbTitle(item));
    }

    PosterFeed catalogueFeed(
        LinearLayout right,
        View oldResults,
        LinearLayout menu,
        String type,
        PosterFeed.Loader loader
    ) {
        return catalogueFeed(right, oldResults, menu, type, loader, null);
    }

    PosterFeed catalogueFeed(
        LinearLayout right,
        View oldResults,
        LinearLayout menu,
        String type,
        PosterFeed.Loader loader,
        PosterFeed.State saved
    ) {
        right.removeAllViews();
        PosterFeed feed = new PosterFeed(
            activity,
            false,
            type,
            activity.io,
            loader,
            new PosterFeed.Cards() {
                public View create(ViewGroup parent) {
                    return posterCard(false);
                }

                public void bind(View card, JSONObject item) {
                    CatalogueScreens.CatalogueScreen active = activity.catalogues.catalogue;
                    bindPoster(card, item, false, active == null ? null : () -> active.preview(item));
                }
            }
        );
        feed.setColumns(6);
        if (activity.catalogues.catalogue != null && activity.catalogues.catalogue.content == right) {
            LinearLayout header = (LinearLayout) right.getParent();
            if (header.getChildCount() > 0 && header.getChildAt(0) instanceof LinearLayout) {
                LinearLayout controls = (LinearLayout) header.getChildAt(0);
                for (int i = 0; i < controls.getChildCount(); i++) {
                    View control = controls.getChildAt(i);
                    if (control.isFocusable()) control.setOnKeyListener((v, key, event) -> {
                        if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                        if (event.getAction() == KeyEvent.ACTION_DOWN) feed.focusBelow(v);
                        return true;
                    });
                }
            }
        }
        if (activity.catalogues.catalogue != null && activity.catalogues.catalogue.content == right) {
            if (activity.catalogues.catalogue.feed != null) {
                activity.catalogues.catalogue.feed.close();
                activity.feeds.remove(activity.catalogues.catalogue.feed);
            }
            activity.catalogues.catalogue.feed = feed;
        }
        activity.feeds.add(feed);
        right.addView(feed, new LinearLayout.LayoutParams(-1, 0, 1));
        routeCategoryFocus(menu, feed);
        if (saved != null && !saved.items.isEmpty()) feed.restore(saved);
        else feed.start();
        return feed;
    }

    static void routeCategoryFocus(LinearLayout menu, PosterFeed feed) {
        for (int i = 0; i < menu.getChildCount(); i++) if (menu.getChildAt(i).isFocusable()) menu.getChildAt(
            i
        ).setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) feed.focusBelow(v);
            return true;
        });
    }

    /** Darkened full-screen artwork of {@code item} behind the current list page, if it has any. */
    void listBackdrop(JSONObject item) {
        if (item == null) return;
        String art = item.optString("backdrop_path");
        if (!art.startsWith("/")) art = item.optString("poster_path");
        if (!art.startsWith("/")) return;
        BackdropView backdrop = activity.fullBleedBackdrop(true);
        loadImage(backdrop, "https://image.tmdb.org/t/p/w1280" + art);
    }

    void loadImage(ImageView view, String url) {
        activity.artworkLoader.load(view, url);
    }

    void tmdbTitle(JSONObject item) {
        String type = item.optString("media_type", item.has("title") ? "movie" : "tv");
        int tmdbId = item.optInt("id");
        Runnable returnTo = activity.destination == null ? activity.homeScreen::home : activity.destination;
        activity.screen("", returnTo);
        activity.playbackTitle = item;
        TextView loading = activity.text("Loading details…", 18);
        activity.page.addView(loading);
        activity.async(
            () ->
                activity.media.tmdb(
                    type + "/" + tmdbId + "?append_to_response=external_ids,credits,videos,alternative_titles"
                ),
            details -> {
                details.put("media_type", type);
                activity.page.removeView(loading);
                activity.titleDetails.renderDetails(details);
            }
        );
    }

    String titleMetadata(JSONObject item) {
        String date = item.optString("release_date", item.optString("first_air_date", ""));
        List<String> parts = new ArrayList<>();
        if (date.length() >= 4) parts.add(date.substring(0, 4));
        parts.add(
            item.optString("media_type", item.has("title") ? "movie" : "tv").equals("tv") ? "Series" : "Movie"
        );
        double score = item.optDouble("vote_average", 0);
        if (score > 0) parts.add("★ " + String.format(Locale.ROOT, "%.1f", score));
        int runtime = item.optInt("runtime");
        if (runtime > 0) parts.add(runtime + " min");
        int seasons = item.optInt("number_of_seasons");
        if (seasons > 0) parts.add(seasons + (seasons == 1 ? " season" : " seasons"));
        return String.join("   ·   ", parts);
    }

    private void posterActions(View anchor, JSONObject item) {
        String name = item.optString("title", item.optString("name", "Title"));
        boolean saved = activity.libraryScreens.isWatchlisted(item);
        String type = item.optString("media_type", item.has("title") ? "movie" : "tv");
        int id = item.optInt("id");
        int rating = RatingActions.get(activity.prefs, type, id);
        SidePanel.of(activity, name)
            .option("View details", () -> tmdbTitle(item))
            .option(RatingActions.likeLabel(rating), () ->
                RatingActions.toggle(activity.prefs, type, id, TitleRatings.LIKE)
            )
            .option(RatingActions.dislikeLabel(rating), () -> {
                if (
                    RatingActions.toggle(activity.prefs, type, id, TitleRatings.DISLIKE) ==
                    TitleRatings.DISLIKE
                ) activity.toast("We won't suggest this in For you");
            })
            .option(saved ? "Remove from My List" : "Add to My List", () -> {
                activity.libraryScreens.toggleWatchlist(item);
                activity.toast(saved ? "Removed from My List" : "Added to My List");
                if (activity.myListRefresh != null) activity.myListRefresh.run();
            })
            .option("More like this", () ->
                activity.catalogues.catalogue(type, type + "/" + item.optInt("id") + "/recommendations", 1)
            )
            .show();
    }

    JSONObject recentForTitle(JSONObject item) {
        try {
            String raw = activity.secrets.get("recentPlayback");
            JSONArray recent = new JSONArray(raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < recent.length(); i++) {
                JSONObject row = recent.getJSONObject(i);
                if (
                    row.optInt("tmdbId") == item.optInt("id") &&
                    row.optString("media_type").equals(item.optString("media_type"))
                ) {
                    Object position = activity
                        .getSharedPreferences("positions", Context.MODE_PRIVATE)
                        .getAll()
                        .get(row.optString("id"));
                    if (position instanceof Number && ((Number) position).longValue() > 0) return row;
                }
            }
        } catch (Exception e) {
            Diagnostics.recovered("Reading resume position", e);
        }
        return null;
    }

    private void posterGrid(LinearLayout target, JSONArray items, String type) throws Exception {
        int visible = 0;
        LinearLayout posterRow = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.optBoolean("adult", false)) continue;
            if (!type.isEmpty()) item.put("media_type", type);
            if (visible % 4 == 0) {
                posterRow = activity.row();
                target.addView(posterRow);
            }
            View card = posterCard(false);
            bindPoster(card, item, false, null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(activity.dp(4), activity.dp(5), activity.dp(4), activity.dp(5));
            posterRow.addView(card, lp);
            visible++;
        }
        if (posterRow != null) while (posterRow.getChildCount() < 4)
            posterRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        if (visible == 0) target.addView(activity.text("No titles found", 18));
    }
}
