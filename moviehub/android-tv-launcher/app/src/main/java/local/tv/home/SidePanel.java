package local.tv.home;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A panel that slides in on the right, in the app's own style, used instead of Android's grey
 * pop-up dialogs and menus. Works from any activity (also the player).
 *
 * <pre>
 * SidePanel.of(activity, "Sort My list")
 *     .choices(names, selected, which -&gt; sortBy(which))
 *     .show();
 * </pre>
 */
final class SidePanel {

    static final int BACKGROUND = 0xff101722;

    private final Activity activity;
    private final String title;
    private String message;
    private View content;
    private final List<Object[]> options = new ArrayList<>();
    private final List<Object[]> actions = new ArrayList<>();
    private String[] choiceNames;
    private int choiceSelected = -1;
    private IntConsumer choicePick;
    private Runnable dismissed;
    /** The last panel shown; tests read it. */
    static Dialog current;

    private SidePanel(Activity activity, String title) {
        this.activity = activity;
        this.title = title;
    }

    static SidePanel of(Activity activity, String title) {
        return new SidePanel(activity, title);
    }

    SidePanel message(String text) {
        message = text;
        return this;
    }

    /** Any custom content (form fields and so on), shown under the title. */
    SidePanel content(View view) {
        content = view;
        return this;
    }

    /** A menu entry. The panel closes, then {@code run} runs. */
    SidePanel option(String label, Runnable run) {
        options.add(new Object[] { label, run });
        return this;
    }

    /** One choice of several; the current one is ticked and focused. */
    SidePanel choices(String[] names, int selected, IntConsumer pick) {
        choiceNames = names;
        choiceSelected = selected;
        choicePick = pick;
        return this;
    }

    /** A button at the bottom; the first one added is focused when there are no options. */
    SidePanel action(String label, Runnable run) {
        actions.add(new Object[] { label, run });
        return this;
    }

    SidePanel onDismiss(Runnable run) {
        dismissed = run;
        return this;
    }

    Dialog show() {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        if (activity.isFinishing() || activity.isDestroyed()) return dialog;
        dialog.setOwnerActivity(activity);
        // The chosen action runs after the panel has closed, so it can open another panel.
        Runnable[] after = new Runnable[1];
        // A Dialog owns input while open. Share press identity with the player so a held key
        // or trailing release cannot reopen a menu after this window is replaced.
        LiveScreen player =
            activity instanceof LiveScreen.Owner ? ((LiveScreen.Owner) activity).liveScreen() : null;
        if (player != null && player.zapper() != null) {
            LiveZapper zapper = player.zapper();
            dialog.setOnKeyListener((d, key, event) ->
                zapper.colourKey(event, colour -> {
                    after[0] = () -> zapper.colour(colour);
                    dialog.dismiss();
                })
            );
        }
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(28), dp(26), dp(28), dp(24));

        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextSize(24);
        heading.setTextColor(0xfff8f8fc);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(heading);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(14), 0, dp(6));
        if (message != null) {
            // Inside the scrolling area, so a long message (such as update notes) never pushes the
            // buttons off the panel.
            TextView text = new TextView(activity);
            text.setText(message);
            text.setTextSize(16);
            text.setTextColor(0xffb4bfd2);
            text.setLineSpacing(0, 1.15f);
            text.setPadding(0, 0, 0, dp(8));
            list.addView(text);
        }
        View focus = null;
        if (content != null) list.addView(content);
        for (Object[] option : options) {
            Runnable run = (Runnable) option[1];
            View row = row((String) option[0], false, () -> {
                after[0] = run;
                dialog.dismiss();
            });
            list.addView(row, rowLayout());
            if (focus == null) focus = row;
        }
        if (choiceNames != null) for (int i = 0; i < choiceNames.length; i++) {
            int which = i;
            View row = row(choiceNames[i], i == choiceSelected, () -> {
                after[0] = () -> choicePick.accept(which);
                dialog.dismiss();
            });
            list.addView(row, rowLayout());
            if (i == choiceSelected || (focus == null && i == 0)) focus = row;
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(list);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (!actions.isEmpty()) {
            LinearLayout buttons = new LinearLayout(activity);
            buttons.setPadding(0, dp(12), 0, 0);
            for (int i = 0; i < actions.size(); i++) {
                Object[] action = actions.get(i);
                Runnable run = (Runnable) action[1];
                Button button = button((String) action[0], () -> {
                    after[0] = run;
                    dialog.dismiss();
                });
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(44));
                layout.rightMargin = dp(12);
                buttons.addView(button, layout);
                if (focus == null && i == 0) focus = button;
            }
            body.addView(buttons);
        }

        dialog.setContentView(body);
        dialog.setOnDismissListener(d -> {
            if (current == dialog) current = null;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (dismissed != null) dismissed.run();
            if (after[0] != null) after[0].run();
        });
        // Android removes an Activity's windows without necessarily calling Dialog.dismiss().
        // Do not retain a showing=true dialog whose DecorView is no longer registered.
        dialog.getWindow().getDecorView().addOnAttachStateChangeListener(
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    // Request input only after this DecorView and its children are attached.
                    // Otherwise the first focus notification can precede the view's attach.
                    view.post(() -> {
                        if (dialog.isShowing() && !activity.isFinishing()) {
                            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                        }
                    });
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    if (current == dialog) current = null;
                }
            }
        );
        // Configure the window before attaching it. Changing layout/system-UI attributes
        // during the initial focus handoff can leave the newly shown panel without input.
        styleWindow(activity, dialog);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        current = dialog;
        dialog.show();
        if (focus != null) focus.requestFocus();
        return dialog;
    }

    /** Activity destruction can precede the DecorView's first attach/detach callbacks. */
    static void closeFor(Activity activity) {
        Dialog dialog = current;
        if (dialog == null || dialog.getOwnerActivity() != activity) return;
        current = null;
        // Closing a screen must not launch another panel through its dismissal callback.
        dialog.setOnDismissListener(null);
        Window window = dialog.getWindow();
        if (window != null && window.getDecorView().isAttachedToWindow()) dialog.dismiss();
    }

    /** The shared panel look: floating on the right, full height, rounded, dimming the screen. */
    static void styleWindow(Activity activity, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(BACKGROUND);
        shape.setCornerRadius(18 * density);
        window.setBackgroundDrawable(shape);
        window.setWindowAnimations(0);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.55f);
        int width = activity.getResources().getDisplayMetrics().widthPixels,
            height = activity.getResources().getDisplayMetrics().heightPixels;
        window.setLayout(
            Math.min((int) (460 * density), width - (int) (48 * density)),
            height - (int) (48 * density)
        );
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        attributes.x = (int) (24 * density);
        window.setAttributes(attributes);
        window
            .getDecorView()
            .setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
    }

    private LinearLayout.LayoutParams rowLayout() {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(50));
        layout.bottomMargin = dp(6);
        return layout;
    }

    /** A full-width row: white with dark text when focused, a tick when it is the current choice. */
    private View row(String label, boolean ticked, Runnable run) {
        TextView row = new TextView(activity);
        row.setText(ticked ? "✓   " + label : label);
        row.setTextSize(17);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(20), 0);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        int idle = ticked ? accent() : 0xfff2f4f8;
        row.setTextColor(idle);
        row.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(7));
            shape.setColor(focused ? 0xfff2f4f8 : ticked ? 0x1fffffff : Color.TRANSPARENT);
            v.setBackground(shape);
            row.setTextColor(focused ? 0xff10141c : idle);
        });
        row.getOnFocusChangeListener().onFocusChange(row, false);
        row.setOnClickListener(v -> run.run());
        return row;
    }

    /** The app's standard button: a dark rounded box, light with an accent outline when selected. */
    private Button button(String label, Runnable run) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(7));
            shape.setColor(focused ? 0xfff2f4f8 : 0xff171d27);
            if (focused) shape.setStroke(dp(3), accent());
            v.setBackground(shape);
            button.setTextColor(focused ? 0xff10141c : 0xfff2f4f8);
        });
        button.getOnFocusChangeListener().onFocusChange(button, false);
        button.setOnClickListener(v -> run.run());
        return button;
    }

    private int accent() {
        return activity.getSharedPreferences("ui", 0).getInt("accent", 0xff8a9eff);
    }

    private int dp(float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + .5f);
    }
}
