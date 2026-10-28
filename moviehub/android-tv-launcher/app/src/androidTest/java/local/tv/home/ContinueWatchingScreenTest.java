package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** The Continue watching screen built from a fixture history (no TMDB requests needed). */
@RunWith(AndroidJUnit4.class)
public class ContinueWatchingScreenTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private Secrets secrets;
    private String recentBefore;
    private Map<String, ?> positionsBefore;
    private Set<String> watchedBefore;
    private boolean oldAutoUpdates;

    private SharedPreferences positions(Context context) {
        return context.getSharedPreferences("positions", 0);
    }

    @Before
    public void setUp() throws Exception {
        Context context = remote.getTargetContext();
        secrets = new Secrets(context);
        recentBefore = secrets.get("recentPlayback");
        positionsBefore = new HashMap<>(positions(context).getAll());
        SharedPreferences ui = context.getSharedPreferences("ui", 0);
        watchedBefore = new HashSet<>(ui.getStringSet("watchedEpisodes", Collections.emptySet()));
        oldAutoUpdates = ui.getBoolean("autoUpdates", true);
        ui.edit().putBoolean("autoUpdates", false).commit();
        positions(context).edit().clear().commit();
        long min = 60_000;
        // Newest first: a short start, a film from two sources, two episodes of one series (the
        // newest only opened for a moment), and a film watched long enough.
        JSONArray recent = new JSONArray()
            .put(record("short", "Branded.to.Kill.1967.UHD", 0, null))
            .put(record("film-new", "Street Fighter", 9001, null))
            .put(record("film-old", "Street Fighter", 9001, null))
            .put(record("ep5", "Fixture Series · S1 E5", 99999901, "99999901:1:5"))
            .put(record("ep4", "Fixture Series · S1 E4", 99999901, "99999901:1:4"))
            .put(record("rocky", "Rocky III", 9002, null));
        secrets.put("recentPlayback", recent.toString());
        positions(context)
            .edit()
            .putLong("short", min)
            .putLong("short:duration", 120 * min)
            .putLong("title:movie:9001", 20 * min)
            .putLong("film-old:duration", 120 * min)
            .putLong("film-new:duration", 120 * min)
            .putLong("ep5", 30_000)
            .putLong("title:tv:99999901:1:4", 12 * min)
            .putLong("ep4:duration", 22 * min)
            .putLong("title:movie:9002", 40 * min)
            .putLong("rocky:duration", 99 * min)
            .commit();
        ui.edit().putStringSet("watchedEpisodes", new HashSet<>()).commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        await(() -> activity.hasWindowFocus());
    }

    private static JSONObject record(String id, String title, int tmdb, String episodeKey)
        throws JSONException {
        JSONObject item = new JSONObject()
            .put("id", id)
            .put("title", title)
            .put("url", "http://127.0.0.1/" + id);
        if (tmdb > 0) item.put("tmdbId", tmdb);
        if (episodeKey == null) {
            if (tmdb > 0) item.put("media_type", "movie");
            return item;
        }
        String[] parts = episodeKey.split(":");
        return item
            .put("media_type", "tv")
            .put("name", "Fixture Series")
            .put("episodeKey", episodeKey)
            .put("season", Integer.parseInt(parts[1]))
            .put("episode", Integer.parseInt(parts[2]));
    }

    @After
    public void tearDown() throws Exception {
        remote.runOnMainSync(() -> {
            if (activity != null) activity.finish();
        });
        Context context = remote.getTargetContext();
        secrets.put("recentPlayback", recentBefore);
        SharedPreferences.Editor edit = positions(context).edit().clear();
        for (Map.Entry<String, ?> entry : positionsBefore.entrySet()) {
            if (entry.getValue() instanceof Long) edit.putLong(entry.getKey(), (Long) entry.getValue());
        }
        edit.commit();
        context
            .getSharedPreferences("ui", 0)
            .edit()
            .putStringSet("watchedEpisodes", watchedBefore)
            .putBoolean("autoUpdates", oldAutoUpdates)
            .commit();
    }

    private void await(BooleanSupplier condition) {
        long until = SystemClock.elapsedRealtime() + 30000;
        boolean[] done = { false };
        do {
            remote.runOnMainSync(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        fail("Continue watching timed out");
    }

    private static void texts(View view, List<String> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) texts(group.getChildAt(i), out);
        }
    }

    /** The visible texts, read on the main thread (await's conditions already run there). */
    private List<String> visibleNow() {
        List<String> out = new ArrayList<>();
        texts(activity.page, out);
        return out;
    }

    /** The visible texts, from the test thread. */
    private List<String> shown() {
        List<String> out = new ArrayList<>();
        remote.runOnMainSync(() -> out.addAll(visibleNow()));
        return out;
    }

    private static int count(List<String> texts, String value) {
        int n = 0;
        for (String text : texts) if (text.equals(value)) n++;
        return n;
    }

    @Test
    public void shortStartsAreHiddenAndEachTitleHasOneRow() {
        remote.runOnMainSync(() -> activity.libraryScreens.continueWatching());
        await(() -> !visibleNow().isEmpty() && visibleNow().contains("Rocky III"));
        List<String> texts = shown();
        assertEquals("A film played from two sources is one row", 1, count(texts, "Street Fighter"));
        assertFalse("A one-minute start is not listed", texts.contains("Branded.to.Kill.1967.UHD"));
        assertEquals("A series is one row, under its own name", 1, count(texts, "Fixture Series"));
        boolean episodeFour = false;
        for (String text : texts) if (text.startsWith("S1 E4")) episodeFour = true;
        assertTrue("The episode watched properly resumes, not the one opened for a moment", episodeFour);
        UiEvidence.capture("continue-watching-rows");
    }

    @Test
    public void aFinishedEpisodeOffersTheNextOneInsteadOfDisappearing() {
        Context context = remote.getTargetContext();
        positions(context).edit().remove("title:tv:99999901:1:4").commit();
        context
            .getSharedPreferences("ui", 0)
            .edit()
            .putStringSet("watchedEpisodes", new HashSet<>(Collections.singleton("99999901:1:4")))
            .commit();
        remote.runOnMainSync(() -> activity.libraryScreens.continueWatching());
        await(() -> visibleNow().contains("Rocky III"));
        List<String> texts = shown();
        // The next episode is looked up from the catalogue in the background; whatever that says,
        // the series was not dropped for being finished, and it is still one row.
        boolean finished = false;
        for (String text : texts)
            if (text.startsWith("Finished S1 E4") || text.startsWith("Next")) finished = true;
        assertTrue("The finished episode's series is offered to continue: " + texts, finished);
        assertTrue(count(texts, "Fixture Series") <= 1);
    }

    @Test
    public void removingASeriesForgetsEveryEpisodeAndSource() throws Exception {
        remote.runOnMainSync(() -> activity.libraryScreens.continueWatching());
        await(() -> visibleNow().contains("Fixture Series"));
        JSONArray recent = new JSONArray(secrets.get("recentPlayback"));
        int before = recent.length();
        // Remove through the same path as the Hold Select panel's "Remove from Continue watching".
        remote.runOnMainSync(() -> {
            try {
                activity.libraryScreens.forget(
                    "tv:99999901",
                    new JSONArray(activity.secrets.get("recentPlayback"))
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            activity.libraryScreens.continueWatching();
        });
        await(() -> !visibleNow().contains("Fixture Series") && visibleNow().contains("Rocky III"));
        JSONArray after = new JSONArray(secrets.get("recentPlayback"));
        assertEquals("Both episode records are gone", before - 2, after.length());
        assertFalse(positions(remote.getTargetContext()).contains("title:tv:99999901:1:4"));
    }
}
