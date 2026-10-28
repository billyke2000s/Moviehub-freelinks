package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DpadRepeatsTest {

    @Test
    public void heldDirectionIsPacedAndSeparatePressesRemainImmediate() {
        Instrumentation remote = InstrumentationRegistry.getInstrumentation();
        Context context = remote.getTargetContext();
        boolean updates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        MainActivity activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        try {
            remote.runOnMainSync(() -> {
                HorizontalScrollView scroll = new HorizontalScrollView(activity);
                LinearLayout row = new LinearLayout(activity);
                scroll.addView(row);
                for (int i = 0; i < 30; i++) {
                    Button button = new Button(activity);
                    button.setText("Item " + i);
                    button.setTag(i);
                    button.setFocusableInTouchMode(true);
                    row.addView(button, new LinearLayout.LayoutParams(120, 120));
                }
                activity.setContentView(scroll);
                row.getChildAt(0).requestFocus();
            });
            remote.waitForIdleSync();
            long down = SystemClock.uptimeMillis();
            remote.sendKeySync(
                new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            );
            for (int i = 1; i <= 30; i++) {
                SystemClock.sleep(20);
                remote.sendKeySync(
                    new KeyEvent(
                        down,
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        i
                    )
                );
            }
            remote.sendKeySync(
                new KeyEvent(
                    down,
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    0
                )
            );
            remote.waitForIdleSync();
            AtomicInteger position = new AtomicInteger();
            remote.runOnMainSync(() -> position.set((Integer) activity.getCurrentFocus().getTag()));
            assertTrue(
                "A held arrow must move, but not race through 30 posters",
                position.get() >= 1 &&
                    position.get() < 31 &&
                    position.get() <= (SystemClock.uptimeMillis() - down) / 260 + 2
            );
            for (int i = 0; i < 4; i++) remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            remote.waitForIdleSync();
            remote.runOnMainSync(() ->
                assertEquals(
                    "Distinct presses stay responsive",
                    position.get() + 4,
                    activity.getCurrentFocus().getTag()
                )
            );
        } finally {
            remote.runOnMainSync(activity::finish);
            context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", updates).commit();
        }
    }
}
