package local.tv.home;

import java.util.Arrays;
import java.util.List;

public final class LiveRacePlanTest {

    private static int checks;

    private static void same(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Race " + checks + ": expected [" + expected + "] got [" + actual + "]");
        }
        checks++;
    }

    public static void main(String[] args) {
        // Copies 0..4: SD, SD, HD (720), FHD (1080), SD. Priority: 1080, 720, then guide order.
        int[] copies = { 0, 1, 2, 3, 4 };
        int[] quality = { 480, 480, 720, 1080, 480 };
        LiveRacePlan plan = new LiveRacePlan(copies, quality, 0);
        same(Arrays.asList(3, 2, 0), plan.launch());
        same(Arrays.asList(), plan.launch());

        // The quickest link plays: a fast SD link is not held back for the 1080p and 720p still
        // being looked up (LiveUpgrade switches to a better copy when one is found)…
        plan.resolved(0);
        same(0, plan.pick(1000));
        same(true, plan.lookingUp(3));
        same(false, plan.lookingUp(0));
        // …the 720p arrives while the SD plays (LiveRace asks pick() again only after a failure).
        plan.resolved(2);

        // LiveUpgrade takes the 720p and plays it; the SD plays no more, the 1080p is still pending.
        plan.playing(2);
        same(false, plan.lookingUp(2));
        // The 720p does not hold: the next copy found plays at once, without waiting.
        plan.failed(2);
        same(Arrays.asList(1, 4), plan.launch());
        plan.resolved(1);
        same(1, plan.pick(3000));
        same(true, plan.remaining());

        // The best possible copy found first plays immediately, inside the window.
        LiveRacePlan best = new LiveRacePlan(copies, quality, 0);
        best.launch();
        best.resolved(3);
        same(3, best.pick(10));

        // Equal quality keeps the guide's copy order, and lookups beyond three wait their turn.
        LiveRacePlan sd = new LiveRacePlan(new int[] { 5, 6, 7, 8 }, new int[] { 480, 480, 480, 480 }, 0);
        same(Arrays.asList(5, 6, 7), sd.launch());
        sd.resolved(7);
        sd.resolved(6);
        // Nothing better than SD is pending, but 5 is ahead of both and still being looked up:
        // wait out the short tie window for it, then play 6, which is ahead of 7.
        same(-1, sd.pick(5));
        same(6, sd.pick(LiveRacePlan.TIE_MS));
        sd.failed(5);
        same(Arrays.asList(8), sd.launch());
        same(7, sd.pick(LiveRacePlan.TIE_MS + 1));
        sd.failed(7);
        sd.failed(8);
        same(false, sd.remaining());
        same(-1, sd.pick(100000));

        // Equal copies that arrive within milliseconds are not chosen by thread timing: inside the
        // short tie window, a later copy waits for one ahead of it that is still being looked up…
        LiveRacePlan tie = new LiveRacePlan(new int[] { 0, 1, 2 }, new int[] { 480, 480, 480 }, 0);
        same(Arrays.asList(0, 1, 2), tie.launch());
        tie.resolved(2);
        same(-1, tie.pick(5));
        tie.resolved(0);
        same(0, tie.pick(6));
        // …but never longer than the tie window, when the earlier copy is a slow lookup.
        LiveRacePlan slowFirst = new LiveRacePlan(new int[] { 0, 1 }, new int[] { 480, 480 }, 0);
        slowFirst.launch();
        slowFirst.resolved(1);
        same(-1, slowFirst.pick(LiveRacePlan.TIE_MS - 1));
        same(1, slowFirst.pick(LiveRacePlan.TIE_MS));

        // Nothing found yet: nothing to play.
        LiveRacePlan none = new LiveRacePlan(new int[] { 1, 2 }, new int[] { 720, 480 }, 0);
        List<Integer> started = none.launch();
        same(2, started.size());
        same(-1, none.pick(100000));
        System.out.println(checks + " live source race checks passed");
    }
}
