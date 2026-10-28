package local.tv.home;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The one Live TV player, on Media3 ExoPlayer, drawn by Media3's own
 * {@link PlayerView} (the standard Android TV picture: correct shape, subtitles, surface lifecycle).
 * There is one view for all of Live TV ({@link LiveStage}); it is resized, never handed over.
 *
 * <p>Channel links are found by the existing {@link LiveStream#resolve} step, unchanged. Live
 * playback uses ExoPlayer's own handling (live edge, quality switching, device workarounds).
 * Errors reopen the channel at most twice; after that the owner keeps trying (see LiveStage).
 *
 * <p>An event log records what the player did (decoder, video formats, quality switches, dropped
 * frames, errors, and the picture stopping while sound plays) so a freeze can be explained. It only
 * records; it never repairs.
 */
@OptIn(markerClass = UnstableApi.class)
final class ExoLive {

    interface Listener {
        void playing();

        void buffering(boolean buffering);

        void failed(String reason);

        /**
         * Whether another source of this channel is waiting to be tried. A failing source then
         * moves on at once instead of being reopened, as the standard player does.
         */
        default boolean hasAlternative() {
            return false;
        }
    }

    /** Reopens per selection before the owner is told it failed (see {@link LiveStallRules}). */
    static final int MAX_REOPENS = 2;
    /** The earliest and latest a provider link is renewed, before it expires. */
    static final long RENEW_MIN_MS = 30_000,
        RENEW_MAX_MS = 3 * 3600_000L,
        RENEW_AHEAD_MS = 60_000;
    /** Events kept for the log. */
    static final int LOG_SIZE = ExoLiveLog.SIZE;

    /** Fixture link lookup for device tests (Pluto-native locators); never assigned by app code. */
    static LiveStream.Resolver testResolver;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private static ExoPlayer player;
    private static PlayerView view;
    private static Context app;
    private static String url;
    private static ProviderRelay relay;
    private static Object owner;
    private static Listener listener;
    private static int generation;
    /** Buffering, dead-stream and reconnect rules ported from the LibVLC live player. */
    private static final LiveStallRules rules = new LiveStallRules();
    /** The provider cache was cleared once for this selection. */
    private static boolean engineRefreshed;
    private static Runnable renewal;
    private static Future<?> renewing;
    private static NativeHttp renewingHttp;
    private static Runnable pendingReopen;
    private static Future<?> resolution;
    private static NativeHttp resolvingHttp;
    private static boolean hlsRetried;
    private static String status = "Idle";
    /** Paused by the viewer since the stream last joined the live edge (see {@link #behindLive}). */
    private static boolean behindLive;
    private static final Runnable watch = new Runnable() {
        @Override
        public void run() {
            ExoLiveLog.checkPicture(player);
            checkStalls();
            main.postDelayed(this, 1000);
        }
    };

    private ExoLive() {}

    /** The one picture for Live TV: Media3's PlayerView, without its own on-screen controls. */
    static View createVideoView(Context context) {
        PlayerView picture = new PlayerView(context);
        picture.setUseController(false);
        picture.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        picture.setShutterBackgroundColor(Color.BLACK);
        picture.setKeepContentOnPlayerReset(true);
        picture.setFocusable(false);
        picture.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        return picture;
    }

    /**
     * Plays {@code locator} (a stored channel address or native/provider locator). When it is
     * already playing and {@code restart} is false it carries on and this returns true.
     */
    /**
     * Plays {@code locator} from a link LiveRace already found, without looking it up again. If
     * it later fails, the usual path applies: another source when one waits, else a fresh lookup.
     */
    static void playResolved(Context context, String locator, LiveStream.Resolution source) {
        app = context.getApplicationContext();
        ensurePlayer();
        if (!locator.equals(url)) {
            hlsRetried = false;
            event("New channel");
        }
        // The race chose this source: it gets its own reconnects, as a source chosen by hand.
        newSelection();
        cancelReopen();
        cancelResolution();
        url = locator;
        int ticket = ++generation;
        player.stop();
        closeRelay();
        EngineSources.Stream stream = source.provider;
        event("Link found by the source race");
        start(
            ticket,
            source.relay == null ? source.url : source.relay.url,
            // As open(): provider headers, Pluto's agent for native Pluto, none for a plain address.
            stream != null
                ? stream.userAgent
                : PlutoSource.nativeUrl(locator)
                  ? PlutoSource.USER_AGENT
                  : null,
            stream == null ? null : stream.referer,
            source.relay,
            stream == null ? 0 : stream.expiresAt
        );
    }

    static boolean play(Context context, String locator, boolean restart) {
        app = context.getApplicationContext();
        ensurePlayer();
        if (!restart && locator.equals(url) && active()) {
            player.play();
            return true;
        }
        if (!locator.equals(url)) {
            hlsRetried = false;
            event("New channel");
        }
        // A new channel, or the owner asked to start this one again: a fresh reconnect budget.
        newSelection();
        cancelReopen();
        url = locator;
        open();
        return false;
    }

    private static void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(app).build();
        player.setAudioAttributes(
            new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        );
        if (view != null) view.setPlayer(player);
        player.addListener(
            new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_BUFFERING) {
                        if (!"Buffering".equals(status) && !"Opening".equals(status)) event("Buffering");
                        status = "Buffering";
                        if (listener != null) listener.buffering(true);
                        if (
                            rules.buffering(
                                SystemClock.elapsedRealtime(),
                                true,
                                player.getPlayWhenReady(),
                                hasAlternative()
                            )
                        ) stalled("This source kept buffering");
                    } else if (state == Player.STATE_READY) {
                        status = "Playing";
                        if (listener != null) listener.buffering(false);
                        rules.buffering(
                            SystemClock.elapsedRealtime(),
                            false,
                            player.getPlayWhenReady(),
                            hasAlternative()
                        );
                    } else if (state == Player.STATE_ENDED) {
                        event("Stream ended");
                        reopen("The live stream ended");
                    }
                }

                @Override
                public void onRenderedFirstFrame() {
                    // Reconnects are refilled by 30 s of moving picture (LiveStallRules), not by
                    // one frame: a stream that plays briefly and dies must not reopen forever.
                    rules.firstPicture();
                    if (listener != null) listener.playing();
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Diagnostics.recovered("ExoPlayer live " + error.getErrorCodeName(), error);
                    event("Error " + error.getErrorCodeName());
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        // Fell behind the live window: rejoin live without reopening the channel.
                        behindLive = false;
                        player.seekToDefaultPosition();
                        player.prepare();
                        return;
                    }
                    if (
                        !hlsRetried &&
                        (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
                    ) {
                        // An address without an extension may still be an HLS playlist.
                        hlsRetried = true;
                        open();
                        return;
                    }
                    reopen("This source could not be played (" + error.getErrorCodeName() + ")");
                }
            }
        );
        player.addAnalyticsListener(ExoLiveLog.analytics());
        main.removeCallbacks(watch);
        main.postDelayed(watch, 1000);
    }

    /**
     * Once a second: the buffering and dead-stream rules (see {@link LiveStallRules}). Nothing is
     * judged while a link is being looked up or a reopen is waiting.
     */
    private static void checkStalls() {
        if (player == null || url == null || listener == null || pendingReopen != null) return;
        if (resolution != null && !resolution.isDone()) return;
        long frames = -1;
        DecoderCounters counters = player.getVideoDecoderCounters();
        if (counters != null) {
            counters.ensureUpdated();
            frames = counters.renderedOutputBufferCount;
        }
        if (
            rules.tick(
                SystemClock.elapsedRealtime(),
                player.getCurrentPosition(),
                frames,
                player.isPlaying(),
                player.getPlayWhenReady(),
                hasAlternative()
            )
        ) stalled(rules.lastWasBuffering() ? "This source kept buffering" : "This source stopped playing");
    }

    /** A rule above says this source is stuck: another source when one waits, else a reconnect. */
    private static void stalled(String reason) {
        event(reason);
        reopen(reason);
    }

    private static boolean hasAlternative() {
        return listener != null && listener.hasAlternative();
    }

    private static void event(String text) {
        ExoLiveLog.event(text);
    }

    /** The event log, newest first. */
    static String eventLog() {
        return ExoLiveLog.eventLog();
    }

    private static void newSelection() {
        rules.newSelection();
        engineRefreshed = false;
        cancelRenewal();
    }

    /**
     * Reopens the channel (a fresh link lookup), or tells the owner once the reconnect budget
     * ({@link #MAX_REOPENS}, refilled after 30 s of moving picture) is used.
     */
    private static void reopen(String reason) {
        if (url == null) return;
        // Another copy is waiting: go to it now. Reopening a failed source (a fresh lookup each
        // time) before trying the others kept the picture black for a long time.
        if (listener != null && listener.hasAlternative()) {
            if (AdapterSources.locator(url)) AdapterSources.failed(url);
            status = "Failed: " + reason;
            event("Moving to another source (" + reasonShort(reason) + ")");
            listener.failed(reason);
            return;
        }
        if (!rules.reconnect()) {
            status = "Failed: " + reason;
            if (listener != null) listener.failed(reason);
            return;
        }
        // Retire the selected upstream player, rather than preferring the same failed one again.
        if (AdapterSources.locator(url)) AdapterSources.failed(url);
        // Look the link up afresh rather than reusing a cached one.
        if (EngineSources.locator(url) && !engineRefreshed) {
            engineRefreshed = true;
            EngineSources.invalidate();
        }
        if (PlutoSource.nativeUrl(url)) PlutoSource.invalidate();
        status = "Reopening (" + rules.reconnects() + " of " + MAX_REOPENS + ")";
        event("Reopening the channel (" + reasonShort(reason) + ")");
        cancelReopen();
        int ticket = generation;
        pendingReopen = () -> {
            pendingReopen = null;
            if (ticket == generation && url != null && player != null) open();
        };
        main.postDelayed(pendingReopen, 1000);
    }

    private static String reasonShort(String reason) {
        return reason.length() > 60 ? reason.substring(0, 60) + "…" : reason;
    }

    private static void cancelReopen() {
        if (pendingReopen != null) main.removeCallbacks(pendingReopen);
        pendingReopen = null;
    }

    private static void cancelResolution() {
        if (resolvingHttp != null) resolvingHttp.cancel();
        if (resolution != null) resolution.cancel(true);
        resolvingHttp = null;
        resolution = null;
    }

    private static void open() {
        cancelReopen();
        cancelResolution();
        cancelRenewal();
        int ticket = ++generation;
        String locator = url;
        if (locator == null || player == null) return;
        player.stop();
        closeRelay();
        status = "Finding the stream";
        long started = SystemClock.elapsedRealtime();
        if (!(PlutoSource.nativeUrl(locator) || EngineSources.locator(locator))) {
            start(ticket, locator, null, null, null, 0);
            return;
        }
        Context context = app;
        NativeHttp http = new NativeHttp();
        resolvingHttp = http;
        resolution = resolver.submit(() -> {
            try {
                LiveStream.Resolution source = LiveStream.resolve(
                    context,
                    locator,
                    http,
                    ExoLive.testResolver
                );
                EngineSources.Stream stream = source.provider;
                main.post(() -> {
                    event("Link found in " + (SystemClock.elapsedRealtime() - started) / 1000 + " s");
                    start(
                        ticket,
                        source.relay == null ? source.url : source.relay.url,
                        stream == null ? PlutoSource.USER_AGENT : stream.userAgent,
                        stream == null ? null : stream.referer,
                        source.relay,
                        stream == null ? 0 : stream.expiresAt
                    );
                });
            } catch (Exception e) {
                Diagnostics.recovered("Resolving live channel for ExoPlayer", e);
                main.post(() -> {
                    if (ticket != generation) return;
                    event("Link not found after " + (SystemClock.elapsedRealtime() - started) / 1000 + " s");
                    resolutionFailed(locator);
                });
            }
        });
    }

    /** A complete DaddyLive walk already exhausted its candidates; do not repeat it three times. */
    static void resolutionFailed(String locator) {
        String reason = "This channel's address could not be found";
        if (locator.startsWith(AdapterSources.ROOT + "dlhd/")) {
            AdapterSources.failed(locator);
            status = "Failed: " + reason;
            if (listener != null) listener.failed(reason);
        } else reopen(reason);
    }

    private static void start(
        int ticket,
        String stream,
        String userAgent,
        String referer,
        ProviderRelay next,
        long expiresAt
    ) {
        if (ticket != generation || player == null) {
            if (next != null) next.close();
            return;
        }
        relay = next;
        // A fresh opening starts at the live edge.
        behindLive = false;
        rules.opened();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000);
        if (userAgent != null) http.setUserAgent(userAgent);
        if (referer != null && !referer.isEmpty()) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", referer);
            http.setDefaultRequestProperties(headers);
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(stream);
        String lower = stream.toLowerCase(Locale.ROOT);
        if (hlsRetried || lower.contains(".m3u8") || lower.contains("m3u8?")) item.setMimeType(
            MimeTypes.APPLICATION_M3U8
        );
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));
        player.prepare();
        player.play();
        status = "Opening";
        scheduleRenewal(ticket, expiresAt);
    }

    /**
     * A provider link that expires is looked up again a minute before it does (at least 30 s
     * and at most three hours ahead), as the LibVLC live player did. The old link keeps playing
     * during the lookup; a failed lookup leaves it playing.
     */
    private static void scheduleRenewal(int ticket, long expiresAt) {
        cancelRenewal();
        if (expiresAt <= 0 || url == null) return;
        long delay = Math.max(
            RENEW_MIN_MS,
            Math.min(RENEW_MAX_MS, expiresAt - System.currentTimeMillis() - RENEW_AHEAD_MS)
        );
        String locator = url;
        renewal = () -> {
            renewal = null;
            if (ticket != generation || player == null || !locator.equals(url)) return;
            // Paused or stalled: look again shortly rather than restarting a picture nobody sees.
            if (!player.isPlaying()) scheduleRenewal(ticket, System.currentTimeMillis() + RENEW_AHEAD_MS);
            else renew(ticket, locator);
        };
        main.postDelayed(renewal, delay);
    }

    private static void renew(int ticket, String locator) {
        event("Renewing the provider link before it expires");
        Context context = app;
        NativeHttp http = new NativeHttp();
        renewingHttp = http;
        renewing = resolver.submit(() -> {
            try {
                LiveStream.Resolution source = LiveStream.resolve(
                    context,
                    locator,
                    http,
                    ExoLive.testResolver
                );
                main.post(() -> {
                    if (ticket != generation || player == null || !locator.equals(url)) {
                        if (source.relay != null) source.relay.close();
                        return;
                    }
                    event("Provider link renewed");
                    EngineSources.Stream stream = source.provider;
                    ProviderRelay old = relay;
                    relay = null;
                    start(
                        ++generation,
                        source.relay == null ? source.url : source.relay.url,
                        stream == null ? PlutoSource.USER_AGENT : stream.userAgent,
                        stream == null ? null : stream.referer,
                        source.relay,
                        stream == null ? 0 : stream.expiresAt
                    );
                    // Closed after the new link is set, so the old one is not cut mid-read first.
                    if (old != null) old.close();
                });
            } catch (Exception e) {
                Diagnostics.recovered("Renewing a live provider link", e);
                main.post(() -> {
                    if (ticket == generation) event("Link renewal failed; still playing the old link");
                });
            }
        });
    }

    private static void cancelRenewal() {
        if (renewal != null) main.removeCallbacks(renewal);
        renewal = null;
        if (renewingHttp != null) renewingHttp.cancel();
        if (renewing != null) renewing.cancel(true);
        renewingHttp = null;
        renewing = null;
    }

    /** Draws the picture in {@code picture} (the view from {@link #createVideoView}) for {@code who}. */
    static void attach(Object who, View picture, Listener events) {
        owner = who;
        listener = events;
        if (picture instanceof PlayerView && picture != view) {
            if (view != null) view.setPlayer(null);
            view = (PlayerView) picture;
            if (app == null) app = picture.getContext().getApplicationContext();
        }
        if (app != null) ensurePlayer();
        if (view != null && view.getPlayer() != player) view.setPlayer(player);
        if (view != null) styleSubtitles(view);
    }

    /** Subtitle size and background from Settings → Playback ({@link SubtitleStyle}). */
    private static void styleSubtitles(PlayerView picture) {
        SubtitleView subtitles = picture.getSubtitleView();
        if (subtitles == null) return;
        android.content.SharedPreferences prefs = picture.getContext().getSharedPreferences("ui", 0);
        int size = prefs.getInt(SubtitleStyle.SIZE, SubtitleStyle.NORMAL);
        boolean background = prefs.getBoolean(SubtitleStyle.BACKGROUND, false);
        subtitles.setFractionalTextSize(
            SubtitleStyle.exoFraction(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, size)
        );
        subtitles.setStyle(
            new CaptionStyleCompat(
                Color.WHITE,
                SubtitleStyle.exoBackground(background),
                Color.TRANSPARENT,
                background ? CaptionStyleCompat.EDGE_TYPE_NONE : CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null
            )
        );
    }

    /** {@code who} is leaving: stops playback only if nothing else has taken the picture over. */
    static void stopIfOwner(Object who) {
        if (owner != who) return;
        stop();
    }

    static void stop() {
        generation++;
        cancelReopen();
        cancelResolution();
        cancelRenewal();
        main.removeCallbacks(watch);
        listener = null;
        owner = null;
        if (view != null) view.setPlayer(null);
        view = null;
        if (player != null) {
            player.release();
            player = null;
        }
        ExoLiveLog.reset();
        rules.newSelection();
        engineRefreshed = false;
        hlsRetried = false;
        closeRelay();
        url = null;
        owner = null;
        listener = null;
        status = "Stopped";
    }

    private static void closeRelay() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }

    /** Package-private playback evidence for the ExoLiveTest instrumentation suite. */
    static ExoPlayer player() {
        return player;
    }

    static PlayerView picture() {
        return view;
    }

    static boolean retryPending() {
        return pendingReopen != null;
    }

    static boolean active() {
        return player != null && url != null && player.getPlaybackState() != Player.STATE_IDLE;
    }

    static boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    /** Paused by the viewer: the picture is now behind the live broadcast until Back to live. */
    static void pause() {
        if (player == null) return;
        player.pause();
        behindLive = true;
    }

    /** Paused by the viewer (the player is not set to play). */
    static boolean paused() {
        return player != null && !player.getPlayWhenReady();
    }

    /** The picture is behind the live broadcast after a pause, until Back to live or a new channel. */
    static boolean behindLive() {
        return player != null && behindLive;
    }

    /**
     * Catches up with the broadcast: jumps to the live edge when the stream keeps a live window
     * (HLS and DASH live), and returns false when it does not (the owner then reopens the channel).
     */
    static boolean backToLive() {
        if (player == null) return false;
        if (!player.isCurrentMediaItemLive() || !player.isCurrentMediaItemDynamic()) return false;
        behindLive = false;
        event("Back to live");
        player.seekToDefaultPosition();
        player.play();
        return true;
    }

    static void resume() {
        if (player != null) player.play();
    }

    static String currentUrl() {
        return active() ? url : null;
    }

    /**
     * The picture playing, for the information panel: size, frame rate, codec and bitrate, or ""
     * before the first video frame. The frame rate is the one measured on screen when known, as
     * many live streams do not state theirs.
     */
    static String videoDetails() {
        if (player == null) return "";
        Format format = player.getVideoFormat();
        if (format == null) return "";
        // The stream's own rate when it states one; otherwise the rate measured on screen.
        float fps = format.frameRate > 0 ? format.frameRate : Math.round(ExoLiveLog.drawnFps());
        int bitrate = format.bitrate > 0 ? format.bitrate : format.averageBitrate;
        String codec = format.sampleMimeType != null ? format.sampleMimeType : format.codecs;
        return VideoDetails.describe(format.width, format.height, fps, codec, bitrate);
    }

    static String status() {
        String decoder = ExoLiveLog.decoder();
        return status + (decoder.isEmpty() ? "" : " · " + decoder);
    }
}
