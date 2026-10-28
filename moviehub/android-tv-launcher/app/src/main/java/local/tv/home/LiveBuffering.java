package local.tv.home;

import java.util.ArrayDeque;

/** Live thresholds stay independent of the stricter VOD policy. Uses monotonic milliseconds. */
final class LiveBuffering {

    static final long STALL_MS = 8000;
    static final int STARTS_PER_MINUTE = 3;
    private final ArrayDeque<Long> starts = new ArrayDeque<>();
    private boolean buffering, triggered;
    private long since;

    boolean update(long now, boolean stalled, boolean watching) {
        if (!watching) {
            reset();
            return false;
        }
        if (!stalled) {
            buffering = false;
            return false;
        }
        if (!buffering) {
            buffering = true;
            since = now;
            while (!starts.isEmpty() && now - starts.peekFirst() >= 60000) starts.removeFirst();
            starts.addLast(now);
            if (!triggered && starts.size() >= STARTS_PER_MINUTE) {
                triggered = true;
                return true;
            }
        }
        return stalled(now, watching);
    }

    boolean stalled(long now, boolean watching) {
        if (!watching) {
            reset();
            return false;
        }
        if (!triggered && buffering && now - since >= STALL_MS) {
            triggered = true;
            return true;
        }
        return false;
    }

    /** A prolonged pause is still actionable after the frequency threshold warmed a backup. */
    boolean persistent(long now) {
        return buffering && now - since >= STALL_MS;
    }

    void reset() {
        starts.clear();
        buffering = triggered = false;
    }
}
