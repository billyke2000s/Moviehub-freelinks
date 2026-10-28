package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class HotbarTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private SharedPreferences prefs;
    private String oldOrder;
    private boolean updates;

    private void ui(Runnable run) {
        remote.runOnMainSync(run);
        remote.waitForIdleSync();
    }

    private void key(int key) {
        remote.sendKeyDownUpSync(key);
        remote.waitForIdleSync();
    }

    @Before
    public void start() {
        Context context = remote.getTargetContext();
        prefs = context.getSharedPreferences("ui", 0);
        oldOrder = prefs.getString("dockOrder", null);
        updates = prefs.getBoolean("autoUpdates", true);
        prefs.edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void stop() {
        if (activity != null) ui(() -> activity.finish());
        SharedPreferences.Editor edit = prefs.edit().putBoolean("autoUpdates", updates);
        if (oldOrder == null) edit.remove("dockOrder");
        else edit.putString("dockOrder", oldOrder);
        edit.commit();
    }

    @Test
    public void remoteReordersRemovesAndNeverMovesAllApps() throws Exception {
        LinearLayout dock = activity.dock.homeDock;
        // Use fixture tiles so emulator-installed app counts do not affect coverage.
        ui(() -> {
            dock.removeAllViews();
            for (String name : new String[] { "A", "B", "C" })
                activity.dock.addApp(
                    dock,
                    new AppDock.App(name, new ComponentName("fixture." + name, "Main"), null),
                    true
                );
            Button all = new Button(activity);
            all.setText("All Apps");
            dock.addView(all);
            activity.dock.saveVisibleDock();
        });
        View first = dock.getChildAt(0),
            all = dock.getChildAt(3);
        int[] clicks = { 0 };
        ui(() -> {
            first.setOnClickListener(v -> clicks[0]++);
            first.requestFocus();
            assertTrue(first.performLongClick());
        });
        remote.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        remote.waitForIdleSync(); // release the original hold
        assertSame(first, activity.dock.movingApp);
        SystemClock.sleep(200);
        assertTrue(first.getTranslationY() < 0);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertSame(first, dock.getChildAt(1));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertSame(first, dock.getChildAt(2));
        assertSame(all, dock.getChildAt(3));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertNull(activity.dock.movingApp);
        assertEquals(0, clicks[0]);
        assertEquals("fixture.A/Main", new JSONArray(prefs.getString("dockOrder", "[]")).getString(2));
        ui(() -> first.performLongClick());
        key(KeyEvent.KEYCODE_DPAD_UP);
        SystemClock.sleep(500);
        remote.waitForIdleSync();
        assertEquals(3, dock.getChildCount());
        assertSame(all, dock.getChildAt(2));
        assertEquals(2, new JSONArray(prefs.getString("dockOrder", "[]")).length());
        ui(() -> activity.homeScreen.home());
        assertEquals(2, new JSONArray(prefs.getString("dockOrder", "[]")).length());
    }
}
