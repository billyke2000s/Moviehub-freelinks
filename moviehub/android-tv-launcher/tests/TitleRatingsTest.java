package local.tv.home;

import java.util.Arrays;

/** Thumbs up and down storage (TitleRatings). */
public final class TitleRatingsTest {

    private static int checks;

    public static void main(String[] args) {
        String raw = TitleRatings.with("", "movie:603", TitleRatings.LIKE);
        same("first like", "movie:603=1", raw);
        raw = TitleRatings.with(raw, "tv:1399", TitleRatings.DISLIKE);
        same("newest first", "tv:1399=-1;movie:603=1", raw);
        raw = TitleRatings.with(raw, "movie:603", TitleRatings.DISLIKE);
        same("changed rating moves first", "movie:603=-1;tv:1399=-1", raw);
        same("of", TitleRatings.DISLIKE, TitleRatings.of(raw, "movie:603"));
        same("unrated", TitleRatings.NONE, TitleRatings.of(raw, "movie:1"));
        raw = TitleRatings.with(raw, "tv:1399", TitleRatings.NONE);
        same("removed", "movie:603=-1", raw);
        same(
            "damaged parts skipped",
            "{tv:5=1}",
            TitleRatings.parse("x;tv:5=1;movie:0=1;movie:7=3;=1;tv:9=abc").toString()
        );
        same(
            "liked and disliked",
            Arrays.asList("tv:2", "movie:1"),
            TitleRatings.rated("tv:2=1;movie:3=-1;movie:1=1", TitleRatings.LIKE)
        );
        same(
            "disliked",
            Arrays.asList("movie:3"),
            TitleRatings.rated("tv:2=1;movie:3=-1", TitleRatings.DISLIKE)
        );
        String many = "";
        for (int i = 1; i <= TitleRatings.MAX + 5; i++) many = TitleRatings.with(
            many,
            "movie:" + i,
            TitleRatings.LIKE
        );
        same("bounded", TitleRatings.MAX, TitleRatings.parse(many).size());
        same("oldest dropped", TitleRatings.NONE, TitleRatings.of(many, "movie:1"));
        same("newest kept", TitleRatings.LIKE, TitleRatings.of(many, "movie:" + (TitleRatings.MAX + 5)));
        System.out.println(checks + " title rating checks passed");
    }

    private static void same(String name, Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(
            "Title ratings " + checks + " (" + name + "): " + actual
        );
    }
}
