package local.tv.home;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * What the full-screen Live TV picture shows, in the style of the classic UK satellite box:
 * the channel banner (a light-blue title strip with the yellow channel number, name and clock,
 * over a deep-blue panel with Now and Next, how far through the programme is, and the colour
 * keys), the "i" programme information panel, and the pause bar. Static drawables only, so it
 * costs nothing while hidden on slower boxes.
 */
final class SkyOverlay {

    /** Classic satellite-guide colours. */
    static final int STRIP_TOP = 0xff4f86e0,
        STRIP_BOTTOM = 0xff2350b4,
        PANEL_TOP = 0xf0102a7c,
        PANEL_BOTTOM = 0xf00a1a55,
        EDGE = 0x99bcd2ff,
        TIME = 0xff9fd4ff,
        SOFT = 0xffc9d8ff,
        RED = 0xffe53935,
        GREEN = 0xff43a047,
        BLUE = 0xff1e63d6;

    private final LiveScreen a;
    private final LinearLayout banner, info, pauseBar, keys;
    final LinearLayout channelStrip;
    private final TextView number, name, clock, nowTime, nowTitle, nextTime, nextTitle, about, source, hint;
    private final TextView infoTitle, infoWhen, infoAbout, infoChannel, infoPicture, pauseIcon, pauseText;
    private final ProgressBar progress;
    /**
     * The banner's buttons, left to right: the four colour keys. Each works with a tap, and with
     * ◀ ▶ and SELECT, so a remote without coloured buttons can still reach them. The source name
     * in the title strip is text, not a button (package-private for tests).
     */
    final List<View> controls = new ArrayList<>();
    private boolean controlsRevealed;

    SkyOverlay(LiveScreen activity, FrameLayout root) {
        a = activity;
        BannerStack stack = new BannerStack(a.activity());
        FrameLayout.LayoutParams stackLayout = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        stackLayout.setMargins(a.dp(56), 0, a.dp(56), a.dp(34));
        root.addView(stack, stackLayout);

        // The "i" panel: programme information above the banner.
        info = panel();
        LinearLayout infoStrip = strip();
        TextView infoHeading = label(17, true, Color.WHITE);
        infoHeading.setText("Programme information");
        infoStrip.addView(infoHeading, new LinearLayout.LayoutParams(0, -2, 1));
        infoChannel = label(17, true, LiveTvScreen.YELLOW);
        infoStrip.addView(infoChannel);
        info.addView(infoStrip);
        LinearLayout infoBody = body();
        infoTitle = label(24, true, Color.WHITE);
        infoBody.addView(infoTitle);
        infoWhen = label(16, false, TIME);
        infoBody.addView(infoWhen);
        infoAbout = label(17, false, Color.WHITE);
        infoAbout.setSingleLine(false);
        infoAbout.setMaxLines(8);
        infoAbout.setLineSpacing(0, 1.15f);
        infoAbout.setPadding(0, a.dp(8), 0, 0);
        infoBody.addView(infoAbout);
        // What is actually playing: picture size, frame rate, codec, bitrate, and the source.
        infoPicture = label(15, false, SOFT);
        infoPicture.setSingleLine(false);
        infoPicture.setMaxLines(2);
        infoPicture.setPadding(0, a.dp(10), 0, 0);
        infoBody.addView(infoPicture);
        info.addView(infoBody);
        stack.addView(info, spaced());

        // The pause bar, as the satellite recorders showed when live TV was paused.
        pauseBar = panel();
        LinearLayout pauseRow = body();
        pauseRow.setOrientation(LinearLayout.HORIZONTAL);
        pauseRow.setGravity(Gravity.CENTER_VERTICAL);
        pauseIcon = label(22, true, LiveTvScreen.YELLOW);
        pauseIcon.setMinWidth(a.dp(44));
        pauseRow.addView(pauseIcon);
        pauseText = label(18, true, Color.WHITE);
        pauseRow.addView(pauseText, new LinearLayout.LayoutParams(0, -2, 1));
        pauseBar.addView(pauseRow);
        stack.addView(pauseBar, spaced());

        // The banner.
        banner = panel();
        channelStrip = strip();
        LinearLayout title = channelStrip;
        number = label(26, true, LiveTvScreen.YELLOW);
        number.setMinWidth(a.dp(78));
        title.addView(number);
        name = label(24, true, Color.WHITE);
        title.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        // Which source is playing: information only. The green key is the one Source button.
        source = label(15, false, SOFT);
        source.setPadding(a.dp(12), 0, a.dp(16), 0);
        source.setMaxWidth(a.dp(300));
        source.setFocusable(false);
        source.setClickable(false);
        title.addView(source);
        clock = label(20, true, Color.WHITE);
        title.addView(clock);
        // Reserve an actual header row, not only a suggested minimum. Grow with system text size.
        int stripHeight = Math.max(a.dp(64), (int) Math.ceil(number.getTextSize() * 1.5f) + a.dp(16));
        banner.addView(title, new LinearLayout.LayoutParams(-1, stripHeight));
        LinearLayout content = body();
        LinearLayout nowRow = row();
        nowTime = label(18, true, TIME);
        nowTime.setMinWidth(a.dp(150));
        nowRow.addView(nowTime);
        nowTitle = label(20, true, Color.WHITE);
        nowRow.addView(nowTitle, new LinearLayout.LayoutParams(0, -2, 1));
        progress = new ProgressBar(a.activity(), null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(LiveTvScreen.YELLOW));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x55ffffff));
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(a.dp(180), a.dp(8));
        progressLayout.leftMargin = a.dp(16);
        nowRow.addView(progress, progressLayout);
        content.addView(nowRow);
        LinearLayout nextRow = row();
        nextTime = label(18, false, TIME);
        nextTime.setMinWidth(a.dp(150));
        nextRow.addView(nextTime);
        nextTitle = label(19, false, SOFT);
        nextRow.addView(nextTitle, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(nextRow);
        about = label(16, false, SOFT);
        about.setSingleLine(false);
        about.setMaxLines(2);
        about.setPadding(0, a.dp(4), 0, 0);
        content.addView(about);
        keys = row();
        keys.setPadding(0, a.dp(10), 0, 0);
        colourKey(RED, "Audio & subtitles", () -> a.liveMenus().audioAndSubtitles());
        colourKey(GREEN, "Source", () -> a.liveMenus().chooseSource());
        colourKey(LiveTvScreen.YELLOW, "Information", this::toggleInformation);
        colourKey(BLUE, "Options", () -> a.liveMenus().liveOptions());
        // No coloured buttons on the remote: BACK reveals these as buttons to pick with ◀ ▶.
        TextView noColours = label(15, true, SOFT);
        content.addView(keys);
        noColours.setText("No colour buttons? ◀ ▶ then OK");
        content.addView(noColours);
        hint = label(16, true, LiveTvScreen.YELLOW);
        hint.setPadding(0, a.dp(8), 0, 0);
        hint.setText("SELECT to watch   ▲ ▼ channels   ◀ ▶ later programmes   BACK to close");
        content.addView(hint);
        banner.addView(content);
        stack.addView(banner, new ViewGroup.MarginLayoutParams(-1, -2));

        info.setVisibility(View.GONE);
        pauseBar.setVisibility(View.GONE);
        banner.setVisibility(View.GONE);
    }

    // ---- Banner -------------------------------------------------------------------------------

    /**
     * Shows the banner. {@code progress} is how far through the programme on now is (0–1000), or
     * -1 when it is not on now. {@code browsing} swaps the colour keys for the browsing keys.
     */
    void banner(
        String channelNumber,
        String channelName,
        String now,
        String nowAt,
        String next,
        String nextAt,
        String description,
        int progressValue,
        String sourceText,
        boolean browsing
    ) {
        number.setText(channelNumber);
        name.setText(channelName);
        clock.setText(LiveTvScreen.clockTime(new Date()));
        nowTime.setText(nowAt);
        nowTitle.setText(now);
        nextTime.setText(nextAt);
        nextTitle.setText(next);
        nextTime.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        nextTitle.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        about.setText(description);
        about.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(progressValue < 0 ? View.GONE : View.VISIBLE);
        if (progressValue >= 0) progress.setProgress(progressValue);
        source.setText(sourceText);
        keys.setVisibility(browsing || touchRow != null ? View.GONE : View.VISIBLE);
        hint.setVisibility(browsing ? View.VISIBLE : View.GONE);
        if (touchRow != null) touchRow.setVisibility(browsing ? View.GONE : View.VISIBLE);
        slideIn(banner);
    }

    /** The banner slides up into place when it appears, as the satellite box's did. */
    private void slideIn(View panel) {
        if (panel.getVisibility() == View.VISIBLE) return;
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationY(a.dp(48));
        panel.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    /** The channel number being typed, shown where the number goes. */
    void typing(String digits) {
        banner("", "", "", "", "", "", "", -1, "", false);
        number.setText(digits + "–");
        keys.setVisibility(View.GONE);
        nowTime.setVisibility(View.GONE);
    }

    /** Highlights banner button {@code index} (0 red, 1 green, 2 yellow, 3 blue), ready for OK. */
    void focusControl(int index) {
        boxControls();
        if (index >= 0 && index < controls.size()) controls.get(index).requestFocus();
    }

    void hideBanner() {
        // A hidden button must not keep the focus, or the next OK would press it.
        if (focusedControl() >= 0) a.video().requestFocus();
        banner.animate().cancel();
        banner.setVisibility(View.GONE);
        nowTime.setVisibility(View.VISIBLE);
    }

    /**
     * TV layout: the banner's buttons are always drawn boxed, as after BACK, so the banner looks
     * the same however it was opened. ◀ ▶ move between them and OK presses the highlighted one.
     */
    void boxControls() {
        controlsRevealed = true;
        for (View control : controls) {
            // Focusable for the D-pad only: in touch mode (an air mouse) one click presses it.
            control.setFocusable(true);
            controlOutline(control, control.hasFocus());
        }
    }

    /** BACK: highlights the green Source key, the one way to change source from the banner. */
    void focusSources() {
        boxControls();
        for (View control : controls) control.setFocusableInTouchMode(true);
        controls.get(SOURCE_KEY).requestFocus();
    }

    /** The green Source key's place in {@link #controls}. */
    static final int SOURCE_KEY = 1;

    /** ◀ ▶ while the controls are revealed: the next or previous button, stopping at the ends. */
    void moveControl(int direction) {
        int at = focusedControl();
        int next = at < 0 ? 0 : Math.max(0, Math.min(controls.size() - 1, at + direction));
        controls.get(next).requestFocus();
    }

    /** SELECT while the controls are revealed: presses the focused button. False when none is. */
    boolean pressControl() {
        int at = focusedControl();
        if (at < 0) return false;
        controls.get(at).performClick();
        return true;
    }

    /** The button that has the focus, or -1. */
    int focusedControl() {
        for (int i = 0; i < controls.size(); i++) if (controls.get(i).isFocused()) return i;
        return -1;
    }

    private void toggleInformation() {
        if (a.zapper() == null) return;
        if (infoShowing()) a.zapper().hideInfo();
        else a.zapper().information();
    }

    boolean bannerShowing() {
        return banner.getVisibility() == View.VISIBLE;
    }

    // ---- Touch controls (phone layout) ----------------------------------------------------------

    /** Buttons on the banner for touch screens, in place of the colour keys; null on a TV. */
    private LinearLayout touchRow;

    /** Adds touch buttons to the banner: {@code labels[i]} runs {@code actions[i]}. */
    void touchControls(String[] labels, Runnable[] actions) {
        LinearLayout content = (LinearLayout) keys.getParent();
        touchRow = row();
        touchRow.setPadding(0, a.dp(10), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            Runnable action = actions[i];
            TextView button = label(16, true, Color.WHITE);
            button.setText(labels[i]);
            button.setGravity(Gravity.CENTER);
            button.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(10));
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(0x55ffffff);
            shape.setCornerRadius(a.dp(6));
            button.setBackground(shape);
            button.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
            layout.rightMargin = a.dp(6);
            touchRow.addView(button, layout);
        }
        content.addView(touchRow, content.indexOfChild(keys) + 1);
        keys.setVisibility(View.GONE);
    }

    // ---- Programme information -----------------------------------------------------------------

    void info(String channel, String programme, String when, String description) {
        infoChannel.setText(channel);
        infoTitle.setText(programme);
        infoWhen.setText(when);
        infoAbout.setText(description.isEmpty() ? "No description for this programme." : description);
        info.setVisibility(View.VISIBLE);
    }

    /** The information panel's picture line: {@code video} from the player, then the source. */
    void infoPicture(String video, String sourceText) {
        String picture = "Picture: " + (video.isEmpty() ? "waiting for the first frame" : video);
        infoPicture.setText(sourceText.isEmpty() ? picture : picture + "\n" + sourceText);
    }

    /** The picture line as shown (package-private for tests). */
    String infoPictureText() {
        return infoPicture.getText().toString();
    }

    void hideInfo() {
        info.setVisibility(View.GONE);
    }

    boolean infoShowing() {
        return info.getVisibility() == View.VISIBLE;
    }

    // ---- Pause bar ------------------------------------------------------------------------------

    /** Paused, or playing behind the live broadcast; hidden when live. */
    void pause(boolean paused, boolean behindLive) {
        if (!paused && !behindLive) {
            pauseBar.setVisibility(View.GONE);
            return;
        }
        pauseIcon.setText(paused ? "❚❚" : "▶");
        pauseText.setText(
            paused
                ? "PAUSED   ·   Press ▶ to carry on"
                : "BEHIND LIVE   ·   Press ▶▶ or Options → Back to live to catch up"
        );
        pauseBar.setVisibility(View.VISIBLE);
    }

    boolean pauseShowing() {
        return pauseBar.getVisibility() == View.VISIBLE;
    }

    /** Measure the bottom banner first; optional information must never consume its space. */
    private static final class BannerStack extends ViewGroup {

        BannerStack(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int remaining =
                MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                    ? Integer.MAX_VALUE / 4
                    : MeasureSpec.getSize(heightSpec);
            int used = 0;
            // Children are added in visual order: information, pause, channel banner.
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                int margins = lp.topMargin + lp.bottomMargin;
                if (remaining <= margins) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
                    );
                    continue;
                }
                child.measure(
                    MeasureSpec.makeMeasureSpec(
                        Math.max(0, width - lp.leftMargin - lp.rightMargin),
                        MeasureSpec.EXACTLY
                    ),
                    MeasureSpec.makeMeasureSpec(Math.max(0, remaining - margins), MeasureSpec.AT_MOST)
                );
                int height = child.getMeasuredHeight() + margins;
                used += height;
                remaining = Math.max(0, remaining - height);
            }
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(used, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int y = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE || child.getMeasuredHeight() == 0) continue;
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                y += lp.topMargin;
                child.layout(
                    lp.leftMargin,
                    y,
                    lp.leftMargin + child.getMeasuredWidth(),
                    y + child.getMeasuredHeight()
                );
                y += child.getMeasuredHeight() + lp.bottomMargin;
            }
        }

        @Override
        protected LayoutParams generateDefaultLayoutParams() {
            return new MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        }
    }

    // ---- Building blocks ------------------------------------------------------------------------

    /** A rounded panel with a thin light edge; its strip and body go inside. */
    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(a.activity());
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
            PANEL_TOP,
            PANEL_BOTTOM,
        });
        shape.setCornerRadius(a.dp(8));
        shape.setStroke(a.dp(2), EDGE);
        panel.setBackground(shape);
        panel.setClipToOutline(true);
        return panel;
    }

    /** The light-blue title strip across the top of a panel. */
    private LinearLayout strip() {
        LinearLayout strip = row();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBaselineAligned(false);
        strip.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(8));
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
            STRIP_TOP,
            STRIP_BOTTOM,
        });
        float r = a.dp(7);
        shape.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        strip.setBackground(shape);
        return strip;
    }

    private LinearLayout body() {
        LinearLayout body = new LinearLayout(a.activity());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(14));
        return body;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(a.activity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = a.dp(10);
        return layout;
    }

    private void colourKey(int colour, String text, Runnable action) {
        LinearLayout key = row();
        key.setPadding(a.dp(6), a.dp(4), a.dp(10), a.dp(4));
        key.setFocusable(false);
        key.setClickable(true);
        key.setContentDescription(text);
        View swatch = new View(a.activity());
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(a.dp(2));
        shape.setStroke(a.dp(1), 0x66ffffff);
        swatch.setBackground(shape);
        LinearLayout.LayoutParams swatchLayout = new LinearLayout.LayoutParams(a.dp(16), a.dp(16));
        swatchLayout.rightMargin = a.dp(6);
        key.addView(swatch, swatchLayout);
        TextView label = label(15, true, Color.WHITE);
        label.setText(text);
        key.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(LiveTvScreen.YELLOW);
        focus.setCornerRadius(a.dp(3));
        key.setOnFocusChangeListener((v, focused) -> {
            key.setBackground(focused ? focus : null);
            if (!focused) controlOutline(key, false);
            label.setTextColor(focused ? LiveTvScreen.INK : Color.WHITE);
        });
        key.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams keyLayout = new LinearLayout.LayoutParams(0, -2, 1);
        keyLayout.rightMargin = a.dp(6);
        keys.addView(key, keyLayout);
        controls.add(key);
    }

    private void controlOutline(View view, boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(focused ? 0x445d8ae0 : 0x22182260);
        shape.setCornerRadius(a.dp(3));
        shape.setStroke(a.dp(focused ? 2 : 1), focused ? LiveTvScreen.YELLOW : 0x88ffffff);
        view.setBackground(controlsRevealed ? shape : null);
    }

    private TextView label(int size, boolean bold, int colour) {
        TextView text = new TextView(a.activity());
        text.setTextColor(colour);
        text.setTextSize(size);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        SkyFont.apply(text, bold);
        return text;
    }
}
