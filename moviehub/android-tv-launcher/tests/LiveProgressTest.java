package local.tv.home;

public final class LiveProgressTest {

    private static int checks;

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Live progress " + checks);
        checks++;
    }

    public static void main(String[] args) {
        LiveProgress p = new LiveProgress();
        check(!p.stalled(0, 100, 5, true, 8000));
        check(!p.stalled(7999, 100, 5, true, 8000));
        check(p.stalled(8000, 100, 5, true, 8000));
        p.reset();
        check(!p.stalled(0, 100, 5, true, 8000));
        check(!p.stalled(7000, 7100, 5, true, 8000));
        check(p.stalled(8000, 8100, 5, true, 8000));
        p.reset();
        for (int now = 0; now <= 240000; now += 1000) {
            if (p.stalled(now, now, now / 40, true, 8000)) throw new AssertionError("Moving stream stalled");
        }
        check(true);
        check(!p.stalled(300000, 240000, 6000, false, 8000));
        check(!p.stalled(400000, 240000, 6000, false, 8000));
        check(!p.stalled(500000, 240000, 6000, true, 8000));
        p.reset();
        check(!p.stalled(0, 0, -1, true, 8000));
        check(!p.stalled(9000, 9000, -1, true, 8000));
        check(p.stalled(17000, 9000, -1, true, 8000));
        p.reset();
        check(!p.stalled(0, 10000, 300, true, 8000));
        check(!p.stalled(7000, 100, 1, true, 8000));
        check(!p.stalled(14000, 7100, 200, true, 8000));
        p.reset();
        p.stalled(0, 0, 10, true, 8000);
        check(!p.stalled(9000, 0, 300, true, 8000));
        p.reset();
        check(!p.stalled(0, 0, 0, true, 8000));
        check(p.stalled(8000, 8000, 0, true, 8000));
        check(p.clockMoving(8000, 8000));
        p.reset();
        check(!p.stalled(0, 0, 100, true, 8000));
        check(p.stalled(8000, 0, 100, true, 8000));
        check(!p.clockMoving(8000, 8000));
        p.reset();
        for (int now = 0; now <= 240000; now += 1000) {
            check(!p.stalled(now, now, now / 40, true, 8000));
        }
        // A still picture over a moving clock (sound playing) is seen early, once per freeze.
        p.reset();
        check(!p.stalled(0, 0, 10, true, 8000));
        check(!p.stalled(1000, 1000, 10, true, 8000));
        check(!p.pictureFrozen(1000, 2500));
        check(!p.stalled(2500, 2500, 10, true, 8000));
        check(p.pictureFrozen(2500, 2500));
        long freeze = p.pictureSince();
        check(!p.stalled(3500, 3500, 10, true, 8000));
        check(p.pictureSince() == freeze);
        check(!p.stalled(4500, 4500, 11, true, 8000));
        check(!p.pictureFrozen(4500, 2500));
        check(p.pictureSince() != freeze);
        // Buffering (clock and picture both stopped) is not a frozen picture.
        p.reset();
        check(!p.stalled(0, 0, 10, true, 8000));
        check(!p.stalled(3000, 0, 10, true, 8000));
        check(!p.pictureFrozen(3000, 2500));
        System.out.println(checks + " live progress checks passed");
    }
}
