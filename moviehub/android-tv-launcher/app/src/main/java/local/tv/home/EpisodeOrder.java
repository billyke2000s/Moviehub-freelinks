package local.tv.home;

import java.util.*;

/** Pure ordering rules: follow real episode numbers and never auto-play a future episode. */
final class EpisodeOrder {

    static final class Episode {

        final int number;
        final String date;

        Episode(int number, String date) {
            this.number = number;
            this.date = date;
        }
    }

    static int next(List<Episode> episodes, int after, String today) {
        Episode first = null;
        for (Episode episode : episodes) {
            if (episode.number > after && (first == null || episode.number < first.number)) first = episode;
        }
        if (
            first == null ||
            first.date == null ||
            !first.date.matches("\\d{4}-\\d{2}-\\d{2}") ||
            first.date.compareTo(today) > 0
        ) return -1;
        return first.number;
    }

    static List<Integer> laterSeasons(List<Integer> seasons, int current) {
        TreeSet<Integer> sorted = new TreeSet<>();
        for (int season : seasons) if (season > current && season > 0) sorted.add(season);
        return new ArrayList<>(sorted);
    }
}
