package local.tv.home;

import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Movies and Series start page: rows of posters, one category per row (Trending, Popular, Top
 * rated, release lists, then genres), like the major streaming apps.
 *
 * <ul>
 *   <li>Posters have no captions; the focused title is described in the hero area above.
 *   <li>The row being browsed is always pinned at the top of the rows area, so no row is half cut.
 *   <li>Rows load as they come near the screen, so opening the page costs only a few requests.
 *   <li>Position and loaded posters are remembered when opening a title and coming back.
 * </ul>
 */
final class CatalogueRows {

    /** Poster size in dp (2:3). */
    static final int POSTER_WIDTH = 120,
        POSTER_HEIGHT = 180;
    /** Rows loaded ahead of the one being browsed. */
    private static final int LOAD_AHEAD = 2;

    /**
     * Test hook: when set, rows load from this instead of TMDB and no TMDB account is required.
     * Production code never sets it.
     */
    static RowLoader testLoader;

    interface RowLoader {
        PosterFeed.Loader forPath(String path);
    }

    /** What is remembered about a rows page when leaving it, per media kind. */
    static final class Saved {

        final int focusedRow;
        final Map<Integer, PosterFeed.State> feeds;

        Saved(int focusedRow, Map<Integer, PosterFeed.State> feeds) {
            this.focusedRow = focusedRow;
            this.feeds = feeds;
        }
    }

    final class Row {

        final int index;
        final TmdbCategories.Entry entry;
        final LinearLayout view;
        final PosterFeed feed;
        boolean started;

        Row(int index, TmdbCategories.Entry entry, LinearLayout view, PosterFeed feed) {
            this.index = index;
            this.entry = entry;
            this.view = view;
            this.feed = feed;
        }

        void start(PosterFeed.State saved, boolean refocus) {
            if (started) return;
            started = true;
            if (saved != null && !saved.items.isEmpty()) feed.restore(saved, refocus);
            else feed.start();
        }
    }

    private final MainActivity activity;
    private final CatalogueScreens.CatalogueScreen screen;
    final List<Row> rows = new ArrayList<>();
    final ScrollView scroll;
    private final LinearLayout list;
    private Row focusedRow;
    private boolean closed, previewed;
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusWatcher = (before, after) -> {
        Row row = rowContaining(after);
        if (row != null && row != focusedRow) focusRow(row, true);
    };

    CatalogueRows(MainActivity activity, CatalogueScreens.CatalogueScreen screen) {
        this.activity = activity;
        this.screen = screen;
        scroll = new PinnedScroll(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        list = activity.vertical();
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
    }

    /** A scroll view that never scrolls by itself to show focus; rows are pinned explicitly instead. */
    private static final class PinnedScroll extends ScrollView {

        PinnedScroll(android.content.Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }

    /** Builds the rows. Returns the view to place in the catalogue content area. */
    View build(Saved saved) {
        String type = screen.type;
        if (testLoader == null && !activity.secrets.has("tmdb")) {
            list.addView(rowTitle("Trending today"));
            activity.emptyState(
                list,
                "Connect TMDB",
                "Movies and series come from TMDB. Add your free API key to see them here.",
                "Connect TMDB",
                () -> activity.settingsScreens.accountScreen("TMDB")
            );
            return scroll;
        }
        List<TmdbCategories.Entry> entries = new ArrayList<>();
        if (screen.kind.equals("Anime")) entries.addAll(TmdbCategories.anime(java.time.LocalDate.now()));
        else {
            entries.addAll(TmdbCategories.lists(type));
            entries.addAll(activity.catalogues.catalogueGenres(type));
        }
        for (int i = 0; i < entries.size(); i++) addRow(i, entries.get(i));
        int first = saved == null ? 0 : Math.max(0, Math.min(saved.focusedRow, rows.size() - 1));
        // When restoring, the saved poster is refocused instead of the first one.
        if (saved != null && !rows.isEmpty()) rows.get(0).feed.onFirstItems = null;
        for (Row row : rows) {
            PosterFeed.State state = saved == null ? null : saved.feeds.get(row.index);
            if (state != null || row.index <= first + LOAD_AHEAD) row.start(state, row.index == first);
        }
        focusedRow = rows.isEmpty() ? null : rows.get(first);
        observer = scroll.getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener(focusWatcher);
        routeHeaderDown();
        if (focusedRow != null && first > 0) scroll.post(() -> pin(focusedRow, false));
        return scroll;
    }

    private void addRow(int index, TmdbCategories.Entry entry) {
        LinearLayout view = activity.vertical();
        view.setClipChildren(false);
        view.setClipToPadding(false);
        view.addView(rowTitle(entry.name));
        // A row lists films or series by its own path (the Anime page has both).
        String rowType = TmdbCategories.typeOf(entry.path, screen.type);
        PosterFeed.Loader source =
            testLoader != null
                ? testLoader.forPath(entry.path)
                : n -> activity.catalogues.cataloguePage(rowType, entry.path, n);
        Row[] self = new Row[1];
        PosterFeed.Loader loader = n -> {
            JSONObject page = source.load(n);
            JSONArray results = page.optJSONArray("results");
            if (n == 1 && (results == null || results.length() == 0)) activity.runOnUiThread(() ->
                hideRow(self[0])
            );
            return page;
        };
        PosterFeed feed = new PosterFeed(
            activity,
            true,
            rowType,
            activity.io,
            loader,
            new PosterFeed.Cards() {
                public View create(ViewGroup parent) {
                    View card = activity.posters.posterCard(true);
                    int frame = activity.dp(3);
                    card.setPadding(frame, frame, frame, frame);
                    return card;
                }

                public void bind(View card, JSONObject item) {
                    activity.posters.bindPoster(card, item, true, () -> screen.preview(item));
                    if (!previewed && index == 0) {
                        previewed = true;
                        screen.preview(item);
                    }
                }
            }
        );
        feed.setCardSize(POSTER_WIDTH, POSTER_HEIGHT);
        feed.setClipToPadding(false);
        feed.setPadding(0, activity.dp(4), activity.dp(24), activity.dp(4));
        activity.feeds.add(feed);
        view.addView(feed, new LinearLayout.LayoutParams(-1, activity.dp(POSTER_HEIGHT + 16)));
        Row row = new Row(index, entry, view, feed);
        self[0] = row;
        if (index == 0) feed.onFirstItems = this::focusFirstPosterIfNothingChosen;
        rows.add(row);
        list.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView rowTitle(String name) {
        TextView title = activity.compactText(name, 17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(activity.dp(4), activity.dp(10), 0, activity.dp(2));
        return title;
    }

    /** On opening the page, move focus from the header to the first poster, as streaming apps do. */
    private void focusFirstPosterIfNothingChosen() {
        if (closed || rows.isEmpty()) return;
        View focus = activity.getCurrentFocus();
        if (focus != null && rowContaining(focus) != null) return;
        if (focus != null && focus != screen.initialFocus) return;
        View first = rows.get(0).feed.getLayoutManager().findViewByPosition(0);
        if (first != null) first.requestFocus();
    }

    /** Pressing Down on a header button goes to the row currently pinned at the top. */
    private void routeHeaderDown() {
        LinearLayout header = screen.controls;
        for (int i = 0; i < header.getChildCount(); i++) {
            View control = header.getChildAt(i);
            if (!control.isFocusable()) continue;
            control.setOnKeyListener((v, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    Row target =
                        focusedRow != null && focusedRow.view.getVisibility() == View.VISIBLE
                            ? focusedRow
                            : firstVisibleRow();
                    if (target != null) target.feed.focusBelow(v);
                }
                return true;
            });
        }
    }

    private Row firstVisibleRow() {
        for (Row row : rows) if (row.view.getVisibility() == View.VISIBLE) return row;
        return null;
    }

    private Row rowContaining(View view) {
        for (
            ViewParent parent = view == null ? null : view.getParent();
            parent != null;
            parent = parent.getParent()
        ) for (Row row : rows) if (parent == row.feed) return row;
        return null;
    }

    private void focusRow(Row row, boolean animate) {
        focusedRow = row;
        pin(row, animate);
        for (Row other : rows)
            if (other.index > row.index && other.index <= row.index + LOAD_AHEAD) other.start(null, false);
    }

    /** Scrolls so that this row sits at the top of the rows area. */
    private void pin(Row row, boolean animate) {
        if (closed) return;
        int y = row.view.getTop();
        if (animate) scroll.smoothScrollTo(0, y);
        else scroll.scrollTo(0, y);
    }

    private void hideRow(Row row) {
        if (closed || row == null) return;
        row.view.setVisibility(View.GONE);
        Row next = null;
        for (Row other : rows)
            if (other.index > row.index && !other.started) {
                next = other;
                break;
            }
        if (next != null) next.start(null, false);
    }

    /**
     * Moves focus to the top bar and scrolls back to the first row, so the header is one press away
     * from anywhere on the page. Returns false when focus is not on a poster, so Back leaves.
     */
    boolean jumpToTop() {
        if (closed || rows.isEmpty()) return false;
        View focus = activity.getCurrentFocus();
        // Only jump when browsing the rows; from the top bar (or with nothing focused) Back leaves.
        if (focus == null || rowContaining(focus) == null) return false;
        Row first = firstVisibleRow();
        if (first != null) {
            focusedRow = first;
            pin(first, true);
        }
        screen.initialFocus.requestFocus();
        return true;
    }

    /** Stops loading and returns what to restore when this page is shown again. */
    Saved close() {
        if (closed) return null;
        closed = true;
        if (observer != null && observer.isAlive()) observer.removeOnGlobalFocusChangeListener(focusWatcher);
        Map<Integer, PosterFeed.State> states = new HashMap<>();
        for (Row row : rows) {
            if (row.started && !row.feed.items.isEmpty()) states.put(row.index, row.feed.snapshot());
            row.feed.close();
            activity.feeds.remove(row.feed);
        }
        if (rows.isEmpty()) return null;
        return new Saved(focusedRow == null ? 0 : focusedRow.index, states);
    }
}
