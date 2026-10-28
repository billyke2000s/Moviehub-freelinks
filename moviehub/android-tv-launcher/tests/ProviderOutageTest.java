package local.tv.home;

import java.util.ArrayList;
import java.util.List;

public final class ProviderOutageTest {

    private static int checks;

    private static void same(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "Outage " + checks + ": expected [" + expected + "] got [" + actual + "]"
            );
        }
        checks++;
    }

    public static void main(String[] args) {
        // The real failure only: a 403 or a script error is never called geo-locked, because a
        // service that is down for a day looks the same as one refusing a country.
        same("HTTP 403", ProviderOutage.reason("Provider returned HTTP 403"));
        same("HTTP 451", ProviderOutage.reason("Provider returned HTTP 451"));
        same("error (EcmaError)", ProviderOutage.reason("Provider error (EcmaError)"));
        same("timed out", ProviderOutage.reason("Connection timed out"));
        same("timed out", ProviderOutage.reason("Provider refresh timed out"));
        same("HTTP 500", ProviderOutage.reason("Provider returned HTTP 500"));
        same("DNS lookup failed", ProviderOutage.reason("DNS lookup failed"));
        same("connection failed", ProviderOutage.reason("Connection failed"));
        same("failed", ProviderOutage.reason(""));
        same("", ProviderOutage.summary(new ArrayList<>()));

        List<String[]> failed = new ArrayList<>();
        failed.add(new String[] { "LG Channels (US only)", "Provider returned HTTP 403" });
        failed.add(new String[] { "Plex", "Connection timed out" });
        failed.add(new String[] { "Roku Channel (US only)", "Provider returned HTTP 403" });
        same(
            "Unavailable now — HTTP 403: LG Channels (US only), Roku Channel (US only) · " +
                "timed out: Plex (saved channels kept)",
            ProviderOutage.summary(failed)
        );

        // Hiding is the viewer's choice and applies only to services known to be US-only,
        // whatever their refreshes did.
        same("false", String.valueOf(ProviderOutage.hidden(false, "lg")));
        same("true", String.valueOf(ProviderOutage.hidden(true, "lg")));
        same("true", String.valueOf(ProviderOutage.hidden(true, "localnow")));
        same("true", String.valueOf(ProviderOutage.hidden(true, "roku")));
        same("false", String.valueOf(ProviderOutage.hidden(true, "plex")));
        same("false", String.valueOf(ProviderOutage.hidden(true, "samsung")));
        System.out.println(checks + " provider outage checks passed");
    }
}
