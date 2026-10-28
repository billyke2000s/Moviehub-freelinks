package local.tv.home;

public final class ColourPressTest {

    private static int checks;

    private static void check(boolean result) {
        checks++;
        if (!result) throw new AssertionError("Colour press check " + checks);
    }

    public static void main(String[] args) {
        ColourPress p = new ColourPress();
        check(p.accept(1, 'r', 0, 100, false));
        check(!p.accept(1, 'r', 0, 100, false));
        check(!p.accept(1, 'r', 1, 100, false));
        check(!p.accept(1, 'r', 1, 100, false));
        check(p.accept(1, 'r', 0, 200, false));
        check(p.accept(1, 'r', 0, 300, false));
        check(!p.accept(1, 'r', 0, 300, false));
        check(!p.accept(1, 'r', 1, 300, false));
        check(p.accept(1, 'g', 1, 400, false));
        check(!p.accept(1, 'g', 1, 400, false));
        check(p.accept(1, 'g', 1, 500, false));
        check(p.accept(1, 'b', 2, 600, false));
        check(!p.accept(1, 'b', 2, 600, false));
        check(!p.accept(1, 'b', 1, 600, false));
        check(!p.accept(1, 'y', 1, 700, true));
        check(!p.accept(1, 'y', 1, 700, false));
        check(p.accept(1, 'y', 0, 800, false));
        check(!p.accept(1, 'y', 1, 800, true));
        check(p.accept(1, 'y', 0, 900, false));
        check(p.accept(2, 'y', 0, 900, false));
        check(p.accept(1, 'g', 0, 900, false));
        check(!p.accept(1, 'g', 3, 1000, false));
        System.out.println(checks + " colour press checks passed");
    }
}
