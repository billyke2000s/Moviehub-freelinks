package local.tv.home;

import android.os.SystemClock;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * {@link ExoLive}'s event log (Options → Player event log): the decoder, video formats, quality
 * switches, dropped frames, errors, and the picture stopping while the sound plays on. It only
 * records; it never repairs. Main thread only.
 */
@OptIn(markerClass = UnstableApi.class)
final class ExoLiveLog {

    /** Events kept for the log. */
    static final int SIZE = 60;

    private static final ArrayDeque<String> log = new ArrayDeque<>();
    private static String decoder = "";
    /** Rendered-frame count at the last check, to notice the picture stopping while sound plays. */
    private static int lastRendered = -1,
        stillSeconds;
    /** Frames actually drawn per second, from the last one-second check; 0 when unknown. */
    private static float drawnFps;
    private static long lastCheckAt;

    private ExoLiveLog() {}

    static void event(String text) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        log.addFirst(time + "  " + text);
        while (log.size() > SIZE) log.removeLast();
    }

    /** The event log, newest first. */
    static String eventLog() {
        if (log.isEmpty()) return "Nothing recorded yet.";
        StringBuilder text = new StringBuilder();
        for (String line : log) text.append(line).append('\n');
        return text.toString().trim();
    }

    /** The video decoder in use, or "" before one starts. */
    static String decoder() {
        return decoder;
    }

    /** Frames actually drawn per second, from the last one-second check; 0 when unknown. */
    static float drawnFps() {
        return drawnFps;
    }

    /** The player stopped: forget the decoder and the picture measurements (not the log). */
    static void reset() {
        lastRendered = -1;
        stillSeconds = 0;
        drawnFps = 0;
        decoder = "";
    }

    /** Records the player's decoder, formats, dropped frames and errors. */
    static AnalyticsListener analytics() {
        return new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(
                EventTime time,
                String name,
                long initializedTimestampMs,
                long initializationDurationMs
            ) {
                decoder = name;
                event("Video decoder " + name);
            }

            @Override
            public void onVideoInputFormatChanged(
                EventTime time,
                Format format,
                DecoderReuseEvaluation reuse
            ) {
                event(
                    "Video " +
                        format.width +
                        "×" +
                        format.height +
                        (format.frameRate > 0
                            ? String.format(Locale.ROOT, " %.2f fps", format.frameRate)
                            : "") +
                        (format.codecs == null ? "" : " " + format.codecs) +
                        (format.bitrate > 0 ? " " + format.bitrate / 1000 + " kb/s" : "") +
                        (reuse == null
                            ? ""
                            : reuse.result == DecoderReuseEvaluation.REUSE_RESULT_NO
                              ? " (decoder restarted)"
                              : "")
                );
            }

            @Override
            public void onDroppedVideoFrames(EventTime time, int dropped, long elapsedMs) {
                event("Dropped " + dropped + " frames in " + elapsedMs + " ms");
            }

            @Override
            public void onSurfaceSizeChanged(EventTime time, int width, int height) {
                event("Picture size " + width + "×" + height);
            }

            @Override
            public void onVideoCodecError(EventTime time, Exception error) {
                event("Video decoder error " + error.getClass().getSimpleName());
            }

            @Override
            public void onAudioUnderrun(
                EventTime time,
                int bufferSize,
                long bufferSizeMs,
                long elapsedSinceLastFeedMs
            ) {
                event("Sound ran short");
            }
        };
    }

    /** Records (only) when the picture stops advancing while the sound plays on. */
    static void checkPicture(ExoPlayer player) {
        if (player == null || !player.isPlaying()) {
            lastRendered = -1;
            stillSeconds = 0;
            drawnFps = 0;
            return;
        }
        DecoderCounters counters = player.getVideoDecoderCounters();
        if (counters == null) return;
        counters.ensureUpdated();
        int rendered = counters.renderedOutputBufferCount;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastCheckAt;
        // Only a steady one-second sample counts; a restart or a late check is not measured.
        if (lastRendered >= 0 && rendered >= lastRendered && elapsed >= 500 && elapsed <= 3000) {
            drawnFps = (1000f * (rendered - lastRendered)) / elapsed;
        }
        lastCheckAt = now;
        if (rendered == lastRendered) {
            stillSeconds++;
            if (stillSeconds == 3) event("Picture stopped advancing while sound plays");
        } else {
            if (stillSeconds >= 3) event("Picture moving again after " + stillSeconds + " s");
            stillSeconds = 0;
        }
        lastRendered = rendered;
    }
}
