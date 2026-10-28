package local.tv.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rules for finding a live channel's link among its copies, without the threads (see
 * LiveRace). Up to {@link #PARALLEL} copies are looked up at once, in priority order. The quickest
 * copy found plays at once (after the short {@link #TIE_MS} tie window), the best one when several
 * are found together; a better copy found later is offered to LiveUpgrade rather than waited for.
 * After the playing copy fails, the best other copy already found plays straight away. Quality
 * comes from the copy's name ("HD", "1080p"…; see SourceRanking), so it is a hint, not a
 * measurement.
 */
final class LiveRacePlan {

    /** Lookups at once: enough to find a working copy quickly, few enough for a TV box. */
    static final int PARALLEL = 3;
    /**
     * Plain stream addresses need no lookup, so several copies "arrive" within milliseconds in
     * whatever order their threads run. The first choice waits this long for a copy ahead in
     * priority order, so equal copies are not chosen by thread timing.
     */
    static final long TIE_MS = 250;

    private static final int WAITING = 0,
        RESOLVING = 1,
        READY = 2,
        FAILED = 3,
        USED = 4;

    /** Candidates in priority order: quality first, then the guide's copy order. */
    private final int[] order;
    private final int[] quality;
    private final int[] state;
    private final long started;
    private boolean picked;

    /**
     * {@code candidates} are copy indices and {@code qualities} their quality hints (lines, e.g.
     * 1080), in the guide's copy order.
     */
    LiveRacePlan(int[] candidates, int[] qualities, long started) {
        Integer[] sorted = new Integer[candidates.length];
        for (int i = 0; i < sorted.length; i++) sorted[i] = i;
        // Stable: equal quality keeps the guide's order.
        Arrays.sort(sorted, (x, y) -> Integer.compare(qualities[y], qualities[x]));
        order = new int[candidates.length];
        quality = new int[candidates.length];
        for (int i = 0; i < sorted.length; i++) {
            order[i] = candidates[sorted[i]];
            quality[i] = qualities[sorted[i]];
        }
        state = new int[candidates.length];
        this.started = started;
    }

    /**
     * Copies to start looking up now. Lookups running and links found but not yet used count
     * together against {@link #PARALLEL}: a ready standby is as good as a lookup, and looking up
     * more would only load the box and the services.
     */
    List<Integer> launch() {
        int running = 0;
        for (int s : state) if (s == RESOLVING || s == READY) running++;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < order.length && running < PARALLEL; i++) {
            if (state[i] != WAITING) continue;
            state[i] = RESOLVING;
            running++;
            out.add(order[i]);
        }
        return out;
    }

    void resolved(int copy) {
        int i = at(copy);
        if (i >= 0 && state[i] == RESOLVING) state[i] = READY;
    }

    /** The copy's lookup failed, or it was played and did not work. */
    void failed(int copy) {
        int i = at(copy);
        if (i >= 0) state[i] = FAILED;
    }

    /**
     * The copy to play now, or -1 to wait. Only the first choice waits, and only for the tie
     * window; later choices never wait.
     */
    int pick(long now) {
        int best = -1;
        for (int i = 0; i < order.length; i++) if (
            state[i] == READY && (best < 0 || quality[i] > quality[best])
        ) best = i;
        if (best < 0) return -1;
        if (!picked && now - started < TIE_MS) {
            for (int i = 0; i < best; i++) if (state[i] == WAITING || state[i] == RESOLVING) return -1;
        }
        picked = true;
        state[best] = USED;
        return order[best];
    }

    /** Whether any copy other than the one playing could still be tried. */
    boolean remaining() {
        for (int s : state) if (s == WAITING || s == RESOLVING || s == READY) return true;
        return false;
    }

    /** Whether copy {@code copy} is being looked up now. */
    boolean lookingUp(int copy) {
        int i = at(copy);
        return i >= 0 && state[i] == RESOLVING;
    }

    /** LiveUpgrade took copy {@code copy} (found here or looked up itself) and plays it now. */
    void playing(int copy) {
        int i = at(copy);
        if (i >= 0) state[i] = USED;
        picked = true;
    }

    long tieEnds() {
        return started + TIE_MS;
    }

    private int at(int copy) {
        for (int i = 0; i < order.length; i++) if (order[i] == copy) return i;
        return -1;
    }
}
