package local.tv.home;

import java.util.Locale;
import java.util.regex.Pattern;

/** Picture quality first, then bounded startup transfer evidence among equal-quality sources. */
final class SourceRanking {

    private static boolean word(String text, String pattern) {
        return Pattern.compile("(?<![a-z0-9])(?:" + pattern + ")(?![a-z0-9])")
            .matcher(text)
            .find();
    }

    static int resolution(String description) {
        String text = description.toLowerCase(Locale.ROOT);
        if (word(text, "2160p?|4k")) return 2160;
        if (word(text, "1440p?|2k")) return 1440;
        if (word(text, "1080[pi]?|fhd|full[ -]?hd")) return 1080;
        if (word(text, "720p?|hd")) return 720;
        if (word(text, "576p?")) return 576;
        if (word(text, "uhd")) return 2160;
        // A confirmed small picture ("360p", "240p") ranks below a plain SD claim.
        java.util.regex.Matcher low = Pattern.compile("(?<![a-z0-9])([1-4]\\d{2})p(?![a-z0-9])").matcher(
            text
        );
        if (low.find()) return Math.min(480, Integer.parseInt(low.group(1)));
        return 480;
    }

    static boolean camera(String description) {
        return word(description.toLowerCase(Locale.ROOT), "cam|camrip|hdcam|hdts|telesync|telecine|tsrip");
    }

    static int rank(String description, int preferred) {
        int quality = resolution(description);
        int score = quality <= preferred ? quality : -quality;
        if (camera(description)) score -= 10000;
        return score;
    }

    static int readyRank(String description, int preferred, double probeMbps, long responseMs) {
        return readyRank(description, preferred, probeMbps, responseMs, false);
    }

    /**
     * {@code confirmed} means the quality was read from the file or stream itself rather than taken
     * from the website. Among equal quality it outranks any transfer sample; it never lifts a source
     * into a higher quality.
     */
    static int readyRank(
        String description,
        int preferred,
        double probeMbps,
        long responseMs,
        boolean confirmed
    ) {
        // Transfer samples include startup latency and are an estimate, never a stability guarantee.
        double sample = Double.isFinite(probeMbps) ? Math.max(0, Math.min(99, probeMbps)) : 0;
        int response = (int) Math.max(0, 999 - Math.max(0, Math.min(999, responseMs)));
        return (
            rank(description, preferred) * 100000 +
            (confirmed ? 100000 : 0) +
            (int) (sample * 1000) +
            response
        );
    }
}
