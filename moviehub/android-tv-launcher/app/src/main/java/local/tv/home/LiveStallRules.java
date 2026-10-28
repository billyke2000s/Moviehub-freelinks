package local.tv.home;

/**
 * The general live-stream recovery rules, ported from the LibVLC live player to {@link ExoLive}
 * (October 2026). Pure, so the rules are core-tested: the player reports what it sees, and acts
 * when a method returns true. The LibVLC player's surface and decoder workarounds (view
 * refreshes, surface probes, software video) were not ported; Media3 does not need them.
 *
 * <ul>
 *   <li>Buffering ({@link LiveBuffering}): 8 s stuck, or 3 stalls in a minute, counted only after
 *       the first picture and never while paused. With another source waiting, the player moves
 *       on at once. With none, only a long stall with the picture not moving reconnects: brief
 *       HLS stalls never tear down a channel that is still playing.
 *   <li>Dead stream ({@link LiveProgress}): playing, but neither the picture nor the clock has
 *       moved for 8 s, with no error reported. A picture stopped while the clock (the sound)
 *       moves on is only logged by the player.
 *   <li>Reconnects ({@link LiveRestartBudget}): two per selection, refilled only after 30 s of
 *       moving picture, so a stream that plays briefly and dies cannot reconnect forever.
 * </ul>
 */
final class LiveStallRules {

    /** The picture must have moved this recently for a long stall to be left alone. */
    static final long MOVING_MS = 2000;

    private final LiveBuffering buffering = new LiveBuffering();
    private final LiveProgress progress = new LiveProgress();
    private final LiveRestartBudget reconnects = new LiveRestartBudget();
    private boolean pictures, lastBuffering;

    /** A new channel or source was chosen, or the viewer asked to reconnect: a fresh budget. */
    void newSelection() {
        opened();
        reconnects.reset();
    }

    /** The same selection is being opened again (a reconnect): startup is not counted. */
    void opened() {
        pictures = false;
        buffering.reset();
        progress.reset();
    }

    /** The first picture of this opening was drawn; stalls count from now on. */
    void firstPicture() {
        pictures = true;
    }

    /**
     * The player started ({@code stalled}) or stopped buffering. Returns true when the player
     * should recover now: move to another source when {@code alternative}, else reconnect.
     */
    boolean buffering(long now, boolean stalled, boolean playWhenReady, boolean alternative) {
        boolean watching = pictures && playWhenReady;
        if (!buffering.update(now, stalled, watching)) return false;
        return recover(now, alternative);
    }

    /**
     * Once a second: {@code position} is the playback clock, {@code frames} the pictures drawn so
     * far, {@code playing} whether the player is actually playing (not buffering or paused).
     * Returns true when the player should recover now, as {@link #buffering}.
     */
    boolean tick(
        long now,
        long position,
        long frames,
        boolean playing,
        boolean playWhenReady,
        boolean alternative
    ) {
        boolean watching = pictures && playWhenReady;
        reconnects.observe(now, frames, watching && playing);
        lastBuffering = true;
        if (
            (buffering.stalled(now, watching) || (watching && buffering.persistent(now))) &&
            recover(now, alternative)
        ) return true;
        lastBuffering = false;
        if (progress.stalled(now, position, frames, watching && playing, LiveBuffering.STALL_MS)) {
            boolean clockMoving = progress.clockMoving(now, LiveBuffering.STALL_MS);
            progress.reset();
            if (!clockMoving) {
                buffering.reset();
                return true;
            }
        }
        return false;
    }

    /** Whether the last {@link #tick} that returned true acted on buffering (else a dead stream). */
    boolean lastWasBuffering() {
        return lastBuffering;
    }

    /** Takes one reconnect from the budget; false when both are used and none refilled yet. */
    boolean reconnect() {
        return reconnects.acquire();
    }

    int reconnects() {
        return reconnects.attempts();
    }

    private boolean recover(long now, boolean alternative) {
        if (alternative) {
            buffering.reset();
            return true;
        }
        // Three brief stalls may be normal HLS period changes: alone they never tear down a
        // channel that has no other source. A long stall does, unless the picture still moves.
        if (!buffering.persistent(now)) return false;
        if (progress.videoMoving(now, MOVING_MS)) return false;
        buffering.reset();
        return true;
    }
}
