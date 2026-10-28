package local.tv.home;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How long things take on this box, kept in memory while the app runs and shown on the
 * Diagnostics page (Settings → System). Only names and durations are kept, never addresses or
 * account details.
 */
final class Timings {

    /** One measured kind of work. */
    static final class Stat {

        final String name;
        int count;
        long last, total, max;

        Stat(String name) {
            this.name = name;
        }

        long average() {
            return count == 0 ? 0 : total / count;
        }
    }

    private static final Map<String, Stat> STATS = new LinkedHashMap<>();
    private static final Map<String, Long> MARKS = new LinkedHashMap<>();
    /** The player's latest download speed in Mb/s, or -1 before anything played. */
    static volatile float lastMbps = -1;

    private Timings() {}

    /** The current time, to pass to {@link #record(String, long)} when the work ends. */
    static long start() {
        return SystemClock.elapsedRealtime();
    }

    /** Records work that began at {@code started} (from {@link #start()}). */
    static synchronized void record(String name, long started) {
        long took = SystemClock.elapsedRealtime() - started;
        Stat stat = STATS.get(name);
        if (stat == null) STATS.put(name, (stat = new Stat(name)));
        stat.count++;
        stat.last = took;
        stat.total += took;
        stat.max = Math.max(stat.max, took);
    }

    /** Remembers when something began in one place, to be finished elsewhere. */
    static synchronized void mark(String key) {
        MARKS.put(key, SystemClock.elapsedRealtime());
    }

    /** As {@link #mark(String)}, but keeps an earlier mark that has not finished yet. */
    static synchronized void markOnce(String key) {
        MARKS.putIfAbsent(key, SystemClock.elapsedRealtime());
    }

    /** Records the time since {@link #mark(String)} under {@code name}, once per mark. */
    static synchronized void finish(String key, String name) {
        Long started = MARKS.remove(key);
        if (started != null) record(name, started);
    }

    static synchronized List<Stat> snapshot() {
        List<Stat> copy = new ArrayList<>();
        for (Stat stat : STATS.values()) {
            Stat item = new Stat(stat.name);
            item.count = stat.count;
            item.last = stat.last;
            item.total = stat.total;
            item.max = stat.max;
            copy.add(item);
        }
        return copy;
    }

    static synchronized void clear() {
        STATS.clear();
        MARKS.clear();
        lastMbps = -1;
    }
}
