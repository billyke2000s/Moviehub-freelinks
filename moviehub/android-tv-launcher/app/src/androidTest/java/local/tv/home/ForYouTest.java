package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** The "For you" blend, with fixture recommendations instead of TMDB. */
@RunWith(AndroidJUnit4.class)
public class ForYouTest {

    SharedPreferences prefs;
    Secrets secrets;
    final List<String> requested = new ArrayList<>();
    String recentBefore;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("for-you-test", 0);
        prefs.edit().clear().commit();
        secrets = new Secrets(context);
        recentBefore = secrets.get("recentPlayback");
        secrets.put("recentPlayback", "[]");
    }

    @After
    public void tearDown() throws Exception {
        prefs.edit().clear().commit();
        secrets.put("recentPlayback", recentBefore);
    }

    static JSONObject movie(int id) throws JSONException {
        return new JSONObject()
            .put("id", id)
            .put("media_type", "movie")
            .put("title", "Movie " + id);
    }

    static JSONArray ids(int... ids) throws JSONException {
        JSONArray out = new JSONArray();
        for (int id : ids) out.put(movie(id));
        return out;
    }

    static List<Integer> order(JSONArray items) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) out.add(items.optJSONObject(i).optInt("id"));
        return out;
    }

    ForYou forYou() {
        return new ForYou(prefs, secrets, path -> {
            requested.add(path);
            if (path.startsWith("trending")) return new JSONObject()
                .put("results", ids(900, 901))
                .put("total_pages", 1);
            // movie/<seed>/recommendations: seed 1 → 10..14, seed 2 → 12, 20, 21
            int seed = Integer.parseInt(path.split("/")[1]);
            JSONArray results = seed == 1 ? ids(10, 11, 12, 13, 14) : ids(12, 20, 21, 2);
            return new JSONObject().put("results", results).put("total_pages", 1);
        });
    }

    @Test
    public void titlesRecommendedBySeveralSeedsRiseAndSeedsAndWatchedAreHidden() throws Exception {
        ForYou.Seed played = new ForYou.Seed("movie", 1, 3),
            listed = new ForYou.Seed("movie", 2, 2);
        Map<ForYou.Seed, JSONArray> recs = new LinkedHashMap<>();
        recs.put(played, ids(10, 11, 12, 13, 2));
        recs.put(listed, ids(12, 20, 1, 11));
        JSONArray blended = ForYou.blend(
            Arrays.asList(played, listed),
            recs,
            Collections.singleton("movie:13")
        );
        List<Integer> order = order(blended);
        assertEquals("Shared by both seeds comes first", Integer.valueOf(12), order.get(0));
        assertFalse("Seeds are not recommended back", order.contains(1) || order.contains(2));
        assertFalse("Watched movies are hidden", order.contains(13));
        assertEquals(Arrays.asList(12, 11, 10, 20), order);
    }

    @Test
    public void withNoHistoryTheShelfShowsTrending() throws Exception {
        assertEquals(Arrays.asList(900, 901), order(forYou().page(1).getJSONArray("results")));
    }

    @Test
    public void thumbsDownIsHiddenFromTrendingEvenWithoutRecommendationSeeds() throws Exception {
        prefs.edit().putString(TitleRatings.PREF, "movie:900=-1").commit();
        ForYou shelf = forYou();
        assertEquals(Arrays.asList(901), order(shelf.page(1).getJSONArray("results")));
        prefs.edit().putString(TitleRatings.PREF, "").commit();
        assertEquals(Arrays.asList(900, 901), order(shelf.page(1).getJSONArray("results")));
    }

    @Test
    public void blendsSeveralSignalsAndStaysTheSameUntilTheyChange() throws Exception {
        prefs
            .edit()
            .putString("watchlist", new JSONArray().put(movie(2)).toString())
            .commit();
        ForYou.noteInterest(prefs, "movie", 1);
        prefs.edit().commit();
        ForYou forYou = forYou();
        List<Integer> first = order(forYou.page(1).getJSONArray("results"));
        assertEquals("Shared title first, then the stronger My List seed", Integer.valueOf(12), first.get(0));
        assertTrue(first.containsAll(Arrays.asList(10, 11, 13, 14, 20, 21)));
        int calls = requested.size();
        assertEquals(first, order(forYou.page(1).getJSONArray("results")));
        assertEquals("Cached: no new requests while signals are unchanged", calls, requested.size());

        ForYou.noteInterest(prefs, "movie", 3);
        prefs.edit().commit();
        forYou.page(1);
        assertTrue("A new signal rebuilds the shelf", requested.size() > calls);
    }

    @Test
    public void migratesTheOldSingleSeed() throws Exception {
        prefs.edit().putInt("seed", 1).putString("seedType", "movie").commit();
        List<ForYou.Seed> seeds = forYou().seeds();
        assertEquals(1, seeds.size());
        assertEquals("movie:1", seeds.get(0).key());
    }
}
