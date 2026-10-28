package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;

/** Timings and the Diagnostics page. */
@RunWith(AndroidJUnit4.class)
public class DiagnosticsTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    boolean updates;

    @Before
    public void start() {
        Timings.clear();
        Context context = remote.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        updates = prefs.getBoolean("autoUpdates", true);
        prefs.edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void stop() {
        remote.runOnMainSync(() -> activity.finish());
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", updates)
            .commit();
        Timings.clear();
    }

    static boolean shows(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (
            shows(((ViewGroup) view).getChildAt(i), text)
        ) return true;
        return false;
    }

    private Button button(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void troubleshootingKeepsTheSettingsSidebarAndReturnsToSystem() {
        remote.runOnMainSync(() -> activity.troubleshooting.show());
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            for (String tab : new String[] { "Accounts", "Appearance", "Playback", "System" }) {
                Button choice = button(root, tab);
                assertNotNull("Settings navigation: " + tab, choice);
                assertEquals(tab.equals("System"), choice.isSelected());
            }
            button(root, "Back").performClick();
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            assertTrue(button(root, "System").isSelected());
            assertTrue(shows(root, "Automatic updates"));
            button(root, "Appearance").performClick();
            assertTrue(shows(activity.getWindow().getDecorView(), "Look and feel"));
        });
        remote.runOnMainSync(() -> activity.troubleshooting.show());
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            button(activity.getWindow().getDecorView(), "Playback").performClick();
            assertTrue(shows(activity.getWindow().getDecorView(), "Playback preferences"));
        });
    }

    @Test
    public void recordsTimingsAndShowsThem() {
        Timings.clear(); // anything the app measured while starting
        long started = Timings.start() - 1500;
        Timings.record("Fixture work", started);
        Timings.mark("fixture");
        SystemClock.sleep(20);
        Timings.finish("fixture", "Marked work");
        Timings.finish("fixture", "Marked work");
        java.util.List<Timings.Stat> stats = Timings.snapshot();
        assertEquals(2, stats.size());
        assertTrue(stats.get(0).last >= 1500);
        assertEquals("A mark finishes once", 1, stats.get(1).count);

        remote.runOnMainSync(() -> activity.troubleshooting.show());
        remote.waitForIdleSync();
        UiEvidence.capture("diagnostics");
        remote.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            assertTrue(shows(root, "Fixture work"));
            assertTrue(shows(root, "1.5 s last"));
            assertTrue(shows(root, "Last download speed while playing"));
            assertTrue(shows(root, "Internet speed"));
            assertTrue(shows(root, "Connections"));
            assertTrue(shows(root, "Recent errors"));
        });
        // A recovered error appears with its place and type, never its message.
        Diagnostics.recovered("Fixture problem", new IllegalStateException("https://example.test/secret"));
        remote.runOnMainSync(() -> activity.troubleshooting.show());
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            assertTrue(shows(root, "Fixture problem"));
            assertTrue(shows(root, "IllegalStateException"));
            assertFalse(shows(root, "secret"));
        });
        ErrorLog.clear();
    }
}
