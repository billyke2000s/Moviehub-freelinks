package local.tv.home;

/** Preserve normal/raced fallback budgets while bounding the final ZLive attempt. */
final class LiveSourceDeadline {

    static final long START_MS = 15_000,
        RACE_MS = 8_000,
        ZLIVE_MS = 60_000;

    private LiveSourceDeadline() {}

    static long milliseconds(boolean alternative, boolean zlive, boolean raced) {
        if (alternative) return raced ? RACE_MS : START_MS;
        return zlive ? ZLIVE_MS : 0;
    }
}
