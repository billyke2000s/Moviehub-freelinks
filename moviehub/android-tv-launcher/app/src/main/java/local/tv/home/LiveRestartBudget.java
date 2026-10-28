package local.tv.home;

/** Bounded reconnects per live selection, replenished only by sustained observed progress. */
final class LiveRestartBudget {

    static final long HEALTHY_MS = 30000;
    private int attempts;
    private long healthySince = -1,
        previous = -1;

    boolean acquire() {
        healthySince = previous = -1;
        if (attempts >= 2) return false;
        attempts++;
        return true;
    }

    void observe(long now, long frames, boolean watching) {
        if (!watching || frames < 0 || previous < 0 || frames <= previous) healthySince = -1;
        else {
            if (healthySince < 0) healthySince = now;
            if (now - healthySince >= HEALTHY_MS) attempts = 0;
        }
        previous = watching ? frames : -1;
    }

    int attempts() {
        return attempts;
    }

    void reset() {
        attempts = 0;
        healthySince = previous = -1;
    }
}
