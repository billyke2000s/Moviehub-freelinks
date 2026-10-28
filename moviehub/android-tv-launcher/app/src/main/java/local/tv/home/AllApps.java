package local.tv.home;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Apps screen, like the Google TV app drawer: a search box, Android shortcuts (Wi-Fi,
 * Bluetooth, display, ...), recently used apps, then every app as a TV banner tile. Apps can be
 * hidden from this screen and shown again with "Show hidden".
 */
final class AllApps {

    static final int COLUMNS = 5;
    /** Rows of apps shown before the rest are added. */
    static final int FIRST_ROWS = 3;

    /** An Android settings page offered as a shortcut, shown only when this box has it. */
    static final String[][] SHORTCUTS = {
        { "Settings", Settings.ACTION_SETTINGS },
        { "Wi-Fi", Settings.ACTION_WIFI_SETTINGS },
        { "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS },
        { "Display", Settings.ACTION_DISPLAY_SETTINGS },
        { "Sound", Settings.ACTION_SOUND_SETTINGS },
        { "Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS },
        { "Manage apps", Settings.ACTION_APPLICATION_SETTINGS },
        { "Date & time", Settings.ACTION_DATE_SETTINGS },
    };

    private final MainActivity activity;
    boolean showHidden;
    /** Where the filtered app tiles go; rebuilt as the search changes. */
    LinearLayout grid;
    LinearLayout extras;
    EditText search;

    AllApps(MainActivity activity) {
        this.activity = activity;
    }

    void show() {
        activity.screen("Apps", activity.homeScreen::home);
        AppDock dock = activity.dock;
        dock.queryApps();

        LinearLayout top = activity.row();
        search = activity.searchBox(top, "Search apps");
        top.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button hidden = activity.button(showHidden ? "Hide hidden apps" : "Show hidden", () -> {
            showHidden = !showHidden;
            show();
        });
        LinearLayout.LayoutParams hiddenLayout = new LinearLayout.LayoutParams(-2, activity.dp(44));
        hiddenLayout.leftMargin = activity.dp(14);
        top.addView(hidden, hiddenLayout);
        activity.page.addView(top, new LinearLayout.LayoutParams(-1, -2));

        extras = activity.vertical();
        extras.setClipChildren(false);
        activity.page.addView(extras, new LinearLayout.LayoutParams(-1, -2));
        shortcuts(extras);
        recent(extras);

        grid = activity.vertical();
        grid.setClipChildren(false);
        activity.page.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        search.addTextChangedListener(
            new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                public void afterTextChanged(android.text.Editable text) {
                    fill(text.toString().trim());
                }
            }
        );
        fill("");
        // Start on the first app, not the search box, so the keyboard does not open by itself.
        activity.page.post(() -> {
            View first = firstTile();
            if (first != null) first.requestFocus();
        });
    }

    /** Counts fills; rows still waiting from an older fill (an earlier search) are dropped. */
    private int fillTicket;

    /** Fills the app grid with the apps whose name contains the search. */
    void fill(String query) {
        fillTicket++;
        grid.removeAllViews();
        extras.setVisibility(query.isEmpty() ? View.VISIBLE : View.GONE);
        String needle = query.toLowerCase(Locale.ROOT);
        List<AppDock.App> shown = new ArrayList<>();
        for (AppDock.App app : activity.dock.apps) {
            if (!showHidden && activity.dock.isHidden(app)) continue;
            if (!needle.isEmpty() && !app.label.toLowerCase(Locale.ROOT).contains(needle)) continue;
            shown.add(app);
        }
        heading(grid, query.isEmpty() ? "All apps" : shown.size() + " matching \"" + query + "\"");
        if (shown.isEmpty()) {
            grid.addView(activity.muted(query.isEmpty() ? "No apps found" : "No app has that name", 16));
            return;
        }
        // The first rows appear at once; the rest are added straight after the screen is first
        // drawn (a posted task runs after the pending layout and draw), all in one go.
        int ticket = fillTicket;
        LinearLayout target = grid;
        int first = Math.min(shown.size(), FIRST_ROWS * COLUMNS);
        addRows(shown, 0, first);
        if (first < shown.size()) target.post(() -> {
            if (ticket == fillTicket && target == grid && target.isAttachedToWindow()) addRows(
                shown,
                first,
                shown.size()
            );
        });
    }

    /**
     * Adds the tiles from {@code from} to {@code to}, {@link #COLUMNS} to a row. {@code from} is
     * always at the start of a row.
     */
    private void addRows(List<AppDock.App> shown, int from, int to) {
        LinearLayout line = null;
        for (int i = from; i < to; i++) {
            if (i % COLUMNS == 0) {
                line = activity.row();
                line.setClipChildren(false);
                grid.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
            addTile(line, shown.get(i));
        }
    }

    View firstTile() {
        LinearLayout source = extras.getVisibility() == View.VISIBLE ? extras : grid;
        return firstFocusable(source);
    }

    /** The first shortcut button or app tile (not the search box or a scroll container). */
    private View firstFocusable(View view) {
        boolean control = view instanceof Button || view.getTag() instanceof String;
        if (control && view.isFocusable() && view.getVisibility() == View.VISIBLE) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstFocusable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void heading(LinearLayout parent, String title) {
        TextView label = activity.compactText(title, 17);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(activity.dp(4), activity.dp(16), 0, activity.dp(6));
        parent.addView(label);
    }

    private void shortcuts(LinearLayout parent) {
        PackageManager manager = activity.getPackageManager();
        heading(parent, "Android");
        View title = parent.getChildAt(parent.getChildCount() - 1);
        LinearLayout line = activity.horizontal(parent);
        int added = 0;
        for (String[] shortcut : SHORTCUTS) {
            Intent intent = new Intent(shortcut[1]);
            if (intent.resolveActivity(manager) == null) continue;
            Button button = activity.button(shortcut[0], () -> {
                try {
                    activity.startActivity(intent);
                } catch (Exception e) {
                    activity.toast(shortcut[0] + " settings are not available on this box");
                }
            });
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, activity.dp(40));
            layout.rightMargin = activity.dp(10);
            line.addView(button, layout);
            added++;
        }
        if (added == 0) {
            parent.removeView(title);
            parent.removeView((View) line.getParent());
        }
    }

    private void recent(LinearLayout parent) {
        List<AppDock.App> recent = new ArrayList<>();
        for (String id : activity.dock.recentApps()) {
            AppDock.App app = activity.dock.find(id);
            if (app == null || (!showHidden && activity.dock.isHidden(app))) continue;
            recent.add(app);
            if (recent.size() == COLUMNS) break;
        }
        if (recent.isEmpty()) return;
        heading(parent, "Recently used");
        LinearLayout line = activity.row();
        line.setClipChildren(false);
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        for (AppDock.App app : recent) addTile(line, app);
    }

    /** A 16:9 tile: the app's TV banner, or its icon and name when it has no banner. */
    View addTile(LinearLayout line, AppDock.App app) {
        int width = tileWidth(),
            height = (width * 9) / 16;
        FrameLayout tile = new FrameLayout(activity);
        tile.setTag(app.component.flattenToString());
        tile.setContentDescription(app.label);
        GradientDrawable back = activity.box(0xff1a2230);
        back.setCornerRadius(activity.dp(10));
        tile.setBackground(back);
        tile.setClipToOutline(true);
        Drawable banner = banner(app.component);
        if (banner != null) {
            ImageView image = new ImageView(activity);
            image.setImageDrawable(banner);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout content = activity.vertical();
            content.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(activity);
            if (app.icon != null) icon.setImageDrawable(app.icon);
            content.addView(icon, new LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)));
            TextView label = activity.compactText(app.label, 13);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setPadding(activity.dp(6), activity.dp(4), activity.dp(6), 0);
            content.addView(label, new LinearLayout.LayoutParams(-1, -2));
            tile.addView(content, new FrameLayout.LayoutParams(-1, -1));
        }
        boolean hidden = activity.dock.isHidden(app);
        tile.setAlpha(hidden ? .45f : 1f);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(activity.dp(10));
        ring.setStroke(activity.dp(3), 0xffffffff);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setOnFocusChangeListener((v, focused) -> {
            tile.setForeground(focused ? ring : null);
            tile.setScaleX(focused ? 1.08f : 1f);
            tile.setScaleY(focused ? 1.08f : 1f);
            tile.setTranslationZ(focused ? activity.dp(6) : 0);
            tile.setAlpha(focused ? 1f : hidden ? .45f : 1f);
        });
        tile.setOnClickListener(v -> activity.dock.launch(app));
        tile.setOnLongClickListener(v -> {
            activity.dock.appMenu(v, app, this::refresh);
            return true;
        });
        tile.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_MENU) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) activity.dock.appMenu(v, app, this::refresh);
            return true;
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(width, height);
        layout.setMargins(activity.dp(4), activity.dp(6), activity.dp(8), activity.dp(6));
        line.addView(tile, layout);
        return tile;
    }

    /** Rebuilds the screen after an app was hidden, shown or uninstalled, keeping the search. */
    void refresh() {
        String query = search == null ? "" : search.getText().toString();
        show();
        if (!query.isEmpty()) search.setText(query);
    }

    private int tileWidth() {
        int available = activity.getResources().getDisplayMetrics().widthPixels - activity.dp(80);
        return available / COLUMNS - activity.dp(12);
    }

    private Drawable banner(ComponentName component) {
        PackageManager manager = activity.getPackageManager();
        try {
            Drawable banner = manager.getActivityBanner(component);
            if (banner == null) banner = manager.getApplicationBanner(component.getPackageName());
            return banner;
        } catch (Exception e) {
            return null;
        }
    }
}
