package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;

/** The safety notice shows when MovieHub first opens and stops showing once read. */
@RunWith(AndroidJUnit4.class)
public class SafetyNoticeTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    final Context context = remote.getTargetContext();
    final SharedPreferences prefs = context.getSharedPreferences("ui", 0);
    MainActivity activity;
    boolean updates;

    @Before
    public void start() {
        updates = prefs.getBoolean("autoUpdates", true);
        prefs.edit().putBoolean(SafetyNotice.PREF, false).putBoolean("autoUpdates", false).commit();
    }

    @After
    public void stop() {
        if (activity != null) {
            remote.runOnMainSync(() -> activity.finish());
            await("activity destroyed", activity::isDestroyed);
        }
        prefs.edit().putBoolean(SafetyNotice.PREF, true).putBoolean("autoUpdates", updates).commit();
    }

    void await(String what, java.util.function.BooleanSupplier check) {
        long end = SystemClock.uptimeMillis() + 8000;
        AtomicBoolean ok = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < end) {
            remote.runOnMainSync(() -> ok.set(check.getAsBoolean()));
            if (ok.get()) return;
            SystemClock.sleep(80);
        }
        fail("Timed out: " + what);
    }

    @Test
    public void showsOnFirstStartUntilRead() {
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        await("notice shown", () -> SidePanel.current != null && SidePanel.current.isShowing());
        UiEvidence.capture("safety-notice");
        await("notice focused", () -> SidePanel.current.getWindow().getDecorView().hasWindowFocus());
        // "I understand" has the focus.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("read", () -> SafetyNotice.seen(context));
    }

    /**
     * The first start asks about free providers before the safety notice. Back leaves the question
     * unanswered (asked again next time) and turns nothing on; the notice follows.
     */
    @Test
    public void asksAboutFreeProvidersBeforeTheNotice() {
        boolean fls = prefs.getBoolean(EngineSources.PREF, false);
        prefs.edit().putBoolean(ProviderSettings.ASKED, false).commit();
        try {
            activity = (MainActivity) remote.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            );
            await(
                "provider question shown",
                () -> SidePanel.current != null && SidePanel.current.isShowing()
            );
            UiEvidence.capture("free-provider-question");
            android.app.Dialog question = SidePanel.current;
            await("question focused", () -> question.getWindow().getDecorView().hasWindowFocus());
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            await(
                "notice after the question",
                () ->
                    SidePanel.current != question &&
                    SidePanel.current != null &&
                    SidePanel.current.isShowing()
            );
            assertFalse("Back does not answer the question", prefs.getBoolean(ProviderSettings.ASKED, false));
            assertEquals("Back turns nothing on", fls, prefs.getBoolean(EngineSources.PREF, false));
            await("notice focused", () -> SidePanel.current.getWindow().getDecorView().hasWindowFocus());
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            await("read", () -> SafetyNotice.seen(context));
        } finally {
            prefs.edit().putBoolean(ProviderSettings.ASKED, true).commit();
        }
    }
}
