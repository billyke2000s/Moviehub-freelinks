package local.tv.home;

public class SkipQueueTest {

    public static void main(String[] args) {
        SkipQueue skips = new SkipQueue();
        if (skips.pending()) throw new AssertionError("nothing pending at first");
        if (skips.take() != -1) throw new AssertionError("taking nothing gives -1");
        // Six +10 s presses from 1:00 combine into one jump to 2:00.
        long target = 0;
        for (int i = 0; i < 6; i++) target = skips.skip(60_000, 10_000, 600_000);
        if (target != 120_000) throw new AssertionError("presses add up: " + target);
        // Presses count from the pending target, not from where the picture still is.
        if (skips.skip(65_000, -10_000, 600_000) != 110_000) throw new AssertionError("back from target");
        if (skips.take() != 110_000 || skips.pending()) throw new AssertionError("take clears");
        // Kept within the film.
        if (skips.skip(5_000, -10_000, 600_000) != 0) throw new AssertionError("not before the start");
        skips.clear();
        if (skips.skip(595_000, 10_000, 600_000) != 600_000) throw new AssertionError("not past the end");
        skips.clear();
        // Unknown length: no upper limit.
        if (skips.skip(595_000, 10_000, 0) != 605_000) throw new AssertionError("unknown length");
        // The seek bar sets the target directly; a later press adds to it.
        if (skips.moveTo(300_000, 600_000) != 300_000) throw new AssertionError("seek bar target");
        if (skips.skip(10_000, 10_000, 600_000) != 310_000) throw new AssertionError("press after seek bar");
        skips.clear();
        if (skips.pending()) throw new AssertionError("clear");
        System.out.println("11 skip checks passed");
    }
}
