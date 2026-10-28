package local.tv.home;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which rows the Continue watching screen shows, from the playback history (newest first), as
 * streaming apps do it. Pure, so it is core-tested; LibraryScreens builds the entries and draws
 * the rows.
 *
 * <ul>
 *   <li>Nothing shorter than {@link #MIN_MS} watched is listed: a title opened by mistake, or a
 *       source tried for a minute, is not "continue watching".
 *   <li>One row per film or series, whichever source or file played it: the newest wins.
 *   <li>A series is one row. Its newest episode watched for at least {@link #MIN_MS} resumes; a
 *       finished one offers the next episode ({@link Row#next}), which LibraryScreens looks up and
 *       drops when there is none (the series is finished, or up to date).
 * </ul>
 */
final class ContinueWatching {

    /** How much must be watched before a film or episode is listed. */
    static final long MIN_MS = 3 * 60_000L;

    /** One history record: its index in the history, and what is known about its progress. */
    static final class Entry {

        final int index;
        final String group;
        final boolean series;
        final long position;
        final boolean finished;

        /**
         * {@code position} is the saved resume point (0 when none: never played far, or finished);
         * {@code finished} that this episode was watched to the end.
         */
        Entry(int index, String group, boolean series, long position, boolean finished) {
            this.index = index;
            this.group = group;
            this.series = series;
            this.position = position;
            this.finished = finished;
        }
    }

    /** A row to show: resume history entry {@link #index}, or its next episode when {@link #next}. */
    static final class Row {

        final int index;
        final boolean next;

        Row(int index, boolean next) {
            this.index = index;
            this.next = next;
        }

        @Override
        public String toString() {
            return index + (next ? " next" : "");
        }
    }

    private ContinueWatching() {}

    /**
     * What makes records the same title: the TMDB series or film when known, else the title as
     * shown (case and spacing ignored), else the file's own id.
     */
    static String group(boolean series, int tmdbId, String title, String id) {
        if (tmdbId > 0) return (series ? "tv:" : "movie:") + tmdbId;
        String name = title == null ? "" : title.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return name.isEmpty() ? "id:" + id : "title:" + name;
    }

    /** How recently a next episode must have aired to be shown as new. */
    static final int NEW_DAYS = 7;

    /**
     * Whether a next episode that aired on {@code airDate} ("yyyy-MM-dd") counts as new on
     * {@code today}: aired, and no more than {@link #NEW_DAYS} days ago.
     */
    static boolean isNew(String airDate, String today) {
        if (airDate == null || !airDate.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            java.time.LocalDate aired = java.time.LocalDate.parse(airDate),
                now = java.time.LocalDate.parse(today);
            return !aired.isAfter(now) && !aired.isBefore(now.minusDays(NEW_DAYS));
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    /** The rows, newest first, from {@code history} newest first. */
    static List<Row> rows(List<Entry> history) {
        List<Row> rows = new ArrayList<>();
        Set<String> decided = new HashSet<>();
        for (Entry entry : history) {
            if (decided.contains(entry.group)) continue;
            if (!entry.series) {
                // The newest record of a film decides it: listed once far enough in, else not at all.
                decided.add(entry.group);
                if (entry.position >= MIN_MS) rows.add(new Row(entry.index, false));
                continue;
            }
            // A series: the newest episode watched properly decides it. One only opened for a
            // moment leaves the decision to the episode before it.
            if (entry.position >= MIN_MS) {
                decided.add(entry.group);
                rows.add(new Row(entry.index, false));
            } else if (entry.finished) {
                decided.add(entry.group);
                rows.add(new Row(entry.index, true));
            }
        }
        return rows;
    }
}
