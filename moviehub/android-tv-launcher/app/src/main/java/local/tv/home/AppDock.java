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

/** Installed apps: the Home dock (ordering, lifting, removing) and the All Apps screen. */
final class AppDock {

    private final MainActivity activity;

    AppDock(MainActivity activity) {
        this.activity = activity;
    }

    static final int MAX_RECENT = 10;
    final List<App> apps = new ArrayList<>();
    View movingApp;
    LinearLayout homeDock;
    TextView dockHint;
    boolean eatSelectUp, refreshAppsOnResume, dockRemoving;

    static class App {

        String label;
        ComponentName component;
        Drawable icon;

        App(String label, ComponentName component, Drawable icon) {
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }

    /**
     * Set when apps are installed, updated or removed (see MainActivity's package receiver).
     * Loading every app's name and icon is slow, so Home reuses the list until then.
     */
    volatile boolean appsStale = true;

    /** Loads the app list only if it changed since the last load. */
    void ensureApps() {
        if (appsStale || apps.isEmpty()) queryApps();
    }

    void queryApps() {
        appsStale = false;
        apps.clear();
        PackageManager manager = activity.getPackageManager();
        Set<ComponentName> seen = new HashSet<>();
        // Many apps list one entry for TV launchers and another for phone launchers: the same
        // package and name but a different activity. They are one app, shown once (the TV entry,
        // found first). Different packages are different apps, even with the same name.
        Map<String, App> sameApp = new HashMap<>();
        Map<String, String> merged = new HashMap<>();
        for (String category : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER })
            for (ResolveInfo r : manager.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(category),
                0
            )) {
                ActivityInfo a = r.activityInfo;
                if (!a.enabled || !a.exported || a.packageName.equals(activity.getPackageName())) continue;
                ComponentName component = new ComponentName(a.packageName, a.name);
                if (!seen.add(component)) continue;
                String label = r.loadLabel(manager).toString();
                String key = a.packageName + "\n" + label;
                App kept = sameApp.get(key);
                if (kept != null) {
                    merged.put(component.flattenToString(), kept.component.flattenToString());
                    continue;
                }
                App app = new App(label, component, r.loadIcon(manager));
                sameApp.put(key, app);
                apps.add(app);
            }
        apps.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
        if (!merged.isEmpty()) movePins(merged);
    }

    /**
     * Hotbar pins and recently used apps saved on an entry that was merged into another now point
     * at the one shown. Hidden apps are left alone: hiding the extra entry must not hide the app.
     */
    private void movePins(Map<String, String> merged) {
        List<String> order = dockOrder(),
            moved = remap(order, merged);
        if (!moved.equals(order)) saveDockOrder(moved);
        List<String> recent = recentApps(),
            movedRecent = remap(recent, merged);
        if (!movedRecent.equals(recent)) activity.prefs
            .edit()
            .putString("recentApps", new JSONArray(movedRecent).toString())
            .apply();
    }

    /** {@code ids} with merged entries replaced by the kept one, each listed once, in order. */
    static List<String> remap(List<String> ids, Map<String, String> merged) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : ids) out.add(merged.containsKey(id) ? merged.get(id) : id);
        return new ArrayList<>(out);
    }

    /** The installed app with this component (flattened), or null. Call queryApps() first. */
    App find(String component) {
        for (App app : apps) if (app.component.flattenToString().equals(component)) return app;
        return null;
    }

    /** Opens an app and remembers it for "Recently used" on the Apps screen. */
    void launch(App app) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component));
        } catch (Exception e) {
            activity.toast(app.label + " is unavailable");
            return;
        }
        List<String> recent = recentApps();
        String id = app.component.flattenToString();
        recent.remove(id);
        recent.add(0, id);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        activity.prefs.edit().putString("recentApps", new JSONArray(recent).toString()).apply();
    }

    /** Apps opened from MovieHub, newest first. */
    List<String> recentApps() {
        return stringList("recentApps");
    }

    boolean isHidden(App app) {
        return stringList("hiddenApps").contains(app.component.flattenToString());
    }

    void setHidden(App app, boolean hidden) {
        List<String> list = stringList("hiddenApps");
        String id = app.component.flattenToString();
        list.remove(id);
        if (hidden) list.add(id);
        activity.prefs.edit().putString("hiddenApps", new JSONArray(list).toString()).apply();
    }

    private List<String> stringList(String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(activity.prefs.getString(key, "[]"));
            for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
        } catch (JSONException e) {
            Diagnostics.recovered("Reading " + key, e);
        }
        return out;
    }

    /** The installed app with exactly this name, or null. Call queryApps() first. */
    App findApp(String label) {
        for (App app : apps) if (app.label.equals(label)) return app;
        return null;
    }

    /**
     * Just the app's own icon, with no tile behind it, for the Home top bar. Focus enlarges it and
     * adds a soft round glow instead of a box.
     */
    View iconShortcut(App app) {
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3));
        icon.setContentDescription(app.label);
        icon.setFocusable(true);
        icon.setFocusableInTouchMode(true);
        icon.setAlpha(.85f);
        android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable();
        glow.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        glow.setColor(0x33ffffff);
        icon.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(focused ? glow : null);
            v.setAlpha(focused ? 1f : .85f);
            v.setScaleX(focused ? 1.2f : 1f);
            v.setScaleY(focused ? 1.2f : 1f);
        });
        icon.setOnClickListener(v -> launch(app));
        return icon;
    }

    View addApp(LinearLayout into, App app, boolean dock) {
        LinearLayout cell = activity.vertical();
        cell.setTag(app.component.flattenToString());
        cell.setGravity(Gravity.CENTER);
        ImageView image = new ImageView(activity);
        image.setImageDrawable(app.icon);
        cell.addView(image, new LinearLayout.LayoutParams(activity.dp(52), activity.dp(52)));
        TextView label = activity.text(app.label, 16);
        label.setMaxLines(2);
        label.setGravity(Gravity.CENTER);
        cell.addView(label);
        activity.focus(cell);
        cell.setBackground(activity.box(0xff202c43));
        cell.setOnClickListener(v -> launch(app));
        cell.setOnLongClickListener(v -> {
            if (dock) beginDockMove(v);
            else appMenu(v, app, null);
            return true;
        });
        cell.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (dock) beginDockMove(v);
                    else appMenu(v, app, null);
                }
                return true;
            }
            return false;
        });
        activity.addRow(into, cell, 130, 114);
        return cell;
    }

    /** Migrate the old unordered pins once, using their existing visible order. */
    List<String> dockOrder() {
        List<String> order = new ArrayList<>();
        String saved = activity.prefs.getString("dockOrder", null);
        if (saved != null) {
            try {
                JSONArray array = new JSONArray(saved);
                for (int i = 0; i < array.length(); i++) {
                    String id = array.getString(i);
                    if (!order.contains(id)) order.add(id);
                }
            } catch (JSONException e) {
                Diagnostics.recovered("Reading saved dock order", e);
            }
        } else {
            Set<String> pins = activity.prefs.getStringSet("pinned", null);
            for (App app : apps)
                if (
                    pins == null ? order.size() < 6 : pins.contains(app.component.flattenToString())
                ) order.add(app.component.flattenToString());
            saveDockOrder(order);
        }
        return order;
    }

    private void saveDockOrder(List<String> order) {
        activity.prefs.edit().putString("dockOrder", new JSONArray(order).toString()).apply();
    }

    void saveVisibleDock() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < homeDock.getChildCount(); i++) {
            Object id = homeDock.getChildAt(i).getTag();
            if (id instanceof String) order.add((String) id);
        }
        saveDockOrder(order);
    }

    private void beginDockMove(View view) {
        if (dockRemoving) return;
        finishDockMove();
        movingApp = view;
        eatSelectUp = true;
        view.requestFocus();
        view.animate().translationY(-activity.dp(8)).setDuration(160).start();
        view.setElevation(activity.dp(10));
        dockHint.setText("← → Move · ↑ Remove · Select Done");
    }

    void finishDockMove() {
        if (movingApp != null) {
            movingApp.animate().translationY(0).setDuration(160).start();
            movingApp.setElevation(0);
            movingApp = null;
        }
        if (dockHint != null) dockHint.setText("Hold Select to rearrange");
    }

    /** Animate from the positions actually on screen, including any unfinished movement. */
    private void animateDockChange(Runnable change) {
        LinearLayout dock = homeDock;
        Map<View, Float> positions = new HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View child = dock.getChildAt(i);
            positions.put(child, child.getX());
            child.animate().cancel();
        }
        change.run();
        dock.getViewTreeObserver().addOnPreDrawListener(
            new android.view.ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    dock.getViewTreeObserver().removeOnPreDrawListener(this);
                    for (int i = 0; i < dock.getChildCount(); i++) {
                        View child = dock.getChildAt(i);
                        Float old = positions.get(child);
                        if (old != null) {
                            child.setTranslationX(old - child.getLeft());
                            child
                                .animate()
                                .translationX(0)
                                .setDuration(220)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                        }
                    }
                    return true;
                }
            }
        );
    }

    void moveDockApp(int direction) {
        View cell = movingApp;
        int index = homeDock.indexOfChild(cell),
            target = index + direction;
        if (target < 0 || target >= homeDock.getChildCount() - 1) return;
        animateDockChange(() -> {
            homeDock.removeView(cell);
            homeDock.addView(cell, target);
            cell.requestFocus();
        });
        saveVisibleDock();
    }

    void removeDockApp() {
        View cell = movingApp;
        LinearLayout dock = homeDock;
        dockRemoving = true;
        int index = dock.indexOfChild(cell);
        finishDockMove();
        cell.setEnabled(false);
        cell.setFocusable(false);
        homeDock.getChildAt(Math.min(index + 1, homeDock.getChildCount() - 1)).requestFocus();
        // Remove from saved order immediately; keep the outgoing tile until its exit ends.
        List<String> order = dockOrder();
        order.remove((String) cell.getTag());
        saveDockOrder(order);
        cell.animate()
            .translationY(-activity.dp(24))
            .alpha(0)
            .setDuration(160)
            .withEndAction(() -> {
                dockRemoving = false;
                if (cell.getParent() != homeDock || homeDock != dock) return;
                animateDockChange(() -> homeDock.removeView(cell));
                homeDock.getChildAt(Math.min(index, homeDock.getChildCount() - 1)).requestFocus();
            })
            .start();
    }

    /** Open, hotbar, hide, app info and uninstall. {@code changed} runs after hide or show. */
    void appMenu(View anchor, App app, Runnable changed) {
        boolean pinned = dockOrder().contains(app.component.flattenToString());
        boolean hidden = isHidden(app);
        SidePanel panel = SidePanel.of(activity, app.label)
            .option("Open", () -> launch(app))
            .option(pinned ? "Remove from hotbar" : "Add to hotbar", () -> {
                List<String> order = dockOrder();
                String component = app.component.flattenToString();
                if (!order.remove(component)) order.add(component);
                saveDockOrder(order);
                activity.toast(pinned ? "Removed from hotbar" : "Added to hotbar");
            })
            .option(hidden ? "Show in Apps" : "Hide from Apps", () -> {
                setHidden(app, !hidden);
                activity.toast(
                    hidden
                        ? app.label + " is shown again"
                        : app.label + " is hidden. Use Show hidden to find it."
                );
                if (changed != null) changed.run();
            })
            .option("App info", () -> openSystem(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
        try {
            ApplicationInfo info = activity
                .getPackageManager()
                .getApplicationInfo(app.component.getPackageName(), 0);
            if (
                (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0 ||
                (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            ) panel.option("Uninstall", () -> openSystem(Intent.ACTION_DELETE, app));
        } catch (PackageManager.NameNotFoundException ignored) {}
        panel.show();
    }

    /** App info or uninstall; the Apps screen refreshes when the user comes back. */
    private void openSystem(String action, App app) {
        try {
            refreshAppsOnResume = true;
            activity.startActivity(
                new Intent(action, android.net.Uri.parse("package:" + app.component.getPackageName()))
            );
        } catch (Exception e) {
            refreshAppsOnResume = false;
            activity.toast("Android could not open this action");
        }
    }

    /** Opens the Apps screen; see {@link AllApps}. */
    void allApps() {
        activity.appsScreen.show();
    }
}
