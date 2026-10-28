package local.tv.home;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/**
 * Programme reminders, as on a satellite box: set one on a programme in the guide, and when it is
 * about to start a panel offers to switch to it. Reminders show while MovieHub is open (the guide,
 * Home or the player); one missed by more than five minutes is dropped.
 */
final class Reminders {

    static final String PREF = "liveReminders";
    /** A reminder shows from a minute before the programme until five minutes after it starts. */
    static final long EARLY_MS = 60_000,
        LATE_MS = 5 * 60_000;

    static final class Reminder {

        final long start;
        final String title, channel, key;
        final int number;

        Reminder(long start, String title, String channel, String key, int number) {
            this.start = start;
            this.title = title;
            this.channel = channel;
            this.key = key;
            this.number = number;
        }

        String id() {
            return key + "@" + start;
        }

        String json() {
            try {
                return new JSONObject()
                    .put("start", start)
                    .put("title", title)
                    .put("channel", channel)
                    .put("key", key)
                    .put("number", number)
                    .toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        static Reminder parse(String json) {
            try {
                JSONObject item = new JSONObject(json);
                return new Reminder(
                    item.getLong("start"),
                    item.optString("title"),
                    item.optString("channel"),
                    item.getString("key"),
                    item.optInt("number")
                );
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** A reminder chosen with "Watch now" while the player was showing something else. */
    static volatile Reminder pending;

    private Reminders() {}

    private static SharedPreferences prefs(Context activity) {
        return activity.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }

    static List<Reminder> all(Context activity) {
        List<Reminder> out = new ArrayList<>();
        for (String json : prefs(activity).getStringSet(PREF, Collections.emptySet())) {
            Reminder item = Reminder.parse(json);
            if (item != null) out.add(item);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    static boolean has(Context activity, ChannelDatabase.Row row, ChannelDatabase.Programme programme) {
        String id = row.key + "@" + programme.start;
        for (Reminder item : all(activity)) if (item.id().equals(id)) return true;
        return false;
    }

    static void set(
        Context activity,
        ChannelDatabase.Row row,
        ChannelDatabase.Programme programme,
        boolean on
    ) {
        Reminder reminder = new Reminder(programme.start, programme.title, row.name, row.key, row.number);
        Set<String> values = new HashSet<>();
        for (Reminder item : all(activity)) if (!item.id().equals(reminder.id())) values.add(item.json());
        if (on) values.add(reminder.json());
        prefs(activity).edit().putStringSet(PREF, values).apply();
    }

    /**
     * The reminder due now, removed from the list, or null. Reminders more than five minutes past
     * their start are dropped.
     */
    static Reminder due(Context activity, long now) {
        Reminder found = null;
        Set<String> keep = new HashSet<>();
        boolean changed = false;
        for (Reminder item : all(activity)) {
            if (now > item.start + LATE_MS) {
                changed = true;
                continue;
            }
            if (found == null && now >= item.start - EARLY_MS) {
                found = item;
                changed = true;
                continue;
            }
            keep.add(item.json());
        }
        if (changed) prefs(activity).edit().putStringSet(PREF, keep).apply();
        return found;
    }

    /** Interface for the "Watch now" choice. */
    interface Watch {
        void watch(Reminder reminder);
    }

    /**
     * Shows the due reminder, if any: "EastEnders starts at 8.00pm on 101 BBC One", with Watch now
     * and Dismiss. Returns the panel, or null when nothing was due.
     */
    static android.app.Dialog show(Activity activity, Watch watch) {
        if (activity.isFinishing()) return null;
        Reminder due = due(activity, System.currentTimeMillis());
        if (due == null) return null;
        boolean started = System.currentTimeMillis() >= due.start;
        return SidePanel.of(activity, "Reminder")
            .message(
                due.title +
                    (started ? " started at " : " starts at ") +
                    LiveTvScreen.clockTime(new Date(due.start)) +
                    " on " +
                    (due.number > 0 ? due.number + " " : "") +
                    due.channel +
                    "."
            )
            .action("Watch now", () -> watch.watch(due))
            .action("Dismiss", null)
            .show();
    }
}
