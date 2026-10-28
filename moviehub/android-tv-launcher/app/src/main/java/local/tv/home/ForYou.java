package local.tv.home;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Home "For you" shelf: a blend of TMDB recommendations for several titles you showed interest
 * in, kept on the box (no account needed).
 *
 * <p>Signals, strongest first: titles you gave a thumbs up ({@link TitleRatings}), titles you
 * played, titles on My List, and titles whose sources or episodes you opened. A title you gave a
 * thumbs down is never a seed and never suggested. Up to {@link #MAX_SEEDS} of them are used. Each seed's TMDB recommendations
 * are scored by the seed's weight and how high TMDB ranks them; a title recommended by several seeds
 * adds up and rises. Titles you already played or that are seeds themselves are left out.
 *
 * <p>The result only changes when the signals change, and it is cached for {@link #CACHE_MS} so
 * returning to Home costs no requests. With no signals yet, the shelf shows weekly trending.
 */
final class ForYou {

    static final int MAX_SEEDS = 6,
        PAGE_SIZE = 20,
        MAX_INTEREST = 20;
    static final long CACHE_MS = 6 * 60 * 60 * 1000L;

    /** Fetches one TMDB path (for example {@code movie/603/recommendations?page=1}). */
    interface Source {
        JSONObject get(String path) throws Exception;
    }

    /** A title used to find recommendations. */
    static final class Seed {

        final String type;
        final int id;
        final double weight;

        Seed(String type, int id, double weight) {
            this.type = type;
            this.id = id;
            this.weight = weight;
        }

        String key() {
            return type + ":" + id;
        }
    }

    private final SharedPreferences prefs;
    private final Secrets secrets;
    private final Source source;
    /** Blended list for the current signals; built once per signature. */
    private JSONArray blended;
    private String blendedSignature;
    private long blendedAt;

    ForYou(SharedPreferences prefs, Secrets secrets, Source source) {
        this.prefs = prefs;
        this.secrets = secrets;
        this.source = source;
    }

    /** Records that the user opened sources or episodes for a title. Newest first, deduplicated. */
    static void noteInterest(SharedPreferences prefs, String type, int id) {
        if (id <= 0 || !(type.equals("movie") || type.equals("tv"))) return;
        try {
            JSONArray old = new JSONArray(prefs.getString("interest", "[]")),
                next = new JSONArray();
            next.put(new JSONObject().put("id", id).put("media_type", type));
            for (int i = 0; i < old.length() && next.length() < MAX_INTEREST; i++) {
                JSONObject row = old.optJSONObject(i);
                if (row == null) continue;
                if (row.optInt("id") == id && type.equals(row.optString("media_type"))) continue;
                next.put(row);
            }
            prefs.edit().putString("interest", next.toString()).apply();
        } catch (Exception e) {
            Diagnostics.recovered("Saving For you interest", e);
        }
    }

    /** One page of the shelf, in TMDB's response shape ({@code results}, {@code total_pages}). */
    synchronized JSONObject page(int page) throws Exception {
        List<Seed> seeds = seeds();
        if (seeds.isEmpty()) return trending(page);
        // A new thumbs down must hide a title at once, even when the seeds stay the same.
        String signature = signature(seeds) + "|" + prefs.getString(TitleRatings.PREF, "");
        long now = System.currentTimeMillis();
        if (blended == null || !signature.equals(blendedSignature) || now - blendedAt > CACHE_MS) {
            blended = build(seeds);
            blendedSignature = signature;
            blendedAt = now;
        }
        if (blended.length() == 0) return trending(page);
        int pages = (blended.length() + PAGE_SIZE - 1) / PAGE_SIZE;
        JSONArray results = new JSONArray();
        for (
            int i = (page - 1) * PAGE_SIZE;
            i < Math.min(blended.length(), page * PAGE_SIZE);
            i++
        ) results.put(blended.get(i));
        return new JSONObject().put("results", results).put("page", page).put("total_pages", pages);
    }

    private JSONObject trending(int page) throws Exception {
        JSONObject response = source.get("trending/all/week?page=" + page);
        JSONArray items = response.optJSONArray("results"),
            filtered = new JSONArray();
        Set<String> disliked = new HashSet<>(
            TitleRatings.rated(prefs.getString(TitleRatings.PREF, ""), TitleRatings.DISLIKE)
        );
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (
                item != null &&
                !disliked.contains(TitleRatings.key(item.optString("media_type"), item.optInt("id")))
            ) filtered.put(item);
        }
        return response.put("results", filtered);
    }

    private JSONArray build(List<Seed> seeds) {
        Map<Seed, JSONArray> recommendations = new LinkedHashMap<>();
        for (Seed seed : seeds) {
            try {
                JSONArray results = source
                    .get(seed.type + "/" + seed.id + "/recommendations?page=1")
                    .optJSONArray("results");
                if (results != null) recommendations.put(seed, results);
            } catch (Exception e) {
                // One failed seed must not empty the shelf; the others still count.
                Diagnostics.recovered("Loading For you recommendations", e);
            }
        }
        Set<String> exclude = watched();
        exclude.addAll(TitleRatings.rated(prefs.getString(TitleRatings.PREF, ""), TitleRatings.DISLIKE));
        return blend(seeds, recommendations, exclude);
    }

    /**
     * Scores and orders recommendations. Pure: no I/O, so the ranking can be checked directly.
     *
     * @param exclude {@code type:id} keys never shown (already played)
     */
    static JSONArray blend(List<Seed> seeds, Map<Seed, JSONArray> recommendations, Set<String> exclude) {
        Set<String> skip = new HashSet<>(exclude);
        for (Seed seed : seeds) skip.add(seed.key());
        Map<String, Double> score = new HashMap<>();
        Map<String, JSONObject> items = new LinkedHashMap<>();
        Map<String, Integer> firstSeen = new HashMap<>();
        int order = 0;
        for (Map.Entry<Seed, JSONArray> entry : recommendations.entrySet()) {
            Seed seed = entry.getKey();
            JSONArray results = entry.getValue();
            for (int rank = 0; rank < results.length(); rank++) {
                JSONObject item = results.optJSONObject(rank);
                if (item == null || item.optInt("id") <= 0) continue;
                // Recommendations for a movie can include series and vice versa; trust TMDB's type.
                String type = item.optString("media_type", seed.type);
                if (!type.equals("movie") && !type.equals("tv")) continue;
                String key = type + ":" + item.optInt("id");
                if (skip.contains(key)) continue;
                double value = seed.weight * (1.0 - rank / 40.0);
                score.merge(key, Math.max(0.05, value), Double::sum);
                if (!items.containsKey(key)) {
                    try {
                        items.put(key, new JSONObject(item.toString()).put("media_type", type));
                    } catch (Exception e) {
                        continue;
                    }
                    firstSeen.put(key, order++);
                }
            }
        }
        List<String> keys = new ArrayList<>(items.keySet());
        keys.sort((a, b) -> {
            int byScore = Double.compare(score.get(b), score.get(a));
            return byScore != 0 ? byScore : Integer.compare(firstSeen.get(a), firstSeen.get(b));
        });
        JSONArray out = new JSONArray();
        for (String key : keys) out.put(items.get(key));
        return out;
    }

    /**
     * The seeds, strongest first: thumbs up (4), played (3), My List (2), opened sources/episodes
     * (1); never a title with a thumbs down.
     */
    List<Seed> seeds() {
        Map<String, Seed> picked = new LinkedHashMap<>();
        String ratings = prefs.getString(TitleRatings.PREF, "");
        JSONArray liked = new JSONArray();
        for (String key : TitleRatings.rated(ratings, TitleRatings.LIKE)) {
            String[] parts = key.split(":");
            try {
                liked.put(new JSONObject().put("id", Integer.parseInt(parts[1])).put("media_type", parts[0]));
            } catch (Exception ignored) {}
        }
        addSeeds(picked, liked, "id", 4.0);
        // Older entries count a little less so the shelf follows what you watch lately.
        addSeeds(picked, recentPlayback(), "tmdbId", 3.0);
        addSeeds(picked, array(prefs.getString("watchlist", "[]")), "id", 2.0);
        JSONArray interest = array(prefs.getString("interest", "[]"));
        // Migrate the single seed older versions stored.
        int legacy = prefs.getInt("seed", 0);
        if (legacy > 0 && interest.length() == 0) try {
            interest.put(
                new JSONObject().put("id", legacy).put("media_type", prefs.getString("seedType", "movie"))
            );
        } catch (Exception ignored) {}
        addSeeds(picked, interest, "id", 1.0);
        for (String key : TitleRatings.rated(ratings, TitleRatings.DISLIKE)) picked.remove(key);
        List<Seed> seeds = new ArrayList<>(picked.values());
        seeds.sort((a, b) -> Double.compare(b.weight, a.weight));
        return new ArrayList<>(seeds.subList(0, Math.min(MAX_SEEDS, seeds.size())));
    }

    private static void addSeeds(Map<String, Seed> picked, JSONArray rows, String idKey, double weight) {
        int used = 0;
        for (int i = 0; i < rows.length() && used < MAX_SEEDS; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            int id = row.optInt(idKey);
            String type = row.optString("media_type");
            if (id <= 0 || !(type.equals("movie") || type.equals("tv"))) continue;
            Seed seed = new Seed(type, id, weight * Math.pow(0.85, used));
            if (picked.containsKey(seed.key())) continue;
            picked.put(seed.key(), seed);
            used++;
        }
    }

    private Set<String> watched() {
        Set<String> keys = new HashSet<>();
        JSONArray recent = recentPlayback();
        for (int i = 0; i < recent.length(); i++) {
            JSONObject row = recent.optJSONObject(i);
            // Only movies you played are hidden; a series you watch can still be suggested by others.
            if (row != null && "movie".equals(row.optString("media_type"))) keys.add(
                "movie:" + row.optInt("tmdbId")
            );
        }
        return keys;
    }

    private JSONArray recentPlayback() {
        try {
            return array(secrets.get("recentPlayback"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONArray array(String raw) {
        try {
            return new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static String signature(List<Seed> seeds) {
        StringBuilder out = new StringBuilder();
        for (Seed seed : seeds) out.append(seed.key()).append(',');
        return out.toString();
    }
}
