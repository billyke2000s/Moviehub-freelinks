package local.tv.home;

import android.app.Activity;
import android.content.Context;

/**
 * A plain warning, shown when MovieHub is first opened and kept in Settings: MovieHub never asks
 * you to leave the app, visit a website, ring a number, install anything or pay during a stream.
 * Free channels come from public lists MovieHub does not control, and a stream could one day show
 * a scam message ("your device has a virus, call …"), so it says what to do if one does.
 */
final class SafetyNotice {

    static final String PREF = "safetyNoticeSeen";

    static final String TITLE = "Staying safe";
    static final String TEXT =
        "MovieHub will never ask you to leave the app while you're watching.\n\n" +
        "If a message in a stream tells you to visit a website, ring a number, install something " +
        "or pay, it isn't from us. Ignore it and watch something else.\n\n" +
        "Free channels and optional sources come from independent third parties. MovieHub does not " +
        "host or endorse their content. Only watch content you are authorised to access.\n\n" +
        "You can read this again in Settings → System.";

    private SafetyNotice() {}

    static boolean seen(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    /** Shows the notice the first time MovieHub opens; it comes back until "I understand". */
    static void showOnce(Activity activity) {
        if (!seen(activity)) show(activity);
    }

    static void show(Activity activity) {
        SidePanel.of(activity, TITLE)
            .message(TEXT)
            .action("I understand", () ->
                activity
                    .getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF, true)
                    .apply()
            )
            .show();
    }
}
