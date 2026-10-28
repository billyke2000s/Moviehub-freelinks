package local.tv.home;

import java.util.Arrays;
import java.util.List;

/** Continue watching rows: minimum watched, one row per title, series as next episode. */
public final class ContinueWatchingTest {

    private static int checks;
    private static final long MIN = ContinueWatching.MIN_MS;

    public static void main(String[] args) {
        // Newest first: a film played twice from different sources, two short starts, a series.
        List<ContinueWatching.Entry> history = Arrays.asList(
            film(0, "movie:11", 2 * MIN),
            film(1, "movie:11", 5 * MIN),
            film(2, ContinueWatching.group(false, 0, "Branded.to.Kill.1967.UHD", "a"), 0),
            film(3, ContinueWatching.group(false, 0, "Smiling Friends S01", "b"), 60_000),
            film(4, "movie:22", 30 * MIN)
        );
        same("only the films watched long enough, once each", "[0, 4]", rows(history));

        // The newest record of a film decides, even when an older source got further.
        same(
            "newest record of a film decides",
            "[0]",
            rows(Arrays.asList(film(0, "movie:11", 4 * MIN), film(1, "movie:11", 50 * MIN)))
        );

        // A series: the newest episode in progress resumes…
        same(
            "series resumes its episode",
            "[0]",
            rows(Arrays.asList(episode(0, "tv:5", 10 * MIN, false), episode(1, "tv:5", 0, true)))
        );
        // …a finished newest episode offers the next one, once for the whole series…
        same(
            "finished episode offers the next",
            "[0 next]",
            rows(
                Arrays.asList(
                    episode(0, "tv:5", 0, true),
                    episode(1, "tv:5", 0, true),
                    episode(2, "tv:5", 20 * MIN, false)
                )
            )
        );
        // …and an episode only opened for a moment leaves it to the one before.
        same(
            "a moment's episode is skipped",
            "[1 next]",
            rows(Arrays.asList(episode(0, "tv:5", 30_000, false), episode(1, "tv:5", 0, true)))
        );
        same(
            "nothing watched properly",
            "[]",
            rows(Arrays.asList(episode(0, "tv:5", 30_000, false), episode(1, "tv:5", 0, false)))
        );
        // Rewatching a finished episode part-way resumes it rather than skipping on.
        same("rewatch in progress resumes", "[0]", rows(Arrays.asList(episode(0, "tv:5", 6 * MIN, true))));

        // Two series and a film keep their own rows, newest first.
        same(
            "titles keep their order",
            "[0 next, 1, 2]",
            rows(
                Arrays.asList(
                    episode(0, "tv:5", 0, true),
                    film(1, "movie:11", 9 * MIN),
                    episode(2, "tv:6", 9 * MIN, false),
                    episode(3, "tv:5", 9 * MIN, false)
                )
            )
        );
        same("exactly the minimum counts", "[0]", rows(Arrays.asList(film(0, "movie:1", MIN))));

        // Grouping: TMDB identity first, then the shown title (case and spacing ignored), then id.
        same("tmdb film", "movie:7", ContinueWatching.group(false, 7, "Anything", "x"));
        same("tmdb series", "tv:7", ContinueWatching.group(true, 7, "Anything", "x"));
        same(
            "same title from two files",
            ContinueWatching.group(false, 0, "Street  Fighter", "x"),
            ContinueWatching.group(false, 0, " street fighter", "y")
        );
        same("no title", "id:x", ContinueWatching.group(false, 0, "  ", "x"));
        // New episode alerts: aired within the last week, not in the future.
        same("aired today", "true", "" + ContinueWatching.isNew("2026-10-27", "2026-10-27"));
        same("a week ago", "true", "" + ContinueWatching.isNew("2026-10-20", "2026-10-27"));
        same("eight days ago", "false", "" + ContinueWatching.isNew("2026-10-19", "2026-10-27"));
        same("not yet aired", "false", "" + ContinueWatching.isNew("2026-10-28", "2026-10-27"));
        same("no date", "false", "" + ContinueWatching.isNew("", "2026-10-27"));
        same("bad date", "false", "" + ContinueWatching.isNew("2026-13-40", "2026-10-27"));
        System.out.println(checks + " continue watching checks passed");
    }

    private static ContinueWatching.Entry film(int index, String group, long position) {
        return new ContinueWatching.Entry(index, group, false, position, false);
    }

    private static ContinueWatching.Entry episode(int index, String group, long position, boolean finished) {
        return new ContinueWatching.Entry(index, group, true, position, finished);
    }

    private static String rows(List<ContinueWatching.Entry> history) {
        return ContinueWatching.rows(history).toString();
    }

    private static void same(String name, String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError("Continue watching " + checks + " (" + name + "): " + actual);
        }
    }
}
