package local.tv.home;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.util.*;
import java.util.concurrent.Executor;
import org.json.*;

/** Append-only, recycled TMDB feed. A failed page is retried without replacing earlier cards. */
final class PosterFeed extends RecyclerView implements AutoCloseable {

    interface Loader {
        JSONObject load(int page) throws Exception;
    }

    interface Cards {
        View create(ViewGroup parent);
        void bind(View card, JSONObject item);
    }

    final ArrayList<JSONObject> items = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor worker;
    private final Loader loader;
    private final Cards cards;
    private final String type;
    private final boolean horizontal;
    private long lastFocused = Long.MIN_VALUE;
    private int columns = 4;
    /** Size of each poster in a horizontal feed, in dp. */
    private int cardWidthDp = 108,
        cardHeightDp = 150;
    private boolean pendingBelow;
    Runnable onFirstItems;
    private int next = 1,
        total = 1;
    private boolean loading, closed, failed, capped;
    /** False until start() or restore(); an unstarted feed never fetches, even when laid out. */
    private boolean started;
    private View pendingFrom;
    private final FeedAdapter adapter = new FeedAdapter();

    PosterFeed(
        Context context,
        boolean horizontal,
        String type,
        Executor worker,
        Loader loader,
        Cards cards
    ) {
        super(context);
        this.horizontal = horizontal;
        this.type = type;
        this.worker = worker;
        this.loader = loader;
        this.cards = cards;
        setItemAnimator(null);
        setPreserveFocusAfterLayout(true);
        setClipToPadding(true);
        setPadding(0, 0, 0, 0);
        if (horizontal) setLayoutManager(
            new LinearLayoutManager(context, HORIZONTAL, false) {
                @Override
                protected void calculateExtraLayoutSpace(RecyclerView.State state, int[] extra) {
                    super.calculateExtraLayoutSpace(state, extra);
                    keepNeighboursLaidOut(extra, getWidth());
                }
            }
        );
        else {
            GridLayoutManager grid = new GridLayoutManager(context, 4) {
                @Override
                protected void calculateExtraLayoutSpace(RecyclerView.State state, int[] extra) {
                    super.calculateExtraLayoutSpace(state, extra);
                    keepNeighboursLaidOut(extra, getHeight());
                }
            };
            grid.setSpanSizeLookup(
                new GridLayoutManager.SpanSizeLookup() {
                    @Override
                    public int getSpanSize(int position) {
                        return position == items.size() ? columns : 1;
                    }
                }
            );
            setLayoutManager(grid);
        }
        adapter.setHasStableIds(true);
        setAdapter(adapter);
        addOnScrollListener(
            new OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView view, int dx, int dy) {
                    prefetch();
                }
            }
        );
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
    }

    /**
     * Keeps half a screen of posters laid out beyond each edge. A quick D-pad press can focus a poster
     * just past the edge before scrolling catches up (CI showed one or two posters ahead); without
     * this margin, a layout at that moment would recycle the focused poster and focus would be lost.
     */
    private static void keepNeighboursLaidOut(int[] extra, int page) {
        extra[0] = Math.max(extra[0], page / 2);
        extra[1] = Math.max(extra[1], page / 2);
    }

    static final class State {

        final ArrayList<JSONObject> items;
        final int next, total;
        final boolean capped;
        final android.os.Parcelable layout;
        final long focused;

        State(PosterFeed feed) {
            items = new ArrayList<>(feed.items);
            next = feed.next;
            total = feed.total;
            capped = feed.capped;
            layout = feed.getLayoutManager().onSaveInstanceState();
            View view = feed.findFocus();
            ViewHolder holder = view == null ? null : feed.findContainingViewHolder(view);
            focused =
                holder == null || holder.getItemId() == Long.MIN_VALUE
                    ? feed.lastFocused
                    : holder.getItemId();
        }
    }

    State snapshot() {
        return new State(this);
    }

    private long restoredFocus = Long.MIN_VALUE;

    void restore(State state) {
        restore(state, true);
    }

    /** Restores saved posters and position; focuses the saved poster only when {@code refocus}. */
    void restore(State state, boolean refocus) {
        items.clear();
        items.addAll(state.items);
        seen.clear();
        for (JSONObject item : items) seen.add(item.optString("media_type", type) + ":" + item.optInt("id"));
        next = state.next;
        total = state.total;
        capped = state.capped;
        restoredFocus = refocus ? state.focused : Long.MIN_VALUE;
        started = true;
        adapter.notifyDataSetChanged();
        getLayoutManager().onRestoreInstanceState(state.layout);
        post(this::prefetch);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        focusedView = focused;
        int position = getChildAdapterPosition(child);
        if (position >= 0 && position < items.size()) lastFocused = adapter.getItemId(position);
    }

    /** The focused view inside this feed, if focus is currently here. */
    private View focusedView;

    /**
     * Quick D-pad presses can move focus to a poster that is still being scrolled into view. A layout
     * during that scroll can recycle the focused poster, which leaves nothing focused and makes the
     * remote appear dead. When that happens, focus is given back to the same title.
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusWatcher = (before, after) -> {
        if (after != null) {
            if (!isDescendant(after)) focusedView = null;
            return;
        }
        if (before != null && before == focusedView) recoverFocus();
    };

    private boolean isDescendant(View view) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) if (
            parent == this
        ) return true;
        return false;
    }

    private void recoverFocus() {
        long id = lastFocused;
        if (closed || id == Long.MIN_VALUE) return;
        post(() -> recoverFocus(id, 20));
    }

    /** Refocuses the title; waits for scrolling to stop before scrolling back to it. */
    private void recoverFocus(long id, int attemptsLeft) {
        if (closed || !isAttachedToWindow() || getRootView().findFocus() != null) return;
        ViewHolder holder = findViewHolderForItemId(id);
        if (holder != null && holder.itemView.isAttachedToWindow() && holder.itemView.requestFocus()) return;
        if (getScrollState() != SCROLL_STATE_IDLE && attemptsLeft > 0) {
            postDelayed(() -> recoverFocus(id, attemptsLeft - 1), 50);
            return;
        }
        for (int position = 0; position < items.size(); position++) if (adapter.getItemId(position) == id) {
            // onLayout focuses the item once it has been laid out again.
            restoredFocus = id;
            scrollToPosition(position);
            return;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusWatcher);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusWatcher);
        super.onDetachedFromWindow();
    }

    /** Sets the poster size for a horizontal feed. Call before the feed starts. */
    void setCardSize(int widthDp, int heightDp) {
        cardWidthDp = widthDp;
        cardHeightDp = heightDp;
    }

    void setColumns(int value) {
        columns = value;
        ((GridLayoutManager) getLayoutManager()).setSpanCount(value);
    }

    void start() {
        started = true;
        loadMore();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private void prefetch() {
        if (!started || closed || loading || failed || next > total) return;
        LinearLayoutManager manager = (LinearLayoutManager) getLayoutManager();
        int last = manager.findLastVisibleItemPosition();
        if (last >= items.size() - (horizontal ? 6 : 12)) post(() -> {
            if (!closed && !failed) loadMore();
        });
    }

    void loadMore() {
        if (!started || closed || loading || next > total) return;
        loading = true;
        failed = false;
        statusChanged();
        final int requested = next;
        worker.execute(() -> {
            try {
                JSONObject response = loader.load(requested);
                main.post(() -> {
                    if (closed) return;
                    try {
                        JSONArray results = response.getJSONArray("results");
                        ArrayList<JSONObject> additions = new ArrayList<>();
                        Set<String> addedKeys = new HashSet<>();
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject item = results.getJSONObject(i);
                            String media = item.optString("media_type", type);
                            int id = item.optInt("id");
                            if (
                                item.optBoolean("adult", false) ||
                                id <= 0 ||
                                (!media.equals("movie") && !media.equals("tv"))
                            ) continue;
                            if (!seen.contains(media + ":" + id) && addedKeys.add(media + ":" + id)) {
                                item.put("media_type", media);
                                additions.add(item);
                            }
                        }
                        int first = items.size();
                        seen.addAll(addedKeys);
                        items.addAll(additions);
                        capped = response.optInt("total_pages", requested) > 500;
                        total = Math.min(500, Math.max(requested, response.optInt("total_pages", requested)));
                        next = requested + 1;
                        loading = false;
                        // The footer stays at the end; existing poster holders are never rebound on append.
                        if (!additions.isEmpty()) {
                            adapter.notifyItemRangeInserted(first, additions.size());
                            if (first == 0) getLayoutManager().scrollToPosition(0);
                        }
                        statusChanged();
                        post(() -> {
                            completePendingFocus();
                            prefetch();
                        });
                    } catch (JSONException e) {
                        failed = true;
                        loading = false;
                        statusChanged();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (!closed) {
                        failed = true;
                        loading = false;
                        statusChanged();
                    }
                });
            }
        });
    }

    private void statusChanged() {
        adapter.notifyItemChanged(items.size());
    }

    boolean focusNearest(View from) {
        return focusFrom(from, false);
    }

    boolean focusBelow(View from) {
        return focusFrom(from, true);
    }

    private boolean focusFrom(View from, boolean below) {
        Rect source = new Rect();
        from.getGlobalVisibleRect(source);
        View best = null;
        int distance = Integer.MAX_VALUE,
            left = Integer.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int position = getChildAdapterPosition(child);
            if (position < 0 || position >= items.size()) continue;
            Rect r = new Rect();
            if (!child.getGlobalVisibleRect(r)) continue;
            int d = below
                ? Math.abs(r.centerX() - source.centerX())
                : Math.abs(r.centerY() - source.centerY());
            if (d < distance || (d == distance && r.left < left)) {
                best = child;
                distance = d;
                left = r.left;
            }
        }
        if (best != null) {
            pendingFrom = null;
            return best.requestFocus();
        }
        pendingFrom = from;
        pendingBelow = below;
        return false;
    }

    private void completePendingFocus() {
        if (pendingFrom != null) {
            if (pendingFrom.hasFocus()) focusFrom(pendingFrom, pendingBelow);
            else pendingFrom = null;
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (pendingFrom != null) post(this::completePendingFocus);
        if (restoredFocus != Long.MIN_VALUE) {
            ViewHolder holder = findViewHolderForItemId(restoredFocus);
            if (holder != null) {
                restoredFocus = Long.MIN_VALUE;
                View target = holder.itemView;
                post(target::requestFocus);
            }
        }
        if (onFirstItems != null && !items.isEmpty() && getLayoutManager().findViewByPosition(0) != null) {
            Runnable callback = onFirstItems;
            onFirstItems = null;
            post(callback);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean forward = horizontal ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_DOWN;
        if (forward) {
            View current = findFocus();
            ViewHolder holder = current == null ? null : findContainingViewHolder(current);
            if (next > total && holder != null && !items.isEmpty()) {
                int position = holder.getBindingAdapterPosition();
                boolean last = horizontal
                    ? position == items.size() - 1
                    : position >= 0 && position / columns == (items.size() - 1) / columns;
                if (last) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) smoothScrollToPosition(items.size());
                    return true;
                }
            }
            View target =
                current == null ? null : focusSearch(current, horizontal ? FOCUS_RIGHT : FOCUS_DOWN);
            boolean inside = false;
            for (
                View node = target;
                node != null;
                node = node.getParent() instanceof View ? (View) node.getParent() : null
            ) if (node == this) {
                inside = true;
                break;
            }
            if (!inside) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && !failed) loadMore();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void close() {
        closed = true;
        pendingFrom = null;
        main.removeCallbacksAndMessages(null);
    }

    private final class Holder extends ViewHolder {

        Holder(View view) {
            super(view);
        }
    }

    private final class FeedAdapter extends Adapter<Holder> {

        @Override
        public int getItemCount() {
            return items.size() + 1;
        }

        @Override
        public int getItemViewType(int p) {
            return p == items.size() ? 1 : 0;
        }

        @Override
        public long getItemId(int p) {
            if (p == items.size()) return Long.MIN_VALUE;
            JSONObject item = items.get(p);
            return ((long) item.optInt("id") << 1) | ("tv".equals(item.optString("media_type")) ? 1 : 0);
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            View card;
            if (type == 1) {
                Button status = new Button(getContext());
                status.setAllCaps(false);
                status.setTextColor(0xfff2f4f8);
                status.setTextSize(14);
                status.setBackgroundColor(0xff090d14);
                status.setOnFocusChangeListener((v, on) -> {
                    status.setBackgroundColor(on ? 0xfff2f4f8 : 0xff090d14);
                    status.setTextColor(on ? 0xff10141c : 0xfff2f4f8);
                });
                status.setOnClickListener(v -> {
                    View last = getLayoutManager().findViewByPosition(items.size() - 1);
                    if (last != null) last.requestFocus();
                    loadMore();
                });
                card = status;
            } else card = cards.create(parent);
            LayoutParams lp = new LayoutParams(
                horizontal ? dp(type == 1 ? 170 : cardWidthDp) : LayoutParams.MATCH_PARENT,
                horizontal ? dp(cardHeightDp) : type == 1 ? dp(48) : LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            card.setLayoutParams(lp);
            return new Holder(card);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            if (position == items.size()) {
                Button status = (Button) holder.itemView;
                boolean end = next > total;
                status.setText(
                    failed
                        ? "Could not load · Retry"
                        : end
                          ? items.isEmpty()
                              ? "No titles found · End of list"
                              : capped
                                ? "End of list · Refine category for more results"
                                : "End of list"
                          : "Loading titles…"
                );
                status.setFocusable(failed);
                status.setEnabled(failed);
                return;
            }
            cards.bind(holder.itemView, items.get(position));
        }
    }
}
