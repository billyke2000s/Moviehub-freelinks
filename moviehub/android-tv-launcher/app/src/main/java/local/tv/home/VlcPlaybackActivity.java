package local.tv.home;

import android.app.*;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/**
 * Full-screen film and series playback on LibVLC; account and resume storage retain their
 * original identities. Live TV plays on ExoPlayer in {@link LiveStage}, not here.
 */
public class VlcPlaybackActivity extends Activity {

    final Handler overlayHandler = new Handler(Looper.getMainLooper());
    /** Where the picture is drawn. */
    FrameLayout video;
    /** A side panel is open over the picture. */
    boolean dialogOpen;

    private LibVLC engine;
    MediaPlayer player;
    /** The title at the top of the controls (changes with the channel). */
    TextView titleView;
    /** The side panels (tracks, speed, picture, sleep timer). */
    final PlayerMenus menus = new PlayerMenus(this);
    VodPlayback vod;
    private VLCVideoLayout filmVideo;
    private String url, id;
    /** The film or episode this file is (see ResumePoint.titleKey), or null. */
    private String titleKey;
    private long position;
    boolean ended, foreground, dragging;
    /** Paused by the viewer (the player reports pausing a moment later, so this is kept here). */
    boolean paused;
    private int reminderTicks;
    float playbackRate = 1f;
    long sleepAt;
    String aspect;
    final Runnable sleep = () -> {
        Toast.makeText(this, "Sleep timer finished", Toast.LENGTH_SHORT).show();
        finish();
    };
    /** The play/pause control and its icon. */
    View pause;
    ImageView pauseIcon;
    SeekBar timeline;
    TextView timeLeft, status, speed;
    /** Latest download speed in megabits per second, or -1 when unknown. */
    private float downloadMbps = -1;
    private AudioManager audio;
    private AudioFocusRequest audioFocus;
    android.view.View controls;
    /** A second Back within this time leaves the player. */
    static final long EXIT_WINDOW_MS = 3000;
    private long backPressedAt;
    /** Resume point the media was opened at; checked once after playback starts. */
    private long resumeCheck;
    /** Playback has started at least once, so later buffering is a pause while watching. */
    private boolean playedOnce;
    /** A buffering pause is in progress (counted once, not for every progress update). */
    private boolean buffering;
    TextView exitHint;
    private final Runnable hideExitHint = () -> {
        if (exitHint != null) exitHint.setVisibility(View.GONE);
    };
    final Runnable hideOverlay = () -> {
        if (
            controls != null && !dragging && !dialogOpen && player != null && player.isPlaying()
        ) controls.setVisibility(View.GONE);
    };
    private final Runnable stalled = () -> showError("The source is taking too long to respond.");
    /** The furthest position played or jumped to; decides whether an end is a real finish. */
    private long furthestMs;
    /** The resume point the media was opened at (never cleared, unlike {@link #resumeCheck}). */
    private long openedAtMs;

    /**
     * Where playback really is: the player's time, but never more than was played or jumped to.
     * A stream that closed early may report its full length (or nothing) as the time, and resuming
     * from that would open a backup source, Try again or the next visit at the wrong place.
     */
    long playedPosition() {
        long time = player == null ? -1 : player.getTime();
        long reached = Math.max(Math.max(furthestMs, openedAtMs), resumeCheck);
        return PlaybackEnd.playedPosition(time, reached);
    }

    /** Film skips and seek-bar moves waiting to become one jump (see {@link SkipQueue}). */
    final SkipQueue skips = new SkipQueue();
    final Runnable applySkip = () -> {
        long target = skips.take();
        if (target >= 0 && vod != null) vod.seeking(target);
        if (target >= 0) furthestMs = Math.max(furthestMs, target);
        if (target >= 0 && player != null && player.isSeekable()) player.setTime(target);
    };
    private boolean nextPromptShown, nextCancelled;

    private void launchNextEpisode() {
        if (SourcesScreen.Binge.imdb == null || isFinishing()) return;
        android.content.Intent next = new android.content.Intent(this, MainActivity.class);
        next.addFlags(
            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        next.putExtra("bingeNext", true);
        startActivity(next);
        finish();
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (vod != null) vod.tick();
            if (player != null) {
                // While a skip is waiting, show where it will land.
                long length = player.getLength(),
                    time = skips.pending() ? skips.target() : Math.max(0, player.getTime());
                furthestMs = Math.max(furthestMs, player.getTime());
                timeLeft.setText(length > 0 ? PlayerOverlay.timeLeft(Math.max(0, length - time)) : "—");
                if (
                    (vod == null || !vod.rejectedMedia()) &&
                    !nextPromptShown &&
                    length >= PlaybackEnd.MIN_LENGTH_MS &&
                    time > 0 &&
                    length - time <= 10000 &&
                    length - time > 0 &&
                    getIntent().getStringExtra("episodeKey") != null &&
                    SourcesScreen.Binge.imdb != null
                ) {
                    nextPromptShown = true;
                    SidePanel.of(VlcPlaybackActivity.this, "Up next")
                        .message("Ready for the next episode?")
                        .action("Play next episode", () -> launchNextEpisode())
                        .action("Keep watching", () -> nextCancelled = true)
                        .show();
                }
                // A programme reminder that is due shows over the picture (checked every 15 s).
                if (++reminderTicks % 15 == 0 && !dialogOpen) showDueReminder();
                downloadMbps = vod == null ? PlayerOverlay.downloadSpeed(player) : vod.incomingMbps();
                if (downloadMbps >= 0) Timings.lastMbps = downloadMbps;
                String traffic =
                    downloadMbps < 0
                        ? "Receiving stream…"
                        : String.format(Locale.ROOT, "Downloading at %.1f Mb/s", downloadMbps);
                if (vod != null) {
                    long bytes = vod.incomingBytes();
                    traffic =
                        "Source: " +
                        vod.sourceLabel() +
                        "\n" +
                        traffic +
                        (bytes < 0
                            ? ""
                            : String.format(Locale.ROOT, " · %.2f MB received", bytes / 1000000d));
                }
                speed.setText(traffic);
                timeline.setEnabled(player.isSeekable() && length > 0);
                if (!dragging && length > 0) timeline.setProgress(
                    (int) Math.min(1000, (time * 1000) / length)
                );
            }
            if (foreground) overlayHandler.postDelayed(this, 1000);
        }
    };

    int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    void revealControls() {
        if (controls == null) return;
        boolean hidden = controls.getVisibility() != View.VISIBLE;
        controls.setVisibility(View.VISIBLE);
        if (hidden) pause.requestFocus();
        overlayHandler.removeCallbacks(hideOverlay);
        overlayHandler.postDelayed(hideOverlay, 4500);
    }

    private void immersive() {
        getWindow()
            .getDecorView()
            .setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // Handled here, never by Android's default, and acted on when the key is released
            // (as Android does), so a held key counts as one press.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBackKey();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                toggle();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (player != null) player.play();
                paused = false;
                if (vod != null) vod.pauseChanged();
                revealControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (player != null) player.pause();
                paused = true;
                if (vod != null) vod.pauseChanged();
                showPlaying(false);
                revealControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_REWIND) {
                seek(-10000);
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                seek(10000);
                return true;
            }
            if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_CENTER) {
                if (controls.getVisibility() != View.VISIBLE) {
                    revealControls();
                    return true;
                }
                revealControls();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        id = getIntent().getStringExtra("id");
        titleKey = getIntent().getStringExtra("titleKey");
        if (id == null) id = "media";
        try {
            url = Playlist.networkUrl(
                getIntent().getStringExtra("url"),
                getSharedPreferences("ui", MODE_PRIVATE).getBoolean("http", false)
            );
        } catch (Exception e) {
            Toast.makeText(this, "Invalid or insecure playback URL", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        position = state == null ? ResumePoint.load(this, id, titleKey) : state.getLong("position");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new FrameLayout(this);
        filmVideo = new VLCVideoLayout(this);
        video.addView(filmVideo, new FrameLayout.LayoutParams(-1, -1));
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setShadowLayer(dp(6), 0, dp(1), 0xcc000000);
        status.setGravity(Gravity.CENTER);
        status.setText("Opening source…");
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        PlayerOverlay.build(this, root);
        // An air mouse's OK arrives as a click on the picture: same overlay as the OK key.
        root.setOnClickListener(v -> revealControls());
        setContentView(root);
        pause.requestFocus();
        revealControls();
    }

    LibVLC filmEngine() {
        return engine;
    }

    void adoptVod(MediaPlayer ready, VLCVideoLayout layout, String link) {
        MediaPlayer previous = player;
        VLCVideoLayout previousView = filmVideo;
        int volume = previous == null ? 100 : previous.getVolume();
        // Backup preparation mutes native players. Android controls the user volume;
        // never carry a transient native mute into the selected replacement.
        if (volume <= 0) volume = 100;
        player = ready;
        filmVideo = layout;
        url = link;
        getIntent().putExtra("url", link);
        ready.setEventListener(event -> {
            if (player == ready) onPlayerEvent(event);
        });
        if (previous != null) {
            previous.setEventListener(null);
            previous.setVolume(0);
            VodNative.retire(previous, previousView, engine, () -> {});
        }

        layout.setAlpha(1f);
        ready.setVolume(volume);
        position = Math.max(0, ready.getTime());
        furthestMs = Math.max(furthestMs, position);
        resumeCheck = 0;
        buffering = false;
        playedOnce = true;
        overlayHandler.removeCallbacks(stalled);
        status.setVisibility(View.GONE);
        showPlaying(true);
    }

    /** Shows the play or pause icon to match the player. */
    private void showPlaying(boolean playing) {
        if (pauseIcon == null) return;
        pauseIcon.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        pause.setContentDescription(playing ? "Pause" : "Play");
    }

    @Override
    public void onStart() {
        super.onStart();
        foreground = true;
        if (url == null) return;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener(change -> {
                if (change < 0 && player != null) {
                    player.pause();
                    paused = true;
                    if (vod != null) vod.pauseChanged();
                    showPlaying(false);
                    revealControls();
                }
            })
            .build();
        if (audio.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) startPlayer();
        else showError("Audio is in use by another app.");
        overlayHandler.post(tick);
    }

    private void startPlayer() {
        if (!foreground) return;
        status.setBackgroundColor(Color.TRANSPARENT);
        release();
        status.setText("Opening source…");
        status.setVisibility(View.VISIBLE);
        overlayHandler.removeCallbacks(stalled);
        VodSources.Session sources = VodSources.get(getIntent().getStringExtra("vodSession"));
        if (sources != null) vod = new VodPlayback(this, sources);
        armStartupTimeout();
        // Use an owned TextureView for films and prepared replacements. Its buffer queue can
        // survive Activity-window destruction while the native decoder stops off main.
        if (filmVideo != null) video.removeView(filmVideo);
        filmVideo = new VLCVideoLayout(this);
        video.addView(filmVideo, new FrameLayout.LayoutParams(-1, -1));
        engine = new LibVLC(this, new ArrayList<>(PlayerOptions.film(this)));
        player = new MediaPlayer(engine);
        player.setRate(playbackRate);
        if (aspect != null) player.setAspectRatio(aspect);
        player.attachViews(filmVideo, null, false, true);
        MediaPlayer opened = player;
        player.setEventListener(event -> {
            if (player == opened) onPlayerEvent(event);
        });
        String mediaUrl = url;
        try {
            if (vod != null) mediaUrl = vod.mediaUrl(vod.activeIndex(), url);
        } catch (Exception error) {
            showError("Could not prepare this source's stream headers");
            return;
        }
        Media media = new Media(engine, Uri.parse(mediaUrl));
        media.setHWDecoderEnabled(true, false);
        // Open directly at the resume point instead of starting at 0:00 and then seeking, which
        // made the player buffer twice.
        if (position > 1000) {
            media.addOption(":start-time=" + position / 1000);
            resumeCheck = position;
            // Reached on an earlier play: kept when saving, but not proof this copy plays that far.
            openedAtMs = Math.max(openedAtMs, position);
            position = 0;
        }
        player.setMedia(media);
        media.release();
        player.play();
    }

    private void armStartupTimeout() {
        overlayHandler.removeCallbacks(stalled);
        if (!foreground) return;
        status.setText("Opening source…");
        // A film with other sources moves on sooner than one with nowhere else to go.
        overlayHandler.postDelayed(stalled, vod != null ? 15000 : 45000);
    }

    /** The player's events: playing, buffering, the end, and errors (which try the other format). */
    private void onPlayerEvent(MediaPlayer.Event event) {
        if (!foreground || player == null) return;
        if (event.type == MediaPlayer.Event.Playing) {
            Timings.finish("play", "Press play to picture");
            Timings.finish("stall", "Buffering pause while watching");
            playedOnce = true;
            if (vod != null) vod.onPlaying();
            player.setRate(playbackRate);
            if (aspect != null) player.setAspectRatio(aspect);
            overlayHandler.removeCallbacks(stalled);
            status.setVisibility(View.GONE);
            // The media was opened at the resume point (start-time). If this format ignored
            // that, seek once as a fallback.
            long target = resumeCheck;
            resumeCheck = 0;
            if (target > 0) overlayHandler.postDelayed(() -> {
                if (player != null && player.isSeekable() && player.getTime() < target - 5000) {
                    // The jump to the resume point is a seek too, not a stall.
                    if (vod != null) vod.seeking(target);
                    player.setTime(target);
                }
            }, 1500);
            showPlaying(true);
            revealControls();
        } else if (event.type == MediaPlayer.Event.Buffering) {
            if (event.getBuffering() < 100) {
                if (playedOnce) Timings.markOnce("stall");
                if (playedOnce && vod != null && !buffering) vod.onBuffering(true);
                buffering = true;
                status.setText(
                    "Buffering… " +
                        Math.round(event.getBuffering()) +
                        "%" +
                        (downloadMbps < 0 ? "" : String.format(Locale.ROOT, "  ·  %.1f Mb/s", downloadMbps))
                );
                status.setVisibility(View.VISIBLE);
            } else {
                status.setVisibility(View.GONE);
                Timings.finish("stall", "Buffering pause while watching");
                buffering = false;
                if (vod != null) vod.onBuffering(false);
            }
        } else if (event.type == MediaPlayer.Event.EndReached) {
            if (vod != null && vod.rejectShortVideo(true)) return;
            // Only positions seen while playing (or jumped to) count: when a stream closes early the
            // player may report its time as the full length, so the time now is not trusted.
            if (!PlaybackEnd.finished(player.getLength(), furthestMs)) {
                // A broken source: it ended at once, was a short clip, or was cut off part-way.
                // Not watched, and no next episode; try another source instead.
                showError("This source stopped before the end. Try another source or retry the connection.");
                return;
            }
            ended = true;
            ResumePoint.clear(this, id, titleKey);
            String episode = getIntent().getStringExtra("episodeKey");
            if (episode != null && !episode.isEmpty()) {
                android.content.SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
                Set<String> watched = new HashSet<>(
                    prefs.getStringSet("watchedEpisodes", Collections.emptySet())
                );
                watched.add(episode);
                prefs.edit().putStringSet("watchedEpisodes", watched).apply();
            }
            if (
                !nextCancelled &&
                SourcesScreen.Binge.imdb != null &&
                getIntent().getStringExtra("episodeKey") != null
            ) launchNextEpisode();
            else finish();
        } else if (event.type == MediaPlayer.Event.EncounteredError) {
            showError("This source could not be played. Try another source or retry the connection.");
        }
    }

    private void showError(String message) {
        if (!foreground || isFinishing() || dialogOpen) return;
        if (vod != null && vod.failover()) return;
        overlayHandler.removeCallbacks(stalled);
        if (player != null) {
            long played = playedPosition();
            if (played > 0) position = played;
            player.pause();
        }
        showPlaying(false);
        status.setText("Playback unavailable");
        status.setVisibility(View.VISIBLE);
        dialogOpen = true;
        SidePanel.of(this, "Can't play this")
            .message(message)
            .action("Try again", this::startPlayer)
            .action("Back to sources", this::finish)
            .onDismiss(this::panelClosed)
            .show();
    }

    /** A side panel over the video closed: show the controls again. */
    void panelClosed() {
        dialogOpen = false;
        revealControls();
    }

    /**
     * Back on a film first puts the controls away, then asks "Press Back again", and only leaves
     * on another press within {@link #EXIT_WINDOW_MS}, so a stray press does not stop the film.
     */
    private void onBackKey() {
        long now = SystemClock.elapsedRealtime();
        if (ended || player == null || now - backPressedAt < EXIT_WINDOW_MS) {
            finish();
            return;
        }
        if (controls != null && controls.getVisibility() == View.VISIBLE) {
            overlayHandler.removeCallbacks(hideOverlay);
            controls.setVisibility(View.GONE);
            return;
        }
        backPressedAt = now;
        showExitHint();
    }

    private void showExitHint() {
        exitHint.setVisibility(View.VISIBLE);
        overlayHandler.removeCallbacks(hideExitHint);
        overlayHandler.postDelayed(hideExitHint, EXIT_WINDOW_MS);
    }

    void toggle() {
        revealControls();
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            paused = true;
            showPlaying(false);
        } else {
            player.play();
            paused = false;
            showPlaying(true);
        }
        if (vod != null) vod.pauseChanged();
        revealControls();
    }

    /** Skips back or forward, combining quick presses into one jump ({@link SkipQueue}). */
    void seek(long delta) {
        revealControls();
        if (player == null || !player.isSeekable()) return;
        long length = player.getLength();
        showSkipTarget(skips.skip(player.getTime(), delta, length), length);
    }

    /** Shows a waiting film jump on the controls and jumps once the presses stop. */
    void showSkipTarget(long target, long length) {
        if (vod != null) vod.seeking(-1);
        if (length > 0) {
            timeline.setProgress((int) Math.min(1000, (target * 1000) / length));
            timeLeft.setText(PlayerOverlay.timeLeft(Math.max(0, length - target)));
        }
        overlayHandler.removeCallbacks(applySkip);
        overlayHandler.postDelayed(applySkip, SkipQueue.DELAY_MS);
    }

    /** "Watch now" on a reminder leaves for the guide, which opens the channel. */
    private void showDueReminder() {
        Reminders.show(this, reminder -> {
            Reminders.pending = reminder;
            finish();
        });
    }

    private void release() {
        if (vod != null) {
            vod.close();
            vod = null;
        }
        skips.clear();
        overlayHandler.removeCallbacks(applySkip);
        if (player != null) {
            MediaPlayer retired = player;
            player = null;
            VodNative.retire(retired, filmVideo, engine, () -> {});
            filmVideo = null;
        }
        if (engine != null) {
            engine.release();
            engine = null;
        }
    }

    @Override
    public void onStop() {
        overlayHandler.removeCallbacksAndMessages(null);
        sleepAt = 0;
        foreground = false;
        if (player != null) {
            // Never more than was played or jumped to (see playedPosition). Before playback starts
            // the time is 0, and the resume point the media opened at is kept.
            long played = playedPosition();
            if (played > 0) position = played;
            if (vod == null || !vod.rejectedMedia()) {
                long length = player.getLength();
                boolean completed = ended || PlaybackEnd.watchedWhenLeaving(position, length);
                ResumePoint.save(this, id, titleKey, position, length, completed);
                if (completed) {
                    String episode = getIntent().getStringExtra("episodeKey");
                    if (episode != null && !episode.isEmpty()) {
                        android.content.SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
                        Set<String> watched = new HashSet<>(
                            prefs.getStringSet("watchedEpisodes", Collections.emptySet())
                        );
                        watched.add(episode);
                        prefs.edit().putStringSet("watchedEpisodes", watched).apply();
                    }
                }
            }
        }
        release();
        if (audio != null && audioFocus != null) audio.abandonAudioFocusRequest(audioFocus);
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("position", Math.max(position, playedPosition()));
    }

    @Override
    public void onDestroy() {
        SidePanel.closeFor(this);
        overlayHandler.removeCallbacksAndMessages(null);
        release();
        if (isFinishing()) VodSources.forget(getIntent().getStringExtra("vodSession"));
        super.onDestroy();
    }
}
