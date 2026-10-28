package local.tv.home;

/**
 * Whether a website's quality labels can be believed. Each time a site's claimed quality can be
 * compared with the real picture (read from the file's header, or from the playing video), the
 * result is remembered. A site caught overstating quality in two of its last five comparisons has
 * its claims ignored when ranking; a confirmed size from the file itself is still used. Old results
 * fall out of the window, so a site that fixes its labels is believed again.
 *
 * Plain Java, so the core checks run it without Android; FreeVodProviders stores the history.
 */
final class QualityClaims {

    static final int REMEMBERED = 5;
    static final int DISTRUST_AFTER = 2;

    /** The claim was higher than the real picture. */
    static final int OVERSTATED = 1;
    /** The claim matched (or understated) the real picture. */
    static final int HONEST = 0;
    /** No specific claim to judge, such as "SD" or no label at all. */
    static final int NO_CLAIM = -1;

    private QualityClaims() {}

    /**
     * Compares a website's label with the real picture tier (see {@link VideoDetails#tier}). Only
     * labels naming 576p or better are judged: "SD" or a missing label promises nothing.
     */
    static int verdict(String claimed, int actualTier) {
        if (claimed == null || actualTier <= 0) return NO_CLAIM;
        int promised = SourceRanking.resolution(claimed);
        if (promised < 576) return NO_CLAIM;
        return actualTier < promised ? OVERSTATED : HONEST;
    }

    /** The history string after one more verdict: the last five as '1' (overstated) or '0'. */
    static String record(String history, int verdict) {
        String previous = history == null ? "" : history.replaceAll("[^01]", "");
        if (verdict == NO_CLAIM) return previous;
        String next = previous + (verdict == OVERSTATED ? '1' : '0');
        return next.length() > REMEMBERED ? next.substring(next.length() - REMEMBERED) : next;
    }

    static boolean distrusted(String history) {
        if (history == null) return false;
        int overstated = 0;
        for (int i = 0; i < history.length(); i++) if (history.charAt(i) == '1') overstated++;
        return overstated >= DISTRUST_AFTER;
    }

    /**
     * The quality worth telling the viewer about while a film plays: the best {@code confirmed}
     * tier among sources not {@code skipped} (the playing one, failed ones) that is HD, within the
     * {@code preferred} quality, better than the {@code current} source and better than anything
     * already {@code announced}. 0 when there is nothing new. Claims alone never count.
     */
    static int better(int[] confirmed, boolean[] skipped, int current, int preferred, int announced) {
        int best = 0;
        for (int i = 0; i < confirmed.length; i++) {
            int quality = confirmed[i];
            if (skipped[i] || quality < 720 || quality > preferred) continue;
            if (quality > current && quality > announced && quality > best) best = quality;
        }
        return best;
    }

    /** A tier's usual name: "4K", "1080p", "480p". */
    static String name(int tier) {
        return tier >= 2160 ? "4K" : tier + "p";
    }

    /**
     * The Sources-menu label once a source has played long enough to know its real picture:
     * "site · 480p ✓ (said 1080p)" when the website overstated it, and for a streaming playlist
     * playing below what it offers, "site · playing 480p of up to 1080p".
     */
    static String playingLabel(String provider, int actual, int confirmed, String claimed, boolean playlist) {
        if (playlist && confirmed > actual) {
            return provider + " · playing " + name(actual) + " of up to " + name(confirmed);
        }
        String label = provider + " · " + name(actual) + " ✓";
        if (confirmed == 0 && verdict(claimed, actual) == OVERSTATED) {
            label += " (said " + name(SourceRanking.resolution(claimed)) + ")";
        }
        return label;
    }

    /**
     * The quality used to rank a source: what the file says when it was read, otherwise the
     * website's claim, unless that website is distrusted, when the claim is treated as SD.
     */
    static String effective(String label, boolean confirmed, String siteHistory) {
        if (confirmed || !distrusted(siteHistory)) return label;
        return "SD";
    }
}
