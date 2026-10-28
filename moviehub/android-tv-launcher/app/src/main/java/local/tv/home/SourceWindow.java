package local.tv.home;

import java.util.*;
import java.util.function.ToIntFunction;

/** Selection window starts at search entry, never restarts when another provider finishes. */
final class SourceWindow<T> {

    static final long WAIT_MS = 3000;
    /** The longest the first choice waits while nothing good enough has been found yet. */
    static final long LONGER_WAIT_MS = 8000;
    private final long started;
    private final ToIntFunction<T> rank;
    private final Map<String, T> ready = new LinkedHashMap<>();
    private boolean cancelled;

    SourceWindow(long started, ToIntFunction<T> rank) {
        this.started = started;
        this.rank = rank;
    }

    synchronized boolean add(String key, T value) {
        if (cancelled || ready.containsKey(key)) return false;
        ready.put(key, value);
        return true;
    }

    synchronized List<T> snapshot() {
        List<T> result = new ArrayList<>(ready.values());
        result.sort(Comparator.comparingInt(rank).reversed());
        return result;
    }

    synchronized T pick(long now) {
        if (cancelled || now - started < WAIT_MS || ready.isEmpty()) return null;
        return snapshot().get(0);
    }

    /**
     * Like {@link #pick(long)}, but when the best source so far is below {@code target} quality
     * (by {@code quality}), keeps waiting up to {@link #LONGER_WAIT_MS} for a better one, unless
     * the search has {@code finished}. A good source found within three seconds starts as before.
     */
    synchronized T pick(long now, boolean finished, int target, ToIntFunction<T> quality) {
        T best = pick(now);
        if (best == null || finished || now - started >= LONGER_WAIT_MS) return best;
        return quality.applyAsInt(best) >= target ? best : null;
    }

    synchronized void cancel() {
        cancelled = true;
        ready.clear();
    }
}
