package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;

/** The classic-guide Live TV grid with fixture channels: numbering, ten per page, paging by D-pad. */
@RunWith(AndroidJUnit4.class)
public class LiveGuideTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    SharedPreferences prefs;
    String free;
    boolean updates;

    @Before
    public void start() throws Exception {
        Context context = remote.getTargetContext();
        prefs = context.getSharedPreferences("ui", 0);
        free = prefs.getString(FreeChannels.PREF, null);
        updates = prefs.getBoolean("autoUpdates", true);
        prefs.edit().putString(FreeChannels.PREF, "off").putBoolean("autoUpdates", false).commit();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        ChannelDatabase.Writer writer = db.writer("guide-test", () -> {}, n -> {});
        try {
            for (int i = 0; i < 25; i++) writer.accept(
                new Playlist.Channel(
                    String.format(java.util.Locale.ROOT, "Channel %02d", i),
                    "https://example.test/live/" + i + ".m3u8",
                    "News",
                    "",
                    ""
                )
            );
            writer.commit();
        } finally {
            writer.abort();
        }
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void stop() {
        if (activity != null) remote.runOnMainSync(() -> activity.finish());
        SharedPreferences.Editor edit = prefs.edit().putBoolean("autoUpdates", updates);
        if (free == null) edit.remove(FreeChannels.PREF);
        else edit.putString(FreeChannels.PREF, free);
        edit.commit();
        ParentalLock.clear(remote.getTargetContext());
        ChannelDatabase.get(remote.getTargetContext()).clear();
    }

    void await(String what, java.util.function.BooleanSupplier check) {
        long end = SystemClock.uptimeMillis() + 8000;
        AtomicBoolean ok = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < end) {
            remote.runOnMainSync(() -> ok.set(check.getAsBoolean()));
            if (ok.get()) return;
            SystemClock.sleep(80);
        }
        fail("Timed out: " + what);
    }

    String focused() {
        View focus = activity.getCurrentFocus();
        return focus instanceof TextView ? ((TextView) focus).getText().toString() : String.valueOf(focus);
    }

    /** A failed load on the guide floats one small card over it; the grid keeps its full size. */
    @Test
    public void failedLoadOnGuideFloatsSmallCardWithoutShrinkingGrid() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await("ten guide rows", () -> activity.liveTv.cells.size() == 10);
        remote.waitForIdleSync();
        int[] before = new int[2];
        remote.runOnMainSync(() -> {
            before[0] = activity.liveTv.frame.grid.getHeight();
            before[1] = activity.page.getChildCount();
        });
        assertTrue("Grid laid out", before[0] > 0);
        for (int i = 0; i < 2; i++) remote.runOnMainSync(() ->
            activity.async(
                () -> {
                    throw new IllegalStateException("Channel no longer exists");
                },
                result -> {}
            )
        );
        await(
            "floating error card",
            () -> ((ViewGroup) activity.page.getParent()).findViewWithTag(MainActivity.FLOATING_ERROR) != null
        );
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            ViewGroup host = (ViewGroup) activity.page.getParent();
            int cards = 0;
            for (int i = 0; i < host.getChildCount(); i++) if (
                MainActivity.FLOATING_ERROR.equals(host.getChildAt(i).getTag())
            ) cards++;
            assertEquals("One card, not stacked", 1, cards);
            assertEquals("Nothing added to the guide page", before[1], activity.page.getChildCount());
            assertEquals("Grid keeps its height", before[0], activity.liveTv.frame.grid.getHeight());
            View card = host.findViewWithTag(MainActivity.FLOATING_ERROR);
            assertTrue("Card is small", card.getHeight() < host.getHeight() / 4);
        });
        UiEvidence.capture("live-guide-floating-error");
    }

    @Test
    public void labelsFitShortGuideRowsInsteadOfBeingCutInHalf() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await("ten guide rows", () -> activity.liveTv.cells.size() == 10);
        remote.runOnMainSync(() -> {
            LinearLayout grid = activity.liveTv.frame.grid;
            LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) grid.getLayoutParams();
            layout.height = activity.dp(200);
            layout.weight = 0;
            grid.setLayoutParams(layout);
        });
        await("text fits short rows", () -> {
            for (View view : activity.liveTv.cells) {
                TextView label = (TextView) view;
                android.graphics.Paint.FontMetrics fm = label.getPaint().getFontMetrics();
                if (
                    label.getHeight() == 0 ||
                    fm.descent - fm.ascent >
                        label.getHeight() - label.getPaddingTop() - label.getPaddingBottom() + 1
                ) return false;
            }
            return true;
        });
        remote.runOnMainSync(() -> {
            for (java.util.List<TextView> cells : activity.liveTv.rows.programmes)
                for (TextView label : cells) {
                    android.graphics.Paint.FontMetrics fm = label.getPaint().getFontMetrics();
                    assertTrue(
                        "Programme clipped",
                        fm.descent - fm.ascent <=
                            label.getHeight() - label.getPaddingTop() - label.getPaddingBottom() + 1
                    );
                }
        });
        UiEvidence.capture("live-guide-short-rows");
    }

    @Test
    public void tenChannelsPerPageNumberedFrom101AndPagedWithTheRemote() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await(
            "first page",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        UiEvidence.capture("live-guide");
        // Down past the bottom row: the next page.
        for (int i = 0; i < 10; i++) {
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            remote.waitForIdleSync();
        }
        await(
            "second page",
            () -> activity.liveTv.pageStart == 10 && focused().startsWith("111  Channel 10")
        );
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_GREEN); // green: page down
        await("page down", () -> activity.liveTv.pageStart == 20 && activity.liveTv.cells.size() == 5);
        // The whole list's size sits on the title line, not just this page's rows.
        remote.runOnMainSync(() ->
            assertEquals("ALL CHANNELS   25 channels", activity.liveTv.frame.titleText())
        );
        assertEquals("1 channel", GuideFrame.titleCount(1));
        assertEquals("11,000 channels", GuideFrame.titleCount(11000));
        await("on the first row of the last page", () -> focused().startsWith("121  Channel 20"));
        // Up from the top row turns back a page and lands on its last row.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await(
            "back up to the previous page, last row",
            () -> activity.liveTv.pageStart == 10 && focused().startsWith("120  Channel 19")
        );
    }

    @Test
    public void okJumpsToATypedNumberAtOnceWithoutOpeningTheHighlightedChannel() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await(
            "first page",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        long started = SystemClock.uptimeMillis();
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_1);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_1);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_2);
        remote.runOnMainSync(() -> {
            assertEquals("112", activity.liveTv.typedNumber());
            assertEquals("The guide draws the number as typed", "112–", activity.liveTv.frame.numberShown());
        });
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("OK jumps to 112", () -> focused().startsWith("112  Channel 11"));
        assertTrue(
            "OK used the number before the digit wait ran out",
            SystemClock.uptimeMillis() - started < LiveTvScreen.TYPE_MS + 1500
        );
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            assertEquals("", activity.liveTv.typedNumber());
            assertEquals(
                "The number box goes once the guide has jumped",
                "",
                activity.liveTv.frame.numberShown()
            );
            assertTrue("The OK did not also open the highlighted channel", activity.hasWindowFocus());
        });
    }

    @Test
    public void typedNumbersProgrammeInfoAndTheParentalLock() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await(
            "first page",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        // Typing a number goes straight to that channel, as on a satellite box.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_1);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_2);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_3);
        await("typed 123", () -> activity.liveTv.pageStart == 20 && focused().startsWith("123  Channel 22"));
        remote.waitForIdleSync();
        // Right moves onto the programmes, keeping the channel names on the left.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        await("on the programme", () -> activity.liveTv.rows.programmes.get(2).get(0).hasFocus());
        // Right again, past the last slot, moves the times on half an hour (same row).
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(
            "times moved on",
            () ->
                activity.liveTv.windowOffset == LiveTvScreen.HALF_HOUR &&
                activity.liveTv.rows.programmes.size() == 5 &&
                activity.liveTv.rows.programmes.get(2).get(0).hasFocus()
        );
        UiEvidence.capture("live-guide-later");
        // Left goes back to now, then onto the channel name.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        await(
            "back to now",
            () ->
                activity.liveTv.windowOffset == 0 &&
                activity.liveTv.rows.programmes.size() == 5 &&
                activity.liveTv.rows.programmes.get(2).get(0).hasFocus()
        );
        remote.waitForIdleSync();
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        await("on the channel name", () -> focused().startsWith("123  Channel 22"));
        // i shows the programme information.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO);
        await("programme information", () -> SidePanel.current != null && SidePanel.current.isShowing());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        // Options → Tomorrow: the same time tomorrow; Back to today returns.
        remote.runOnMainSync(() -> activity.liveTv.nextDay(2));
        await(
            "tomorrow",
            () -> activity.liveTv.windowOffset == LiveTvScreen.MAX_OFFSET && activity.liveTv.cells.size() == 5
        );
        UiEvidence.capture("live-guide-tomorrow");
        remote.runOnMainSync(() -> activity.liveTv.previousDay(2));
        await("today", () -> activity.liveTv.windowOffset == 0 && activity.liveTv.cells.size() == 5);
        // A locked channel asks for the PIN instead of playing.
        ChannelDatabase.Row row = ChannelDatabase.get(activity)
            .browse("all", "", java.util.Collections.emptySet(), 22)
            .get(0);
        ParentalLock.setPin(activity, "1234");
        ParentalLock.setChannel(activity, row, true);
        await("guide still showing", () -> activity.liveTv.cells.size() == 5);
        remote.runOnMainSync(() -> activity.liveTv.cells.get(2).performClick());
        await("PIN asked", () -> SidePanel.current != null && SidePanel.current.isShowing());
        UiEvidence.capture("live-pin");
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
    }

    @Test
    public void optionsAboveTheGridAndTheGuideMenuBehindBack() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await(
            "first page",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        UiEvidence.capture("live-guide-classic");
        // Up from the top row: the Options button; down again: the first row.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await("on Options", () -> activity.liveTv.frame.optionsButton.hasFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        await("first row", () -> focused().startsWith("101  Channel 00"));
        // BACK: the guide menu; 1 opens All Channels again.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(
            "guide menu",
            () -> activity.liveTv.menu.rows.size() == 10 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
        UiEvidence.capture("live-guide-menu");
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_1);
        await(
            "all channels from the menu",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        // Blue: the sections (the guide menu), as on the satellite guide.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_BLUE);
        await(
            "sections",
            () -> activity.liveTv.menu.rows.size() == 10 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
    }

    /** Every row of a guide menu is on screen in full (none cut off at the bottom). */
    void assertAllRowsOnScreen(int count) {
        assertEquals(count, activity.liveTv.menu.rows.size());
        for (View row : activity.liveTv.menu.rows) {
            android.graphics.Rect visible = new android.graphics.Rect();
            assertTrue("menu row visible", row.getGlobalVisibleRect(visible));
            assertTrue("menu row cut off", row.getHeight() > 0 && visible.height() >= row.getHeight());
        }
    }

    @Test
    public void liveTvOpensTheGuideMenuWithEveryRowAndTheTopPartsWorking() {
        // Live TV from Home: the guide menu first, like the TV Guide button.
        remote.runOnMainSync(() -> activity.liveTv.start());
        await(
            "guide menu",
            () -> activity.liveTv.menu.rows.size() == 10 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
        remote.waitForIdleSync();
        UiEvidence.capture("live-guide-menu-first");
        remote.runOnMainSync(() -> assertAllRowsOnScreen(10));
        // Down to the last row (0 More…) and select: the second page, all on screen too.
        for (int i = 0; i < 9; i++) remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        await("on More…", () -> activity.liveTv.menu.rows.get(9).hasFocus());
        // Down from the last row goes round to the top, as on the satellite box.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        await("round to the top", () -> activity.liveTv.menu.rows.get(0).hasFocus());
        remote.runOnMainSync(() -> activity.liveTv.menu.rows.get(9).requestFocus());
        await("back on More…", () -> activity.liveTv.menu.rows.get(9).hasFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(
            "more",
            () -> activity.liveTv.menu.rows.size() == 6 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> assertAllRowsOnScreen(6));
        // BACK: the main menu again; 9 opens Music in the grid.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("main menu", () -> activity.liveTv.menu.rows.size() == 10);
        // The satellite box's order: 8 is Music, 9 Radio.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_8);
        await("music", () -> activity.liveTv.filter.equals("section:Music"));
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(
            "menu after music",
            () -> activity.liveTv.menu.rows.size() == 10 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
        // Up from the first row: the parts along the top; right to Reminders, which opens.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await("top parts", () -> activity.liveTv.menu.tabs.get(0).hasFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        await("reminders part", () -> activity.liveTv.menu.tabs.get(1).hasFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("reminders open", () -> SidePanel.current != null && SidePanel.current.isShowing());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        // Search opens its form (a dialog over the menu, so the menu loses the window focus).
        await("menu focused again", () -> activity.hasWindowFocus());
        remote.runOnMainSync(() -> activity.liveTv.menu.tabs.get(2).requestFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("search open", () -> !activity.hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("search closed", () -> activity.hasWindowFocus());
    }

    @Test
    public void pagesWrapRoundLikeTheSatelliteGuide() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await(
            "first page",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        for (int round = 0; round < 4; round++) {
            // Paging must not depend on a channel cell retaining focus during row replacement.
            remote.runOnMainSync(() -> activity.liveTv.frame.focusTop());
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_UP);
            await(
                "last page from header",
                () -> activity.liveTv.pageStart == 20 && activity.liveTv.cells.size() == 5
            );
            remote.runOnMainSync(() -> activity.liveTv.frame.focusTop());
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN);
            await(
                "first page again from header",
                () -> activity.liveTv.pageStart == 0 && activity.liveTv.cells.size() == 10
            );
        }
    }

    @Test
    public void theGuideMenuFollowsTheSatelliteBoxOrderOverTwoPages() {
        remote.runOnMainSync(() -> activity.liveTv.start());
        await("menu", () -> activity.liveTv.menu.rows.size() == 10);
        String[][] first = GuideMenu.pageItems(0),
            second = GuideMenu.pageItems(1);
        assertEquals("All Channels", first[0][0]);
        assertEquals("Entertainment", first[1][0]);
        assertEquals("Music", first[7][0]);
        assertEquals("Radio", first[8][0]);
        assertEquals("More…", first[9][0]);
        assertEquals("Favourites", second[0][0]);
        assertEquals("Options", second[second.length - 1][0]);
        // 0 is More…; BACK goes up a page.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_0);
        await("second page", () -> activity.liveTv.menu.page == 1 && activity.liveTv.menu.rows.size() == 6);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("first page", () -> activity.liveTv.menu.page == 0 && activity.liveTv.menu.rows.size() == 10);
    }

    @Test
    public void physicalColoursWorkFromTheHeaderAndBackRevealsTheSameActions() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await("first page", () -> activity.liveTv.cells.size() == 10);
        remote.runOnMainSync(() -> activity.liveTv.frame.optionsButton.requestFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_GREEN);
        await(
            "green pages from header",
            () -> activity.liveTv.pageStart == 10 && activity.liveTv.cells.size() == 10
        );
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("back focuses colour row", () -> activity.liveTv.frame.bar.focused() == 0);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(
            "OK presses red page up",
            () -> activity.liveTv.pageStart == 0 && activity.liveTv.cells.size() == 10
        );
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_F2);
        await("F2 also pages down", () -> activity.liveTv.pageStart == 10);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_RED);
        await("red pages up", () -> activity.liveTv.pageStart == 0);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_BLUE);
        await("blue opens sections", () -> activity.liveTv.menu.rows.get(0).hasFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_GREEN);
        await("green opens reminders", () -> SidePanel.current != null && SidePanel.current.isShowing());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
    }
}
