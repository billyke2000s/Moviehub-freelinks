package local.tv.home;

import java.util.ArrayList;
import java.util.List;

/**
 * The last {@link #CAPACITY} failures the app recovered from, for Settings → System →
 * Troubleshooting. Like the logcat line, each keeps only the time, where it happened and the
 * exception type: messages can hold provider addresses with account details, so they are never
 * kept. In memory only, cleared when MovieHub closes. Plain Java so the core checks can test it.
 */
final class ErrorLog {

    static final int CAPACITY = 50;

    static final class Entry {

        final long time;
        final String where, type;

        Entry(long time, String where, String type) {
            this.time = time;
            this.where = where;
            this.type = type;
        }
    }

    private static final List<Entry> entries = new ArrayList<>();

    private ErrorLog() {}

    static synchronized void add(String where, Throwable error) {
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
        entries.add(new Entry(System.currentTimeMillis(), where == null ? "" : where, type));
        while (entries.size() > CAPACITY) entries.remove(0);
    }

    /** Newest first. */
    static synchronized List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(entries);
        java.util.Collections.reverse(out);
        return out;
    }

    static synchronized void clear() {
        entries.clear();
    }
}
