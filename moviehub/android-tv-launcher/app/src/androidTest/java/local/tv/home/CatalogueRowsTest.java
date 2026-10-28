package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** The Movies/Series rows page, driven with real D-pad events and fixture data (no network). */
@RunWith(AndroidJUnit4.class)
public class CatalogueRowsTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    boolean autoUpdates;
    /** Paths whose first page was requested, in order. */
    final List<String> requested = Collections.synchronizedList(new ArrayList<>());

    interface Check {
        boolean ok();
    }

    void await(String stage, Check check) {
        long end = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicBoolean ok = new AtomicBoolean();
            remote.runOnMainSync(() -> ok.set(check.ok()));
            if (ok.get()) return;
            SystemClock.sleep(80);
        }
        UiEvidence.capture("catalogue-rows-timeout");
        AtomicReference<String> state = new AtomicReference<>();
        remote.runOnMainSync(() -> state.set(describe()));
        fail("Timed out at stage '" + stage + "': " + state.get());
    }

    String describe() {
        View focus = activity.getCurrentFocus();
        CatalogueRows rows = rows();
        return (
            "focus=" +
            (focus == null ? "none" : focus.getContentDescription()) +
            ", focusedRow=" +
            focusedRowIndex() +
            ", scrollY=" +
            (rows == null ? -1 : rows.scroll.getScrollY()) +
            ", requested=" +
            requested
        );
    }

    @Before
    public void launch() {
        Context context = remote.getTargetContext();
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        CatalogueRows.testLoader = path -> n -> {
            if (n == 1) requested.add(path);
            JSONArray items = new JSONArray();
            for (int i = 1; i <= 12; i++) items.put(
                new JSONObject()
                    .put("id", Math.abs(path.hashCode() % 100000) * 100 + i)
                    .put("media_type", "movie")
                    .put("title", path + " #" + i)
                    .put(
                        "overview",
                        i % 2 == 0
                            ? "Short synopsis."
                            : new String(new char[12]).replace(
                                  "\0",
                                  "A much longer synopsis that wraps over several lines. "
                              )
                    )
            );
            return new JSONObject().put("results", items).put("total_pages", 1);
        };
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void cleanup() {
        CatalogueRows.testLoader = null;
        remote.runOnMainSync(() -> activity.finish());
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", autoUpdates)
            .commit();
    }

    CatalogueRows rows() {
        CatalogueScreens.CatalogueScreen screen = activity.catalogues.catalogue;
        return screen == null ? null : screen.rowsView;
    }

    int focusedRowIndex() {
        CatalogueRows rows = rows();
        View focus = activity.getCurrentFocus();
        if (rows == null || focus == null) return -1;
        for (CatalogueRows.Row row : rows.rows)
            for (ViewParent p = focus.getParent(); p != null; p = p.getParent()) if (
                p == row.feed
            ) return row.index;
        return -1;
    }

    /** The focused row's top edge sits at the top of the rows area. */
    boolean pinned(int index) {
        CatalogueRows rows = rows();
        return Math.abs(rows.rows.get(index).view.getTop() - rows.scroll.getScrollY()) <= 2;
    }

    void key(int key) {
        remote.sendKeyDownUpSync(key);
        remote.waitForIdleSync();
    }

    @Test
    public void rowsOpenOnFirstPosterPinTheFocusedRowAndLoadLazily() {
        remote.runOnMainSync(() -> activity.catalogues.rows("Movies"));
        await("first poster focused", () -> focusedRowIndex() == 0);
        remote.runOnMainSync(() -> {
            CatalogueRows rows = rows();
            assertEquals("Trending today", rows.rows.get(0).entry.name);
            assertTrue("Several categories plus genres", rows.rows.size() > 10);
            View card = activity.getCurrentFocus();
            assertTrue("Poster cards have no caption", ((ViewGroup) card).getChildCount() == 1);
            assertTrue(
                "Hero describes the focused title",
                activity.catalogues.catalogue.previewTitle
                    .getText()
                    .toString()
                    .startsWith("trending/movie/day")
            );
        });
        UiEvidence.capture("catalogue-rows-entry");
        int initiallyLoaded = requested.size();
        assertTrue("Only the first rows load on entry, not every genre: " + requested, initiallyLoaded <= 3);

        int[] rowsTop = new int[2];
        remote.runOnMainSync(() -> rows().scroll.getLocationOnScreen(rowsTop));
        for (int i = 0; i < 2; i++) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            remote.runOnMainSync(() -> {
                int[] now = new int[2];
                rows().scroll.getLocationOnScreen(now);
                assertEquals("Posters stay put when the synopsis length changes", rowsTop[1], now[1]);
            });
        }
        remote.runOnMainSync(() ->
            assertEquals(
                "trending/movie/day #3",
                activity.catalogues.catalogue.previewTitle.getText().toString()
            )
        );

        for (int target = 1; target <= 4; target++) {
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            final int expected = target;
            await(
                "row " + expected + " focused and pinned",
                () -> focusedRowIndex() == expected && pinned(expected)
            );
        }
        UiEvidence.capture("catalogue-rows-pinned");
        assertTrue("Rows ahead of the focused one load as you scroll", requested.size() > initiallyLoaded);

        for (int target = 3; target >= 0; target--) {
            key(KeyEvent.KEYCODE_DPAD_UP);
            final int expected = target;
            await("back to row " + expected, () -> focusedRowIndex() == expected && pinned(expected));
        }
        key(KeyEvent.KEYCODE_DPAD_UP);
        await("header reached from the first row", () -> {
            View focus = activity.getCurrentFocus();
            return (
                focus != null &&
                focusedRowIndex() == -1 &&
                focus.getParent() == activity.catalogues.catalogue.controls
            );
        });
    }

    @Test
    public void backFromAGridOpenedFromRowsReturnsToTheSameRow() {
        remote.runOnMainSync(() -> activity.catalogues.rows("Movies"));
        await("first poster focused", () -> focusedRowIndex() == 0);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        await("row 2 focused", () -> focusedRowIndex() == 2 && pinned(2));
        remote.runOnMainSync(() -> activity.catalogues.browse("Movies", "Popular", "movie/popular", 1));
        await(
            "grid shown",
            () ->
                rows() == null &&
                "Popular".contentEquals(activity.catalogues.catalogue.collection.getText()) &&
                activity.catalogues.catalogue.collection.isShown()
        );
        key(KeyEvent.KEYCODE_BACK);
        await("rows restored on row 2", () -> rows() != null && focusedRowIndex() == 2 && pinned(2));
        key(KeyEvent.KEYCODE_BACK);
        await(
            "first Back jumps to the top bar and first row",
            () ->
                activity.catalogues.catalogue != null &&
                activity.getCurrentFocus() != null &&
                activity.getCurrentFocus().getParent() == activity.catalogues.catalogue.controls &&
                rows().scroll.getScrollY() == 0
        );
        key(KeyEvent.KEYCODE_BACK);
        await(
            "second Back goes Home",
            () -> activity.catalogues.catalogue == null && activity.homeShelf != null
        );
    }
}
