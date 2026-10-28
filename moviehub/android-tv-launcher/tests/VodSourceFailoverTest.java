package local.tv.home;

import java.util.Arrays;

/** Policy boundaries, exclusions, duplicate rejection, provider pinning and bounded exhaustion. */
public final class VodSourceFailoverTest {

    private static int checks;

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("VOD check " + checks);
    }

    private static VodSourceFailover queue() {
        VodSourceFailover q = new VodSourceFailover();
        q.setSources(
            Arrays.asList(
                new VodSourceFailover.Source("https://example.org/a", "A", "one"),
                new VodSourceFailover.Source("https://example.org/b", "B", "one"),
                new VodSourceFailover.Source("https://example.org/c", "C", "two")
            )
        );
        return q;
    }

    public static void main(String[] args) {
        var q = queue();
        check(!q.onBufferingStarted(0));
        check(!q.isStalled(5000));
        q.onPlaying();
        check(!q.onBufferingStarted(1000));
        check(!q.isStalled(4999));
        check(q.isStalled(5000));
        q.onBufferingEnded();
        check(!q.isStalled(5001));
        check(q.onBufferingStarted(60000));
        check(q.failover().label.equals("B"));
        check(!q.onBufferingStarted(62000));
        q.onPlaying();
        q.setSeeking(true);
        check(!q.onBufferingStarted(65000));
        q.setSeeking(false);
        q.setUserPaused(true);
        check(!q.onBufferingStarted(66000));
        q.setUserPaused(false);
        check(!q.onBufferingStarted(67000));
        check(q.failover().label.equals("C"));
        check(q.failover() == null);
        check(q.failover() == null);
        q = queue();
        q.onPlaying();
        check(!q.onBufferingStarted(1000));
        q.onBufferingEnded();
        check(!q.onBufferingStarted(61001));
        q.onBufferingEnded();
        check(q.onBufferingStarted(62000));
        q = queue();
        check(q.select(0, true));
        check(q.failover().label.equals("B"));
        check(q.failover() == null);
        check(q.select(2, false));
        check(q.currentIndex() == 2);
        check(!q.select(-1, false));
        q = queue();
        check(q.select(1, false));
        q.exclude(0);
        check(q.failover().label.equals("C"));
        check(q.failover() == null);
        q.setSources(
            Arrays.asList(
                new VodSourceFailover.Source("a", "A", "p"),
                new VodSourceFailover.Source("a", "duplicate", "p"),
                null
            )
        );
        check(q.sources().size() == 1);
        check(q.failover() == null);
        System.out.println(checks + " VOD failover checks passed");
    }
}
