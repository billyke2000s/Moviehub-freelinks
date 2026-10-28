package local.tv.home;

import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import java.util.concurrent.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Keeps the old VOD stream until a muted replacement has decoded at the resume point. Main thread only. */
final class VodPlayback {

    private final VlcPlaybackActivity activity;
    private final VodSources.Session session;
    final VodSourceFailover queue = new VodSourceFailover();
    private final ExecutorService resolver = new ThreadPoolExecutor(
        2,
        2,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(2)
    );
    private Future<?> resolving;
    private final java.util.Map<Integer, ProviderRelay> relays = new java.util.HashMap<>();
    private MediaPlayer candidate;
    private VLCVideoLayout candidateView;
    private VodSources.Resolved resolved;
    private int ticket, active;
    private boolean preparing, closed, playing, waitingForSources, rejectedShort;
    private long deadline,
        target,
        seekUntil,
        seekTarget = -1,
        lastCatchup;
    /**
     * A jump in a film is not a broken stream: slow websites can take well over five seconds to
     * deliver the new position. Buffering counts as a stall again only once playback has moved this
     * far past the jump's target, or after {@link #SEEK_SETTLE_MAX_MS}.
     */
    static final long SEEK_ADVANCE_MS = 2000,
        SEEK_SETTLE_MAX_MS = 30_000;
    private final Runnable check = this::check;
    final VodQuality quality;

    VodPlayback(VlcPlaybackActivity activity, VodSources.Session session) {
        this.activity = activity;
        this.session = session;
        queue.setSources(session.sources);
        active = session.active;
        queue.select(active, session.pinned);
        quality = new VodQuality(activity, session.sources, queue, session.free);
    }

    boolean automaticSources() {
        return !session.pinned;
    }

    int activeIndex() {
        return active;
    }

    boolean preparing() {
        return preparing;
    }

    boolean hasChoices() {
        queue.appendSources(session.sources);
        return session.sources.size() > 1;
    }

    void onPlaying() {
        queue.onPlaying();
    }

    /** Whether a jump is still settling (see {@link #SEEK_ADVANCE_MS}). */
    private boolean seekingNow() {
        if (activity.dragging || activity.skips.pending()) return true;
        long now = SystemClock.elapsedRealtime();
        if (now >= seekUntil) return false;
        long time = activity.player == null ? -1 : activity.player.getTime();
        if (seekTarget >= 0 && time >= seekTarget + SEEK_ADVANCE_MS) {
            seekUntil = 0;
            return false;
        }
        return true;
    }

    void onBuffering(boolean buffering) {
        queue.setUserPaused(activity.paused);
        queue.setSeeking(seekingNow());
        if (buffering) {
            if (queue.onBufferingStarted(SystemClock.elapsedRealtime())) failover();
        } else queue.onBufferingEnded();
    }

    void tick() {
        if (closed) return;
        queue.appendSources(session.sources);
        boolean seeking = seekingNow();
        queue.setSeeking(seeking);
        queue.setUserPaused(activity.paused);
        quality.tick(
            active,
            !seeking &&
                !activity.paused &&
                !preparing &&
                activity.player != null &&
                activity.player.isPlaying()
        );
        if (seeking || activity.paused) {
            cancelPreparation();
            return;
        }
        rejectShortVideo();
        if (waitingForSources && !preparing) {
            waitingForSources = false;
            if (!failover()) unavailable();
        }
        if (!preparing && queue.isStalled(SystemClock.elapsedRealtime())) failover();
    }

    /** A jump is waiting or starting; {@code target} is where it lands, or -1 while still choosing. */
    void seeking(long target) {
        seekUntil = SystemClock.elapsedRealtime() + SEEK_SETTLE_MAX_MS;
        seekTarget = target;
        queue.setSeeking(true);
        cancelPreparation();
    }

    void pauseChanged() {
        queue.setUserPaused(activity.paused);
        if (activity.paused) cancelPreparation();
    }

    boolean failover() {
        if (closed) return false;
        queue.appendSources(session.sources);
        if (preparing) return true;
        if (queue.failover() == null) {
            if (!session.searching.getAsBoolean()) return false;
            waitingForSources = true;
            activity.status.setText("Still searching for another playable source…");
            activity.status.setVisibility(View.VISIBLE);
            return true;
        }
        waitingForSources = false;
        prepare(queue.currentIndex());
        return true;
    }

    void select(int index) {
        cancelPreparation();
        if (!queue.select(index, !session.free)) return;
        session.pinned = !session.free;
        if (index == active) {
            if (activity.player != null && activity.player.isPlaying()) queue.onPlaying();
            return;
        }
        queue.exclude(active);
        prepare(index);
    }

    void automatic() {
        cancelPreparation();
        session.pinned = false;
        queue.select(active, false);
        if (activity.player != null && activity.player.isPlaying()) queue.onPlaying();
    }

    String sourceLabel() {
        return session.sources.get(active).label;
    }

    long incomingBytes() {
        ProviderRelay relay = relays.get(active);
        return relay == null ? -1 : relay.incomingBytes();
    }

    String providerReport() {
        return session.report.get();
    }

    /** Only known, positive durations are rejected; an unknown duration is not a short film. */
    boolean rejectedMedia() {
        if (!session.free || activity.player == null) return false;
        long duration = activity.player.getLength();
        return rejectedShort || (duration > 0 && duration < 120000);
    }

    boolean rejectShortVideo() {
        return rejectShortVideo(false);
    }

    boolean rejectShortVideo(boolean ended) {
        if (!session.free || activity.player == null) return false;
        long duration = activity.player.getLength();
        if (duration <= 0 && ended) duration = activity.player.getTime();
        if (duration <= 0 || duration >= 120000) return false;
        if (!rejectedShort) {
            rejectedShort = true;
            activity.player.pause();
            if (!failover()) unavailable();
        }
        return true;
    }

    private void unavailable() {
        activity.status.setText("No working backup available. Open Sources to choose another stream.");
        activity.status.setVisibility(View.VISIBLE);
        activity.revealControls();
    }

    float incomingMbps() {
        ProviderRelay relay = relays.get(active);
        return relay == null ? PlayerOverlay.downloadSpeed(activity.player) : relay.incomingMbps();
    }

    String mediaUrl(int index, String link) throws Exception {
        EngineSources.Stream stream = session.transport.apply(index);
        if (stream == null) return link;
        ProviderRelay relay = relays.get(index);
        if (relay == null) {
            relay = new ProviderRelay(stream);
            relays.put(index, relay);
        }
        return relay.url;
    }

    private void prepare(int index) {
        preparing = true;
        playing = false;
        lastCatchup = 0;
        int request = ++ticket;
        target = Math.max(0, activity.playedPosition());
        deadline = SystemClock.elapsedRealtime() + 15000;
        activity.status.setText("Preparing " + session.sources.get(index).label + "…");
        activity.status.setVisibility(View.VISIBLE);
        activity.overlayHandler.postDelayed(check, 100);
        try {
            resolving = resolver.submit(() -> {
                try {
                    VodSources.Resolved next = session.resolve.get(index);
                    String link = Playlist.networkUrl(
                        next.url,
                        activity.getSharedPreferences("ui", 0).getBoolean("http", false)
                    );
                    activity.runOnUiThread(() -> {
                        if (closed || request != ticket || !activity.foreground) return;
                        resolved = new VodSources.Resolved(link, next.files);
                        try {
                            candidateView = new VLCVideoLayout(activity);
                            candidateView.setAlpha(0f);
                            activity.video.addView(candidateView, new FrameLayout.LayoutParams(-1, -1));
                            candidate = new MediaPlayer(activity.filmEngine());
                            // A TextureView allows the prepared picture to stay hidden until the cut, including API 28.
                            candidate.attachViews(candidateView, null, false, true);
                            candidate.setVolume(0);
                            candidate.setRate(activity.playbackRate);
                            if (activity.aspect != null) candidate.setAspectRatio(activity.aspect);
                            candidate.setEventListener(event -> {
                                if (closed || request != ticket || !preparing) return;
                                if (event.type == MediaPlayer.Event.Playing) playing = true;
                                else if (
                                    event.type == MediaPlayer.Event.EncounteredError ||
                                    event.type == MediaPlayer.Event.EndReached
                                ) failed();
                            });
                            Media media = new Media(activity.filmEngine(), Uri.parse(mediaUrl(index, link)));
                            try {
                                media.setHWDecoderEnabled(true, false);
                                if (target > 1000) media.addOption(":start-time=" + target / 1000);
                                candidate.setMedia(media);
                            } finally {
                                media.release();
                            }
                            candidate.play();
                        } catch (Exception error) {
                            failed();
                        }
                    });
                } catch (Exception error) {
                    activity.runOnUiThread(() -> {
                        if (!closed && request == ticket) failed();
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            activity.overlayHandler.post(() -> {
                if (!closed && request == ticket) failed();
            });
        }
    }

    private void check() {
        if (closed || !preparing) return;
        if (!activity.foreground || activity.paused) {
            cancelPreparation();
            return;
        }
        if (
            candidate != null && session.free && candidate.getLength() > 0 && candidate.getLength() < 120000
        ) {
            failed();
            return;
        }
        if (candidate != null && playing && candidate.isPlaying()) {
            IMedia media = candidate.getMedia();
            boolean decoded = false;
            if (media != null) try {
                IMedia.Stats stats = media.getStats();
                decoded = stats != null && stats.decodedVideo > 0;
            } finally {
                media.release();
            }
            long oldTime = Math.max(target, activity.playedPosition());
            long nextTime = candidate.getTime();
            if (decoded && Math.abs(nextTime - oldTime) <= 2000) {
                MediaPlayer ready = candidate;
                VLCVideoLayout view = candidateView;
                String link = resolved.url;
                if (resolved.files != null) SourcesScreen.Binge.files = resolved.files;
                candidate = null;
                candidateView = null;
                activity.adoptVod(ready, view, link);
                ProviderRelay oldRelay = relays.remove(active);
                if (oldRelay != null) oldRelay.close();
                active = queue.currentIndex();
                ProviderRelay selectedRelay = relays.get(active);
                if (selectedRelay != null) selectedRelay.resetIncomingRate();
                session.active = active;
                rejectedShort = false;
                queue.onPlaying();
                // The prepared decoder is now selected and audible; old native shutdown continues off main.
                preparing = false;
                return;
            }
            // The original stream may recover during lookup. Catch the replacement up before cutting.
            if (
                decoded &&
                nextTime < oldTime - 2000 &&
                candidate.isSeekable() &&
                SystemClock.elapsedRealtime() - lastCatchup >= 2000
            ) {
                lastCatchup = SystemClock.elapsedRealtime();
                candidate.setTime(oldTime);
            }
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            failed();
            return;
        }
        activity.overlayHandler.postDelayed(check, 100);
    }

    private void failed() {
        // Invalidate events before scheduling native stop: a stop can itself enqueue EndReached.
        int request = ++ticket;
        preparing = true;
        discardCandidate(() -> {
            if (closed || request != ticket) return;
            preparing = false;
            if (queue.failover() != null) prepare(queue.currentIndex());
            else if (session.searching.getAsBoolean()) waitingForSources = true;
            else unavailable();
        });
    }

    private void discardCandidate() {
        discardCandidate(() -> {});
    }

    private void discardCandidate(Runnable completed) {
        activity.overlayHandler.removeCallbacks(check);
        if (resolving != null) resolving.cancel(true);
        resolving = null;
        MediaPlayer retired = candidate;
        VLCVideoLayout retiredView = candidateView;
        candidate = null;
        candidateView = null;
        resolved = null;
        ProviderRelay unused = queue.currentIndex() == active ? null : relays.remove(queue.currentIndex());
        // Disconnect pending network reads before waiting for VLC to stop.
        if (unused != null) unused.close();
        VodNative.retire(retired, retiredView, activity.filmEngine(), completed);
    }

    private void cancelPreparation() {
        if (!preparing) return;
        ticket++;
        preparing = false;
        discardCandidate();
        queue.select(active, session.pinned);
        if (activity.player != null && activity.player.isPlaying()) queue.onPlaying();
    }

    void close() {
        closed = true;
        waitingForSources = false;
        ticket++;
        preparing = false;
        discardCandidate();
        resolver.shutdownNow();
        for (ProviderRelay relay : relays.values()) relay.close();
        relays.clear();
    }
}
