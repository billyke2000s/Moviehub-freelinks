package local.tv.home;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The rules for moving a live channel to a better copy, without the threads (see LiveUpgrade).
 * The quickest copy plays first; as soon as a copy whose name claims a better picture than the one
 * playing (by its name and by the picture actually measured) has been found, it takes over. The
 * new copy is then on trial for {@link #TRIAL_MS}: if it fails or keeps buffering in that time
 * (ExoLive's ported buffering rules decide), the copy that was playing comes back and the better
 * one is put on {@link Cooldowns cooldown}. Quality only ever goes up and a copy that fell back
 * cools down, so switching cannot loop.
 */
final class LiveUpgradePlan {

    /** How long an upgraded copy must hold before it is kept. */
    static final long TRIAL_MS = 60_000;
    /** The first cooldown after a copy fell back; each further one doubles, up to the maximum. */
    static final long COOLDOWN_MS = 10 * 60_000,
        MAX_COOLDOWN_MS = 60 * 60_000;

    /**
     * Copies that fell back recently, by their stable channel key, for the whole app session: a
     * channel change does not forget them, so the race and upgrades leave them alone meanwhile.
     */
    static final class Cooldowns {

        private final Map<String, Long> until = new HashMap<>();
        private final Map<String, Long> length = new HashMap<>();

        /** {@code key} fell back: cool it down, for longer each time it happens again. */
        void start(String key, long now) {
            Long last = length.get(key);
            long next = last == null ? COOLDOWN_MS : Math.min(MAX_COOLDOWN_MS, last * 2);
            length.put(key, next);
            until.put(key, now + next);
        }

        /** {@code key} held through its trial: the next cooldown starts short again. */
        void held(String key) {
            length.remove(key);
            until.remove(key);
        }

        boolean active(String key, long now) {
            Long end = until.get(key);
            return end != null && now < end;
        }

        void clear() {
            until.clear();
            length.clear();
        }
    }

    private static final int IDLE = 0,
        LOOKING = 1,
        TRIAL = 2;

    private final Cooldowns cooldowns;
    private final long trialMs;
    private int phase = IDLE;
    private int target = -1,
        previous = -1;
    private String targetKey;
    private long trialSince;
    /** Copies that fell back this visit: tried, but allowed again once their cooldown ends. */
    private final Set<Integer> fellBack = new HashSet<>();

    LiveUpgradePlan(Cooldowns cooldowns, long trialMs) {
        this.cooldowns = cooldowns;
        this.trialMs = trialMs;
    }

    /** A new channel, or a copy chosen another way: no upgrade is on its way or on trial. */
    void reset() {
        phase = IDLE;
        target = previous = -1;
        targetKey = null;
        fellBack.clear();
    }

    /**
     * The copy to look for now, or -1. {@code current} plays with picture {@code currentLines}
     * (the better of its name and its measured picture); {@code lines}, {@code keys} and
     * {@code untried} describe every copy. Only a copy claiming more lines than the one playing,
     * not cooling down, and not yet tried this visit (or fell back and has cooled down) qualifies;
     * the best claim wins, then the guide's copy order.
     */
    int choose(int current, int currentLines, int[] lines, String[] keys, boolean[] untried, long now) {
        if (phase != IDLE || current < 0) return -1;
        int best = -1;
        for (int i = 0; i < lines.length; i++) {
            if (i == current || lines[i] <= currentLines) continue;
            if (!untried[i] && !fellBack.contains(i)) continue;
            if (cooldowns.active(keys[i], now)) continue;
            if (best < 0 || lines[i] > lines[best]) best = i;
        }
        if (best < 0) return -1;
        phase = LOOKING;
        target = best;
        previous = current;
        targetKey = keys[best];
        return best;
    }

    /** The copy being looked for could not be reached: nothing changed on screen. */
    void notFound() {
        if (phase == LOOKING) phase = IDLE;
    }

    /** The copy just found plays now, on trial. */
    void started(long now) {
        if (phase != LOOKING) return;
        phase = TRIAL;
        trialSince = now;
    }

    /**
     * The copy on trial failed or kept buffering: returns the copy to go back to (and cools the
     * failed one down), or -1 when no upgrade is on trial.
     */
    int fallBack(long now) {
        if (phase != TRIAL) return -1;
        cooldowns.start(targetKey, now);
        fellBack.add(target);
        int back = previous;
        phase = IDLE;
        target = previous = -1;
        targetKey = null;
        return back;
    }

    /** Called regularly: true once the copy on trial has held for its whole trial and is kept. */
    boolean held(long now) {
        if (phase != TRIAL || now - trialSince < trialMs) return false;
        cooldowns.held(targetKey);
        phase = IDLE;
        target = previous = -1;
        targetKey = null;
        return true;
    }

    boolean idle() {
        return phase == IDLE;
    }

    boolean looking() {
        return phase == LOOKING;
    }

    boolean trial() {
        return phase == TRIAL;
    }

    /** The copy being looked for or on trial, else -1. */
    int target() {
        return target;
    }

    /** The copy playing before the upgrade, else -1. */
    int previous() {
        return previous;
    }

    /**
     * The picture size as lines on the same scale as copy names (SourceRanking.resolution): a
     * 1920×800 film-shaped picture counts as 1080, as its width says. 0 when unknown.
     */
    static int lines(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        return Math.max(height, (width * 9) / 16);
    }
}
