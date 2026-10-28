package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PosterFeedTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    boolean autoUpdates;
    PosterFeed feed;
    CountDownLatch release = new CountDownLatch(1);

    interface Check {
        boolean ok();
    }

    void await(Check check) throws Exception {
        long end = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicBoolean ok = new AtomicBoolean();
            remote.runOnMainSync(() -> ok.set(check.ok()));
            if (ok.get()) return;
            SystemClock.sleep(80);
        }
        UiEvidence.capture("poster-feed-timeout");
        fail(
            "Timed out waiting for poster feed: items=" +
                (feed == null ? -1 : feed.items.size()) +
                ", attached=" +
                (feed != null && feed.isAttachedToWindow()) +
                ", width=" +
                (feed == null ? -1 : feed.getWidth()) +
                ", height=" +
                (feed == null ? -1 : feed.getHeight()) +
                ", children=" +
                (feed == null ? -1 : feed.getChildCount())
        );
    }

    @Before
    public void launch() {
        Context context = remote.getTargetContext();
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void cleanup() {
        release.countDown();
        remote.runOnMainSync(() -> {
            if (feed != null) feed.close();
            activity.finish();
        });
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", autoUpdates)
            .commit();
    }

    JSONObject page(int number, int size, int pages, String type) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < size; i++) items.put(
            new JSONObject()
                .put("id", (number - 1) * 20 + i + 1)
                .put("media_type", type)
                .put("poster_path", "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg")
                .put(
                    "title",
                    i == 0
                        ? "An extraordinarily long movie and series title that needs to scroll so every word can be read on a television screen"
                        : "Poster " + ((number - 1) * 20 + i + 1)
                )
        );
        return new JSONObject().put("results", items).put("total_pages", pages);
    }

    View label(View node, String value) {
        if (node instanceof TextView && value.contentEquals(((TextView) node).getText())) return node;
        if (node instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) node;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View found = label(parent.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    boolean fullyVisible(View view) {
        if (view == null) return false;
        Rect r = new Rect();
        return view.getGlobalVisibleRect(r) && r.height() >= view.getHeight() && r.width() >= view.getWidth();
    }

    void key(int key) {
        remote.sendKeyDownUpSync(key);
        remote.waitForIdleSync();
    }

    LinearLayout mount(String kind, PosterFeed.Loader loader) throws Exception {
        return mount(kind, loader, true);
    }

    LinearLayout mount(String kind, PosterFeed.Loader loader, boolean wait) throws Exception {
        AtomicReference<LinearLayout> navigation = new AtomicReference<>();
        remote.runOnMainSync(() -> {
            activity.catalogues.browse(
                kind,
                "Top rated",
                kind.equals("Movies") ? "movie/top_rated" : "tv/top_rated",
                1
            );
            LinearLayout right = activity.catalogues.catalogue.content,
                menu = (LinearLayout) ((LinearLayout) right.getParent()).getChildAt(0);
            navigation.set(menu);
            feed = activity.posters.catalogueFeed(
                right,
                right.getChildAt(0),
                menu,
                kind.equals("Movies") ? "movie" : "tv",
                loader
            );
        });
        if (wait) await(
            () -> feed.items.size() >= 20 && feed.getLayoutManager().findViewByPosition(0) != null
        );
        return navigation.get();
    }

    @Test
    public void categoryTabsRouteDownToNearestPosterForMoviesAndSeries() throws Exception {
        for (String kind : new String[] { "Movies", "Series" }) {
            LinearLayout menu = mount(kind, n -> page(n, 20, 1, kind.equals("Movies") ? "movie" : "tv"));
            View category = label(menu, "Browse");
            remote.runOnMainSync(() -> category.requestFocus());
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            remote.runOnMainSync(() -> {
                View focused = activity.getCurrentFocus();
                assertNotNull(focused);
                assertSame("Down enters a poster, not the Back button", feed, focused.getParent());
                Rect source = new Rect();
                category.getGlobalVisibleRect(source);
                Rect chosen = new Rect();
                focused.getGlobalVisibleRect(chosen);
                for (int i = 0; i < feed.getChildCount(); i++) {
                    View card = feed.getChildAt(i);
                    if (feed.getChildAdapterPosition(card) >= feed.items.size()) continue;
                    Rect bounds = new Rect();
                    card.getGlobalVisibleRect(bounds);
                    assertTrue(
                        "Poster is nearest to category horizontal position",
                        Math.abs(chosen.centerX() - source.centerX()) <=
                            Math.abs(bounds.centerX() - source.centerX())
                    );
                }
            });
        }
        screenshot("poster-category-focus");
    }

    @Test
    public void downDuringInitialLoadingEntersPostersWhenReady() throws Exception {
        LinearLayout menu = mount(
            "Movies",
            n -> {
                assertTrue(release.await(15, TimeUnit.SECONDS));
                return page(n, 20, 1, "movie");
            },
            false
        );
        View category = label(menu, "Browse");
        remote.runOnMainSync(() -> category.requestFocus());
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        remote.runOnMainSync(() -> assertSame(category, activity.getCurrentFocus()));
        release.countDown();
        await(() -> activity.getCurrentFocus() != null && activity.getCurrentFocus().getParent() == feed);
    }

    @Test
    public void delayedPagesAppendWithoutMovingRemoteFocusOrScroll() throws Exception {
        AtomicInteger requested = new AtomicInteger();
        mount("Movies", n -> {
            requested.set(n);
            if (n == 2) assertTrue(release.await(15, TimeUnit.SECONDS));
            return page(n, n == 3 ? 7 : 20, 3, "movie");
        });
        remote.runOnMainSync(() -> feed.getLayoutManager().findViewByPosition(0).requestFocus());
        for (int i = 0; i < 4; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> requested.get() == 2);
        await(
            () -> feed.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && !feed.hasPendingAdapterUpdates()
        );
        AtomicReference<View> focused = new AtomicReference<>();
        AtomicInteger offset = new AtomicInteger();
        remote.runOnMainSync(() -> {
            focused.set(activity.getCurrentFocus());
            offset.set(focused.get().getTop());
            assertSame(feed, focused.get().getParent());
        });
        release.countDown();
        await(() -> feed.items.size() >= 40);
        remote.runOnMainSync(() -> {
            assertSame("Append preserves the actual focused card", focused.get(), activity.getCurrentFocus());
            assertEquals("Append preserves scroll position", offset.get(), focused.get().getTop());
            assertNull(label(feed, "Next"));
        });
        for (int i = 0; i < 12; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> feed.items.size() == 47);
        for (int i = 0; i < 8; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> fullyVisible(label(feed, "End of list")));
        remote.runOnMainSync(() -> assertSame(feed, activity.getCurrentFocus().getParent()));
        screenshot("poster-end-of-list");
    }

    @Test
    public void homeShelfContinuesBeyondTwentyAndDockHeadingStaysAboveTiles() throws Exception {
        AtomicReference<Object> shelf = new AtomicReference<>();
        remote.runOnMainSync(() -> {
            HomeScreen.HomeShelf value = activity.homeShelf;
            shelf.set(value);
            value.load("", n -> {
                if (n == 2) assertTrue(release.await(15, TimeUnit.SECONDS));
                return page(n, 20, 2, "movie");
            });
            feed = value.feed;
        });
        await(() -> feed.items.size() == 20 && feed.getLayoutManager().findViewByPosition(0) != null);
        remote.runOnMainSync(() -> feed.getLayoutManager().findViewByPosition(0).requestFocus());
        for (int i = 0; i < 16; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(
            () -> feed.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && !feed.hasPendingAdapterUpdates()
        );
        AtomicReference<View> focus = new AtomicReference<>();
        AtomicInteger offset = new AtomicInteger();
        remote.runOnMainSync(() -> {
            focus.set(activity.getCurrentFocus());
            offset.set(feed.computeHorizontalScrollOffset());
        });
        release.countDown();
        await(() -> feed.items.size() >= 40);
        remote.runOnMainSync(() -> {
            assertSame(focus.get(), activity.getCurrentFocus());
            assertEquals(offset.get(), feed.computeHorizontalScrollOffset());
        });
        for (int i = 0; i < 30; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(() -> fullyVisible(label(feed, "End of list")));
        remote.runOnMainSync(() -> {
            View heading = label(activity.getWindow().getDecorView(), "Your apps"),
                apps = label(activity.getWindow().getDecorView(), "All Apps");
            apps.requestFocus();
            Rect title = new Rect(),
                tile = new Rect();
            heading.getGlobalVisibleRect(title);
            apps.getGlobalVisibleRect(tile);
            assertTrue(title.bottom <= tile.top);
            assertTrue(((View) heading.getParent()).getElevation() > 0);
        });
        screenshot("home-continuous-shelf-and-dock");
    }

    @Test
    public void longTitlesUseTwoLinesThenMarqueeOnlyWhileFocused() throws Exception {
        mount("Series", n -> page(n, 20, 1, "tv"));
        AtomicReference<PosterTitle> title = new AtomicReference<>();
        remote.runOnMainSync(() -> {
            LinearLayout card = (LinearLayout) feed.getLayoutManager().findViewByPosition(0);
            PosterTitle text = (PosterTitle) card.getChildAt(1);
            title.set(text);
            assertEquals(2, text.getMaxLines());
            assertEquals(0, text.getPaddingTop());
            card.requestFocus();
        });
        await(() -> title.get().isSelected());
        remote.runOnMainSync(() -> {
            assertNull("Full focused title is not ellipsized", title.get().getEllipsize());
            assertEquals(1, title.get().getMaxLines());
        });
        SystemClock.sleep(3500);
        Bitmap first = titleCrop(title.get());
        SystemClock.sleep(600);
        Bitmap second = titleCrop(title.get());
        assertFalse("Focused marquee must actually move", first.sameAs(second));
        first.recycle();
        second.recycle();
        screenshot("poster-focused-title");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        remote.runOnMainSync(() -> {
            assertFalse(title.get().isSelected());
            assertEquals(2, title.get().getMaxLines());
        });
    }

    @Test
    public void failedPageRetriesSameNumberAndClosedFeedIgnoresLateResponse() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        mount("Movies", n -> {
            if (n == 2 && attempts.incrementAndGet() == 1) throw new IOException("fixture outage");
            return page(n, 20, 2, "movie");
        });
        await(() -> attempts.get() >= 1);
        SystemClock.sleep(200);
        remote.runOnMainSync(() -> feed.loadMore());
        await(() -> feed.items.size() == 40);
        assertEquals(2, attempts.get());
        mount("Series", n -> {
            if (n == 2) assertTrue(release.await(15, TimeUnit.SECONDS));
            return page(n, 20, 2, "tv");
        });
        PosterFeed old = feed;
        remote.runOnMainSync(() -> {
            old.loadMore();
            old.close();
        });
        release.countDown();
        SystemClock.sleep(300);
        remote.runOnMainSync(() -> assertEquals(20, old.items.size()));
    }

    @Test
    public void completePosterAspectAndFilterOverlayPreserveTheGrid() throws Exception {
        mount("Movies", n -> page(n, 20, 1, "movie"));
        remote.runOnMainSync(() -> {
            LinearLayout card = (LinearLayout) feed.getLayoutManager().findViewByPosition(0);
            ImageView image = (ImageView) card.getChildAt(0);
            assertEquals(ImageView.ScaleType.FIT_CENTER, image.getScaleType());
            assertEquals(Math.round(image.getWidth() * 1.5f), image.getHeight());
            assertTrue(card.getHeight() >= image.getHeight() + card.getChildAt(1).getHeight());
            card.requestFocus();
        });
        remote.runOnMainSync(() -> {
            View filters = label(activity.getWindow().getDecorView(), "Filters");
            assertNotNull(filters);
            filters.performClick();
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            try {
                android.app.Dialog dialog = activity.catalogueDialog;
                assertTrue(dialog.isShowing());
                assertNotNull(label(dialog.getWindow().getDecorView(), "Years & decades"));
                assertTrue(feed.isAttachedToWindow());
                assertEquals(20, feed.items.size());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        screenshot("catalogue-filter-overlay");
        key(KeyEvent.KEYCODE_BACK);
        remote.runOnMainSync(() -> {
            assertTrue(feed.isAttachedToWindow());
            assertEquals(20, feed.items.size());
        });
        screenshot("catalogue-full-posters");
    }

    @Test
    public void restoredCatalogueKeepsLoadedPagesAndFocusedPosterWithoutRefetching() throws Exception {
        mount("Movies", n -> page(n, 20, 2, "movie"));
        await(() -> feed.items.size() == 40);
        remote.runOnMainSync(() -> feed.scrollToPosition(12));
        await(() -> feed.getLayoutManager().findViewByPosition(12) != null);
        AtomicReference<PosterFeed.State> saved = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        remote.runOnMainSync(() -> {
            assertTrue(feed.getLayoutManager().findViewByPosition(12).requestFocus());
            saved.set(feed.snapshot());
            assertNotEquals(Long.MIN_VALUE, saved.get().focused);
            feed.close();
            feed = new PosterFeed(
                activity,
                false,
                "movie",
                Runnable::run,
                n -> {
                    requests.incrementAndGet();
                    return page(n, 20, 2, "movie");
                },
                new PosterFeed.Cards() {
                    public View create(ViewGroup parent) {
                        Button card = new Button(activity);
                        card.setFocusableInTouchMode(true);
                        return card;
                    }

                    public void bind(View card, JSONObject item) {
                        ((Button) card).setText(item.optString("title"));
                    }
                }
            );
            feed.setColumns(6);
            activity.setContentView(feed);
            feed.restore(saved.get());
        });
        await(() -> feed.findFocus() != null);
        remote.runOnMainSync(() -> {
            assertEquals(saved.get().focused, feed.findContainingViewHolder(feed.findFocus()).getItemId());
            assertEquals(40, feed.items.size());
        });
        assertEquals(0, requests.get());
    }

    Bitmap titleCrop(PosterTitle title) {
        Rect bounds = new Rect();
        remote.runOnMainSync(() -> title.getGlobalVisibleRect(bounds));
        Bitmap screen = remote.getUiAutomation().takeScreenshot();
        assertNotNull(screen);
        Bitmap crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height());
        screen.recycle();
        return crop;
    }

    void screenshot(String name) throws Exception {
        SystemClock.sleep(400);
        Bitmap image = remote.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        File directory = new File(remote.getTargetContext().getExternalFilesDir(null), "screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, name + ".png");
        try (OutputStream output = new FileOutputStream(file)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        image.recycle();
        for (String command : new String[] {
            "mkdir -p /sdcard/Download/moviehub-test-screenshots",
            "cp " + file.getAbsolutePath() + " /sdcard/Download/moviehub-test-screenshots/" + name + ".png",
        })
            try (
                android.os.ParcelFileDescriptor fd = remote.getUiAutomation().executeShellCommand(command);
                InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
                ByteArrayOutputStream output = new ByteArrayOutputStream()
            ) {
                int b;
                while ((b = in.read()) != -1) output.write(b);
                assertEquals("Screenshot export failed", "", output.toString("UTF-8").trim());
            }
    }
}
