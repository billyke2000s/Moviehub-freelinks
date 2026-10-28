package local.tv.home;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Side panels (the app's own pop-ups) and the plain-language error explanations. */
@RunWith(AndroidJUnit4.class)
public class SidePanelTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    boolean updates;

    @Before
    public void start() {
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
        remote.runOnMainSync(() -> {
            if (SidePanel.current != null) SidePanel.current.dismiss();
            activity.finish();
        });
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", updates)
            .commit();
    }

    @Test
    public void choicesFocusTheCurrentOneAndRunAfterClosing() {
        AtomicInteger picked = new AtomicInteger(-1);
        AtomicBoolean closedFirst = new AtomicBoolean();
        Dialog[] shown = new Dialog[1];
        remote.runOnMainSync(
            () ->
                shown[0] = SidePanel.of(activity, "Sort")
                    .choices(new String[] { "One", "Two", "Three" }, 1, which -> {
                        closedFirst.set(!shown[0].isShowing());
                        picked.set(which);
                    })
                    .show()
        );
        remote.waitForIdleSync();
        UiEvidence.capture("side-panel");
        remote.runOnMainSync(() -> {
            View focus = shown[0].getCurrentFocus();
            assertTrue(focus instanceof TextView);
            assertTrue(
                "The current choice is focused and ticked",
                ((TextView) focus).getText().toString().contains("Two")
            );
            assertTrue(((TextView) focus).getText().toString().startsWith("✓"));
        });
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        assertEquals(2, picked.get());
        assertTrue("The choice runs after the panel has closed", closedFirst.get());
        assertNull(SidePanel.current);
    }

    @Test
    public void closingTheActivityClearsItsPanelWithoutRunningAnotherAction() {
        AtomicBoolean continued = new AtomicBoolean();
        remote.runOnMainSync(() -> {
            SidePanel.of(activity, "Closing fixture")
                .action("Continue", () -> continued.set(true))
                .onDismiss(() -> continued.set(true))
                .show();
            activity.finish();
        });
        long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
        while (!activity.isDestroyed() && android.os.SystemClock.elapsedRealtime() < deadline) {
            remote.waitForIdleSync();
            android.os.SystemClock.sleep(20);
        }
        remote.runOnMainSync(() -> {
            assertTrue("The Activity was destroyed", activity.isDestroyed());
            assertNull("No panel retains a detached Activity window", SidePanel.current);
            assertFalse("Closing the Activity does not continue onboarding", continued.get());
        });
    }

    @Test
    public void errorsAreExplainedPlainlyWithoutProviderText() {
        assertEquals(
            "No internet connection",
            ErrorMessages.explain(new UnknownHostException("secret.example.com")).title
        );
        ErrorMessages.Explanation key = ErrorMessages.explain(new java.io.IOException("Server returned 401"));
        assertEquals(ErrorMessages.Fix.SETTINGS, key.fix);
        assertEquals(
            ErrorMessages.Fix.SETTINGS,
            ErrorMessages.explain(new Exception("Connect TMDB first")).fix
        );
        ErrorMessages.Explanation other = ErrorMessages.explain(
            new java.io.IOException("https://provider.example/user/pass")
        );
        assertFalse(other.title.contains("provider") || other.detail.contains("provider"));
        assertTrue(
            ErrorMessages.explain(new java.io.IOException("Server returned 503")).title.contains("problems")
        );
    }
}
