package local.tv.home;

/**
 * Combines quick skip presses into one jump, as Kodi does. Each press moves a pending target, and
 * the player seeks once, {@link #DELAY_MS} after the last press. Pressing +10 s six times is then a
 * single one-minute jump instead of six jumps that each reconnect and buffer. Pure Java so the core
 * checks can test it.
 */
final class SkipQueue {

    /** Time after the last press before the jump happens (Kodi's default seek delay). */
    static final long DELAY_MS = 750;

    private long target = -1;

    /**
     * Adds a skip of {@code delta} milliseconds to the pending target, or to {@code now} when nothing
     * is pending. Returns the new target, kept within the film when its {@code length} is known.
     */
    long skip(long now, long delta, long length) {
        long from = target >= 0 ? target : Math.max(0, now);
        return moveTo(from + delta, length);
    }

    /** Sets the pending target to {@code to} (from the seek bar). Returns the kept target. */
    long moveTo(long to, long length) {
        long kept = Math.max(0, to);
        if (length > 0) kept = Math.min(length, kept);
        target = kept;
        return kept;
    }

    boolean pending() {
        return target >= 0;
    }

    /** The pending target, or -1 when nothing is pending. */
    long target() {
        return target;
    }

    /** Returns the pending target and clears it; -1 when nothing is pending. */
    long take() {
        long taken = target;
        target = -1;
        return taken;
    }

    void clear() {
        target = -1;
    }
}
