package local.tv.home;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Builds the player's on-screen controls: the title and download speed at the top, the timeline
 * and flat icon controls at the bottom, and the "Press Back again" hint. Split out of
 * {@link VlcPlaybackActivity}, whose fields it fills in.
 */
final class PlayerOverlay {

    private PlayerOverlay() {}

    static void build(VlcPlaybackActivity a, FrameLayout root) {
        // Streaming-app layout: the title on a shade at the top; at the bottom a full-width
        // timeline with the time on the right, then play controls on the left and options on
        // the right. The whole overlay hides together.
        FrameLayout overlay = new FrameLayout(a);
        a.controls = overlay;
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(a.dp(48), a.dp(28), a.dp(48), a.dp(56));
        top.setBackground(shade(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM));
        TextView title = new TextView(a);
        a.titleView = title;
        title.setText(a.getIntent().getStringExtra("title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setShadowLayer(a.dp(6), 0, a.dp(1), 0xcc000000);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title);
        // How fast the video is arriving: tells a slow connection apart from a slow app.
        a.speed = new TextView(a);
        a.speed.setTextColor(0xccffffff);
        a.speed.setTextSize(14);
        a.speed.setText("Receiving stream…");
        a.speed.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
        top.addView(a.speed);
        overlay.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(a.dp(48), a.dp(64), a.dp(48), a.dp(26));
        bottom.setBackground(shade(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP));
        LinearLayout progress = new LinearLayout(a);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        a.timeLeft = label(a);
        a.timeline = new SeekBar(a);
        a.timeline.setMax(1000);
        int tint = a
            .getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            .getInt("accent", 0xff8a9eff);
        styleTimeline(a, tint);
        a.timeline.setEnabled(false);
        progress.addView(a.timeline, new LinearLayout.LayoutParams(0, a.dp(28), 1));
        LinearLayout.LayoutParams timeLayout = new LinearLayout.LayoutParams(-2, -2);
        timeLayout.leftMargin = a.dp(16);
        progress.addView(a.timeLeft, timeLayout);
        bottom.addView(progress);
        a.timeline.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar bar) {
                    a.dragging = true;
                    a.overlayHandler.removeCallbacks(a.hideOverlay);
                }

                public void onStopTrackingTouch(SeekBar bar) {
                    a.dragging = false;
                    // A finger let go: jump now rather than waiting.
                    a.overlayHandler.removeCallbacks(a.applySkip);
                    a.applySkip.run();
                    a.revealControls();
                }

                public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (fromUser && a.player != null && a.player.isSeekable()) {
                        // Moving along the bar jumps once, when it stops, not at every step.
                        long length = a.player.getLength();
                        a.showSkipTarget(a.skips.moveTo((length * value) / 1000, length), length);
                        a.revealControls();
                    }
                }
            }
        );
        // Flat icon controls, as streaming apps use: no button shapes; the selected one turns
        // bright white and grows a little, the others are dimmed.
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, a.dp(8), 0, 0);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        bottom.setClipChildren(false);
        add(a, buttons, R.drawable.ic_player_back10, "10", "−10 s", () -> a.seek(-10000));
        a.pause = add(a, buttons, R.drawable.ic_player_pause, null, "Pause", a::toggle);
        a.pauseIcon = (ImageView) ((ViewGroup) a.pause).getChildAt(0);
        add(a, buttons, R.drawable.ic_player_forward10, "10", "+10 s", () -> a.seek(10000));
        buttons.addView(new android.view.View(a), new LinearLayout.LayoutParams(0, 1, 1));
        add(
            a,
            buttons,
            R.drawable.ic_player_subtitles,
            "Audio & Subtitles",
            "Audio & Subtitles",
            a.menus::audioAndSubtitles
        );
        add(a, buttons, R.drawable.ic_player_video, "Video", "Video", a.menus::resolution);
        add(a, buttons, R.drawable.ic_player_more, "More", "More", a.menus::moreControls);
        bottom.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        overlay.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        a.exitHint = new TextView(a);
        a.exitHint.setText("Press Back again to leave playback");
        a.exitHint.setTextColor(Color.WHITE);
        a.exitHint.setTextSize(16);
        a.exitHint.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        a.exitHint.setPadding(a.dp(22), a.dp(10), a.dp(22), a.dp(10));
        android.graphics.drawable.GradientDrawable hintShape =
            new android.graphics.drawable.GradientDrawable();
        hintShape.setColor(0xe6141a24);
        hintShape.setCornerRadius(a.dp(6));
        a.exitHint.setBackground(hintShape);
        a.exitHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLayout = new FrameLayout.LayoutParams(
            -2,
            -2,
            Gravity.CENTER_HORIZONTAL | Gravity.TOP
        );
        hintLayout.topMargin = a.dp(96);
        root.addView(a.exitHint, hintLayout);
    }

    private static android.graphics.drawable.GradientDrawable shade(
        android.graphics.drawable.GradientDrawable.Orientation direction
    ) {
        return new android.graphics.drawable.GradientDrawable(direction, new int[] {
            0xe6000000,
            0x99000000,
            0x00000000,
        });
    }

    /** A slim rounded timeline in the accent colour with a white dot, like streaming apps. */
    private static void styleTimeline(VlcPlaybackActivity a, int accent) {
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(0x4dffffff);
        track.setCornerRadius(a.dp(3));
        android.graphics.drawable.GradientDrawable played = new android.graphics.drawable.GradientDrawable();
        played.setColor(accent);
        played.setCornerRadius(a.dp(3));
        android.graphics.drawable.LayerDrawable bar = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {
                track,
                new android.graphics.drawable.ClipDrawable(
                    played,
                    Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL
                ),
            }
        );
        bar.setId(0, android.R.id.background);
        bar.setId(1, android.R.id.progress);
        // Keep the bar thin inside the taller touch/focus area.
        bar.setLayerInset(0, 0, a.dp(11), 0, a.dp(11));
        bar.setLayerInset(1, 0, a.dp(11), 0, a.dp(11));
        a.timeline.setProgressDrawable(bar);
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(Color.WHITE);
        dot.setSize(a.dp(14), a.dp(14));
        a.timeline.setThumb(dot);
        a.timeline.setSplitTrack(false);
        a.timeline.setPadding(a.dp(7), 0, a.dp(7), 0);
    }

    private static TextView label(VlcPlaybackActivity a) {
        TextView t = new TextView(a);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setText("—");
        return t;
    }

    /**
     * One flat control: an icon with an optional short label, no background shape. The
     * description names it for accessibility and tests.
     */
    private static View add(
        VlcPlaybackActivity a,
        LinearLayout parent,
        int icon,
        String label,
        String description,
        Runnable action
    ) {
        LinearLayout item = new LinearLayout(a);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setContentDescription(description);
        ImageView image = new ImageView(a);
        image.setImageResource(icon);
        item.addView(image, new LinearLayout.LayoutParams(a.dp(30), a.dp(30)));
        TextView text = null;
        if (label != null) {
            text = new TextView(a);
            text.setText(label);
            text.setTextSize(16);
            text.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(-2, -2);
            textLayout.leftMargin = a.dp(8);
            item.addView(text, textLayout);
        }
        TextView caption = text;
        View.OnFocusChangeListener style = (v, focused) -> {
            int colour = focused ? Color.WHITE : 0x99ffffff;
            image.setColorFilter(colour);
            if (caption != null) caption.setTextColor(colour);
            v.setScaleX(focused ? 1.18f : 1f);
            v.setScaleY(focused ? 1.18f : 1f);
        };
        item.setOnFocusChangeListener(style);
        style.onFocusChange(item, false);
        item.setOnClickListener(v -> {
            a.revealControls();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(52));
        lp.rightMargin = a.dp(18);
        parent.addView(item, lp);
        return item;
    }

    /**
     * How fast the player is reading the stream, in megabits per second, or -1. LibVLC 3 reports
     * the input rate in bytes per microsecond (VLC's desktop app shows it × 8000 as kb/s; VLC for
     * Android × 8 × 1024 as kb/s), so × 8 gives megabits per second. Once the player's small
     * buffer is full this follows the stream's bitrate rather than the connection's top speed.
     */
    static float downloadSpeed(org.videolan.libvlc.MediaPlayer player) {
        if (player == null) return -1;
        var media = player.getMedia();
        if (media == null) return -1;
        try {
            var stats = media.getStats();
            return stats == null ? -1 : stats.inputBitrate * 8f;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            media.release();
        }
    }

    /** Time left as "1:02:03" or "2:03". */
    static String timeLeft(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600
            ? String.format(
                  java.util.Locale.ROOT,
                  "%d:%02d:%02d",
                  seconds / 3600,
                  (seconds / 60) % 60,
                  seconds % 60
              )
            : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
