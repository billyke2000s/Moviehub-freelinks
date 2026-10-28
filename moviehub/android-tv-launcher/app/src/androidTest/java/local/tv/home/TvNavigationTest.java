package local.tv.home;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TvNavigationTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private boolean autoUpdates;
    private String freeChannels;

    private View text(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = text(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View root() {
        Dialog dialog = activity.catalogueDialog;
        if (dialog != null && dialog.isShowing()) return dialog.getWindow().getDecorView();
        return activity.getWindow().getDecorView();
    }

    /** Clicks the control with this label, waiting up to 5 s for the screen to finish changing. */
    private void select(String label) {
        long end = SystemClock.uptimeMillis() + 5000;
        java.util.concurrent.atomic.AtomicBoolean present = new java.util.concurrent.atomic.AtomicBoolean();
        while (true) {
            instrumentation.runOnMainSync(() -> present.set(text(root(), label) != null));
            if (present.get() || SystemClock.uptimeMillis() > end) break;
            SystemClock.sleep(100);
        }
        instrumentation.runOnMainSync(() -> {
            View view = text(root(), label);
            if (view == null) UiEvidence.capture("navigation-missing-" + label);
            assertNotNull(
                "No control labelled '" +
                    label +
                    "' on screen after 5 s. Dialog showing: " +
                    (activity.catalogueDialog != null && activity.catalogueDialog.isShowing()) +
                    ", focus: " +
                    (activity.getCurrentFocus() == null
                        ? "none"
                        : activity.getCurrentFocus().getClass().getSimpleName()) +
                    ", visible labels: " +
                    visibleLabels(root()),
                view
            );
            while (!view.hasOnClickListeners() && view.getParent() instanceof View)
                view = (View) view.getParent();
            assertTrue(label, view.performClick());
        });
        instrumentation.waitForIdleSync();
    }

    /**
     * Presses Back, then waits until one of the app's windows has input focus again. Closing a dialog
     * hands focus back to the main window asynchronously; a key sent in that gap is dropped.
     */
    private void back() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        long end = SystemClock.uptimeMillis() + 3000;
        java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.runOnMainSync(() -> {
                Dialog dialog = activity.catalogueDialog;
                focused.set(
                    activity.hasWindowFocus() ||
                        (dialog != null &&
                            dialog.isShowing() &&
                            dialog.getWindow().getDecorView().hasWindowFocus())
                );
            });
            if (focused.get()) return;
            SystemClock.sleep(50);
        }
    }

    /** The first visible text labels on screen, to show where a test ended up. */
    private String visibleLabels(View root) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && labels.size() < 25) {
            View view = queue.poll();
            if (!view.isShown()) continue;
            if (view instanceof TextView && ((TextView) view).getText().length() > 0) labels.add(
                ((TextView) view).getText().toString()
            );
            if (view instanceof ViewGroup) for (
                int i = 0;
                i < ((ViewGroup) view).getChildCount();
                i++
            ) queue.add(((ViewGroup) view).getChildAt(i));
        }
        return labels.toString();
    }

    private void screenshot(String name) throws Exception {
        SystemClock.sleep(500);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(
            instrumentation.getTargetContext().getExternalFilesDir(null),
            "screenshots"
        );
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (OutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
        shell("mkdir -p /sdcard/Download/moviehub-test-screenshots");
        shell(
            "cp " +
                new File(directory, name + ".png").getAbsolutePath() +
                " /sdcard/Download/moviehub-test-screenshots/" +
                name +
                ".png"
        );
    }

    private void shell(String command) throws Exception {
        try (
            android.os.ParcelFileDescriptor descriptor = instrumentation
                .getUiAutomation()
                .executeShellCommand(command);
            InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            assertEquals("Screenshot export failed", "", output.toString("UTF-8").trim());
        }
    }

    @Test
    public void tvScreensNavigateAndKeepAVisibleFocusTarget() throws Exception {
        Context context = instrumentation.getTargetContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        ChannelDatabase.Writer writer = db.writer("navigation-fixture", () -> {}, n -> {});
        try {
            for (int i = 1; i <= 12; i++) writer.accept(
                new Playlist.Channel(
                    "Fixture channel " + i,
                    "https://example.test/live/" + i + ".ts",
                    i % 2 == 0 ? "Sports" : "News",
                    "",
                    ""
                )
            );
            writer.commit();
        } finally {
            writer.abort();
        }
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        // Opening Live TV would otherwise download the full public channel list during the test.
        freeChannels = context.getSharedPreferences("ui", 0).getString(FreeChannels.PREF, null);
        context.getSharedPreferences("ui", 0).edit().putString(FreeChannels.PREF, "off").commit();
        activity = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();
        screenshot("home");
        instrumentation.runOnMainSync(() -> {
            assertNotNull(activity.getCurrentFocus());
            assertFullyVisible("All Apps");
            assertFullyVisible("Settings");
            assertFullyVisible("For you");
            assertFullyVisible("My list");
            assertFullyVisible("Continue");
            for (String category : new String[] { "For you", "Trending", "Top rated", "New movies", "On TV" })
                assertFullyVisible(category);
        });
        instrumentation.runOnMainSync(() -> {
            try {
                JSONArray titles = new JSONArray();
                for (int i = 0; i < 12; i++) titles.put(
                    new JSONObject()
                        .put("id", 550 + i)
                        .put("media_type", "movie")
                        .put("title", i == 0 ? "Fight Club" : "Artwork fixture " + i)
                        .put(
                            "overview",
                            "An insomniac office worker and a soap maker form an underground fight club."
                        )
                        .put("poster_path", "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg")
                        .put("backdrop_path", "/hZkgoQYus5vegHoetLkCJzb17zJ.jpg")
                );
                activity.homeShelf.show(titles, "");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        instrumentation.waitForIdleSync();
        SystemClock.sleep(3000);
        screenshot("home-artwork");
        instrumentation.runOnMainSync(() -> {
            assertFullyVisible("All Apps");
            assertFullyVisible("View details");
        });
        instrumentation.runOnMainSync(() -> text(root(), "Movies").requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            for (String category : new String[] {
                "Search",
                "Browse",
                "Filters",
                "Library",
                "Trending today",
            })
                assertFullyVisible(category);
        });
        screenshot("movies");
        select("Browse");
        instrumentation.runOnMainSync(() -> {
            for (String category : new String[] {
                "Trending this week",
                "Popular",
                "Top rated",
                "Now playing",
                "Upcoming",
                "Action",
                "Romance",
            })
                assertNotNull(category, text(root(), category));
        });
        back();
        back();
        select("Settings");
        select("Playback");
        screenshot("settings-playback");
        select("Accounts");
        screenshot("settings-accounts");
        instrumentation.runOnMainSync(() -> {
            assertEquals(
                "Account overview must not expose a wall of login fields",
                0,
                countInputs(activity.getWindow().getDecorView())
            );
            assertFullyVisible("Xtream");
            assertFullyVisible("Premiumize");
        });
        back();
        select("Live TV");
        SystemClock.sleep(1000);
        screenshot("live-tv");
    }

    private int countInputs(View view) {
        int count = view instanceof EditText ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += countInputs(group.getChildAt(i));
        }
        return count;
    }

    private void assertFullyVisible(String label) {
        View view = text(root(), label);
        if (view == null) UiEvidence.capture("navigation-missing-" + label);
        assertNotNull(label, view);
        Rect visible = new Rect();
        assertTrue(label + " visible", view.getGlobalVisibleRect(visible));
        assertTrue(label + " is vertically clipped", visible.height() >= view.getHeight());
        assertTrue(label + " is horizontally clipped", visible.width() >= view.getWidth());
        if (view instanceof TextView) {
            TextView caption = (TextView) view;
            android.text.Layout layout = caption.getLayout();
            assertNotNull(label + " text layout", layout);
            assertEquals(
                label + " label is truncated",
                0,
                layout.getEllipsisCount(layout.getLineCount() - 1)
            );
            assertTrue(
                label + " text is clipped inside its control",
                layout.getLineBottom(layout.getLineCount() - 1) <=
                    caption.getHeight() - caption.getCompoundPaddingTop() - caption.getCompoundPaddingBottom()
            );
        }
    }

    @Test
    public void detailsAndConnectionFormsFitAndKeepDistinctPurposes() throws Exception {
        Context context = instrumentation.getTargetContext();
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        // Opening Live TV would otherwise download the full public channel list during the test.
        freeChannels = context.getSharedPreferences("ui", 0).getString(FreeChannels.PREF, null);
        context.getSharedPreferences("ui", 0).edit().putString(FreeChannels.PREF, "off").commit();
        activity = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            activity.settingsScreens.accountScreen("TMDB");
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            assertEquals(1, countInputs(activity.getWindow().getDecorView()));
            assertFullyVisible("API Key (v3 auth)");
        });
        screenshot("connect-tmdb");
        back();
        instrumentation.runOnMainSync(() -> {
            try {
                activity.screen("Details", () -> {});
                JSONObject fixture = new JSONObject()
                    .put("id", 100)
                    .put("media_type", "tv")
                    .put("name", "A series with a longer title")
                    .put("poster_path", "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg")
                    .put("first_air_date", "2026-01-01")
                    .put("vote_average", 8.1)
                    .put(
                        "overview",
                        "A detailed synopsis that needs room to breathe on a television screen. " +
                            new String(new char[20]).replace(
                                "\0",
                                "The story follows a family on an unexpected journey. "
                            )
                    )
                    .put("genres", new JSONArray().put(new JSONObject().put("name", "Drama")))
                    .put(
                        "credits",
                        new JSONObject().put(
                            "cast",
                            new JSONArray().put(new JSONObject().put("name", "Actor One"))
                        )
                    );
                activity.titleDetails.renderDetails(fixture);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            assertFullyVisible("Episodes");
            assertFullyVisible("+ My list");
            assertNotNull(text(root(), "A series with a longer title"));
            assertNull(
                "The synopsis is shown once, not repeated under an About heading",
                text(root(), "About A series with a longer title")
            );
            assertNotNull(text(root(), "Actor One"));
        });
        screenshot("details");
        instrumentation.runOnMainSync(() -> {
            String previous = context.getSharedPreferences("ui", 0).getString("watchlist", "[]");
            try {
                JSONObject title = new JSONObject()
                    .put("id", 991100)
                    .put("media_type", "tv")
                    .put("name", "Saved series");
                LibraryScreens library = activity.libraryScreens;
                assertFalse(library.isWatchlisted(title));
                library.toggleWatchlist(title);
                assertTrue(library.isWatchlisted(title));
                library.toggleWatchlist(title);
                assertFalse(library.isWatchlisted(title));
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                context.getSharedPreferences("ui", 0).edit().putString("watchlist", previous).commit();
            }
        });
    }

    @Test
    public void everyMovieAndSeriesGenreIsAvailableOnEntryAndRoutesByRemote() throws Exception {
        Context context = instrumentation.getTargetContext();
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        // Opening Live TV would otherwise download the full public channel list during the test.
        freeChannels = context.getSharedPreferences("ui", 0).getString(FreeChannels.PREF, null);
        context.getSharedPreferences("ui", 0).edit().putString(FreeChannels.PREF, "off").commit();
        activity = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();
        for (String kind : new String[] { "Movies", "Series" }) {
            select(kind);
            instrumentation.runOnMainSync(() -> {
                View daily = text(root(), "Trending today");
                assertNotNull(daily);
                assertTrue("Catalogue collection is visible", daily.isShown());
            });
            String type = kind.equals("Movies") ? "movie" : "tv";
            select("Browse");
            instrumentation.runOnMainSync(() -> {
                for (TmdbCategories.Entry genre : TmdbCategories.genres(type)) {
                    View view = text(root(), genre.name);
                    assertNotNull("Category in Browse " + kind + ": " + genre.name, view);
                    assertEquals(genre.path, view.getTag());
                }
                View last = text(root(), "Western");
                last.requestFocus();
            });
            instrumentation.waitForIdleSync();
            screenshot(kind.toLowerCase() + "-categories");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() ->
                assertNotNull("Remote opens the final genre", text(root(), "Western"))
            );
            select("Filters");
            instrumentation.runOnMainSync(() -> {
                for (String name : new String[] {
                    "Years & decades",
                    "Languages",
                    "Countries",
                    "Streaming services",
                    "Ratings",
                    "Runtime",
                    "Sort order",
                    "Keywords & themes",
                    "Studios",
                    "People",
                    type.equals("movie") ? "Collections" : "Series types",
                    type.equals("movie") ? "Release types" : "Series status",
                })
                    assertNotNull("Filter " + name, text(root(), name));
            });
            screenshot(kind.toLowerCase() + "-filters");
            select(type.equals("movie") ? "Release types" : "Series types");
            instrumentation.runOnMainSync(() ->
                assertNotNull(
                    "Media-specific categories",
                    text(root(), type.equals("movie") ? "Digital releases" : "Miniseries")
                )
            );
            back();
            back();
            back();
            // The grid was opened from the rows page, so Back returns there before Home.
            back();
        }
    }

    @After
    public void cleanup() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", autoUpdates)
            .putString(FreeChannels.PREF, freeChannels == null ? "auto" : freeChannels)
            .commit();
        ChannelDatabase.get(instrumentation.getTargetContext()).clear();
    }
}
