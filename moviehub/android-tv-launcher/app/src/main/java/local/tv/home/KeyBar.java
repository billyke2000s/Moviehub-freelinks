package local.tv.home;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The colour-key guide along the bottom of the Live TV guide and its menu. The keys are a guide to
 * the remote's coloured buttons; without them, the same jobs are in the guide menu (BACK).
 */
final class KeyBar {

    private final MainActivity activity;
    final LinearLayout view;
    /** The keys, left to right (for tests and focus). */
    final List<View> keys = new ArrayList<>();
    /** Up from the bar: back to the list above it. */
    private Runnable up = () -> {};
    private boolean revealed;
    private final List<Runnable> actions = new ArrayList<>();

    KeyBar(MainActivity activity) {
        this.activity = activity;
        activity.activeKeyBar = this;
        view = activity.row();
        view.setGravity(Gravity.CENTER_VERTICAL);
    }

    KeyBar onUp(Runnable up) {
        this.up = up;
        return this;
    }

    KeyBar add(int colour, String label, Runnable action) {
        LinearLayout key = activity.row();
        key.setGravity(Gravity.CENTER_VERTICAL);
        key.setPadding(activity.dp(6), activity.dp(3), activity.dp(10), activity.dp(3));
        // BACK reveals these controls for remotes without colour buttons; touch always works.
        key.setFocusable(false);
        key.setContentDescription(label);
        View swatch = new View(activity);
        swatch.setBackground(box(colour, 0x55ffffff));
        LinearLayout.LayoutParams swatchLayout = new LinearLayout.LayoutParams(
            activity.dp(18),
            activity.dp(18)
        );
        swatchLayout.rightMargin = activity.dp(6);
        key.addView(swatch, swatchLayout);
        TextView text = activity.compactText(label, 17);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        key.addView(text);
        View.OnFocusChangeListener style = (v, focused) -> {
            key.setBackground(
                focused ? box(LiveTvScreen.YELLOW, 0) : revealed ? box(0x22182260, 0x88ffffff) : null
            );
            text.setTextColor(focused ? LiveTvScreen.INK : 0xffffffff);
        };
        key.setOnFocusChangeListener(style);
        style.onFocusChange(key, false);
        key.setOnClickListener(v -> action.run());
        key.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                up.run();
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_DOWN;
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
        layout.rightMargin = activity.dp(18);
        view.addView(key, layout);
        keys.add(key);
        actions.add(action);
        return this;
    }

    void focus(int index) {
        if (index >= 0 && index < keys.size()) {
            revealed = true;
            for (View key : keys) {
                key.setFocusable(true);
                key.setFocusableInTouchMode(true);
                key.getOnFocusChangeListener().onFocusChange(key, key.hasFocus());
            }
            keys.get(index).requestFocus();
        }
    }

    /** Route hardware colours independently of which guide widget currently owns focus. */
    boolean handle(KeyEvent event) {
        int code = event.getKeyCode();
        // The event form also recognises standard and learned scan codes, as the player does.
        char colour = LiveTvScreen.colour(activity.prefs, event);
        int at = colour == 'r' ? 0 : colour == 'g' ? 1 : colour == 'y' ? 2 : colour == 'b' ? 3 : -1;
        if (at >= 0 && at < actions.size()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) actions
                .get(at)
                .run();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (!revealed) focus(0);
                else if (activity.back != null) activity.back.run();
            }
            return true;
        }
        int focused = focused();
        if (
            revealed &&
            focused >= 0 &&
            (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) focus(
                Math.max(
                    0,
                    Math.min(keys.size() - 1, focused + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1))
                )
            );
            return true;
        }
        return false;
    }

    /** The key that has the focus, or -1. */
    int focused() {
        for (int i = 0; i < keys.size(); i++) if (keys.get(i).hasFocus()) return i;
        return -1;
    }

    private GradientDrawable box(int colour, int edge) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(3));
        if (edge != 0) shape.setStroke(activity.dp(1), edge);
        return shape;
    }
}
