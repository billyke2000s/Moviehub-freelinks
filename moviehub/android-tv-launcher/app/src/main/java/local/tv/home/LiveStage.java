package local.tv.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * One screen for all of Live TV. MovieHub's pages (the TV Guide menu, the
 * channel guide, and every other screen) are shown in {@link #pageHost}; above them is one video
 * picture that is never rebuilt or moved to another screen, only placed over the guide's corner
 * box or stretched to full screen; above that, the satellite banner while watching full screen.
 * The same {@link ExoLive} player draws on the same surface the whole time.
 *
 * <p>Full screen is not a separate Activity: the guide stays exactly as it was underneath, so BACK
 * returns to it at once. All remote keys go through {@link MainActivity#dispatchKeyEvent}, which
 * sends them here while watching full screen.
 */
final class LiveStage implements LiveScreen {

    private final MainActivity a;
    private final FrameLayout root, pageHost, overlay, bannerHost;
    private final FrameLayout video;
    /** The one picture: Media3's PlayerView. */
    private final View surface;
    private final TextView status;
    private final Handler overlayHandler = new Handler(Looper.getMainLooper());
    private final LiveMenus menus = new ExoMenus(this, this::reconnect, this::backToLive);
    private LiveZapper zapper;
    private boolean dialogOpen, liveFailed, full;
    private boolean foreground = true;
    /** Looks up several copies at once when a channel starts (see LiveRace); null when idle. */
    private LiveRace race;
    private int[] raceCopies = new int[0];
    /** Moves to a better copy as soon as one is found, and back if it does not hold. */
    private final LiveUpgrade upgrade = new LiveUpgrade(this);
    private String url, title;
    /** The guide's corner box the picture sits over, or null when no guide is showing. */
    private View anchor;
    /** The guide's channel label, moved above the picture so the picture does not cover it. */
    private TextView anchorLabel;
    private View focusBeforeFull;
    private final View.OnLayoutChangeListener follow = (v, l, t, r, b, ol, ot, or, ob) -> place();
    private final ExoLive.Listener events = new ExoLive.Listener() {
        @Override
        public void playing() {
            liveFailed = false;
            overlayHandler.removeCallbacks(startupDeadline);
            overlayHandler.removeCallbacks(slow);
            overlayHandler.removeCallbacks(retry);
            status.setVisibility(View.GONE);
            // A new picture (another channel, a reconnect, Back to live) clears the pause bar.
            showPauseBar();
        }

        @Override
        public void buffering(boolean buffering) {
            if (!full || liveFailed) return;
            status.setText("Buffering…");
            status.setBackgroundColor(Color.TRANSPARENT);
            status.setVisibility(buffering ? View.VISIBLE : View.GONE);
        }

        @Override
        public void failed(String reason) {
            if (full) showError(reason);
        }

        @Override
        public boolean hasAlternative() {
            if (!full || !foreground) return false;
            // An upgraded copy on trial: the copy before it is waiting to come back.
            if (upgrade.trial()) return true;
            if (race != null) return race.hasAlternative();
            return zapper != null && zapper.hasUntriedCopy();
        }
    };

    private final LiveRace.Listener raceEvents = new LiveRace.Listener() {
        @Override
        public void play(int index, String locator, LiveStream.Resolution source) {
            if (!full || !foreground || zapper == null) {
                // The screen left meanwhile: discard the link (closing any relay it holds).
                if (source != null && source.relay != null) source.relay.close();
                return;
            }
            url = locator;
            zapper.raceSelected(index);
            ExoLive.attach(LiveStage.this, surface, events);
            ExoLive.playResolved(a, locator, source);
            // A standby is ready or on its way: give this copy less time, as moving on is quick.
            overlayHandler.removeCallbacks(startupDeadline);
            long budget = LiveSourceDeadline.milliseconds(
                race != null && race.hasAlternative(),
                zliveSource(),
                true
            );
            if (budget > 0) overlayHandler.postDelayed(startupDeadline, budget);
        }

        @Override
        public void stillLooking() {
            if (!full) return;
            status.setText("Trying another source…");
            status.setBackgroundColor(Color.TRANSPARENT);
            status.setVisibility(View.VISIBLE);
        }

        @Override
        public void exhausted() {
            endRace(true);
            showError("None of this channel's sources could be found");
        }
    };

    /** Starts looking up {@code copies} of the channel at once; LiveRace plays the one chosen. */
    private void startRace(int[] copies) {
        endRace(false);
        raceCopies = copies;
        opening("Finding the best source…");
        ExoLive.attach(this, surface, events);
        race = new LiveRace(a, ChannelDatabase.get(a), zapper.copies(), copies, a.http(), raceEvents);
        race.start();
    }

    /** Stops the race; {@code failed} tells the zapper its copies were tried, so failover skips them. */
    private void endRace(boolean failed) {
        if (race != null) race.cancel();
        race = null;
        if (failed && zapper != null) zapper.raceFailed(raceCopies);
        raceCopies = new int[0];
    }

    /** How long a raced copy may take to show a picture while a standby is ready or coming. */
    static final long RACE_START_MS = LiveSourceDeadline.RACE_MS;

    LiveStage(MainActivity activity) {
        a = activity;
        root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);
        pageHost = new FrameLayout(a);
        root.addView(pageHost, new FrameLayout.LayoutParams(-1, -1));
        // After the pages, so the picture shows through them wherever it is placed.
        video = new FrameLayout(a);
        surface = ExoLive.createVideoView(a);
        video.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        root.addView(video, hidden());
        overlay = new FrameLayout(a);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(a);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setShadowLayer(a.dp(6f), 0, a.dp(1f), 0xcc000000);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        overlay.addView(status, new FrameLayout.LayoutParams(-1, -1));
        bannerHost = new FrameLayout(a);
        overlay.addView(bannerHost, new FrameLayout.LayoutParams(-1, -1));
    }

    private static FrameLayout.LayoutParams hidden() {
        // Kept attached (so its surface survives) but out of sight.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(2, 2, Gravity.TOP | Gravity.START);
        lp.leftMargin = -100;
        return lp;
    }

    // ---- Pages ---------------------------------------------------------------------------------

    /** Shows one of MovieHub's pages, in place of {@code setContentView}. */
    void setPage(View page) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0 || content.getChildAt(0) != root) {
            if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
            a.setContentView(root);
        }
        if (full) exitFull(false);
        clearAnchor();
        pageHost.removeAllViews();
        pageHost.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    /**
     * A page outside Live TV is opening: leaves full screen, stops the picture and takes the stage
     * off screen. MainActivity then shows that page on its own.
     */
    void release() {
        if (full) exitFull(false);
        endRace(false);
        upgrade.stop();
        overlayHandler.removeCallbacksAndMessages(null);
        ExoLive.stopIfOwner(this);
        clearAnchor();
        pageHost.removeAllViews();
        if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
    }

    /** Whether the stage is on screen (only while a Live TV page or channel is showing). */
    boolean onScreen() {
        return root.isAttachedToWindow();
    }

    /**
     * A channel is starting full screen from a page outside the stage (a reminder's "Watch now"):
     * brings that page into the stage, so BACK returns to it.
     */
    private void adopt() {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return;
        View current = content.getChildCount() == 0 ? null : content.getChildAt(0);
        if (current == root) return;
        if (current != null) content.removeView(current);
        if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        pageHost.removeAllViews();
        if (current != null) pageHost.addView(current, new FrameLayout.LayoutParams(-1, -1));
        a.setContentView(root);
    }

    /**
     * The guide shows its live picture in {@code box}, with {@code label} naming the channel.
     * The one picture is placed over the box (and follows it if it moves).
     */
    void picture(FrameLayout box, TextView label) {
        clearAnchor();
        anchor = box;
        anchor.addOnLayoutChangeListener(follow);
        if (label != null) {
            if (label.getParent() instanceof ViewGroup) ((ViewGroup) label.getParent()).removeView(label);
            anchorLabel = label;
            overlay.addView(label, 0, new FrameLayout.LayoutParams(1, 1));
        }
        ExoLive.attach(this, surface, events);
        box.post(this::place);
    }

    /** The guide's picture stopped (the guide closed or MovieHub went into the background). */
    void pictureStopped() {
        ExoLive.stopIfOwner(this);
        clearAnchor();
    }

    private void clearAnchor() {
        if (anchor != null) anchor.removeOnLayoutChangeListener(follow);
        anchor = null;
        if (anchorLabel != null && anchorLabel.getParent() == overlay) overlay.removeView(anchorLabel);
        anchorLabel = null;
        if (!full) video.setLayoutParams(hidden());
    }

    /** Puts the picture over the guide's box, or full screen. */
    private void place() {
        if (full) {
            video.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
            if (anchorLabel != null) anchorLabel.setVisibility(View.GONE);
            return;
        }
        if (anchor == null || !anchor.isShown() || anchor.getWidth() == 0) {
            video.setLayoutParams(hidden());
            return;
        }
        int[] box = new int[2],
            base = new int[2];
        anchor.getLocationInWindow(box);
        root.getLocationInWindow(base);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            anchor.getWidth(),
            anchor.getHeight(),
            Gravity.TOP | Gravity.START
        );
        lp.leftMargin = box[0] - base[0];
        lp.topMargin = box[1] - base[1];
        video.setLayoutParams(lp);
        if (anchorLabel != null) {
            FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(
                anchor.getWidth(),
                -2,
                Gravity.TOP | Gravity.START
            );
            label.leftMargin = lp.leftMargin;
            label.topMargin = lp.topMargin + anchor.getHeight() - anchorLabel.getHeight();
            anchorLabel.setLayoutParams(label);
            anchorLabel.setVisibility(View.VISIBLE);
        }
    }

    // ---- Full screen ---------------------------------------------------------------------------

    boolean full() {
        return full;
    }

    /** Watches {@code channelUrl} full screen on the same picture (MainActivity.play). */
    void fullScreen(String channelTitle, String channelUrl, String id) {
        endRace(false);
        upgrade.reset();
        url = channelUrl;
        title = channelTitle;
        focusBeforeFull = a.getCurrentFocus();
        adopt();
        full = true;
        liveFailed = false;
        pageHost.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pageHost.setVisibility(View.INVISIBLE);
        place();
        // Another stream than the one playing starts in Automatic sources with nothing tried, as the
        // LibVLC player did when its stream changed. The same stream (back from the guide) keeps its
        // mode, so a source chosen by hand survives the trip.
        if (!channelUrl.equals(ExoLive.currentUrl())) {
            LiveZapper.activeAutomatic = true;
            LiveZapper.attemptedSources.clear();
        }
        LiveZapper.Session guide =
            LiveZapper.session == null
                ? null
                : LiveGuideState.restore(LiveGuideState.save(LiveZapper.session));
        if (guide == null) guide = new LiveZapper.Session("all", "", java.util.Collections.emptySet(), 0);
        zapper = new LiveZapper(this, guide);
        zapper.attach(bannerHost);
        overlay.setFocusable(false);
        video.setFocusable(true);
        video.setFocusableInTouchMode(true);
        video.requestFocus();
        // An air mouse's OK, or a tap on a phone, arrives as a click on the picture: the same
        // banner as OK, as the old LibVLC player screen did.
        video.setOnClickListener(v -> {
            if (zapper == null) return;
            if (zapper.touch) zapper.tapped();
            else zapper.ok();
        });
        ExoLive.attach(this, surface, events);
        if (!ExoLive.play(a, url, false)) opening("Opening channel…");
        else status.setVisibility(View.GONE);
    }

    /** BACK from full screen: the guide, exactly as it was, with the picture back in its box. */
    @Override
    public void leave() {
        exitFull(true);
    }

    private void exitFull(boolean backToGuide) {
        if (!full) return;
        full = false;
        SidePanel.closeFor(a);
        endRace(false);
        upgrade.stop();
        if (zapper != null) zapper.close();
        zapper = null;
        overlayHandler.removeCallbacksAndMessages(null);
        bannerHost.removeAllViews();
        status.setVisibility(View.GONE);
        // Back over the guide's box, the picture must not take the guide's clicks.
        video.setOnClickListener(null);
        video.setClickable(false);
        video.setFocusable(false);
        pageHost.setVisibility(View.VISIBLE);
        pageHost.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        place();
        if (!backToGuide) return;
        if (focusBeforeFull != null && focusBeforeFull.isAttachedToWindow()) focusBeforeFull.requestFocus();
        // As on returning from a separate player screen: the guide moves to the channel watched
        // last (MainActivity.onResume runs this there), and its label names it.
        Runnable refresh = a.resumeRefresh;
        a.resumeRefresh = null;
        if (refresh != null) refresh.run();
        a.liveTv.startPicture();
    }

    /** MovieHub went into the background while watching. */
    void stopped() {
        foreground = false;
        endRace(false);
        upgrade.stop();
        overlayHandler.removeCallbacksAndMessages(null);
        ExoLive.stopIfOwner(this);
    }

    /** Discards screen callbacks and view references when MainActivity is destroyed. */
    void destroyed() {
        stopped();
        SidePanel.closeFor(a);
        if (zapper != null) zapper.close();
        zapper = null;
        full = false;
        clearAnchor();
        bannerHost.removeAllViews();
        focusBeforeFull = null;
    }

    /** MovieHub came back while watching full screen. */
    void started() {
        foreground = true;
        if (!full || url == null) return;
        upgrade.reset();
        ExoLive.attach(this, surface, events);
        if (!ExoLive.play(a, url, false)) opening("Opening channel…");
    }

    /** Keys while watching full screen (the satellite remote, see LiveZapper.key). */
    boolean key(KeyEvent event) {
        if (zapper == null) return false;
        if (zapper.colourKey(event, zapper::colour)) return true;
        int code = event.getKeyCode();
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (liveFailed && select && !zapper.browsing()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (!zapper.retryFromFirst()) reconnect();
            }
            return true;
        }
        if (zapper.key(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY) ExoLive.resume();
            else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) ExoLive.pause();
            else if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) toggle();
            else if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && ExoLive.behindLive()) {
                backToLive();
                return true;
            } else return false;
            zapper.showBanner();
            showPauseBar();
            return true;
        }
        return false;
    }

    /** How long a channel may take to start before the viewer is told it may be unavailable. */
    static final long SLOW_MS = 10_000;

    private final Runnable slow = this::slowHint;

    private void slowHint() {
        if (!full || ExoLive.isPlaying() || status.getVisibility() != View.VISIBLE) return;
        status.setText(
            "Still finding this channel's link…\nIt may not be available right now. Still trying.\n\nBACK  guide     ▲ ▼  another channel"
        );
    }

    /** Says the channel is opening; after {@link #SLOW_MS} says it may not be available, and keeps waiting. */
    private void opening(String text) {
        // A selected channel owns its entire startup budget. Old failed-channel timers cannot
        // restart a slow replacement before its resolver/decoder has had a chance to finish.
        overlayHandler.removeCallbacks(retry);
        status.setText(text);
        status.setBackgroundColor(Color.TRANSPARENT);
        status.setVisibility(View.VISIBLE);
        overlayHandler.removeCallbacks(slow);
        overlayHandler.postDelayed(slow, SLOW_MS);
        // With another source to try, this one gets START_MS to show a picture, as in the
        // standard player. With none, it keeps waiting: a slow link is better than no channel.
        overlayHandler.removeCallbacks(startupDeadline);
        // A race arms its own, shorter deadline once a copy actually starts.
        if (race == null) {
            long budget = LiveSourceDeadline.milliseconds(events.hasAlternative(), zliveSource(), false);
            if (budget > 0) overlayHandler.postDelayed(startupDeadline, budget);
        }
    }

    /** How long a source may take to show a picture before the next source is tried. */
    static final long START_MS = LiveSourceDeadline.START_MS;

    private final Runnable startupDeadline = this::startupMissed;

    private void startupMissed() {
        if (!full || !foreground || ExoLive.isPlaying()) return;
        showError("This source did not start within its startup time limit");
    }

    private boolean zliveSource() {
        if (zapper != null) {
            int selected = zapper.selectedCopy();
            if (selected >= 0 && selected < zapper.copies().size()) return AdapterSources.source(
                "zlive"
            ).equals(zapper.copies().get(selected).source);
        }
        return url != null && url.contains("/engine/zlive/");
    }

    private void reconnect() {
        if (url == null) return;
        endRace(false);
        upgrade.reset();
        liveFailed = false;
        opening("Reconnecting…");
        ExoLive.attach(this, surface, events);
        ExoLive.play(a, url, true);
    }

    /** Every copy failed: keep the player open and keep trying, every {@link #RETRY_MS}. */
    static final long RETRY_MS = 15_000;

    private final Runnable retry = this::retryNow;

    private void showError(String reason) {
        if (!full || !foreground) return;
        // An upgraded copy on trial failed or kept buffering: the copy before it comes back.
        if (upgrade.failed()) return;
        if (race != null) {
            // The next copy already found (or the next one being looked up) takes over.
            if (race.hasAlternative()) {
                overlayHandler.removeCallbacks(startupDeadline);
                race.next();
                return;
            }
            endRace(true);
        }
        if (zapper != null && zapper.failover(reason)) return;
        if (zliveSource()) {
            liveFailed = true;
            overlayHandler.removeCallbacks(slow);
            overlayHandler.removeCallbacks(retry);
            overlayHandler.removeCallbacks(startupDeadline);
            ExoLive.stopIfOwner(this);
            status.setText(
                "This source is unavailable. Choose another source or reconnect.\n\nBACK  guide     ▲ ▼  another channel"
            );
            status.setBackgroundColor(0x99000000);
            status.setVisibility(View.VISIBLE);
            return;
        }
        status.setText(
            "Still trying this channel…\nIt may not be available right now.\n\nBACK  guide     ▲ ▼  another channel"
        );
        status.setBackgroundColor(0x99000000);
        status.setVisibility(View.VISIBLE);
        overlayHandler.removeCallbacks(slow);
        overlayHandler.removeCallbacks(retry);
        overlayHandler.postDelayed(retry, RETRY_MS);
    }

    private void retryNow() {
        if (!full || !foreground || ExoLive.isPlaying()) return;
        // Start again from the first copy, so every copy gets another go; or the one copy again.
        if (zapper == null || !zapper.retryFromFirst()) {
            upgrade.reset();
            ExoLive.attach(this, surface, events);
            ExoLive.play(a, url, true);
        }
        // The new attempt schedules its next retry only after it actually fails.
    }

    // ---- Quality upgrades (LiveUpgrade) ---------------------------------------------------------

    /** Whether an upgrade may change what plays: full screen, in front, playing, no panel open. */
    boolean upgradeWatching() {
        // Never while paused or behind live: switching would start the picture again at the live edge.
        return (
            full &&
            foreground &&
            !liveFailed &&
            !dialogOpen &&
            zapper != null &&
            ExoLive.active() &&
            !ExoLive.paused() &&
            !ExoLive.behindLive()
        );
    }

    LiveRace race() {
        return race;
    }

    boolean allowHttp() {
        return a.http();
    }

    /** LiveUpgrade found a better copy: it plays now, with the race's short startup budget. */
    void upgradeTo(int index, String locator, LiveStream.Resolution source) {
        if (!full || !foreground || zapper == null) {
            if (source != null && source.relay != null) source.relay.close();
            return;
        }
        url = locator;
        zapper.raceSelected(index);
        status.setVisibility(View.GONE);
        ExoLive.attach(this, surface, events);
        ExoLive.playResolved(a, locator, source);
        // The copy before is ready to come back, as a race standby would be.
        overlayHandler.removeCallbacks(startupDeadline);
        overlayHandler.postDelayed(
            startupDeadline,
            LiveSourceDeadline.milliseconds(true, zliveSource(), true)
        );
        Toast.makeText(a, "Better picture found: switching source", Toast.LENGTH_SHORT).show();
    }

    /** The upgraded copy {@code failed} did not hold: copy {@code previous} at {@code previousUrl} plays again. */
    void upgradeFellBack(int previous, int failed, String previousUrl) {
        if (race != null) race.fellBack(previous, failed);
        url = previousUrl;
        if (zapper != null) zapper.raceSelected(previous);
        liveFailed = false;
        opening("Going back to the previous source…");
        ExoLive.attach(this, surface, events);
        ExoLive.play(a, url, true);
    }

    // ---- LiveScreen ----------------------------------------------------------------------------

    @Override
    public Activity activity() {
        return a;
    }

    @Override
    public Handler overlayHandler() {
        return overlayHandler;
    }

    @Override
    public FrameLayout video() {
        return video;
    }

    @Override
    public LiveZapper zapper() {
        return zapper;
    }

    @Override
    public boolean dialogOpen() {
        return dialogOpen;
    }

    @Override
    public void dialogOpen(boolean open) {
        dialogOpen = open;
    }

    @Override
    public boolean liveFailed() {
        return liveFailed;
    }

    @Override
    public int dp(int n) {
        return a.dp((float) n);
    }

    @Override
    public LiveMenus liveMenus() {
        return menus;
    }

    @Override
    public String videoDetails() {
        return ExoLive.videoDetails();
    }

    @Override
    public void switchTo(String newTitle, String newUrl, String newId) {
        if (!foreground || !full) return;
        try {
            url = Playlist.networkUrl(newUrl, a.http());
        } catch (Exception e) {
            if (zapper == null || !zapper.failover("This stream's address is not allowed")) showError(
                "This channel's address is not allowed. Turn on Allow HTTP in Settings → System to play it."
            );
            return;
        }
        title = newTitle;
        liveFailed = false;
        // A new channel, or a copy chosen by hand or by failover: any upgrade is forgotten.
        upgrade.reset();
        // Several copies and automatic sources: look them up at once and play the best found.
        int[] copies = zapper == null ? new int[0] : zapper.raceCandidates();
        if (copies.length > 1) {
            startRace(copies);
            return;
        }
        endRace(false);
        opening("Opening channel…");
        ExoLive.attach(this, surface, events);
        ExoLive.play(a, url, true);
    }

    @Override
    public String playbackUrl() {
        return url;
    }

    @Override
    public void panelClosed() {
        dialogOpen = false;
        if (full && zapper != null) zapper.showBanner();
    }

    @Override
    public void liveSourceFailed() {
        showError("This source could not be played. Try another source or retry the connection.");
    }

    @Override
    public void toggle() {
        if (ExoLive.isPlaying()) ExoLive.pause();
        else ExoLive.resume();
        showPauseBar();
    }

    /**
     * The satellite recorders' pause bar: "PAUSED" while paused, then "BEHIND LIVE" until Back to
     * live (the fast-forward key, or Options), as the old LibVLC player showed it.
     */
    void showPauseBar() {
        if (zapper == null || zapper.overlay == null) return;
        zapper.overlay.pause(ExoLive.paused(), ExoLive.behindLive());
    }

    /** Back to live: to the live edge in the same stream, or the channel reopened when it has none. */
    void backToLive() {
        if (!full) return;
        if (!ExoLive.backToLive()) reconnect();
        showPauseBar();
        if (zapper != null) zapper.showBanner();
    }

    @Override
    public String title() {
        return title;
    }
}
