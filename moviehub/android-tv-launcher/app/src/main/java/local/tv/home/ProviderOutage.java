package local.tv.home;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The guide's one line about providers that failed to refresh, grouped by the actual failure,
 * for example "Unavailable now — HTTP 403: LG Channels (US only), Roku Channel (US only) · timed
 * out: Plex (saved channels kept)". It never guesses why a service failed: a service that is down
 * for a day and one that refuses a country look the same from here. What is known about a service's
 * region is part of its name instead ("US only"), from research, not from its failures. Reasons
 * come only from {@link ProviderFailure}'s fixed summaries, never from provider text.
 */
final class ProviderOutage {

    /** Settings → Provider engine: hide the services known to be US-only. Off by default. */
    static final String HIDE_US_ONLY = "hideUsOnlyProviders";

    /**
     * Services known to be available only in the US (October 2026): LG Channels' public guide
     * service and Roku Channel are supported for the US only by FastChannels, and Local Now's own
     * adapter refuses outside the US. Their labels say "US only".
     */
    static final java.util.Set<String> US_ONLY = new java.util.HashSet<>(
        java.util.Arrays.asList("lg", "roku", "localnow")
    );

    private ProviderOutage() {}

    /** Whether a provider is hidden: only when the viewer chose to hide US-only services. */
    static boolean hidden(boolean hideUsOnly, String id) {
        return hideUsOnly && US_ONLY.contains(id);
    }

    /** The failure as the guide shows it, from its {@link ProviderFailure#describe} summary. */
    static String reason(String failure) {
        String f = failure == null ? "" : failure;
        if (
            f.equals("Connection timed out") ||
            f.equals("Provider refresh timed out") ||
            f.equals("Provider processing timed out")
        ) return "timed out";
        if (f.startsWith("Provider returned HTTP ")) return f.substring("Provider returned ".length());
        if (f.isEmpty()) return "failed";
        String text = f.startsWith("Provider ") ? f.substring("Provider ".length()) : f;
        // "Connection failed" reads as "connection failed"; an initialism such as DNS stays.
        boolean word = text.length() > 1 && Character.isLowerCase(text.charAt(1));
        return word ? Character.toLowerCase(text.charAt(0)) + text.substring(1) : text;
    }

    /** {@code failed} holds {label, failure summary} pairs in refresh order; "" when empty. */
    static String summary(List<String[]> failed) {
        if (failed.isEmpty()) return "";
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        for (String[] item : failed) {
            byReason.computeIfAbsent(reason(item[1]), k -> new ArrayList<>()).add(item[0]);
        }
        List<String> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : byReason.entrySet()) {
            groups.add(group.getKey() + ": " + String.join(", ", group.getValue()));
        }
        return "Unavailable now — " + String.join(" · ", groups) + " (saved channels kept)";
    }
}
