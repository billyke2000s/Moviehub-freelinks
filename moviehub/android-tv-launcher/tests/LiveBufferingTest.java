package local.tv.home;

public final class LiveBufferingTest {

    private static int checks;

    public static void main(String[] args) {
        LiveBuffering live = new LiveBuffering();
        check(!live.update(0, true, false));
        check(!live.stalled(60000, false));
        check(!live.update(60000, true, true));
        check(!live.stalled(67999, true));
        check(live.stalled(68000, true));
        check(!live.stalled(90000, true));
        live.reset();
        check(!live.update(0, true, true));
        check(!live.update(1, true, true));
        check(!live.update(2, true, true));
        check(!live.update(100, false, true));
        check(!live.update(1000, true, true));
        check(!live.update(1100, false, true));
        check(live.update(59999, true, true));
        check(!live.update(60000, true, true));
        live.reset();
        check(!live.update(0, true, true));
        check(!live.update(100, false, true));
        check(!live.update(30000, true, true));
        check(!live.update(30100, false, true));
        check(!live.update(60000, true, true));
        check(!live.update(60001, false, true));
        check(live.update(60002, true, true));
        live.reset();
        check(!live.update(1, true, true));
        check(!live.stalled(5000, false));
        check(!live.update(9000, true, true));
        check(!live.stalled(16999, true));
        check(live.stalled(17000, true));
        live.reset();
        check(!live.update(0, true, true));
        check(!live.update(7999, false, true));
        check(!live.stalled(8000, true));
        check(!live.stalled(60000, true));
        live.reset();
        live.update(0, true, true);
        live.update(1, false, true);
        live.update(1000, true, true);
        live.update(1001, false, true);
        check(live.update(2000, true, true));
        check(!live.persistent(9999));
        check(live.persistent(10000));
        check(!live.stalled(10000, true));
        live.update(10001, false, true);
        check(!live.persistent(10002));
        System.out.println(checks + " live buffering checks passed");
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Live buffering check " + checks);
    }
}
