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
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StreamingMenusTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private SharedPreferences prefs;
    private String watchlist, history;
    private Set<String> watched;
    private boolean updates;

    interface Step {
        void run() throws Exception;
    }

    /** Runs a step on the caller's thread and turns checked exceptions into test failures. */
    private void call(Step step) {
        try {
            step.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private View find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View match = find(((ViewGroup) view).getChildAt(i), label);
            if (match != null) return match;
        }
        return null;
    }

    private View root() {
        return activity.getWindow().getDecorView();
    }

    private void ui(Runnable work) {
        remote.runOnMainSync(work);
        remote.waitForIdleSync();
    }

    @Before
    public void start() {
        Context context = remote.getTargetContext();
        prefs = context.getSharedPreferences("ui", 0);
        updates = prefs.getBoolean("autoUpdates", true);
        watchlist = prefs.getString("watchlist", "[]");
        history = prefs.getString("searchHistory", "[]");
        watched = new HashSet<>(prefs.getStringSet("watchedEpisodes", Collections.emptySet()));
        prefs
            .edit()
            .putBoolean("autoUpdates", false)
            .remove("searchHistory")
            .remove("watchedEpisodes")
            .commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void stop() {
        if (activity != null) ui(activity::finish);
        prefs
            .edit()
            .putBoolean("autoUpdates", updates)
            .putString("watchlist", watchlist)
            .putString("searchHistory", history)
            .putStringSet("watchedEpisodes", watched)
            .commit();
    }

    @Test
    public void searchHistoryIsDeduplicatedAndSearchIsRemoteAccessible() throws Exception {
        ui(() -> {
            for (String value : new String[] { "Alien", "Arrival", "alien" })
                call(() -> activity.catalogueSearch.rememberSearch(value));
            call(() -> activity.catalogueSearch.searchCatalogue("Movies"));
            assertNull(
                "Results appear as you type; no separate search buttons",
                find(root(), "Search titles")
            );
            assertNull(find(root(), "Search people"));
            assertNotNull(find(root(), "Recent searches"));
            assertNotNull("Recent searches are buttons", find(root(), "Arrival"));
            assertTrue("The search box is focused", activity.getCurrentFocus() instanceof EditText);
        });
        JSONArray saved = new JSONArray(prefs.getString("searchHistory", "[]"));
        assertEquals(2, saved.length());
        assertEquals("alien", saved.getString(0));
        UiEvidence.capture("search-redesign");
    }

    @Test
    public void myListFiltersAndSortsWithoutDeletingOtherTitles() throws Exception {
        JSONArray items = new JSONArray()
            .put(new JSONObject().put("id", 1).put("media_type", "movie").put("title", "Zebra"))
            .put(new JSONObject().put("id", 2).put("media_type", "tv").put("name", "Series fixture"))
            .put(new JSONObject().put("id", 3).put("media_type", "movie").put("title", "Alpha"));
        prefs.edit().putString("watchlist", items.toString()).commit();
        ui(() -> {
            call(() -> activity.libraryScreens.watchlist("Movies", "Title A–Z"));
            assertNotNull(find(root(), "Alpha"));
            assertNotNull(find(root(), "Zebra"));
            assertNull(find(root(), "Series fixture"));
        });
        assertEquals(3, new JSONArray(prefs.getString("watchlist", "[]")).length());
        UiEvidence.capture("my-list-redesign");
    }

    @Test
    public void seasonAndEpisodeCardsKeepArtworkSpaceAndWatchedState() throws Exception {
        JSONObject series = new JSONObject()
            .put("id", 100)
            .put("name", "Fixture series")
            .put("media_type", "tv")
            .put(
                "seasons",
                new JSONArray().put(
                    new JSONObject()
                        .put("name", "Season 1")
                        .put("season_number", 1)
                        .put("episode_count", 8)
                        .put("overview", "A season overview.")
                )
            );
        ui(() -> {
            call(() -> activity.screen("Fixture series", (Runnable) () -> {}));
            call(() -> activity.seriesScreens.renderSeasons(series));
            assertNotNull(find(root(), "Season 1"));
            assertNotNull(find(root(), "Choose a season"));
        });
        UiEvidence.capture("seasons-redesign");
        JSONArray episodes = new JSONArray().put(
            new JSONObject()
                .put("episode_number", 1)
                .put("name", "Pilot")
                .put("runtime", 42)
                .put("air_date", "2026-01-01")
                .put("overview", "A new journey begins.")
        );
        ui(() -> {
            call(() -> activity.screen("Fixture series · Season 1", (Runnable) () -> {}));
            call(() ->
                activity.seriesScreens.renderEpisodes(episodes, series, 100, "tt12345", 1, "Fixture series")
            );
            View title = find(root(), "1 · Pilot");
            assertNotNull(title);
            View card = (View) title.getParent().getParent();
            assertTrue(card.performLongClick());
            assertNotNull(find(root(), "✓  1 · Pilot"));
        });
        assertTrue(prefs.getStringSet("watchedEpisodes", Collections.emptySet()).contains("100:1:1"));
        UiEvidence.capture("episodes-redesign");
    }

    @Test
    public void sourceQualityFiltersPreserveTheOtherSources() {
        SourceRepository.Source uhd = new SourceRepository.Source(
            "Provider\n2160p HDR HEVC fixture",
            "magnet:?xt=urn:btih:fixture"
        );
        uhd.cache = SourceRepository.Cache.CACHED;
        SourceRepository.Source hd = new SourceRepository.Source(
            "Provider\n1080p fixture",
            "magnet:?xt=urn:btih:fixture2"
        );
        hd.cache = SourceRepository.Cache.NOT_CACHED;
        List<SourceRepository.Source> sources = Arrays.asList(uhd, hd);
        ui(() -> {
            call(() -> activity.screen("Choose a source", (Runnable) () -> {}));
            LinearLayout target = new LinearLayout(activity);
            target.setOrientation(LinearLayout.VERTICAL);
            activity.page.addView(target);
            call(() ->
                activity.sources.renderSources(
                    target,
                    sources,
                    "4K",
                    new SourceRepository(new Secrets(activity)),
                    null,
                    "tt12345",
                    -1,
                    -1,
                    "Fixture film",
                    "Movies"
                )
            );
            assertNotNull(find(root(), "Ready to play · 1"));
            assertNull(find(root(), "Available to transfer · 1"));
            assertNotNull("Quality shown large", find(root(), "4K"));
            assertNotNull("HDR badge", find(root(), "HDR"));
            assertNotNull("Codec badge", find(root(), "HEVC"));
            assertNotNull("Provider name", find(root(), "Provider"));
        });
        assertEquals(2, sources.size());
        UiEvidence.capture("sources-redesign");
    }

    @Test
    public void searchShowsMovieSeriesAndPeopleRowsAsYouType() throws Exception {
        java.util.List<String> paths = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CatalogueSearch.testSource = path -> {
            paths.add(path);
            try {
                JSONArray items = new JSONArray();
                if (path.startsWith("search/movie")) for (int i = 1; i <= 8; i++) items.put(
                    new JSONObject()
                        .put("id", 500 + i)
                        .put("media_type", "movie")
                        .put("title", "Alien " + i)
                );
                if (path.startsWith("search/person")) items.put(
                    new JSONObject()
                        .put("id", 9)
                        .put("name", "Sigourney Weaver")
                        .put("known_for_department", "Acting")
                );
                return new JSONObject().put("results", items).put("total_pages", 1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        try {
            ui(() -> call(() -> activity.catalogueSearch.searchCatalogue("Movies")));
            ui(() -> ((EditText) activity.getCurrentFocus()).setText("Alien"));
            long end = SystemClock.uptimeMillis() + 10000;
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            while (!ready.get() && SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(100);
                remote.runOnMainSync(() ->
                    ready.set(find(root(), "Sigourney Weaver") != null && find(root(), "Movies") != null)
                );
            }
            assertTrue("Results appeared without pressing a button: " + paths, ready.get());
            SystemClock.sleep(500);
            remote.runOnMainSync(() -> {
                View series = find(root(), "Series");
                assertTrue("A category with no results is hidden", series == null || !series.isShown());
                assertNull("Typing does not open the old grid screen", activity.catalogues.catalogue);
            });
            assertTrue("Searched movies, series and people", paths.size() >= 3);
            UiEvidence.capture("search-results");
        } finally {
            CatalogueSearch.testSource = null;
        }
    }
}
