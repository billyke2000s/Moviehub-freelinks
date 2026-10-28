package local.tv.home;

import android.content.pm.ActivityInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Live TV laid out for a phone or tablet: a portrait, touch list of channels with their number,
 * name and what is on now and next; section tabs and a search box at the top; tap to watch, hold
 * to add or remove a favourite. Shown when the phone layout was chosen (see {@link DeviceMode}).
 * The channel list, numbers, favourites, lock and player are the same as on the TV.
 */
final class PhoneLive {

    /** Tabs: label and the channel list each shows. */
    static final String[][] TABS = {
        { "All", "all" },
        { "Favourites", "favourites" },
        { "Entertainment", "section:Entertainment" },
        { "Movies", "section:Movies" },
        { "Sports", "section:Sports" },
        { "News", "section:News" },
        { "Docs", "section:Documentaries" },
        { "Kids", "section:Kids" },
        { "Music", "section:Music" },
        { "Radio", "section:Radio" },
        { "International", "section:International" },
    };

    static final int BACKGROUND = 0xff0b1a4a,
        CARD = 0xff15296d,
        CARD_LINE = 0x33ffffff,
        SOFT = 0xffc9d8ff;

    /** One channel in the list with what is on now and next (either may be null). */
    static final class Item {

        final ChannelDatabase.Row channel;
        final ChannelDatabase.Programme now, next;

        Item(ChannelDatabase.Row channel, ChannelDatabase.Programme now, ChannelDatabase.Programme next) {
            this.channel = channel;
            this.now = now;
            this.next = next;
        }
    }

    private final MainActivity activity;
    private final LiveTvScreen tv;
    String filter = "all",
        search = "";
    /** The channels loaded so far (for tests and paging). */
    final List<Item> items = new ArrayList<>();
    private boolean more, loading;
    private int ticket;
    private ListView list;
    private Adapter adapter;
    private TextView empty;
    private final List<TextView> tabs = new ArrayList<>();
    private final Runnable searchLater = () -> reload();

    PhoneLive(MainActivity activity) {
        this.activity = activity;
        this.tv = activity.liveTv;
    }

    void show() {
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.destination = this::show;
        activity.fixedScreen(null, activity::finish, true);
        try {
            ChannelSync.refreshIfStale(activity, true);
        } catch (Exception e) {
            Diagnostics.recovered("Starting channel refresh", e);
        }
        LinearLayout page = activity.page;
        page.setPadding(activity.dp(14), activity.dp(14), activity.dp(14), 0);
        page.setBackgroundColor(BACKGROUND);

        LinearLayout top = activity.row();
        TextView title = label("Live TV", 24, true, 0xffffffff);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView menu = label("⋮", 26, true, 0xffffffff);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Menu");
        menu.setOnClickListener(v -> menu());
        menu.setBackground(box(CARD));
        top.addView(menu, new LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)));
        page.addView(top);

        EditText query = new EditText(activity);
        query.setSingleLine(true);
        query.setHint("Search channels");
        query.setText(search);
        query.setTextColor(0xffffffff);
        query.setHintTextColor(0x99ffffff);
        query.setTextSize(16);
        query.setBackground(box(CARD));
        query.setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10));
        query.addTextChangedListener(
            new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                public void onTextChanged(CharSequence s, int a, int b, int c) {}

                public void afterTextChanged(Editable text) {
                    search = text.toString().trim();
                    activity.handler.removeCallbacks(searchLater);
                    activity.handler.postDelayed(searchLater, 400);
                }
            }
        );
        LinearLayout.LayoutParams queryLayout = new LinearLayout.LayoutParams(-1, -2);
        queryLayout.topMargin = activity.dp(10);
        page.addView(query, queryLayout);

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = activity.row();
        tabs.clear();
        for (String[] tab : TABS) {
            TextView item = label(tab[0], 15, true, 0xffffffff);
            item.setGravity(Gravity.CENTER);
            item.setPadding(activity.dp(14), activity.dp(8), activity.dp(14), activity.dp(8));
            item.setOnClickListener(v -> {
                filter = tab[1];
                styleTabs();
                reload();
            });
            LinearLayout.LayoutParams tabLayout = new LinearLayout.LayoutParams(-2, -2);
            tabLayout.rightMargin = activity.dp(6);
            tabRow.addView(item, tabLayout);
            tabs.add(item);
        }
        scroll.addView(tabRow);
        LinearLayout.LayoutParams scrollLayout = new LinearLayout.LayoutParams(-1, -2);
        scrollLayout.topMargin = activity.dp(10);
        scrollLayout.bottomMargin = activity.dp(6);
        page.addView(scroll, scrollLayout);
        styleTabs();

        empty = label("Loading channels…", 16, false, SOFT);
        empty.setPadding(activity.dp(4), activity.dp(16), activity.dp(4), activity.dp(16));
        page.addView(empty);
        list = new ListView(activity);
        list.setDivider(null);
        list.setDividerHeight(activity.dp(6));
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, activity.dp(16));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> watch(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            favourite(position);
            return true;
        });
        list.setOnScrollListener(
            new AbsListView.OnScrollListener() {
                public void onScrollStateChanged(AbsListView view, int state) {}

                public void onScroll(AbsListView view, int first, int visible, int total) {
                    if (more && !loading && first + visible >= total - 10) load(items.size());
                }
            }
        );
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        reload();
    }

    /** Back from the player: the same list, reloaded so numbers and favourites are current. */
    void refresh() {
        show();
    }

    private void reload() {
        items.clear();
        more = false;
        if (adapter != null) adapter.notifyDataSetChanged();
        load(0);
    }

    /** Loads a page of channels from {@code offset}, with what is on now and next on each. */
    private void load(int offset) {
        int request = ++ticket;
        loading = true;
        String listFilter = filter,
            listSearch = search;
        Set<String> favourites = tv.favouriteKeys();
        ChannelDatabase db = ChannelDatabase.get(activity);
        activity.async(
            () -> {
                List<ChannelDatabase.Row> rows = db.browse(listFilter, listSearch, favourites, offset);
                List<Item> page = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (int i = 0; i < Math.min(rows.size(), ChannelDatabase.PAGE); i++) {
                    ChannelDatabase.Row row = rows.get(i);
                    List<ChannelDatabase.Programme> on = db.programmes(
                        row,
                        EpgImporter.COUNTRY,
                        now,
                        now + 6 * 3600000L,
                        2
                    );
                    page.add(
                        new Item(row, on.isEmpty() ? null : on.get(0), on.size() > 1 ? on.get(1) : null)
                    );
                }
                return new Object[] { page, rows.size() > ChannelDatabase.PAGE };
            },
            result -> {
                if (request != ticket) return;
                loading = false;
                @SuppressWarnings("unchecked")
                List<Item> page = (List<Item>) result[0];
                items.addAll(page);
                more = (Boolean) result[1];
                adapter.notifyDataSetChanged();
                empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                if (items.isEmpty()) empty.setText(
                    filter.equals("favourites")
                        ? "No favourites yet. Hold a channel to add it."
                        : "No channels here yet. Channels may still be loading."
                );
            }
        );
    }

    /** Tap: watch it full screen. Channel up and down follow this list. */
    private void watch(int position) {
        if (position < 0 || position >= items.size()) return;
        tv.filter = filter;
        tv.search = search;
        tv.open(items.get(position).channel, position);
    }

    /** A tap on row {@code position}, for tests. */
    void watchForTest(int position) {
        watch(position);
    }

    /** Hold: add to or remove from favourites. */
    private void favourite(int position) {
        if (position < 0 || position >= items.size()) return;
        ChannelDatabase.Row channel = items.get(position).channel;
        Set<String> keys = tv.favouriteKeys();
        boolean added = keys.add(channel.key);
        if (!added) keys.remove(channel.key);
        activity.prefs.edit().putStringSet("favourites", keys).apply();
        activity.toast(
            added ? channel.name + " added to favourites" : channel.name + " removed from favourites"
        );
        adapter.notifyDataSetChanged();
    }

    private void menu() {
        SidePanel.of(activity, "Live TV")
            .option("Reminders", tv.panels::reminders)
            .option("Parental lock", tv.panels::parentalLock)
            .option("Refresh channels", () -> {
                ChannelSync.refreshNow(activity);
                reload();
            })
            .option("Use the TV layout", () -> {
                DeviceMode.choose(activity, DeviceMode.TV);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                activity.homeScreen.home();
            })
            .show();
    }

    private void styleTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            boolean on = TABS[i][1].equals(filter);
            tabs.get(i).setBackground(box(on ? LiveTvScreen.YELLOW : CARD));
            tabs.get(i).setTextColor(on ? LiveTvScreen.INK : 0xffffffff);
        }
    }

    /** The list's rows: number, name (with a star for favourites), now and next. */
    private final class Adapter extends BaseAdapter {

        public int getCount() {
            return items.size();
        }

        public Object getItem(int position) {
            return items.get(position);
        }

        public long getItemId(int position) {
            return items.get(position).channel.id;
        }

        public View getView(int position, View reuse, ViewGroup parent) {
            LinearLayout row;
            TextView number, name, now, next;
            if (reuse instanceof LinearLayout && reuse.getTag() instanceof TextView[]) {
                row = (LinearLayout) reuse;
                TextView[] parts = (TextView[]) reuse.getTag();
                number = parts[0];
                name = parts[1];
                now = parts[2];
                next = parts[3];
            } else {
                row = activity.row();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10));
                row.setBackground(box(CARD));
                row.setMinimumHeight(activity.dp(64));
                number = label("", 17, true, LiveTvScreen.YELLOW);
                number.setMinWidth(activity.dp(58));
                row.addView(number);
                LinearLayout words = activity.vertical();
                name = label("", 17, true, 0xffffffff);
                words.addView(name);
                now = label("", 14, false, 0xffffffff);
                words.addView(now);
                next = label("", 13, false, SOFT);
                words.addView(next);
                row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                row.setTag(new TextView[] { number, name, now, next });
            }
            Item item = items.get(position);
            boolean favourite = tv.favouriteKeys().contains(item.channel.key);
            number.setText(String.valueOf(item.channel.number));
            name.setText(
                (ParentalLock.locked(activity, item.channel) ? "🔒 " : "") +
                    (favourite ? "★ " : "") +
                    item.channel.name
            );
            now.setText(item.now == null ? "No programme information" : "Now  " + item.now.title);
            next.setText(
                item.next == null
                    ? ""
                    : "Next " + LiveTvScreen.clockTime(new Date(item.next.start)) + "  " + item.next.title
            );
            next.setVisibility(item.next == null ? View.GONE : View.VISIBLE);
            return row;
        }
    }

    private TextView label(String value, int size, boolean bold, int colour) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(colour);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        SkyFont.apply(text, bold);
        if (bold && text.getTypeface() == null) text.setTypeface(Typeface.DEFAULT_BOLD);
        return text;
    }

    private GradientDrawable box(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(8));
        shape.setStroke(activity.dp(1), CARD_LINE);
        return shape;
    }
}
