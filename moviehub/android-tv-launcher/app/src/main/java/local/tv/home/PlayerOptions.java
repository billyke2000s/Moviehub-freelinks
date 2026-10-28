package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.List;

/**
 * LibVLC engine options for films and series. (Live TV plays on ExoPlayer, see ExoLive.)
 */
final class PlayerOptions {

    private PlayerOptions() {}

    /** Films and series (VlcPlaybackActivity's own player). */
    static List<String> film(Context context) {
        SharedPreferences prefs = prefs(context);
        List<String> options = new java.util.ArrayList<>(
            Arrays.asList(
                // VOD favours a little more headroom; this can add time to startup and seeks.
                "--network-caching=" + PlaybackBuffer.VOD_MS,
                "--stats",
                "--no-video-title-show",
                // Network files have no subtitle files beside them; do not probe for any.
                "--no-sub-autodetect-file",
                "--audio-language=" + prefs.getString("audioLanguage", "eng,en"),
                "--sub-language=" + prefs.getString("subtitleLanguage", "none")
            )
        );
        // Subtitle size and background (Settings → Playback).
        options.addAll(
            SubtitleStyle.vlcOptions(
                prefs.getInt(SubtitleStyle.SIZE, SubtitleStyle.NORMAL),
                prefs.getBoolean(SubtitleStyle.BACKGROUND, false)
            )
        );
        return options;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }
}
