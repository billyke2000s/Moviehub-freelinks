package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;

/** The first-start TV or phone question, and Live TV in the phone layout. */
@RunWith(AndroidJUnit4.class)
public class PhoneModeTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    final Context context = remote.getTargetContext();
    final SharedPreferences prefs = context.getSharedPreferences("ui", 0);
    MainActivity activity;
    String free;
    boolean updates;

    @Before
    public void start() throws Exception {
        free = prefs.getString(FreeChannels.PREF, null);
        updates = prefs.getBoolean("autoUpdates", true);
        prefs
            .edit()
            .remove(DeviceMode.PREF)
            .putString(FreeChannels.PREF, "off")
            .putBoolean("autoUpdates", false)
            .commit();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        ChannelDatabase.Writer writer = db.writer("phone-test", () -> {}, n -> {});
        try {
            for (String name : new String[] { "Alpha", "Bravo", "Charlie" })
                writer.accept(
                    new Playlist.Channel(name, "https://example.test/" + name + ".m3u8", "General", "", "")
                );
            writer.commit();
        } finally {
            writer.abort();
        }
    }

    @After
    public void stop() {
        if (activity != null) remote.runOnMainSync(() -> activity.finish());
        SharedPreferences.Editor edit = prefs
            .edit()
            .putString(DeviceMode.PREF, DeviceMode.TV)
            .putBoolean("autoUpdates", updates);
        if (free == null) edit.remove(FreeChannels.PREF);
        else edit.putString(FreeChannels.PREF, free);
        edit.commit();
        LiveZapper.session = null;
        ChannelDatabase.get(context).clear();
    }

    void await(String what, java.util.function.BooleanSupplier check) {
        long end = SystemClock.uptimeMillis() + 10000;
        AtomicBoolean ok = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < end) {
            remote.runOnMainSync(() -> ok.set(check.getAsBoolean()));
            if (ok.get()) return;
            SystemClock.sleep(80);
        }
        fail("Timed out: " + what);
    }

    static Button find(View view, String start) {
        if (
            view instanceof Button && ((TextView) view).getText().toString().startsWith(start)
        ) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = find(((ViewGroup) view).getChildAt(i), start);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    public void asksOnFirstStartThenShowsLiveTvForTouch() {
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        AtomicReference<Button> phone = new AtomicReference<>();
        await("the question", () -> {
            phone.set(find(activity.page, "📱"));
            return phone.get() != null && find(activity.page, "📺") != null;
        });
        UiEvidence.capture("layout-question");
        remote.runOnMainSync(() -> phone.get().performClick());
        await("phone Live TV", () -> DeviceMode.phone(context) && activity.phoneLive.items.size() == 3);
        UiEvidence.capture("phone-live-tv");

        // Tap a channel: Live TV goes full screen with the touch buttons on its banner.
        remote.runOnMainSync(() -> activity.phoneLive.watchForTest(1));
        await(
            "touch controls",
            () ->
                activity.stage != null &&
                activity.stage.full() &&
                activity.stage.zapper() != null &&
                activity.stage.zapper().touch &&
                activity.stage.zapper().position() == 1
        );
        UiEvidence.capture("phone-live-full-screen");
        remote.runOnMainSync(() -> activity.stage.leave());
    }
}
