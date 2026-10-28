package local.tv.home;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The guide menu, laid out like the satellite box's: a numbered list in the guide's dark blue
 * (1 All Channels, 2 Favourites, 3 Entertainment … 0 More…), the chosen row in yellow, number keys
 * to pick one, and the colour keys underneath. Live TV opens here, like the box's TV Guide button;
 * the parts along the top (Reminders, Search, Options) can be chosen too. BACK from the channel
 * grid comes here; BACK from here goes Home.
 */
final class GuideMenu {

    /**
     * The menu, in the satellite box's order: label and the guide list each opens. Favourites is
     * on the blue key (and on the second page). Long lists split into pages of nine with More… at
     * the bottom, as the box did.
     */
    static final String[][] ITEMS = {
        { "All Channels", "all" },
        { "Entertainment", "section:Entertainment" },
        { "Movies", "section:Movies" },
        { "Sports", "section:Sports" },
        { "News", "section:News" },
        { "Documentaries", "section:Documentaries" },
        { "Kids", "section:Kids" },
        { "Music", "section:Music" },
        { "Radio", "section:Radio" },
        { "Favourites", "favourites" },
        { "Religion", "section:Religion" },
        { "Shopping", "section:Shopping" },
        { "International", "countries" },
        { "Channel groups from your lists", "groups" },
        { "Options", "options" },
    };

    /** Rows on a page, More… included. */
    static final int PAGE = 10;

    /** The page shown (0 is the first). */
    int page;

    private final MainActivity activity;
    private final LiveTvScreen guide;
    /** The menu's rows, and the parts along the top (for tests). */
    final List<View> rows = new ArrayList<>(),
        tabs = new ArrayList<>();
    /** The colour keys along the bottom, which can be chosen with the D-pad. */
    KeyBar bar;

    GuideMenu(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    /** Live TV opens here first, as the satellite box's TV Guide button did. */
    void show() {
        page(0);
    }

    /** The items on page {@code index}: nine and More… while more follow, else the rest. */
    static String[][] pageItems(int index) {
        List<String[]> out = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < index; i++) from += PAGE - 1;
        boolean last = ITEMS.length - from <= PAGE;
        int to = last ? ITEMS.length : from + PAGE - 1;
        for (int i = from; i < to; i++) out.add(ITEMS[i]);
        if (!last) out.add(new String[] { "More…", "" });
        return out.toArray(new String[0][]);
    }

    /** Shows a page of the menu; BACK goes up a page, then Home. */
    private void page(int index) {
        page = index;
        build(
            pageItems(index),
            index == 0 ? "TV GUIDE" : "MORE",
            index == 0 ? activity.homeScreen::home : () -> page(index - 1)
        );
    }

    private void build(String[][] items, String heading, Runnable back) {
        activity.fixedScreen(null, back, true);
        LinearLayout page = activity.page;
        page.setPadding(activity.dp(80), activity.dp(24), activity.dp(80), activity.dp(18));
        page.setBackground(GuideFrame.background());

        // The heading row: the guide's name, then its parts. Each part can be chosen: this list,
        // reminders, search and options.
        LinearLayout top = activity.row();
        top.setGravity(Gravity.BOTTOM);
        TextView name = text("TV Guide", 30, true);
        name.setPadding(0, 0, activity.dp(30), 0);
        top.addView(name);
        String[] parts = { heading, "REMINDERS", "SEARCH", "OPTIONS" };
        Runnable[] actions = {
            () -> rows.get(0).requestFocus(),
            guide.panels::reminders,
            guide.panels::searchPanel,
            guide.panels::options,
        };
        tabs.clear();
        for (int i = 0; i < parts.length; i++) {
            Runnable action = actions[i];
            boolean current = i == 0;
            TextView tab = text(parts[i], 18, true);
            tab.setPadding(activity.dp(10), activity.dp(3), activity.dp(10), activity.dp(3));
            tab.setFocusable(true);
            tab.setFocusableInTouchMode(true);
            tab.setOnClickListener(v -> action.run());
            View.OnFocusChangeListener style = (v, focused) -> {
                tab.setBackground(focused ? box(LiveTvScreen.YELLOW) : null);
                tab.setTextColor(focused ? LiveTvScreen.INK : current ? 0xffffffff : 0xffe2eeff);
                tab.setAlpha(focused || current ? 1f : 0.8f);
            };
            tab.setOnFocusChangeListener(style);
            style.onFocusChange(tab, false);
            tab.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    rows.get(0).requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                return shortcut(items, key);
            });
            LinearLayout.LayoutParams tabLayout = new LinearLayout.LayoutParams(-2, -2);
            tabLayout.rightMargin = activity.dp(8);
            top.addView(tab, tabLayout);
            tabs.add(tab);
        }
        page.addView(top);

        // The rows share the height, so every one fits on the screen with nothing to scroll.
        LinearLayout list = activity.vertical();
        list.setPadding(activity.dp(40), activity.dp(18), activity.dp(40), activity.dp(12));
        rows.clear();
        for (int i = 0; i < items.length; i++) {
            String[] item = items[i];
            int index = i;
            View row = row(digit(items, i), item[0]);
            row.setOnClickListener(v -> open(item[1]));
            row.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP && index == 0) {
                    tabs.get(0).requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && index == items.length - 1) {
                    rows.get(0).requestFocus(); // round to the top, as on the satellite box
                    return true;
                }
                return shortcut(items, key);
            });
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, 0, 1);
            rowLayout.bottomMargin = activity.dp(3);
            list.addView(row, rowLayout);
            rows.add(row);
        }
        // A short list (More…) keeps rows the usual height instead of stretching them.
        int spare = PAGE - items.length;
        if (spare > 0) list.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, spare));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        bar = new KeyBar(activity)
            .onUp(() -> rows.get(rows.size() - 1).requestFocus())
            .add(GuideFrame.RED, "Options", guide.panels::options)
            .add(GuideFrame.GREEN, "Reminders", guide.panels::reminders)
            .add(GuideFrame.YELLOW, "Search A–Z", guide.panels::searchPanel)
            .add(GuideFrame.BLUE, "Favourites", () -> guide.show("favourites", ""));
        bar.view.setPadding(activity.dp(34), 0, 0, 0);
        page.addView(bar.view);
        rows.get(0).requestFocus();
    }

    /** The number shown beside a row: 1 to 9, then 0 for the tenth. */
    private static int digit(String[][] items, int index) {
        return (index + 1) % 10;
    }

    /** Number keys open a row; the colour keys do what the key guide at the bottom says. */
    private boolean shortcut(String[][] items, int key) {
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            int pressed = key - KeyEvent.KEYCODE_0;
            for (int j = 0; j < items.length; j++) {
                if (digit(items, j) == pressed) {
                    rows.get(j).requestFocus();
                    open(items[j][1]);
                    return true;
                }
            }
            return true;
        }
        char colour = guide.colour(key);
        if (colour == 'r') guide.panels.options();
        else if (colour == 'g') guide.panels.reminders();
        else if (colour == 'y') guide.panels.searchPanel();
        else if (colour == 'b') guide.show("favourites", "");
        else return false;
        return true;
    }

    private void open(String target) {
        switch (target) {
            case "":
                page(page + 1); // More…
                break;
            case "countries":
                guide.panels.countries();
                break;
            case "groups":
                guide.panels.groups();
                break;
            case "options":
                guide.panels.options();
                break;
            default:
                guide.show(target, "");
        }
    }

    /** A numbered row: the number on a lighter cell, the name in capitals; yellow when chosen. */
    private View row(int digit, String label) {
        LinearLayout row = activity.row();
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        TextView number = text(String.valueOf(digit), 19, true);
        number.setGravity(Gravity.CENTER);
        row.addView(number, new LinearLayout.LayoutParams(activity.dp(52), -1));
        TextView name = text(label.toUpperCase(Locale.UK), 19, true);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setPadding(activity.dp(16), 0, activity.dp(12), 0);
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(0, -1, 1);
        nameLayout.leftMargin = activity.dp(3);
        row.addView(name, nameLayout);
        View.OnFocusChangeListener style = (v, focused) -> {
            number.setBackground(box(focused ? LiveTvScreen.YELLOW_DARK : LiveTvScreen.BLUE_DARK));
            number.setTextColor(focused ? LiveTvScreen.INK : LiveTvScreen.NUMBER_TEXT);
            name.setBackground(box(focused ? LiveTvScreen.YELLOW : LiveTvScreen.BLUE));
            name.setTextColor(focused ? LiveTvScreen.INK : 0xffffffff);
        };
        row.setOnFocusChangeListener(style);
        style.onFocusChange(row, false);
        return row;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView label = activity.compactText(value, size);
        label.setTextColor(0xffffffff);
        if (bold) label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return label;
    }

    private GradientDrawable box(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(2));
        return shape;
    }
}
