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

/**
 * Shared building blocks for the TV interface: colours, sizes, focus styling and the
 * standard text, button, row and form widgets used by every screen.
 */
abstract class TvActivity extends Activity {

    SharedPreferences prefs;

    int accent() {
        return prefs.getInt("accent", 0xff8a9eff);
    }

    int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    GradientDrawable box(int colour) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(7));
        return d;
    }

    LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(0xfff8f8fc);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    void styleFocus(View view, boolean focused) {
        GradientDrawable background = box(focused ? 0xfff2f4f8 : view.isSelected() ? 0xff29313e : 0xff171d27);
        if (focused || view.isSelected()) background.setStroke(dp(focused ? 3 : 1), accent());
        view.setBackground(
            "navigation".equals(view.getTag()) && !focused && !view.isSelected()
                ? new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                : background
        );
        focusText(view, focused ? 0xff10141c : 0xfff2f4f8);
        // Small items (posters, buttons, app tiles) grow slightly. Wide items such as list rows would
        // grow into their neighbours and past the screen edge, so they only change colour.
        boolean grow = focused && view.getWidth() < getResources().getDisplayMetrics().widthPixels * 0.4f;
        view.setScaleX(grow ? 1.025f : 1f);
        view.setScaleY(grow ? 1.025f : 1f);
        // Draw the focused item above its neighbours so its outline is never hidden behind them.
        view.setTranslationZ(focused ? dp(4) : 0);
    }

    void focusText(View view, int colour) {
        if (view instanceof TextView) ((TextView) view).setTextColor(colour);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) focusText(group.getChildAt(i), colour);
        }
    }

    void focus(View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnFocusChangeListener((v, focused) -> styleFocus(v, focused));
    }

    Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xfff8f8fc);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(box(0xff171d27));
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        focus(b);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    Button navigationButton(String label, Runnable action) {
        Button b = button(label, action);
        b.setTag("navigation");
        styleFocus(b, false);
        return b;
    }

    TextView muted(String value, int size) {
        TextView label = compactText(value, size);
        label.setTextColor(0xffa6b2c8);
        return label;
    }

    void section(LinearLayout parent, String title) {
        TextView label = text(title, 20);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(0, dp(20), 0, dp(10));
        parent.addView(label);
    }

    void emptyState(LinearLayout target, String title, String description, String action, Runnable run) {
        LinearLayout card = vertical();
        card.setPadding(dp(28), dp(24), dp(28), dp(28));
        card.setBackground(box(0xff131c2a));
        card.addView(text(title, 26));
        card.addView(muted(description, 16));
        if (action != null) {
            Button button = button(action, run);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
            lp.topMargin = dp(16);
            card.addView(button, lp);
        }
        target.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    Button chip(LinearLayout parent, String label, Runnable action) {
        Button choice = navigationButton(label, action);
        choice.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.rightMargin = dp(8);
        parent.addView(choice, lp);
        return choice;
    }

    void primary(Button button) {
        button.setSelected(true);
        styleFocus(button, button.hasFocus());
    }

    void addRow(LinearLayout parent, View view, int width, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(width), dp(height));
        p.setMargins(dp(5), dp(5), dp(8), dp(5));
        parent.addView(view, p);
    }

    LinearLayout horizontal(LinearLayout parent) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout r = row();
        r.setPadding(dp(4), dp(8), dp(4), dp(8));
        scroll.addView(r);
        parent.addView(scroll);
        return r;
    }

    TextView compactText(String value, int size) {
        TextView label = text(value, size);
        label.setPadding(0, dp(3), 0, dp(3));
        return label;
    }

    /** The large rounded search box used by Search and Apps. Adds it to {@code parent}. */
    EditText searchBox(LinearLayout parent, String hint) {
        LinearLayout holder = vertical();
        EditText box = field(holder, hint, false, "");
        holder.removeViewAt(0); // the field's own label; the box shows a hint instead
        holder.removeView(box);
        box.setHint(hint);
        box.setTextSize(20);
        box.setPadding(dp(24), dp(12), dp(24), dp(12));
        styleSearchBox(box, false);
        View.OnFocusChangeListener keyboardHandling = box.getOnFocusChangeListener();
        box.setOnFocusChangeListener((v, focused) -> {
            if (keyboardHandling != null) keyboardHandling.onFocusChange(v, focused);
            styleSearchBox(box, focused);
        });
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void styleSearchBox(EditText box, boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(30));
        shape.setColor(focused ? 0x33ffffff : 0x1fffffff);
        shape.setStroke(dp(focused ? 3 : 1), focused ? accent() : 0x40ffffff);
        box.setBackground(shape);
    }

    EditText field(LinearLayout parent, String hint, boolean password, String value) {
        parent.addView(compactText(hint, 14));
        EditText edit = new EditText(this);
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        edit.setId(View.generateViewId());
        edit.setHint("Select to type");
        edit.setTextColor(0xfff8f8fc);
        edit.setHintTextColor(0xff99a6bc);
        edit.setTextSize(18);
        edit.setPadding(dp(14), dp(10), dp(14), dp(10));
        edit.setSingleLine(true);
        edit.setInputType(
            password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT
        );
        edit.setTypeface(Typeface.DEFAULT);
        edit.setText(value);
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setShowSoftInputOnFocus(false);
        GradientDrawable idle = box(0xff080e1a);
        idle.setStroke(dp(1), 0xff526079);
        edit.setBackground(idle);
        edit.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable bg = box(0xff080e1a);
            bg.setStroke(dp(focused ? 3 : 1), focused ? accent() : 0xff526079);
            v.setBackground(bg);
            if (!focused) (
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)
            ).hideSoftInputFromWindow(v.getWindowToken(), 0);
        });
        Runnable keyboard = () -> {
            edit.setShowSoftInputOnFocus(true);
            edit.requestFocus();
            (
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)
            ).showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        };
        edit.setOnClickListener(v -> keyboard.run());
        edit.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP) keyboard.run();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    View next = v.focusSearch(
                        key == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN
                    );
                    if (next != null) next.requestFocus();
                }
                return true;
            }
            return false;
        });
        parent.addView(edit, new LinearLayout.LayoutParams(-1, dp(44)));
        return edit;
    }

    Dialog form(String title, LinearLayout content) {
        Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout shell = vertical();
        shell.setPadding(dp(28), dp(22), dp(28), dp(24));
        TextView heading = text(title, 24);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        shell.addView(heading);
        shell.addView(content);
        Button close = button("Close", d::dismiss);
        LinearLayout.LayoutParams closeLayout = new LinearLayout.LayoutParams(-2, dp(44));
        closeLayout.topMargin = dp(14);
        shell.addView(close, closeLayout);
        scroll.addView(shell);
        d.setContentView(scroll);
        d.show();
        // The same floating panel as SidePanel, so every pop-up in the app looks alike.
        SidePanel.styleWindow(this, d);
        d.getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        );
        return d;
    }

    void settingsStyle(View view) {
        if (view instanceof Button || view instanceof Switch) {
            view.setFocusable(true);
            view.setOnFocusChangeListener((v, on) -> {
                GradientDrawable bg = box(on ? 0xff263550 : 0xff101827);
                bg.setStroke(dp(on ? 2 : 0), accent());
                v.setBackground(bg);
            });
            view.setBackground(box(0xff101827));
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) settingsStyle(group.getChildAt(i));
        }
    }

    /** Tags on text inside a {@link #listRow()}: secondary text and small outlined badges. */
    static final String MUTED = "muted",
        BADGE = "badge";

    /**
     * A streaming-style list row (sources, episodes, files, channels): rounded and see-through,
     * white with dark text when focused. Add children with {@link #rowText} and {@link #badge}.
     */
    LinearLayout listRow() {
        LinearLayout row = row();
        row.setPadding(dp(14), dp(12), dp(20), dp(12));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClipToOutline(true);
        row.setOnFocusChangeListener((v, focused) -> styleListRow(v, focused));
        styleListRow(row, false);
        return row;
    }

    void styleListRow(View row, boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        shape.setColor(focused ? 0xfff2f4f8 : 0x1affffff);
        row.setBackground(shape);
        row.setTranslationZ(focused ? dp(4) : 0);
        listRowColours(row, focused);
    }

    private void listRowColours(View view, boolean focused) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Object tag = text.getTag();
            if (BADGE.equals(tag)) {
                GradientDrawable outline = new GradientDrawable();
                outline.setCornerRadius(dp(5));
                outline.setStroke(dp(1), focused ? 0x9910141c : 0x99ffffff);
                text.setBackground(outline);
                text.setTextColor(focused ? 0xff10141c : 0xfff2f4f8);
            } else text.setTextColor(
                MUTED.equals(tag) ? (focused ? 0xff4a5568 : 0xffa6b2c8) : focused ? 0xff10141c : 0xfff8f8fc
            );
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) listRowColours(group.getChildAt(i), focused);
        }
    }

    /** One line of text for a list row: main (bold when {@code strong}) or muted, ellipsized. */
    TextView rowText(String value, int size, boolean muted, int lines) {
        TextView text = compactText(value, size);
        if (muted) text.setTag(MUTED);
        text.setMaxLines(lines);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return text;
    }

    /** A small outlined label such as "4K", "HDR" or "12.4 GB". */
    TextView badge(String value) {
        TextView badge = compactText(value, 12);
        badge.setTag(BADGE);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setPadding(dp(7), dp(1), dp(7), dp(1));
        badge.setSingleLine(true);
        listRowColours(badge, false);
        return badge;
    }

    /** A thin progress bar in the accent colour, {@code fraction} from 0 to 1. */
    ProgressBar progress(float fraction) {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress((int) Math.max(0, Math.min(1000, fraction * 1000)));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55ffffff));
        return bar;
    }

    /** Page title block: a small spaced kicker, a large bold title and an optional subtitle. */
    void pageTitle(LinearLayout parent, String kicker, String title, String subtitle) {
        if (kicker != null && !kicker.isEmpty()) {
            TextView small = compactText(kicker.toUpperCase(java.util.Locale.ROOT), 12);
            small.setLetterSpacing(.12f);
            small.setTextColor(0xffc4cede);
            parent.addView(small);
        }
        TextView heading = compactText(title, 32);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setShadowLayer(dp(6), 0, dp(1), 0xcc000000);
        parent.addView(heading);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView under = compactText(subtitle, 15);
            under.setTextColor(0xffdfe5ee);
            under.setMaxLines(2);
            under.setEllipsize(android.text.TextUtils.TruncateAt.END);
            parent.addView(under);
        }
    }

    /**
     * Puts the current screen on top of full-screen artwork, shaded so text on the left and
     * content lower down stay readable, as streaming apps do. Returns the artwork view.
     */
    BackdropView fullBleedBackdrop() {
        return fullBleedBackdrop(false);
    }

    /** As {@link #fullBleedBackdrop()}; {@code dim} darkens the artwork further for list pages. */
    BackdropView fullBleedBackdrop(boolean dim) {
        ViewGroup content = findViewById(android.R.id.content);
        View screen = content.getChildAt(0);
        content.removeView(screen);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff090d14);
        BackdropView backdrop = new BackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        root.addView(
            shade(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {
                0xff090d14,
                0xf0090d14,
                0xb3090d14,
                0x40090d14,
                0x00090d14,
                0x00090d14,
            })
        );
        root.addView(
            shade(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
                0xb3090d14,
                0x00090d14,
                0x00090d14,
                0x99090d14,
                0xf2090d14,
                0xff090d14,
                0xff090d14,
            })
        );
        if (dim) {
            View veil = new View(this);
            veil.setBackgroundColor(0x99090d14);
            root.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        }
        screen.setBackgroundColor(Color.TRANSPARENT);
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        return backdrop;
    }

    private View shade(GradientDrawable.Orientation direction, int[] colours) {
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(direction, colours));
        shade.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return shade;
    }
}
