package local.tv.home;

import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.LinearLayout;

/** Thumbs up and down on a film or series ({@link TitleRatings}), from its page and its poster. */
final class RatingActions {

    private RatingActions() {}

    static int get(SharedPreferences prefs, String type, int id) {
        return TitleRatings.of(prefs.getString(TitleRatings.PREF, ""), TitleRatings.key(type, id));
    }

    /** Rates the title; choosing the rating it already has removes it, as streaming apps do. */
    static int toggle(SharedPreferences prefs, String type, int id, int rating) {
        if (id <= 0 || !(type.equals("movie") || type.equals("tv"))) return TitleRatings.NONE;
        int next = get(prefs, type, id) == rating ? TitleRatings.NONE : rating;
        prefs
            .edit()
            .putString(
                TitleRatings.PREF,
                TitleRatings.with(prefs.getString(TitleRatings.PREF, ""), TitleRatings.key(type, id), next)
            )
            .apply();
        return next;
    }

    static String likeLabel(int rating) {
        return rating == TitleRatings.LIKE ? "👍 Liked" : "👍 Like";
    }

    static String dislikeLabel(int rating) {
        return rating == TitleRatings.DISLIKE ? "👎 Not for me ✓" : "👎 Not for me";
    }

    /** The two buttons on a title's page; each shows the current choice and updates the other. */
    static void buttons(MainActivity activity, LinearLayout parent, String type, int id) {
        int rating = get(activity.prefs, type, id);
        Button[] like = new Button[1],
            dislike = new Button[1];
        like[0] = activity.button(likeLabel(rating), () -> {});
        dislike[0] = activity.button(dislikeLabel(rating), () -> {});
        Runnable show = () -> {
            int now = get(activity.prefs, type, id);
            like[0].setText(likeLabel(now));
            dislike[0].setText(dislikeLabel(now));
        };
        like[0].setOnClickListener(v -> {
            toggle(activity.prefs, type, id, TitleRatings.LIKE);
            show.run();
        });
        dislike[0].setOnClickListener(v -> {
            int now = toggle(activity.prefs, type, id, TitleRatings.DISLIKE);
            show.run();
            if (now == TitleRatings.DISLIKE) activity.toast("We won't suggest this in For you");
        });
        for (Button button : new Button[] { like[0], dislike[0] }) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, activity.dp(38));
            lp.rightMargin = activity.dp(12);
            parent.addView(button, lp);
        }
    }
}
