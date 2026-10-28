package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ArtworkTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    Artwork art;
    PosterFeed feed;
    ExecutorService worker = Executors.newSingleThreadExecutor();
    boolean updates;
    CountDownLatch release = new CountDownLatch(1);
    String prefix = "fixture:" + UUID.randomUUID() + ":";
    /** Focus changes recorded during the test, reported when a wait times out. */
    final List<String> focusLog = Collections.synchronizedList(new ArrayList<>());
    volatile String phase = "setup";

    interface Check {
        boolean ready();
    }

    void await(Check check) {
        await("condition", check);
    }

    /** Waits up to 12 seconds; on timeout the message names the stage and lists unready posters. */
    void await(String stage, Check check) {
        long end = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicBoolean ready = new AtomicBoolean();
            remote.runOnMainSync(() -> ready.set(check.ready()));
            if (ready.get()) return;
            SystemClock.sleep(50);
        }
        UiEvidence.capture("artwork-timeout");
        AtomicReference<String> posters = new AtomicReference<>("");
        remote.runOnMainSync(() -> posters.set(describePosters()));
        fail(
            "Artwork did not become ready at stage '" +
                stage +
                "': focused=" +
                activity.hasWindowFocus() +
                ", currentFocus=" +
                (activity.getCurrentFocus() == null
                    ? "none"
                    : activity.getCurrentFocus().getContentDescription()) +
                ", items=" +
                (feed == null ? -1 : feed.items.size()) +
                ", scrollState=" +
                (feed == null ? -1 : feed.getScrollState()) +
                ", width=" +
                (feed == null ? -1 : feed.getWidth()) +
                ", height=" +
                (feed == null ? -1 : feed.getHeight()) +
                ", children=" +
                (feed == null ? -1 : feed.getChildCount()) +
                posters.get() +
                "; recent focus changes: " +
                recentFocusChanges()
        );
    }

    String recentFocusChanges() {
        synchronized (focusLog) {
            int from = Math.max(0, focusLog.size() - 12);
            return focusLog.subList(from, focusLog.size()).toString();
        }
    }

    String focusName(View view) {
        if (view == null) return "none";
        CharSequence description = view.getContentDescription();
        return description != null ? description.toString() : view.getClass().getSimpleName();
    }

    /** Lists visible poster cards that do not show their expected image. */
    String describePosters() {
        if (feed == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < feed.getChildCount(); i++) {
            View card = feed.getChildAt(i);
            int position = feed.getChildAdapterPosition(card);
            if (!(card instanceof ImageView) || position < 0 || position >= 60) continue;
            ImageView image = (ImageView) card;
            if (colour(image, 0xff000000 | (position + 1))) continue;
            String shown = "none";
            if (image.getDrawable() instanceof BitmapDrawable) shown = Integer.toHexString(
                ((BitmapDrawable) image.getDrawable()).getBitmap().getPixel(0, 0) & 0xffffff
            );
            out.append("; unready position=")
                .append(position)
                .append(" description=")
                .append(card.getContentDescription())
                .append(" tag=")
                .append(card.getTag())
                .append(" shown=")
                .append(shown)
                .append(" attached=")
                .append(card.isAttachedToWindow());
        }
        return out.toString();
    }

    Bitmap bitmap(int colour) {
        Bitmap image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        image.eraseColor(colour);
        return image;
    }

    boolean colour(ImageView image, int colour) {
        return (
            image.getDrawable() instanceof BitmapDrawable &&
            ((BitmapDrawable) image.getDrawable()).getBitmap().getPixel(0, 0) == colour
        );
    }

    @Before
    public void launch() {
        Context context = remote.getTargetContext();
        updates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
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
            if (art != null) art.close();
            activity.finish();
        });
        worker.shutdownNow();
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", updates)
            .commit();
    }

    @Test
    public void offscreenBindingLoadsOnAttachmentAndLateResultCannotReplaceReboundImage() {
        AtomicReference<ImageView> image = new AtomicReference<>();
        AtomicReference<FrameLayout> host = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        remote.runOnMainSync(() -> {
            art = new Artwork(url -> {
                calls.incrementAndGet();
                if (url.endsWith("old")) {
                    assertTrue(release.await(12, TimeUnit.SECONDS));
                    return bitmap(Color.RED);
                }
                return bitmap(Color.GREEN);
            });
            FrameLayout root = new FrameLayout(activity);
            host.set(root);
            activity.setContentView(root);
            ImageView view = new ImageView(activity);
            image.set(view);
            art.load(view, prefix + "old");
        });
        SystemClock.sleep(150);
        assertEquals("Prefetched detached views must not consume downloads", 0, calls.get());
        remote.runOnMainSync(() -> host.get().addView(image.get(), new FrameLayout.LayoutParams(100, 100)));
        await(() -> calls.get() == 1);
        remote.runOnMainSync(() -> art.load(image.get(), prefix + "new"));
        await(() -> colour(image.get(), Color.GREEN));
        release.countDown();
        SystemClock.sleep(200);
        remote.runOnMainSync(() ->
            assertTrue("Stale artwork must not overwrite a recycled card", colour(image.get(), Color.GREEN))
        );
    }

    @Test
    public void visibleRequestsBeyondOldQueueLimitAreNotDiscardedAndFailuresRetry() {
        ArrayList<ImageView> images = new ArrayList<>();
        AtomicInteger retry = new AtomicInteger();
        remote.runOnMainSync(() -> {
            art = new Artwork(url -> {
                if (url.endsWith("retry") && retry.incrementAndGet() == 1) throw new IOException(
                    "temporary artwork failure"
                );
                return bitmap(Color.BLUE);
            });
            FrameLayout root = new FrameLayout(activity);
            activity.setContentView(root);
            for (int i = 0; i < 80; i++) {
                ImageView image = new ImageView(activity);
                images.add(image);
                art.load(image, prefix + i);
                root.addView(image, new FrameLayout.LayoutParams(10, 10));
            }
            ImageView image = new ImageView(activity);
            images.add(image);
            art.load(image, prefix + "retry");
            root.addView(image, new FrameLayout.LayoutParams(10, 10));
        });
        await(() -> {
            for (ImageView image : images) if (!colour(image, Color.BLUE)) return false;
            return true;
        });
        assertEquals(2, retry.get());
    }

    @Test
    public void remoteScrollingPastFirstPageAndBackLoadsEveryRecycledPoster() {
        remote.runOnMainSync(() -> {
            art = new Artwork(url -> {
                int id = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1));
                return bitmap(0xff000000 | id);
            });
            feed = new PosterFeed(
                activity,
                true,
                "movie",
                worker,
                n -> {
                    JSONArray items = new JSONArray();
                    for (int i = 1; i <= 60; i++) items.put(
                        new JSONObject().put("id", i).put("title", "Poster " + i)
                    );
                    return new JSONObject().put("results", items).put("total_pages", 1);
                },
                new PosterFeed.Cards() {
                    public View create(ViewGroup parent) {
                        ImageView image = new ImageView(activity);
                        image.setFocusable(true);
                        image.setFocusableInTouchMode(true);
                        return image;
                    }

                    public void bind(View card, JSONObject item) {
                        int id = item.optInt("id");
                        card.setContentDescription("Poster " + id);
                        art.load((ImageView) card, prefix + id);
                    }
                }
            );
            FrameLayout host = new FrameLayout(activity);
            host.addView(
                feed,
                new FrameLayout.LayoutParams(
                    -1,
                    (int) (158 * activity.getResources().getDisplayMetrics().density)
                )
            );
            activity.setContentView(host);
            host.getViewTreeObserver().addOnGlobalFocusChangeListener((before, after) ->
                focusLog.add(
                    phase +
                        ": " +
                        focusName(before) +
                        " -> " +
                        focusName(after) +
                        " (scroll " +
                        feed.getScrollState() +
                        ", children " +
                        feed.getChildCount() +
                        ")"
                )
            );
            feed.start();
        });
        await(
            "first page laid out",
            () ->
                activity.hasWindowFocus() &&
                feed.items.size() == 60 &&
                feed.getLayoutManager().findViewByPosition(0) != null
        );
        remote.runOnMainSync(() ->
            assertTrue(
                "Initial poster accepts D-pad focus",
                feed.getLayoutManager().findViewByPosition(0).requestFocus()
            )
        );
        await("initial focus", () -> activity.getCurrentFocus() != null);
        for (int i = 0; i < 59; i++) {
            phase = "right " + (i + 1);
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            remote.waitForIdleSync();
        }
        phase = "settling at end";
        await("scroll idle at end", () -> feed.getScrollState() == RecyclerView.SCROLL_STATE_IDLE);
        await("posters ready at end", this::visiblePostersReady);
        await("focus at end", () -> activity.getCurrentFocus() != null);
        remote.runOnMainSync(() ->
            assertEquals("Poster 60", activity.getCurrentFocus().getContentDescription())
        );
        for (int i = 0; i < 59; i++) {
            phase = "left " + (i + 1);
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            remote.waitForIdleSync();
        }
        await("scroll idle at start", () -> feed.getScrollState() == RecyclerView.SCROLL_STATE_IDLE);
        await("posters ready at start", this::visiblePostersReady);
        remote.runOnMainSync(() ->
            assertEquals("Poster 1", activity.getCurrentFocus().getContentDescription())
        );
    }

    boolean visiblePostersReady() {
        for (int i = 0; i < feed.getChildCount(); i++) {
            View card = feed.getChildAt(i);
            int position = feed.getChildAdapterPosition(card);
            if (
                card instanceof ImageView &&
                position >= 0 &&
                position < 60 &&
                !colour((ImageView) card, 0xff000000 | (position + 1))
            ) return false;
        }
        return true;
    }
}
