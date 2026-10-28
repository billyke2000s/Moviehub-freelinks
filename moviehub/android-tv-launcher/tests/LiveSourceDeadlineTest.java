package local.tv.home;

public final class LiveSourceDeadlineTest {

    public static void main(String[] args) {
        boolean[][] cases = {
            { false, false, false },
            { false, false, true },
            { true, false, false },
            { true, false, true },
            { false, true, false },
            { false, true, true },
            { true, true, false },
            { true, true, true },
        };
        long[] expected = { 0, 0, 15000, 8000, 60000, 60000, 15000, 8000 };
        for (int i = 0; i < cases.length; i++) {
            boolean[] c = cases[i];
            if (LiveSourceDeadline.milliseconds(c[0], c[1], c[2]) != expected[i]) throw new AssertionError(
                "Startup budget case " + i
            );
        }
        System.out.println(cases.length + " live source deadline checks passed");
    }
}
