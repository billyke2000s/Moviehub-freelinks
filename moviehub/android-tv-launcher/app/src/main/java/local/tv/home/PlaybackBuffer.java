package local.tv.home;

/** The LibVLC network buffer for films and series (Live TV plays on ExoPlayer's own buffering). */
final class PlaybackBuffer {

    /** VOD favours a little headroom; this can add time to startup and seeks. */
    static final int VOD_MS = 2500;

    private PlaybackBuffer() {}
}
