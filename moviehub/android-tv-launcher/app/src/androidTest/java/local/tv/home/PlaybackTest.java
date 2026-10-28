package local.tv.home;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** Exercises the real LibVLC engine using generated video served on device loopback. */
@RunWith(AndroidJUnit4.class)
public class PlaybackTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private byte[] transportStream;
    private byte[] movie;
    private VlcPlaybackActivity activity;
    private ServerSocket server;
    private volatile boolean closed;
    /** Requests for broken fixture streams, so tests can see that no source is retried in a loop. */
    private final java.util.concurrent.atomic.AtomicInteger missingRequests =
        new java.util.concurrent.atomic.AtomicInteger();
    private boolean oldHttp;

    private MediaPlayer player() {
        return activity.player;
    }

    private void await(BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        }
        assertTrue("Playback condition timed out", condition.getAsBoolean());
    }

    /** A player control by its description (the controls are icons, not text buttons). */
    private View find(View view, String label) {
        if (
            view.isClickable() && label.contentEquals(String.valueOf(view.getContentDescription()))
        ) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void click(String label) {
        instrumentation.runOnMainSync(() -> {
            View button = find(activity.getWindow().getDecorView(), label);
            assertNotNull(label, button);
            button.performClick();
        });
    }

    @Before
    public void prepare() throws Exception {
        Context context = instrumentation.getTargetContext();
        oldHttp = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", true).commit();
        byte[] data;
        try (
            InputStream input = instrumentation.getContext().getAssets().open("playback.mp4");
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            data = output.toByteArray();
        }
        try (
            InputStream input = instrumentation.getContext().getAssets().open("playback.ts");
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            transportStream = output.toByteArray();
        }
        try (
            InputStream input = instrumentation.getContext().getAssets().open("vod.mp4");
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            movie = output.toByteArray();
        }
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    new Thread(() -> serve(socket, data), "fixture-response").start();
                } catch (IOException ignored) {}
            }
        }, "fixture-server").start();
        Intent intent = new Intent(context, VlcPlaybackActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("url", "http://127.0.0.1:" + server.getLocalPort() + "/playback.mp4")
            .putExtra("id", "playback-test")
            .putExtra("title", "Generated playback test");
        activity = (VlcPlaybackActivity) instrumentation.startActivitySync(intent);
    }

    private void serve(Socket socket, byte[] data) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII)
            );
            String request = reader.readLine(),
                line;
            int start = 0;
            boolean range = false,
                finiteRange = false;
            while ((line = reader.readLine()) != null && !line.isEmpty())
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("range: bytes=")) {
                    String rawRange = line.substring(line.indexOf('=') + 1);
                    finiteRange = rawRange.matches("[0-9]+-[0-9]+");
                    String value = rawRange.split("-", 2)[0];
                    if (!value.isEmpty()) {
                        start = Integer.parseInt(value);
                        range = true;
                    }
                }
            if (request != null && request.contains("/finite-vod.mp4") && !finiteRange) {
                connection
                    .getOutputStream()
                    .write(
                        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII
                        )
                    );
                return;
            }
            if (request != null && request.contains("/missing")) {
                missingRequests.incrementAndGet();
                // A broken channel: nothing there.
                connection
                    .getOutputStream()
                    .write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII
                        )
                    );
                return;
            }
            if (
                request != null && (request.contains("/vod.mp4") || request.contains("/finite-vod.mp4"))
            ) data = movie;
            String mime = "video/mp4";
            if (request != null && request.contains(".m3u8")) {
                data = (
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:31\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:30.0,\n" +
                    "http://127.0.0.1:" +
                    server.getLocalPort() +
                    "/segment.ts" +
                    "\n#EXT-X-ENDLIST\n"
                ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                mime = "application/vnd.apple.mpegurl";
            } else if (request != null && request.contains(".ts")) {
                data = transportStream;
                mime = "video/mp2t";
            }
            start = Math.max(0, Math.min(data.length - 1, start));
            OutputStream output = connection.getOutputStream();
            String headers =
                "HTTP/1.1 " +
                (range ? "206 Partial Content" : "200 OK") +
                "\r\nContent-Type: " +
                mime +
                "\r\nAccept-Ranges: bytes\r\nContent-Length: " +
                (data.length - start) +
                "\r\n" +
                (range
                    ? "Content-Range: bytes " + start + "-" + (data.length - 1) + "/" + data.length + "\r\n"
                    : "") +
                "Connection: close\r\n\r\n";
            output.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (request != null && !request.startsWith("HEAD")) output.write(
                data,
                start,
                data.length - start
            );
            output.flush();
        } catch (Exception ignored) {}
    }

    @Test
    public void playsSeeksAndHonoursMediaKeysAfterControlsHide() throws Exception {
        await(() -> {
            try {
                return player() != null && player().isPlaying() && player().getTime() > 500;
            } catch (Exception e) {
                return false;
            }
        }, 30000);
        MediaPlayer engine = player();
        assertTrue(engine.getLength() > 25000);
        assertTrue(engine.getAudioTracks().length > 0);
        long before = engine.getTime();
        click("+10 s");
        await(() -> engine.getTime() > before + 8000, 5000);
        SystemClock.sleep(5500);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
        await(() -> !engine.isPlaying(), 3000);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY);
        await(engine::isPlaying, 3000);
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        await(
            () ->
                instrumentation
                    .getTargetContext()
                    .getSharedPreferences("positions", 0)
                    .getLong("playback-test", 0) > 0,
            5000
        );
    }

    private void openVod(VodSources.Session session) {
        openVod(session, "/vod.mp4");
    }

    private void openVod(VodSources.Session session, String path) {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        await(activity::isDestroyed, 10000);
        Context context = instrumentation.getTargetContext();
        activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", "http://127.0.0.1:" + server.getLocalPort() + path)
                .putExtra("id", "vod-backup-test")
                .putExtra("vodSession", VodSources.remember(session))
                .putExtra("title", "VOD backup fixture")
        );
        await(
            () ->
                activity.vod != null &&
                player() != null &&
                (path.equals("/playback.mp4") || (player().isPlaying() && player().getTime() > 500)),
            30000
        );
    }

    private java.util.List<VodSourceFailover.Source> vodEntries() {
        return java.util.Arrays.asList(
            new VodSourceFailover.Source("first", "Current", "Premiumize"),
            new VodSourceFailover.Source("broken", "Broken backup", "Premiumize"),
            new VodSourceFailover.Source("third", "Working backup", "Premiumize")
        );
    }

    @Test
    public void playingFilmCorrectsAnOverstatedLabelAndOnlySuggestsAConfirmedBetterSource() throws Exception {
        Context context = instrumentation.getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        prefs.edit().remove("quality_claims.fixture.example").remove(AutoPlayback.QUALITY).commit();
        // The real fixture is 160×90; the website said 1080p.
        VodSourceFailover.Source labelled = new VodSourceFailover.Source(
            "labelled",
            "fixture.example · 1080p",
            "fixture.example"
        );
        labelled.claimed = "1080p";
        labelled.site = "fixture.example";
        // A website's claim alone is never suggested; a size read from the file is.
        VodSourceFailover.Source claimedOnly = new VodSourceFailover.Source("claimed", "other · 4K", "other");
        claimedOnly.claimed = "4K";
        VodSourceFailover.Source confirmed = new VodSourceFailover.Source(
            "confirmed",
            "better · 720p ✓",
            "better"
        );
        confirmed.claimed = "720p";
        confirmed.confirmed = 720;
        VodSources.Session session = new VodSources.Session(
            java.util.Arrays.asList(labelled, claimedOnly, confirmed),
            index -> {
                throw new IOException("No automatic switch expected");
            },
            false
        );
        session.free = true;
        long previous = VodQuality.settleMs;
        VodQuality.settleMs = 1000;
        try {
            openVod(session);
            await(() -> labelled.actual > 0, 15000);
            assertEquals(90, labelled.actual);
            assertEquals("fixture.example · 90p ✓ (said 1080p)", labelled.label);
            assertEquals("1", prefs.getString("quality_claims.fixture.example", ""));
            java.util.concurrent.atomic.AtomicInteger announced =
                new java.util.concurrent.atomic.AtomicInteger();
            await(() -> {
                instrumentation.runOnMainSync(() -> announced.set(activity.vod.quality.announced()));
                return announced.get() == 720;
            }, 5000);
            instrumentation.runOnMainSync(() -> {
                assertEquals("No automatic switch", 0, activity.vod.activeIndex());
                assertTrue(player().isPlaying());
            });
        } finally {
            VodQuality.settleMs = previous;
            prefs.edit().remove("quality_claims.fixture.example").commit();
        }
    }

    @Test
    public void freeVodRelayShowsIncomingTrafficWhileRealVlcPlays() throws Exception {
        ProviderRelay.testConnections = uri ->
            (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getLocalPort() + "/vod.mp4"
            ).openConnection();
        EngineSources.Stream transport = new EngineSources.Stream(
            new org.json.JSONObject()
                .put("url", "https://8.8.8.8/vod.mp4")
                .put("headers", new org.json.JSONObject())
        );
        VodSources.Session session = new VodSources.Session(
            java.util.Collections.singletonList(new VodSourceFailover.Source("free", "Free fixture", "Free")),
            index -> {
                throw new IOException("No backup expected");
            },
            true
        );
        session.transport = index -> transport;
        session.free = true;
        openVod(session);
        java.util.concurrent.atomic.AtomicBoolean labelled = new java.util.concurrent.atomic.AtomicBoolean();
        await(() -> {
            instrumentation.runOnMainSync(() -> {
                activity.revealControls();
                labelled.set(
                    activity.speed.getText().toString().contains("Downloading at ") &&
                        activity.speed.getText().toString().contains("MB received") &&
                        activity.vod.incomingBytes() > 0 &&
                        activity.speed.isShown()
                );
            });
            return labelled.get();
        }, 5000);
        instrumentation.runOnMainSync(() -> {
            assertTrue(activity.vod.incomingMbps() >= 0);
            assertTrue(player().isPlaying());
            assertEquals("No blind source switch", 0, activity.vod.activeIndex());
            player().setTime(8000);
        });
        await(() -> player().getTime() >= 6000, 10000);
    }

    @Test
    public void finiteRangeOnlyHostPlaysAndSeeksThroughTheRealRelay() throws Exception {
        ProviderRelay.testConnections = uri ->
            (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getLocalPort() + "/finite-vod.mp4"
            ).openConnection();
        for (boolean explicit : new boolean[] { true, false }) {
            EngineSources.Stream transport = new EngineSources.Stream(
                new org.json.JSONObject()
                    .put("url", "https://8.8.8.8/film.mkv")
                    .put("headers", new org.json.JSONObject())
                    .put("boundedRanges", explicit)
            );
            if (explicit) assertTrue(
                "Finite-range files require the relay even with no custom headers",
                ProviderRelay.needed(transport)
            );
            VodSources.Session session = new VodSources.Session(
                java.util.Collections.singletonList(
                    new VodSourceFailover.Source("finite", "Finite range host", "Fixture")
                ),
                index -> {
                    throw new IOException("No backup in transport test");
                },
                true
            );
            session.transport = index -> transport;
            session.free = true;
            openVod(session);
            await(() -> player() != null && player().isPlaying() && player().getTime() > 500, 30000);
            long midpoint = player().getLength() / 2;
            assertTrue("The full fixture duration survives the bounded upstream request", midpoint > 10000);
            instrumentation.runOnMainSync(() -> player().setTime(midpoint));
            await(() -> player().isPlaying() && Math.abs(player().getTime() - midpoint) < 3000, 15000);
            long before = player().getTime();
            await(() -> player().getTime() > before + 500, 5000);
            assertTrue("Seeked playback is downloading through the relay", activity.vod.incomingBytes() > 0);
        }
    }

    @Test
    public void vodRepeatedBufferingPreparesBackupsWithoutStoppingCurrentPlayback() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var resolves = new java.util.concurrent.atomic.AtomicInteger();
        openVod(
            new VodSources.Session(
                vodEntries(),
                index -> {
                    resolves.incrementAndGet();
                    if (index == 1) release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return new VodSources.Resolved(
                        "http://127.0.0.1:" +
                            server.getLocalPort() +
                            (index == 1 ? "/missing.mp4" : "/vod.mp4"),
                        null
                    );
                },
                false
            )
        );
        MediaPlayer original = player();
        assertEquals("Alternatives are not resolved at startup", 0, resolves.get());
        instrumentation.runOnMainSync(() -> {
            activity.vod.onBuffering(true);
            activity.vod.onBuffering(false);
            activity.vod.onBuffering(true);
        });
        await(() -> resolves.get() == 1, 5000);
        assertSame("Current player survives backup resolution", original, player());
        long before = original.getTime();
        SystemClock.sleep(1100);
        assertTrue("Current stream keeps advancing", original.getTime() > before);
        release.countDown();
        await(
            () ->
                player() != original &&
                !activity.vod.preparing() &&
                activity.vod.activeIndex() == 2 &&
                player().isPlaying(),
            30000
        );
        assertEquals("Broken backup skipped once", 2, resolves.get());
        assertTrue("Replacement preserves progress", player().getTime() >= before - 2000);
        instrumentation.runOnMainSync(() ->
            assertTrue("Replacement audio is enabled", player().getVolume() > 0)
        );
        instrumentation.runOnMainSync(activity.menus::chooseVodSource);
        assertTrue(
            "In-player sources are available",
            SidePanel.current != null && SidePanel.current.isShowing()
        );
    }

    @Test
    public void vodPauseCancelsPendingReplacementAndRejectsItsLateResult() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        openVod(
            new VodSources.Session(
                vodEntries(),
                index -> {
                    entered.countDown();
                    try {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    return new VodSources.Resolved(
                        "http://127.0.0.1:" + server.getLocalPort() + "/vod.mp4",
                        null
                    );
                },
                false
            )
        );
        MediaPlayer original = player();
        instrumentation.runOnMainSync(() -> activity.vod.failover());
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        instrumentation.runOnMainSync(activity::toggle);
        await(() -> activity.paused && !activity.vod.preparing(), 5000);
        release.countDown();
        SystemClock.sleep(1000);
        assertSame("Late resolution cannot replace paused playback", original, player());
        assertFalse(player().isPlaying());
    }

    @Test
    public void vodExhaustionKeepsTheOriginalAndDoesNotRetryForever() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        openVod(
            new VodSources.Session(
                vodEntries(),
                index -> {
                    attempts.incrementAndGet();
                    throw new IOException("Fixture unavailable");
                },
                true
            )
        );
        MediaPlayer original = player();
        instrumentation.runOnMainSync(() -> activity.vod.failover());
        await(() -> attempts.get() == 2 && !activity.vod.preparing(), 5000);
        assertSame(original, player());
        SystemClock.sleep(1100);
        assertEquals(2, attempts.get());
        assertTrue(original.isPlaying());
    }

    @Test
    public void manualFreeServiceFailureSkipsShortClipAndPlaysAnotherProvider() throws Exception {
        var attempts = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var entries = java.util.Arrays.asList(
            new VodSourceFailover.Source("original", "Original", "one"),
            new VodSourceFailover.Source("missing", "Unavailable", "two"),
            new VodSourceFailover.Source("short", "Error clip", "three"),
            new VodSourceFailover.Source("working", "Working", "four")
        );
        VodSources.Session session = new VodSources.Session(
            entries,
            index -> {
                attempts.add(index);
                String path = index == 1 ? "/missing.mp4" : index == 2 ? "/playback.mp4" : "/vod.mp4";
                return new VodSources.Resolved("http://127.0.0.1:" + server.getLocalPort() + path, null);
            },
            false
        );
        session.free = true;
        openVod(session);
        MediaPlayer original = player();
        instrumentation.runOnMainSync(() -> activity.vod.select(1));
        await(() -> activity.vod.activeIndex() == 3 && !activity.vod.preparing(), 30000);
        instrumentation.waitForIdleSync();
        assertNotSame(original, player());
        assertEquals(java.util.Arrays.asList(1, 2, 3), attempts);
        assertFalse(activity.isFinishing());
        assertTrue(player().isPlaying());
        assertTrue(player().getLength() >= 120000);
    }

    @Test
    public void shortInitialFreeClipWaitsForLateBackupWithoutEndingMovie() throws Exception {
        VodSources.Session session = new VodSources.Session(
            java.util.Collections.singletonList(new VodSourceFailover.Source("short", "Error clip", "one")),
            index -> new VodSources.Resolved("http://127.0.0.1:" + server.getLocalPort() + "/vod.mp4", null),
            false
        );
        session.free = true;
        var searching = new java.util.concurrent.atomic.AtomicBoolean(true);
        session.searching = searching::get;
        openVod(session, "/playback.mp4");
        await(() -> activity.status.getText().toString().contains("Still searching"), 10000);
        assertFalse(activity.isFinishing());
        assertFalse(activity.ended);
        session.sources.add(new VodSourceFailover.Source("late", "Late backup", "two"));
        searching.set(false);
        await(() -> activity.vod.activeIndex() == 1 && !activity.vod.preparing(), 30000);
        assertTrue(player().isPlaying());
        assertTrue(player().getLength() >= 120000);
    }

    @Test
    public void leavingRejectedFreeClipPreservesExistingResumeHistory() throws Exception {
        VodSources.Session session = new VodSources.Session(
            java.util.Collections.singletonList(new VodSourceFailover.Source("short", "Error clip", "one")),
            index -> {
                throw new IOException("No backup");
            },
            false
        );
        session.free = true;
        openVod(session, "/playback.mp4");
        await(() -> activity.vod.rejectedMedia(), 10000);
        var positions = activity.getSharedPreferences("positions", 0);
        positions.edit().putLong("vod-backup-test", 600000).commit();
        instrumentation.runOnMainSync(activity::finish);
        await(activity::isDestroyed, 10000);
        assertEquals(
            "Error clips must not clear the film resume point",
            600000,
            positions.getLong("vod-backup-test", 0)
        );
        positions.edit().remove("vod-backup-test").commit();
    }

    @Test
    public void finishingFilmRetainsDetachedTextureUntilNativeStopCompletes() throws Exception {
        await(() -> player() != null && player().isPlaying() && player().getTime() > 500, 30000);
        MediaPlayer original = player();
        android.graphics.SurfaceTexture[] surface = new android.graphics.SurfaceTexture[1];
        TextureView[] texture = new TextureView[1];
        instrumentation.runOnMainSync(() -> {
            texture[0] = VodSurface.texture(activity.video);
            assertNotNull("The film needs an independently retained texture", texture[0]);
            surface[0] = texture[0].getSurfaceTexture();
            assertNotNull(surface[0]);
        });
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        LiveNative.testStopper = retired -> {
            if (retired == original) {
                entered.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            retired.stop();
            if (retired == original) stopped.set(true);
        };
        try {
            instrumentation.runOnMainSync(activity::finish);
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            await(() -> activity.isDestroyed() && !texture[0].isAttachedToWindow(), 5000);
            assertFalse("The decoder still owns the detached buffer queue", surface[0].isReleased());
            assertFalse("The native join is still deliberately blocked", stopped.get());
            release.countDown();
            await(() -> stopped.get() && surface[0].isReleased(), 10000);
        } finally {
            release.countDown();
            LiveNative.testStopper = null;
        }
    }

    @Test
    public void failedVodNativeStopKeepsControlsResponsiveUntilShutdownCompletes() throws Exception {
        VodSources.Session session = new VodSources.Session(
            vodEntries(),
            index ->
                new VodSources.Resolved(
                    "http://127.0.0.1:" + server.getLocalPort() + (index == 1 ? "/missing.mp4" : "/vod.mp4"),
                    null
                ),
            false
        );
        openVod(session);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        MediaPlayer original = player();
        LiveNative.testStopper = retired -> {
            entered.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            retired.stop();
        };
        try {
            instrumentation.runOnMainSync(() -> activity.vod.select(1));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> {
                activity.revealControls();
                assertSame(original, player());
                assertTrue(activity.controls.isShown());
            });
            long before = original.getTime();
            SystemClock.sleep(1100);
            assertTrue(original.getTime() > before);
            release.countDown();
            await(() -> activity.vod.activeIndex() == 2 && !activity.vod.preparing(), 30000);
            assertTrue(player().isPlaying());
        } finally {
            release.countDown();
            LiveNative.testStopper = null;
        }
    }

    /** Opens a player options dialog, moves down `steps` entries and selects. */
    private void option(Runnable open, int steps) {
        instrumentation.runOnMainSync(open);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(250);
        for (int i = 0; i < steps; i++) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        instrumentation.waitForIdleSync();
    }

    @Test
    public void playbackOptionsApplySpeedPictureAndCancellableSleepTimer() throws Exception {
        await(() -> {
            try {
                return player() != null && player().isPlaying();
            } catch (Exception e) {
                return false;
            }
        }, 30000);
        MediaPlayer engine = player();
        option(activity.menus::chooseSpeed, 1);
        assertEquals(1.25f, engine.getRate(), .01f);
        option(activity.menus::choosePicture, 1);
        assertEquals("16:9", engine.getAspectRatio());
        option(activity.menus::chooseSleep, 1);
        assertTrue(activity.sleepAt > SystemClock.elapsedRealtime());
        option(activity.menus::chooseSleep, 0);
        assertEquals(0, activity.sleepAt);
        UiEvidence.capture("playback-options");
    }

    @Test
    public void anotherSourceOfTheSameFilmCarriesOnWhereItStopped() throws Exception {
        assertEquals("title:movie:603", ResumePoint.titleKey(new org.json.JSONObject().put("id", 603)));
        assertEquals(
            "title:tv:1399:1:2",
            ResumePoint.titleKey(new org.json.JSONObject().put("tmdbId", 1399).put("episodeKey", "1399:1:2"))
        );
        assertNull(
            "A series page is not a film",
            ResumePoint.titleKey(
                new org.json.JSONObject().put("id", 1399).put("first_air_date", "2011-04-17")
            )
        );
        assertNull(ResumePoint.titleKey(null));
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        Context context = instrumentation.getTargetContext();
        android.content.SharedPreferences positions = context.getSharedPreferences("positions", 0);
        // Watched to 0:12 from another source; this file has never been played.
        positions.edit().putLong("title:movie:4242", 12000).commit();
        try {
            activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
                new Intent(context, VlcPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", "http://127.0.0.1:" + server.getLocalPort() + "/playback.mp4")
                    .putExtra("id", "playback-other-source")
                    .putExtra("titleKey", "title:movie:4242")
                    .putExtra("title", "Generated playback test")
            );
            await(() -> {
                try {
                    return player() != null && player().isPlaying() && player().getTime() > 8000;
                } catch (Exception e) {
                    return false;
                }
            }, 30000);
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            positions
                .edit()
                .remove("title:movie:4242")
                .remove("playback-other-source")
                .remove("playback-other-source:duration")
                .commit();
        }
    }

    @Test
    public void backHidesTheControlsThenAsksThenLeaves() throws Exception {
        await(() -> {
            try {
                return player() != null && player().isPlaying();
            } catch (Exception e) {
                return false;
            }
        }, 30000);
        // Keys can only be sent once the player window has input focus.
        await(activity::hasWindowFocus, 5000);
        instrumentation.runOnMainSync(activity::revealControls);
        instrumentation.waitForIdleSync();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        assertFalse("The first Back keeps playing", activity.isFinishing());
        assertEquals("The first Back hides the controls", View.GONE, activity.controls.getVisibility());
        assertEquals("No hint yet", View.GONE, activity.exitHint.getVisibility());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        assertFalse("The second Back still keeps playing", activity.isFinishing());
        assertEquals("The second Back asks", View.VISIBLE, activity.exitHint.getVisibility());
        assertEquals("Asking does not bring the controls back", View.GONE, activity.controls.getVisibility());
        UiEvidence.capture("player-exit-hint");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        assertTrue("Back again leaves", activity.isFinishing());
    }

    @After
    public void cleanup() throws Exception {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        ProviderRelay.testConnections = null;
        closed = true;
        if (server != null) server.close();
        Context context = instrumentation.getTargetContext();
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", oldHttp).commit();
        context
            .getSharedPreferences("positions", 0)
            .edit()
            .remove("playback-test")
            .remove("playback-test:duration")
            .commit();
    }
}
