package local.tv.home;

final class SourceWindowTest {

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    public static void main(String[] args) {
        SourceWindow<Integer> window = new SourceWindow<>(1000, value -> value);
        check(window.pick(3999) == null);
        check(window.add("720", 720));
        check(window.add("1080", 1080));
        check(!window.add("1080", 2160));
        check(window.pick(3999) == null);
        check(window.pick(4000) == 1080);
        check(window.add("late", 2160));
        check(window.pick(4050) == 2160);
        SourceWindow<Integer> empty = new SourceWindow<>(0, value -> value);
        check(empty.pick(3000) == null);
        check(empty.add("first-late", 720));
        check(empty.pick(5100) == 720);
        empty.cancel();
        check(empty.pick(5200) == null);
        check(!empty.add("cancelled", 1080));
        // Only a weak first choice waits longer: up to 8 s, or until the search finishes.
        java.util.function.ToIntFunction<Integer> height = value -> value;
        SourceWindow<Integer> good = new SourceWindow<>(0, value -> value);
        check(good.add("1080", 1080));
        check(good.pick(3000, false, 1080, height) == 1080);
        SourceWindow<Integer> weak = new SourceWindow<>(0, value -> value);
        check(weak.add("480", 480));
        check(weak.pick(2999, false, 1080, height) == null);
        check(weak.pick(3000, false, 1080, height) == null);
        check(weak.pick(3000, true, 1080, height) == 480);
        check(weak.add("720", 720));
        check(weak.pick(7999, false, 1080, height) == null);
        check(weak.pick(8000, false, 1080, height) == 720);
        check(weak.add("late-1080", 1080));
        check(weak.pick(5000, false, 1080, height) == 1080);
        check(weak.pick(5000, false, 720, height) == 1080);
        weak.cancel();
        check(weak.pick(9000, true, 1080, height) == null);
        VodSourceFailover queue = new VodSourceFailover();
        queue.setSources(java.util.Arrays.asList(new VodSourceFailover.Source("a", "a", "one")));
        queue.onPlaying();
        check(!queue.onBufferingStarted(100));
        queue.appendSources(java.util.Arrays.asList(new VodSourceFailover.Source("b", "b", "two")));
        check(queue.isStalled(4100));
        check(queue.failover().url.equals("b"));
        queue.appendSources(
            java.util.Arrays.asList(
                new VodSourceFailover.Source("a", "a", "one"),
                new VodSourceFailover.Source("c", "c", "three")
            )
        );
        check(queue.failover().url.equals("c"));
        check(queue.failover() == null);
        System.out.println("35 source selection and late-backup checks passed");
    }
}
