package local.tv.home;

/** Detects a stopped live clock or video counter, independently of buffering events. */
final class LiveProgress {

    private long position = -1,
        pictures = -1,
        clockAt,
        videoAt;

    boolean stalled(long now, long time, long frames, boolean watching, long limit) {
        if (!watching) {
            position = pictures = -1;
            return false;
        }
        if (position < 0 || time != position) clockAt = now;
        if (pictures < 0 || frames != pictures) videoAt = now;
        position = time;
        pictures = frames;
        // Some live demuxers do not expose a useful clock. Actual advancing video wins.
        return frames >= 0 ? now - videoAt >= limit : now - clockAt >= limit;
    }

    /**
     * The picture has not changed for {@code limit} while the clock (and so the sound) still
     * moved within it: a stopped picture over playing audio, not buffering.
     */
    boolean pictureFrozen(long now, long limit) {
        return pictures >= 0 && position >= 0 && now - videoAt >= limit && now - clockAt < limit;
    }

    /** When the current still picture began; the same value for one whole freeze. */
    long pictureSince() {
        return videoAt;
    }

    boolean videoMoving(long now, long limit) {
        return pictures >= 0 && now - videoAt < limit;
    }

    boolean clockMoving(long now, long limit) {
        return position >= 0 && now - clockAt < limit;
    }

    void reset() {
        position = pictures = -1;
    }
}
