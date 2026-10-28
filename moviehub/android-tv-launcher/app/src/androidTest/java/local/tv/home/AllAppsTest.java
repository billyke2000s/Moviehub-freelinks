package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;

/** The Apps screen: Android shortcuts, recently used, search, hiding apps. */
@RunWith(AndroidJUnit4.class)
public class AllAppsTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    SharedPreferences prefs;
    String hiddenBefore, recentBefore;
    boolean updates;

    @Before
    public void start() {
        Context context = remote.getTargetContext();
        prefs = context.getSharedPreferences("ui", 0);
        hiddenBefore = prefs.getString("hiddenApps", null);
        recentBefore = prefs.getString("recentApps", null);
        updates = prefs.getBoolean("autoUpdates", true);
        prefs.edit().putBoolean("autoUpdates", false).remove("hiddenApps").remove("recentApps").commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void stop() {
        remote.runOnMainSync(() -> activity.finish());
        SharedPreferences.Editor edit = prefs.edit().putBoolean("autoUpdates", updates);
        if (hiddenBefore == null) edit.remove("hiddenApps");
        else edit.putString("hiddenApps", hiddenBefore);
        if (recentBefore == null) edit.remove("recentApps");
        else edit.putString("recentApps", recentBefore);
        edit.commit();
    }

    static List<String> texts(View view, List<String> out) {
        if (view.getVisibility() != View.VISIBLE) return out;
        if (view instanceof TextView) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) texts(
            ((ViewGroup) view).getChildAt(i),
            out
        );
        return out;
    }

    static List<String> tiles(LinearLayout grid) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View line = grid.getChildAt(i);
            if (!(line instanceof LinearLayout)) continue;
            for (int j = 0; j < ((LinearLayout) line).getChildCount(); j++) {
                Object tag = ((LinearLayout) line).getChildAt(j).getTag();
                if (tag instanceof String) out.add((String) tag);
            }
        }
        return out;
    }

    @Test
    public void appsScreenHasShortcutsRecentSearchAndHiding() {
        remote.runOnMainSync(() -> activity.dock.queryApps());
        assertFalse("The emulator has launchable apps", activity.dock.apps.isEmpty());
        AppDock.App app = activity.dock.apps.get(0);
        String id = app.component.flattenToString();
        prefs.edit().putString("recentApps", new JSONArray().put(id).toString()).commit();

        remote.runOnMainSync(() -> activity.dock.allApps());
        remote.waitForIdleSync();
        long end = android.os.SystemClock.uptimeMillis() + 5000;
        AtomicBoolean focused = new AtomicBoolean();
        while (!focused.get() && android.os.SystemClock.uptimeMillis() < end) {
            remote.runOnMainSync(() -> focused.set(activity.getCurrentFocus() != null));
            if (!focused.get()) android.os.SystemClock.sleep(100);
        }
        UiEvidence.capture("all-apps");
        remote.runOnMainSync(() -> {
            List<String> shown = texts(activity.appsScreen.extras, new ArrayList<>());
            assertTrue(
                "Android shortcuts: " + shown,
                shown.contains("Android") && shown.contains("Settings")
            );
            assertTrue("Recently used row", shown.contains("Recently used"));
            View focus = activity.getCurrentFocus();
            assertNotNull(focus);
            assertFalse("Opening Apps does not focus the search box", focus instanceof EditText);
            assertTrue(tiles(activity.appsScreen.grid).contains(id));

            activity.appsScreen.search.setText(app.label);
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            assertEquals(View.GONE, activity.appsScreen.extras.getVisibility());
            List<String> found = tiles(activity.appsScreen.grid);
            assertTrue("Search finds the app", found.contains(id));
            for (String other : found) {
                AppDock.App match = activity.dock.find(other);
                assertTrue(match.label.toLowerCase().contains(app.label.toLowerCase()));
            }
            activity.dock.setHidden(app, true);
            activity.appsScreen.refresh();
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            assertFalse("Hidden apps leave the list", tiles(activity.appsScreen.grid).contains(id));
            activity.appsScreen.showHidden = true;
            activity.appsScreen.refresh();
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            assertTrue("Show hidden brings them back", tiles(activity.appsScreen.grid).contains(id));
            activity.appsScreen.showHidden = false;
        });
    }

    @Test
    public void everyAppIsListedOnceAndAllAreLoaded() {
        remote.runOnMainSync(() -> activity.dock.queryApps());
        Set<String> seen = new HashSet<>();
        for (AppDock.App app : activity.dock.apps)
            assertTrue(
                "Listed once: " + app.label,
                seen.add(app.component.getPackageName() + "\n" + app.label)
            );
        Map<String, String> merged = Collections.singletonMap("a/.Phone", "a/.Tv");
        assertEquals(
            "Pins on a merged entry move to the one shown, once",
            Arrays.asList("a/.Tv", "b/.Other"),
            AppDock.remap(Arrays.asList("a/.Tv", "a/.Phone", "b/.Other"), merged)
        );
        remote.runOnMainSync(() -> activity.dock.allApps());
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            List<String> shown = tiles(activity.appsScreen.grid);
            assertEquals(
                "Every app has one tile once loading finishes",
                activity.dock.apps.size(),
                shown.size()
            );
            assertEquals("No tile twice", shown.size(), new HashSet<>(shown).size());
            // A search while the rest are loading drops the older rows instead of adding them.
            activity.appsScreen.fill("");
            activity.appsScreen.fill("");
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            List<String> shown = tiles(activity.appsScreen.grid);
            assertEquals(activity.dock.apps.size(), shown.size());
            assertEquals(shown.size(), new HashSet<>(shown).size());
        });
    }
}
