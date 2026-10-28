package local.tv.home;

/**
 * Whether a film or episode that stopped really reached its end, so it may be marked watched and
 * the next episode may start. A broken source can end at once (nothing played, no length), be a
 * short clip in place of the episode, or have its connection close part-way through; none of
 * these is a finish, and the player treats them as a failed source instead.
 */
final class PlaybackEnd {

    private PlaybackEnd() {}

    /** Shorter than this is not a real film or episode (a placeholder, an error clip, a trailer). */
    static final long MIN_LENGTH_MS = 2 * 60 * 1000L;

    /** Treat a video as finished when no more than four minutes (or a tenth of it) remain. */
    static final long COMPLETION_WINDOW_MS = 4 * 60 * 1000L;

    static boolean nearEnd(long position, long length) {
        return (
            length > 0 &&
            position > 0 &&
            position <= length &&
            length - position <= Math.min(COMPLETION_WINDOW_MS, length / 10)
        );
    }

    /** Ending with more than this still to go is a cut-off stream, not a finish. */
    static final long EARLY_END_MS = 60 * 1000L;

    /** Positions are read once a second, so a little past the reported length is normal. */
    static final long PAST_END_SLACK_MS = 5000L;

    /**
     * {@code length} is the media's length (0 or less when unknown) and {@code furthest} the
     * furthest position reached while playing it. A position well past the length (resuming
     * beyond the end of a shorter or broken copy) means this media is not the one being watched.
     */
    static boolean finished(long length, long furthest) {
        if (furthest <= 0) return false;
        if (length <= 0) return furthest >= MIN_LENGTH_MS;
        if (length < MIN_LENGTH_MS) return false;
        if (furthest > length + PAST_END_SLACK_MS) return false;
        long left = length - Math.min(furthest, length);
        return left <= EARLY_END_MS || nearEnd(Math.min(furthest, length), length);
    }

    /** Readings trail by up to a second, so the player may be a little past the furthest seen. */
    static final long READING_SLACK_MS = 5000L;

    /**
     * The position to resume from: the player's {@code time}, capped at {@code reached} (the
     * furthest played, jumped to or opened at); {@code reached} when the time is unknown.
     */
    static long playedPosition(long time, long reached) {
        if (time <= 0) return Math.max(0, reached);
        return Math.min(time, reached + READING_SLACK_MS);
    }

    /** Leaving the player near the end counts as watched only for media of a real length. */
    static boolean watchedWhenLeaving(long position, long length) {
        return length >= MIN_LENGTH_MS && nearEnd(position, length);
    }
}
