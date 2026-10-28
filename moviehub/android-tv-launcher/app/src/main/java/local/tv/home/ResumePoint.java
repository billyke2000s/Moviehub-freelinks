package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where to carry on watching a film or episode (SharedPreferences {@code positions}). Progress is
 * kept by title ({@link #titleKey}: the TMDB film, or the TMDB series, season and episode), so any
 * source or file for the same film or episode carries on from the same place, as streaming apps
 * do. It is also saved under the played file's id, used only when the title is not known (a file
 * opened from the cloud). Live TV is never saved.
 */
final class ResumePoint {

    private ResumePoint() {}

    /** Treat a video as finished near its end (the rule is {@link PlaybackEnd#nearEnd}). */
    static boolean completed(long position, long length) {
        return PlaybackEnd.nearEnd(position, length);
    }

    private static SharedPreferences positions(Context context) {
        return context.getSharedPreferences("positions", Context.MODE_PRIVATE);
    }

    /**
     * The title a file belongs to: "title:tv:" and the episode key (TMDB id, season and episode)
     * for an episode, "title:movie:" and the TMDB id for a film, or null when it is not known (a
     * file opened from the cloud). {@code metadata} is a TMDB item or a recent-playback record.
     */
    static String titleKey(org.json.JSONObject metadata) {
        if (metadata == null) return null;
        String episode = metadata.optString("episodeKey", "");
        if (!episode.isEmpty()) return "title:tv:" + episode;
        // A series (or anything that looks like one) without an episode is not a film.
        if (
            metadata.has("season") ||
            metadata.has("first_air_date") ||
            metadata.has("number_of_seasons") ||
            "tv".equals(metadata.optString("media_type"))
        ) return null;
        int tmdbId = metadata.optInt("tmdbId", metadata.optInt("id"));
        return tmdbId > 0 ? "title:movie:" + tmdbId : null;
    }

    /**
     * The saved position in milliseconds: the title's when it has one (progress follows the film
     * or episode, whichever source played it last), else this file's own (a file opened from the
     * cloud), else 0. Also 0 when resuming is turned off.
     */
    static long load(Context context, String id, String titleKey) {
        if (!context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("resume", true)) return 0;
        java.util.Map<String, ?> all = positions(context).getAll();
        Object saved = titleKey == null ? null : all.get(titleKey);
        if (!(saved instanceof Number)) saved = all.get(id);
        long point = saved instanceof Number ? ((Number) saved).longValue() : 0;
        long duration = duration(all, id, titleKey);
        if (duration > 0 && completed(point, duration)) {
            clear(context, id, titleKey);
            return 0;
        }
        return point;
    }

    /**
     * The saved length: the title's (the copy that played it last, which set its position), else
     * this file's, else 0. Older versions saved the length only under the file's id.
     */
    static long duration(java.util.Map<String, ?> all, String id, String titleKey) {
        Object saved = titleKey == null ? null : all.get(titleKey + ":duration");
        if (!(saved instanceof Number)) saved = all.get(id + ":duration");
        return saved instanceof Number ? ((Number) saved).longValue() : 0;
    }

    /** Saves the length (when known) and, unless it ended or resuming is off, the position. */
    static void save(Context context, String id, String titleKey, long position, long length, boolean ended) {
        SharedPreferences.Editor edit = positions(context).edit();
        if (length > 0) {
            edit.putLong(id + ":duration", length);
            if (titleKey != null) edit.putLong(titleKey + ":duration", length);
        }
        if (ended || completed(position, length)) {
            edit.remove(id);
            if (titleKey != null) edit.remove(titleKey);
        } else if (context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("resume", true)) {
            edit.putLong(id, position);
            if (titleKey != null) edit.putLong(titleKey, position);
        }
        edit.apply();
    }

    /** Forgets the position once the film or episode has been watched to the end. */
    static void clear(Context context, String id, String titleKey) {
        SharedPreferences.Editor edit = positions(context).edit().remove(id);
        if (titleKey != null) edit.remove(titleKey);
        edit.apply();
    }
}
