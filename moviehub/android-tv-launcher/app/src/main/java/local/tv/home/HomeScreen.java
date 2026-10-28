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

/** The launcher home: navigation tabs, artwork preview, poster shelf and the app dock. */
final class HomeScreen {

    private final MainActivity activity;

    HomeScreen(MainActivity activity) {
        this.activity = activity;
    }

    /** Shown as an icon beside Settings when an app with exactly this name is installed. */
    static final String MEMORY_CLEAN = "MemoryClean";

    void home() {
        activity.destination = this::home;
        activity.fixedScreen(null, null);
        activity.page.setPadding(activity.dp(30), activity.dp(10), activity.dp(30), activity.dp(10));
        BackdropView backdrop = activity.fullBleedBackdrop();
        LinearLayout top = activity.row();
        for (String kind : new String[] { "Live TV", "Movies", "Series", "Anime", "My list", "Continue" })
            top.addView(
                activity.navigationButton(kind, () -> {
                    if (kind.equals("Live TV")) activity.liveTv.start();
                    else if (kind.equals("My list")) activity.libraryScreens.watchlist();
                    else if (kind.equals("Continue")) activity.libraryScreens.continueWatching();
                    else activity.libraryScreens.library(kind);
                }),
                new LinearLayout.LayoutParams(
                    activity.dp(kind.equals("Continue") ? 110 : 94),
                    activity.dp(38)
                )
            );
        top.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        activity.clock = activity.compactText("", 16);
        LinearLayout.LayoutParams clockLayout = new LinearLayout.LayoutParams(-2, -2);
        clockLayout.rightMargin = activity.dp(14);
        top.addView(activity.clock, clockLayout);
        activity.updateButton = activity.button("Update", activity.updates::offerUpdate);
        activity.updateButton.setVisibility(
            activity.updates.availableUpdate == null ? View.GONE : View.VISIBLE
        );
        top.addView(activity.updateButton);
        activity.dock.ensureApps();
        AppDock.App memoryClean = activity.dock.findApp(MEMORY_CLEAN);
        if (memoryClean != null) {
            LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(
                activity.dp(38),
                activity.dp(38)
            );
            iconLayout.rightMargin = activity.dp(8);
            top.addView(activity.dock.iconShortcut(memoryClean), iconLayout);
        }
        top.addView(
            activity.navigationButton("Settings", activity.settingsScreens::settings),
            new LinearLayout.LayoutParams(activity.dp(96), activity.dp(38))
        );
        activity.page.addView(top, new LinearLayout.LayoutParams(-1, activity.dp(38)));
        activity.ticker.run();
        activity.handler.removeCallbacks(activity.ticker);
        activity.handler.postDelayed(activity.ticker, 30000);
        // The featured title's text sits directly on the full-screen artwork; there is no box.
        FrameLayout hero = new FrameLayout(activity);
        LinearLayout copy = activity.vertical();
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(activity.dp(4), activity.dp(4), activity.dp(16), activity.dp(4));
        TextView eyebrow = activity.compactText("MOVIEHUB", 11);
        eyebrow.setLetterSpacing(.18f);
        eyebrow.setTextColor(0xffa6b2c8);
        copy.addView(eyebrow);
        TextView heading = activity.compactText("Find your next favourite", 28);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setShadowLayer(activity.dp(6), 0, activity.dp(1), 0xcc000000);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(heading);
        TextView summary = activity.compactText(
            activity.secrets.has("tmdb")
                ? "Choose a title below"
                : "Connect TMDB to browse movies and series",
            14
        );
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setShadowLayer(activity.dp(4), 0, activity.dp(1), 0xcc000000);
        copy.addView(summary);
        Button open = activity.button("Connect TMDB", () -> activity.settingsScreens.accountScreen("TMDB"));
        // The standard button style, sized to its label rather than a long fixed width.
        open.setPadding(activity.dp(20), activity.dp(5), activity.dp(20), activity.dp(5));
        LinearLayout.LayoutParams openLayout = new LinearLayout.LayoutParams(-2, activity.dp(36));
        openLayout.topMargin = activity.dp(6);
        copy.addView(open, openLayout);
        hero.addView(copy, new FrameLayout.LayoutParams(activity.dp(510), -1));
        LinearLayout.LayoutParams heroLayout = new LinearLayout.LayoutParams(-1, 0, 1);
        heroLayout.setMargins(0, activity.dp(6), 0, activity.dp(4));
        activity.page.addView(hero, heroLayout);
        LinearLayout categories = activity.row();
        String[] labels = { "For you", "Trending", "Top rated", "New movies", "On TV" };
        LinearLayout posterHost = activity.vertical();
        posterHost.setClipChildren(false);
        HomeShelf shelf = new HomeShelf(posterHost, heading, summary, open, backdrop);
        activity.homeShelf = shelf;
        java.util.function.Consumer<String> load = path -> {
            activity.generation++;
            shelf.close();
            posterHost.removeAllViews();
            TextView loading = activity.compactText("Loading titles…", 14);
            posterHost.addView(loading);
            if (!activity.secrets.has("tmdb")) {
                loading.setText("Connect TMDB in Settings to see artwork and recommendations.");
                return;
            }
            shelf.load(path, pageNumber ->
                path.isEmpty()
                    ? activity.forYou.page(pageNumber)
                    : activity.media.tmdb(path + "?page=" + pageNumber)
            );
        };
        String[] paths = { "", "trending/all/week", "movie/top_rated", "movie/now_playing", "tv/on_the_air" };
        List<Button> tabs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final String path = paths[i];
            Button tab = activity.navigationButton(labels[i], () -> {});
            tabs.add(tab);
            tab.setTextSize(14);
            tab.setOnClickListener(v -> {
                for (Button other : tabs) {
                    other.setSelected(other == v);
                    activity.styleFocus(other, other.hasFocus());
                }
                load.accept(path);
            });
            categories.addView(tab, new LinearLayout.LayoutParams(activity.dp(114), activity.dp(32)));
        }
        tabs.get(0).setSelected(true);
        activity.styleFocus(tabs.get(0), false);
        activity.page.addView(categories, new LinearLayout.LayoutParams(-1, activity.dp(32)));
        activity.page.addView(posterHost, new LinearLayout.LayoutParams(-1, activity.dp(158)));
        if (activity.prefs.getBoolean("featured", true)) load.accept("");
        else {
            hero.setVisibility(View.GONE);
            categories.setVisibility(View.GONE);
            posterHost.setVisibility(View.GONE);
            activity.page.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout dockHeader = activity.row();
        dockHeader.addView(activity.compactText("Your apps", 13), new LinearLayout.LayoutParams(0, -2, 1));
        activity.dock.dockHint = activity.compactText("Hold Select to rearrange", 11);
        dockHeader.addView(activity.dock.dockHint);
        dockHeader.setElevation(activity.dp(8));
        dockHeader.setBackgroundColor(0xff090d14);
        activity.page.addView(dockHeader, new LinearLayout.LayoutParams(-1, activity.dp(28)));
        HorizontalScrollView appScroll = new HorizontalScrollView(activity);
        appScroll.setHorizontalScrollBarEnabled(false);
        appScroll.setClipChildren(false);
        appScroll.setClipToPadding(false);
        activity.dock.homeDock = activity.row();
        activity.dock.homeDock.setClipChildren(false);
        activity.dock.homeDock.setPadding(0, activity.dp(8), 0, 0);
        appScroll.addView(activity.dock.homeDock);
        activity.page.addView(appScroll, new LinearLayout.LayoutParams(-1, activity.dp(78)));
        for (String id : activity.dock.dockOrder())
            for (AppDock.App app : activity.dock.apps)
                if (id.equals(app.component.flattenToString())) {
                    View cell = activity.dock.addApp(activity.dock.homeDock, app, true);
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
                    lp.width = activity.dp(108);
                    lp.height = activity.dp(62);
                    lp.topMargin = activity.dp(2);
                    lp.bottomMargin = activity.dp(2);
                    cell.setLayoutParams(lp);
                    LinearLayout contents = (LinearLayout) cell;
                    contents
                        .getChildAt(0)
                        .setLayoutParams(new LinearLayout.LayoutParams(activity.dp(30), activity.dp(30)));
                    TextView caption = (TextView) contents.getChildAt(1);
                    caption.setTextSize(12);
                    caption.setMaxLines(1);
                    caption.setPadding(0, activity.dp(3), 0, 0);
                }
        activity.dock.homeDock.addView(
            activity.button("All Apps", activity.dock::allApps),
            new LinearLayout.LayoutParams(activity.dp(104), activity.dp(62))
        );
        activity.page.post(() -> top.getChildAt(0).requestFocus());
    }

    final class HomeShelf {

        final LinearLayout posterHost;
        final TextView heading, summary;
        final Button open;
        final BackdropView backdrop;
        PosterFeed feed;
        boolean previewed;
        Runnable pendingArt;

        HomeShelf(LinearLayout host, TextView title, TextView overview, Button action, BackdropView image) {
            posterHost = host;
            heading = title;
            summary = overview;
            open = action;
            backdrop = image;
        }

        void close() {
            if (pendingArt != null) activity.handler.removeCallbacks(pendingArt);
            if (feed != null) {
                feed.close();
                activity.feeds.remove(feed);
                feed = null;
            }
        }

        void show(JSONArray items, String path) throws JSONException {
            load(path, n -> new JSONObject().put("results", items).put("total_pages", 1));
        }

        void load(String path, PosterFeed.Loader loader) {
            close();
            previewed = false;
            posterHost.removeAllViews();
            feed = new PosterFeed(
                activity,
                true,
                path.startsWith("tv/") ? "tv" : "movie",
                activity.io,
                loader,
                new PosterFeed.Cards() {
                    public View create(ViewGroup parent) {
                        return activity.posters.posterCard(true);
                    }

                    public void bind(View card, JSONObject item) {
                        activity.posters.bindPoster(card, item, true, () -> preview(item));
                        if (!previewed) {
                            previewed = true;
                            preview(item);
                        }
                    }
                }
            );
            activity.feeds.add(feed);
            posterHost.addView(feed, new LinearLayout.LayoutParams(-1, -1));
            feed.start();
        }

        void preview(JSONObject item) {
            String name = item.optString("title", item.optString("name", "Title"));
            heading.setText(name);
            summary.setText(item.optString("overview", ""));
            open.setText("View details");
            open.setOnClickListener(v -> activity.posters.tmdbTitle(item));
            String art = item.optString("backdrop_path");
            if (pendingArt != null) activity.handler.removeCallbacks(pendingArt);
            pendingArt = () -> {
                if (!backdrop.isAttachedToWindow()) return;
                backdrop.setTag(null);
                if (art.startsWith("/")) activity.posters.loadImage(
                    backdrop,
                    "https://image.tmdb.org/t/p/w1280" + art
                );
                else backdrop.clear();
            };
            activity.handler.postDelayed(pendingArt, 320);
        }
    }
}
