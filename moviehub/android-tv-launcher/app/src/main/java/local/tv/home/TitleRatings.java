package local.tv.home;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thumbs up and down on films and series, kept on the box as one preference string
 * ({@code "movie:603=1;tv:1399=-1"}, newest first). Pure, so it is core-tested. "For you" uses
 * liked titles as its strongest seeds and never suggests or seeds from a disliked one (ForYou).
 */
final class TitleRatings {

    static final String PREF = "ratings";
    static final int LIKE = 1,
        NONE = 0,
        DISLIKE = -1;
    /** Ratings kept; the oldest go first. */
    static final int MAX = 300;

    private TitleRatings() {}

    static String key(String type, int id) {
        return type + ":" + id;
    }

    /** The ratings, newest first. Unreadable parts are skipped. */
    static Map<String, Integer> parse(String raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(";")) {
            int equals = part.lastIndexOf('=');
            if (equals <= 0) continue;
            String key = part.substring(0, equals);
            if (!key.matches("(movie|tv):[1-9][0-9]*")) continue;
            try {
                int value = Integer.parseInt(part.substring(equals + 1));
                if ((value == LIKE || value == DISLIKE) && !out.containsKey(key)) out.put(key, value);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    static int of(String raw, String key) {
        Integer value = parse(raw).get(key);
        return value == null ? NONE : value;
    }

    /** {@code raw} with {@code key} rated {@code rating} (first, as the newest), or unrated for NONE. */
    static String with(String raw, String key, int rating) {
        Map<String, Integer> old = parse(raw);
        old.remove(key);
        StringBuilder out = new StringBuilder();
        int kept = 0;
        if (rating == LIKE || rating == DISLIKE) {
            out.append(key).append('=').append(rating);
            kept++;
        }
        for (Map.Entry<String, Integer> entry : old.entrySet()) {
            if (kept >= MAX) break;
            if (out.length() > 0) out.append(';');
            out.append(entry.getKey()).append('=').append(entry.getValue());
            kept++;
        }
        return out.toString();
    }

    /** The keys rated {@code rating}, newest first. */
    static List<String> rated(String raw, int rating) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : parse(raw).entrySet())
            if (entry.getValue() == rating) out.add(entry.getKey());
        return out;
    }
}
