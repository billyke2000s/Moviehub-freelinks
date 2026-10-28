package local.tv.home;

import android.view.KeyEvent;

/** Individual presses stay immediate; a held direction advances at most about four times a second. */
final class DpadRepeats {

    private int key;
    private long accepted;

    boolean consume(KeyEvent event) {
        int code = event.getKeyCode();
        if (
            code != KeyEvent.KEYCODE_DPAD_LEFT &&
            code != KeyEvent.KEYCODE_DPAD_RIGHT &&
            code != KeyEvent.KEYCODE_DPAD_UP &&
            code != KeyEvent.KEYCODE_DPAD_DOWN
        ) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (key == code) key = 0;
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event.getRepeatCount() > 0 && key == code && event.getEventTime() - accepted < 260) return true;
        key = code;
        accepted = event.getEventTime();
        return false;
    }
}
