package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * Live controls and source discovery while a selected resolver is still pending, on the one-screen
 * Live TV (LiveStage, ExoPlayer). Until the LibVLC live player was removed these ran on its
 * separate player screen; the banner, colour keys and source rules are the same code.
 */
@RunWith(AndroidJUnit4.class)
public final class LiveControlsTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger resolutions = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private MainActivity activity;
    private ChannelDatabase db;
    private boolean oldAutoUpdates;

    private void await(BooleanSupplier condition) {
        await(condition, 10000);
    }

    /** Opening MainActivity on a cold emulator can take longer than a control's reaction. */
    private void await(BooleanSupplier condition, long limit) {
        long deadline = SystemClock.elapsedRealtime() + limit;
        java.util.concurrent.atomic.AtomicBoolean matched = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            remote.runOnMainSync(() -> matched.set(condition.getAsBoolean()));
            if (matched.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        if (matched.get()) return;
        String[] state = { "" };
        remote.runOnMainSync(() -> {
            LiveZapper zapper = activity == null || activity.stage == null ? null : activity.stage.zapper();
            state[0] =
                "full " +
                (activity != null && activity.stage != null && activity.stage.full()) +
                " zapper " +
                (zapper != null) +
                " copies " +
                (zapper == null ? -1 : zapper.copies().size()) +
                " resolutions " +
                resolutions.get() +
                " focus " +
                (activity != null && activity.hasWindowFocus()) +
                " log " +
                ExoLive.eventLog().replace('\n', '|');
        });
        fail("Live controls condition timed out; " + state[0].substring(0, Math.min(700, state[0].length())));
    }

    private LiveZapper zapper() {
        return activity.stage.zapper();
    }

    private boolean dialogOpen() {
        return activity.stage.dialogOpen();
    }

    /** BACK out of full screen returns to the guide page underneath (no separate screen closes). */
    private boolean leftFullScreen() {
        return !activity.stage.full();
    }

    private android.app.Dialog currentPanel() {
        java.util.concurrent.atomic.AtomicReference<android.app.Dialog> panel =
            new java.util.concurrent.atomic.AtomicReference<>();
        remote.runOnMainSync(() -> panel.set(SidePanel.current));
        return panel.get();
    }

    private void physicalColour(int key) {
        // A real down/up pair retains its down time when focus moves to a Dialog.
        // sendKeyDownUpSync constructs the two events independently.
        long down = SystemClock.uptimeMillis();
        remote.sendKeySync(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, key, 0));
        remote.sendKeySync(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0));
        remote.waitForIdleSync();
    }

    @Before
    public void open() throws Exception {
        Context context = remote.getTargetContext();
        // As ExoLiveTest: no update prompt may take the window focus from Live TV.
        oldAutoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        remote.runOnMainSync(ExoLive::stop);
        db = ChannelDatabase.get(context);
        db.clear();
        String[] ids = { "5ad8d3a31b95267e225e4e09", "6304f8d5ffb61000071ab478" };
        String[] providers = { "nativepluto", "free" };
        for (int i = 0; i < ids.length; i++) {
            ChannelDatabase.Writer writer = db.writer(providers[i], () -> {}, n -> {});
            try {
                writer.accept(
                    new Playlist.Channel(
                        "Sky Sports Main Event",
                        PlutoSource.ROOT + ids[i],
                        "Sports",
                        "",
                        "SkySportsMainEvent.uk"
                    )
                );
                writer.commit();
            } finally {
                writer.abort();
            }
        }
        var channel = db.browse("all", "", Collections.emptySet(), 0).get(0);
        assertEquals(2, db.copies(channel).size());
        ExoLive.testResolver = (locator, http) -> {
            resolutions.incrementAndGet();
            release.await(30, TimeUnit.SECONDS);
            throw new IOException("Fixture resolver stopped");
        };
        // Cold/direct live launches have no process-global guide session.
        LiveZapper.session = null;
        String url = db.url(channel.id, channel.key);
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        await(() -> activity.hasWindowFocus(), 30000);
        remote.runOnMainSync(() -> {
            activity.stage.setPage(new android.widget.FrameLayout(activity));
            activity.stage.fullScreen(channel.name, url, channel.key);
        });
        await(() -> zapper() != null && zapper().copies().size() == 2 && resolutions.get() == 1, 30000);
        // Resolver readiness does not imply that Android has finished transferring input focus.
        // Inject physical keys only once this newly launched player owns the window.
        await(() -> activity.hasWindowFocus(), 30000);
    }

    @After
    public void close() {
        remote.runOnMainSync(() -> {
            if (SidePanel.current != null && SidePanel.current.isShowing()) SidePanel.current.dismiss();
            if (activity != null) activity.finish();
            ExoLive.stop();
        });
        release.countDown();
        remote.waitForIdleSync();
        ExoLive.testResolver = null;
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", oldAutoUpdates)
            .commit();
        LiveZapper.session = null;
        if (db != null) db.clear();
    }

    @Test
    public void backRevealsSourcesBeforeLeavingEvenWhenBannerWasAlreadyVisible() {
        var samePlayer = ExoLive.player();
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.waitForIdleSync();
        assertTrue(activity.stage.full());
        assertTrue(zapper().overlay.bannerShowing());
        assertSame(samePlayer, ExoLive.player());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        assertNotNull(SidePanel.current);
        assertTrue("SELECT opens Sources", SidePanel.current.isShowing());
        assertEquals("Opening controls and Sources must not resolve backups", 1, resolutions.get());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        remote.waitForIdleSync();
        await(() -> !dialogOpen() && SidePanel.current == null && activity.hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(this::leftFullScreen);
    }

    @Test
    public void bannerButtonsWorkWithoutColourKeysAndAutoNamesTheProvider() {
        remote.runOnMainSync(() ->
            assertEquals(
                "Auto mode names the provider playing now",
                "Source: Auto · Pluto TV · 1 of 2",
                zapper().sourceText()
            )
        );
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.waitForIdleSync();
        assertEquals(
            "BACK focuses the green Source key",
            SkyOverlay.SOURCE_KEY,
            zapper().overlay.focusedControl()
        );
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        remote.waitForIdleSync();
        assertEquals("▶ moves along to Information", 2, zapper().overlay.focusedControl());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        assertTrue("SELECT presses Information", zapper().overlay.infoShowing());
        remote.runOnMainSync(() -> {
            String picture = zapper().overlay.infoPictureText();
            assertTrue("Information names the picture", picture.startsWith("Picture: "));
            assertTrue("Information names the source", picture.contains("Source: Auto · Pluto TV"));
            assertEquals("Only the four colour keys are buttons", 4, zapper().overlay.controls.size());
        });
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        remote.waitForIdleSync();
        assertEquals("▶ stops at Options, the last button", 3, zapper().overlay.focusedControl());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        assertNotNull(SidePanel.current);
        assertTrue("SELECT presses Options", SidePanel.current.isShowing());
        assertEquals("The buttons must not resolve backups", 1, resolutions.get());
        // Leave as the other tests do: close the panels, then BACK out of the player, so no
        // window from this test is left over the next test's video.
        remote.runOnMainSync(() -> {
            SidePanel.current.dismiss();
            zapper().hideInfo();
        });
        remote.waitForIdleSync();
        await(() -> !dialogOpen() && SidePanel.current == null && activity.hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(this::leftFullScreen);
    }

    @Test
    public void manualChoicePinsOneSourceAndAutomaticCanBeRestoredWithoutEagerProbing() {
        assertTrue(zapper().automaticSources());
        assertEquals("Loading the channel did not probe its backup", 1, resolutions.get());
        remote.runOnMainSync(() -> zapper().useCopy(0, false));
        // ExoPlayer opens the chosen copy afresh, even the one already playing (the LibVLC player
        // carried on): wait for that one lookup, so it is not counted as background discovery.
        await(() -> !zapper().automaticSources() && resolutions.get() == 2);
        remote.runOnMainSync(() -> assertEquals("The chosen copy plays", 0, zapper().selectedCopy()));
        remote.runOnMainSync(() ->
            assertFalse("Manual mode must stay on the selected source", zapper().failover("fixture failure"))
        );
        int before = resolutions.get();
        SystemClock.sleep(500);
        assertEquals("No background backup discovery", before, resolutions.get());
        remote.runOnMainSync(() -> {
            zapper().automaticSources(true);
            assertTrue("Automatic mode can try the next saved copy", zapper().failover("fixture failure"));
        });
        await(() -> zapper().selectedCopy() == 1 && resolutions.get() > before);
        remote.runOnMainSync(() ->
            assertFalse("Automatic mode stops after the last copy", zapper().failover("fixture failure"))
        );
    }

    @Test
    public void automaticRestoredOnTheLastManualSourceTriesEarlierCopiesOnlyOnce() {
        remote.runOnMainSync(() -> zapper().useCopy(1, false));
        await(() -> zapper().selectedCopy() == 1 && resolutions.get() == 2);
        remote.runOnMainSync(() -> {
            zapper().automaticSources(true);
            assertTrue(zapper().failover("fixture failure"));
        });
        await(() -> zapper().selectedCopy() == 0 && resolutions.get() == 3);
        remote.runOnMainSync(() ->
            assertFalse("No automatic loop back to a tried source", zapper().failover("fixture failure"))
        );
    }

    @Test
    public void physicalColourButtonsOpenTheirAdvertisedPlayerActions() {
        physicalColour(KeyEvent.KEYCODE_PROG_RED);
        remote.waitForIdleSync();
        assertTrue("Red opens Audio & subtitles", SidePanel.current != null && SidePanel.current.isShowing());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        remote.waitForIdleSync();
        await(() -> !dialogOpen() && SidePanel.current == null && activity.hasWindowFocus());
        physicalColour(KeyEvent.KEYCODE_PROG_GREEN);
        remote.waitForIdleSync();
        assertTrue("Green opens Sources", SidePanel.current != null && SidePanel.current.isShowing());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        remote.waitForIdleSync();
        await(() -> !dialogOpen() && SidePanel.current == null && activity.hasWindowFocus());
        physicalColour(KeyEvent.KEYCODE_PROG_YELLOW);
        remote.waitForIdleSync();
        assertTrue("Yellow opens information", zapper().overlay.infoShowing());
        physicalColour(KeyEvent.KEYCODE_PROG_BLUE);
        remote.waitForIdleSync();
        assertTrue("Blue opens Options", SidePanel.current != null && SidePanel.current.isShowing());
    }

    @Test
    public void physicalColoursKeepWorkingWhileAnotherPlayerPanelOwnsFocus() {
        physicalColour(KeyEvent.KEYCODE_PROG_RED);
        await(() -> SidePanel.current != null && SidePanel.current.isShowing());
        android.app.Dialog audio = currentPanel();
        await(() -> audio.getWindow().getDecorView().hasWindowFocus());
        physicalColour(KeyEvent.KEYCODE_PROG_GREEN);
        await(() -> SidePanel.current != null && SidePanel.current != audio);
        android.app.Dialog sources = currentPanel();
        assertFalse("Green closes Audio", audio.isShowing());
        await(() -> sources.getWindow().getDecorView().hasWindowFocus());
        physicalColour(KeyEvent.KEYCODE_PROG_BLUE);
        await(() -> SidePanel.current != null && SidePanel.current != sources);
        android.app.Dialog options = currentPanel();
        assertFalse("Blue closes Sources", sources.isShowing());
        await(() -> options.getWindow().getDecorView().hasWindowFocus());
        physicalColour(KeyEvent.KEYCODE_PROG_YELLOW);
        await(() -> SidePanel.current == null && zapper().overlay.infoShowing());
        assertFalse("Yellow closes Options", options.isShowing());
        assertEquals("Colour shortcuts never restart the source", 1, resolutions.get());
    }

    @Test
    public void completeHeaderSurvivesProgrammeUpdatesAndBackControls() {
        remote.runOnMainSync(() -> {
            zapper().overlay.hideBanner();
            zapper().overlay.typing("325");
            zapper().overlay.hideBanner();
            zapper().overlay.banner(
                "325",
                "Pluto TV Movies",
                "Black Knight",
                "Now 12.49pm",
                "The Way Back (2011)",
                "Next 2.39pm",
                "A long programme description that wraps onto two lines while the channel header stays visible.",
                600,
                "Source: Auto · Pluto TV · 1 of 3",
                false
            );
            zapper().overlay.focusSources();
        });
        remote.waitForIdleSync();
        SystemClock.sleep(250);
        remote.runOnMainSync(() -> {
            android.widget.LinearLayout strip = zapper().overlay.channelStrip;
            android.graphics.Rect visible = new android.graphics.Rect();
            assertTrue(strip.getGlobalVisibleRect(visible));
            assertTrue("Full channel strip has reserved height", strip.getHeight() >= activity.stage.dp(64));
            assertEquals("Header is not vertically clipped", strip.getHeight(), visible.height());
            for (int i = 0; i < strip.getChildCount(); i++) {
                android.view.View label = strip.getChildAt(i);
                assertTrue("Each channel/header label is visible", label.getGlobalVisibleRect(visible));
                assertEquals("Header label is not vertically clipped", label.getHeight(), visible.height());
                assertTrue("Header label has width", visible.width() > 0);
            }
        });
        UiEvidence.capture("live-complete-channel-header");
    }

    private boolean containsText(android.view.View view, String expected) {
        if (
            view instanceof android.widget.TextView &&
            expected.contentEquals(((android.widget.TextView) view).getText())
        ) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (
                containsText(group.getChildAt(i), expected)
            ) return true;
        }
        return false;
    }

    private void playerKey(int action, int key, int repeat) {
        playerKey(new KeyEvent(1, 2, action, key, repeat));
    }

    private void playerKey(KeyEvent event) {
        remote.runOnMainSync(() -> {
            if (SidePanel.current != null) SidePanel.current.dispatchKeyEvent(event);
            else activity.dispatchKeyEvent(event);
        });
        remote.waitForIdleSync();
    }

    @Test
    public void colourPressWorksBeforeReleaseAndDoesNotRepeatAcrossWindows() {
        remote.runOnMainSync(() -> zapper().overlay.focusSources());
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 0);
        android.app.Dialog audio = currentPanel();
        assertNotNull("A press opens Audio without waiting for release", audio);
        assertTrue(containsText(audio.getWindow().getDecorView(), "Audio & Subtitles"));
        playerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_RED, 0);
        assertSame("The release must not reopen Audio", audio, SidePanel.current);
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_GREEN, 0);
        android.app.Dialog sources = currentPanel();
        assertNotSame("Green works while a panel owns input", audio, sources);
        assertTrue(containsText(sources.getWindow().getDecorView(), "Sources"));
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_GREEN, 1);
        playerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_GREEN, 0);
        assertSame("Held/released green must not reopen Sources", sources, SidePanel.current);
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_BLUE, 0);
        android.app.Dialog options = currentPanel();
        assertTrue(containsText(options.getWindow().getDecorView(), "Options"));
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_YELLOW, 0);
        assertNull(SidePanel.current);
        assertTrue(zapper().overlay.infoShowing());
        playerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_YELLOW, 0);
        assertTrue("Yellow release must not close Information again", zapper().overlay.infoShowing());
    }

    @Test
    public void standardColoursCannotBeSwappedByOldLearnedAliases() {
        android.content.SharedPreferences prefs = activity.getSharedPreferences("ui", 0);
        java.util.Map<String, ?> previous = prefs.getAll();
        try {
            prefs
                .edit()
                .putInt("remoteRed", KeyEvent.KEYCODE_PROG_GREEN)
                .putInt("remoteGreen", KeyEvent.KEYCODE_PROG_BLUE)
                .putInt("remoteYellow", KeyEvent.KEYCODE_F1)
                .putInt("remoteBlue", KeyEvent.KEYCODE_PROG_YELLOW)
                .commit();
            assertEquals('r', LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_PROG_RED));
            assertEquals('g', LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_PROG_GREEN));
            assertEquals('y', LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_PROG_YELLOW));
            assertEquals('b', LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_PROG_BLUE));
            assertEquals(
                "Nonstandard learned aliases are retained",
                'y',
                LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_F1)
            );
            physicalColour(KeyEvent.KEYCODE_PROG_GREEN);
            await(() -> SidePanel.current != null && SidePanel.current.isShowing());
            remote.runOnMainSync(() ->
                assertTrue(containsText(SidePanel.current.getWindow().getDecorView(), "Sources"))
            );
        } finally {
            android.content.SharedPreferences.Editor edit = prefs.edit();
            for (String key : LiveTvScreen.COLOUR_PREFS) {
                if (previous.containsKey(key)) edit.putInt(key, (Integer) previous.get(key));
                else edit.remove(key);
            }
            edit.commit();
        }
    }

    @Test
    public void bannerKeepsFullHeaderAndColoursWhenOptionalPanelsExceedTheViewport() {
        android.widget.FrameLayout host = new android.widget.FrameLayout(activity);
        SkyOverlay[] overlay = new SkyOverlay[1];
        remote.runOnMainSync(() -> {
            android.view.ViewGroup content = activity.findViewById(android.R.id.content);
            content.addView(host, new android.widget.FrameLayout.LayoutParams(-1, activity.stage.dp(260)));
            overlay[0] = new SkyOverlay(activity.stage, host);
            overlay[0].banner(
                "407",
                "Sky Sports F1 (720p)",
                "No programme information",
                "",
                "",
                "",
                "",
                -1,
                "Source: Auto · FreeShot (UK sports) · 2 of 3",
                false
            );
            overlay[0].info(
                "407 Sky Sports F1",
                "Programme information",
                "19:42",
                "Line one\nLine two\nLine three\nLine four\nLine five\nLine six\nLine seven\nLine eight"
            );
            overlay[0].pause(true, true);
            overlay[0].focusSources();
        });
        try {
            remote.waitForIdleSync();
            SystemClock.sleep(250);
            remote.runOnMainSync(() -> {
                android.graphics.Rect visible = new android.graphics.Rect();
                android.view.View strip = overlay[0].channelStrip;
                assertTrue("Full header remains shown in limited space", strip.getGlobalVisibleRect(visible));
                assertTrue(strip.getHeight() >= activity.stage.dp(64));
                assertEquals(
                    "Header never gives up its height to optional panels",
                    strip.getHeight(),
                    visible.height()
                );
                for (android.view.View control : overlay[0].controls) {
                    assertTrue("Colour buttons remain visible", control.getGlobalVisibleRect(visible));
                    assertEquals(control.getHeight(), visible.height());
                }
            });
            UiEvidence.capture("live-banner-constrained-viewport");
        } finally {
            remote.runOnMainSync(() -> ((android.view.ViewGroup) host.getParent()).removeView(host));
        }
    }

    @Test
    public void entryOkClickAndBackUseTheSameFullBanner() {
        remote.runOnMainSync(() -> {
            assertTrue("Live input focuses the visible video", activity.stage.video().hasFocus());
        });
        remote.waitForIdleSync();
        SystemClock.sleep(250);
        android.graphics.Rect initial = new android.graphics.Rect();
        remote.runOnMainSync(() -> assertTrue(zapper().overlay.channelStrip.getGlobalVisibleRect(initial)));
        // Sky-style: OK brings the banner up with Audio & subtitles highlighted; OK again presses it.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        assertNull("The first OK opens no panel", SidePanel.current);
        assertTrue(zapper().overlay.bannerShowing());
        assertFalse("OK shows only the banner", zapper().overlay.infoShowing());
        remote.runOnMainSync(() -> assertEquals(0, zapper().overlay.focusedControl()));
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        android.app.Dialog pressed = currentPanel();
        assertNotNull("The second OK presses the highlighted button", pressed);
        remote.runOnMainSync(() -> {
            assertTrue(containsText(pressed.getWindow().getDecorView(), "Audio & Subtitles"));
            SidePanel.current.dismiss();
        });
        remote.waitForIdleSync();
        await(() -> SidePanel.current == null && !dialogOpen());
        remote.runOnMainSync(() -> {
            zapper().overlay.hideBanner();
            activity.stage.video().performClick();
            assertTrue("Click uses the same live banner", zapper().overlay.bannerShowing());
            assertFalse("Click matches OK", zapper().overlay.infoShowing());
        });
        remote.waitForIdleSync();
        SystemClock.sleep(250);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            android.graphics.Rect after = new android.graphics.Rect();
            assertTrue(zapper().overlay.channelStrip.getGlobalVisibleRect(after));
            assertEquals("Every reveal retains the complete header layout", initial, after);
            assertEquals(
                "Back only enables focus on the existing controls",
                SkyOverlay.SOURCE_KEY,
                zapper().overlay.focusedControl()
            );
            assertNull(SidePanel.current);
        });
        UiEvidence.capture("live-entry-ok-click-back-one-banner");
    }

    @Test
    public void bannerAlwaysShowsBoxedButtonsAndArrowsMoveBetweenThem() {
        remote.runOnMainSync(() -> {
            zapper().overlay.hideBanner();
            assertTrue("The video keeps the focus", activity.stage.video().hasFocus());
        });
        // ▶ with the banner hidden opens the same banner, with no button highlighted yet.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            assertFalse("No reduced browsing layout", zapper().browsing());
            assertTrue(zapper().overlay.bannerShowing());
            assertEquals(-1, zapper().overlay.focusedControl());
            for (android.view.View control : zapper().overlay.controls)
                assertNotNull("Every button is boxed", control.getBackground());
        });
        // OK with nothing highlighted highlights Audio & subtitles and opens nothing yet.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            assertNull(SidePanel.current);
            assertTrue(zapper().overlay.bannerShowing());
            assertEquals(0, zapper().overlay.focusedControl());
        });
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        android.app.Dialog audio = currentPanel();
        assertNotNull("OK presses the highlighted button", audio);
        remote.runOnMainSync(() ->
            assertTrue(containsText(audio.getWindow().getDecorView(), "Audio & Subtitles"))
        );
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        remote.waitForIdleSync();
        await(() -> SidePanel.current == null && !dialogOpen());
        // Hiding the banner returns the focus to the video, so the next OK cannot press a hidden button.
        remote.runOnMainSync(() -> {
            zapper().overlay.hideBanner();
            assertEquals(-1, zapper().overlay.focusedControl());
        });
    }

    @Test
    public void learnedScanCodeColourWorksInThePlayer() {
        android.content.SharedPreferences prefs = activity.getSharedPreferences("ui", 0);
        java.util.Map<String, ?> previous = prefs.getAll();
        try {
            prefs.edit().putInt("remoteRedScan", 250).commit();
            assertEquals(
                'r',
                LiveTvScreen.colour(
                    prefs,
                    remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 250, 0, 700)
                )
            );
            assertEquals(
                "An unlearned unknown button is not a colour",
                (char) 0,
                LiveTvScreen.colour(
                    prefs,
                    remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 251, 0, 701)
                )
            );
            playerKey(remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 250, 0, 702));
            android.app.Dialog audio = currentPanel();
            assertNotNull("A learned scan code opens red's panel", audio);
            assertTrue(containsText(audio.getWindow().getDecorView(), "Audio & Subtitles"));
            playerKey(remoteEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN, 250, 0, 702));
        } finally {
            android.content.SharedPreferences.Editor edit = prefs.edit();
            for (String key : LiveTvScreen.COLOUR_SCAN_PREFS) {
                if (previous.containsKey(key)) edit.putInt(key, (Integer) previous.get(key));
                else edit.remove(key);
            }
            edit.commit();
        }
    }

    private KeyEvent remoteEvent(int action, int key, int scan, int repeat, long pressTime) {
        return new KeyEvent(
            pressTime,
            pressTime + 1,
            action,
            key,
            repeat,
            0,
            77,
            scan,
            0,
            android.view.InputDevice.SOURCE_DPAD
        );
    }

    @Test
    public void standardColourDeliveryVariantsShareOneActionAcrossWindows() {
        playerKey(remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 398, 2, 100));
        android.app.Dialog audio = currentPanel();
        assertNotNull("First received press need not have repeat count zero", audio);
        assertTrue(containsText(audio.getWindow().getDecorView(), "Audio & Subtitles"));
        playerKey(remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 398, 3, 100));
        playerKey(remoteEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_RED, 398, 0, 100));
        assertSame("A held press and release crossing windows run only once", audio, SidePanel.current);
        playerKey(remoteEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_GREEN, 399, 0, 200));
        android.app.Dialog sources = currentPanel();
        assertTrue(
            "Release-only green still opens Sources",
            containsText(sources.getWindow().getDecorView(), "Sources")
        );
        playerKey(remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 401, 0, 300));
        android.app.Dialog options = currentPanel();
        assertTrue(
            "Android's standard blue scan identity works without translation",
            containsText(options.getWindow().getDecorView(), "Options")
        );
        playerKey(remoteEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN, 401, 0, 300));
        assertSame(options, SidePanel.current);
        playerKey(remoteEvent(KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_PROG_YELLOW, 400, 1, 400));
        assertNull(SidePanel.current);
        assertTrue(zapper().overlay.infoShowing());
        playerKey(remoteEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_YELLOW, 400, 0, 400));
        assertTrue("The final release does not toggle Information off", zapper().overlay.infoShowing());
        android.content.SharedPreferences prefs = activity.getSharedPreferences("ui", 0);
        for (int i = 0; i < 4; i++) assertEquals(
            LiveTvScreen.COLOURS[i],
            LiveTvScreen.colour(
                prefs,
                remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 398 + i, 0, 500 + i)
            )
        );
        assertEquals(
            "A known non-colour key is never remapped using its scan",
            (char) 0,
            LiveTvScreen.colour(
                prefs,
                remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 398, 0, 600)
            )
        );
    }

    @Test
    public void learnedColourWinsOverNumberEntryWithControlsHidden() {
        android.content.SharedPreferences prefs = activity.getSharedPreferences("ui", 0);
        boolean existed = prefs.contains("remoteGreen");
        int previous = prefs.getInt("remoteGreen", -1);
        try {
            prefs.edit().putInt("remoteGreen", KeyEvent.KEYCODE_7).commit();
            remote.runOnMainSync(() -> zapper().overlay.hideBanner());
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_7);
            await(() -> SidePanel.current != null && SidePanel.current.isShowing());
            assertEquals("Colour did not change channel", 0, zapper().position());
            assertEquals("Colour did not resolve another source", 1, resolutions.get());
        } finally {
            if (existed) prefs.edit().putInt("remoteGreen", previous).commit();
            else prefs.edit().remove("remoteGreen").commit();
        }
    }

    @Test
    public void bannerColourButtonsFitWithLongSourceAndChannelNames() {
        remote.runOnMainSync(() ->
            zapper().overlay.banner(
                "401",
                "A very long channel name with region and quality information",
                "Programme on now",
                "Now 12:00",
                "Next programme",
                "Next 13:00",
                "Description",
                500,
                "Source: Auto · A provider with a very long name · 10 of 100",
                false
            )
        );
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            android.graphics.Rect screen = new android.graphics.Rect();
            activity.getWindow().getDecorView().getGlobalVisibleRect(screen);
            for (android.view.View control : zapper().overlay.controls) {
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue("Every colour control is visible", control.getGlobalVisibleRect(visible));
                assertEquals("Control is not clipped horizontally", control.getWidth(), visible.width());
                assertTrue("Control stays inside the display", screen.contains(visible));
            }
        });
    }

    @Test
    public void guideSnapshotSurvivesAndDoesNotShareMutableStaticPosition() {
        LiveZapper.Session original = new LiveZapper.Session(
            "favourites",
            "sports",
            Collections.singleton("fixture-key"),
            4
        );
        android.os.Bundle snapshot = LiveGuideState.save(original);
        original.position = 99;
        LiveZapper.session = null;
        LiveZapper.Session restored = LiveGuideState.restore(snapshot);
        assertEquals("favourites", restored.filter);
        assertEquals("sports", restored.search);
        assertEquals(4, restored.position);
        assertTrue(restored.favourites.contains("fixture-key"));
    }
}
