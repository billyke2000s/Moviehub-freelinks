package local.tv.home;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Settings → System → Troubleshooting: an internet speed test, checks that each connected service
 * answers, playback timings (see {@link Timings}) and recent recovered errors (see
 * {@link ErrorLog}). It shows service names, times and plain explanations only, never addresses,
 * keys or account details.
 */
final class Troubleshooting {

    private final MainActivity activity;
    private LinearLayout body;
    /** The last results, kept while MovieHub is open so Refresh does not lose them. */
    private volatile String speedResult = "Not run yet";
    private volatile List<String[]> checkResults = new ArrayList<>();
    /** Set on the screen thread, cleared by the test itself, so leaving the screen cannot leave it stuck. */
    private volatile boolean testing, checking;

    Troubleshooting(MainActivity activity) {
        this.activity = activity;
    }

    void show() {
        body = activity.settingsScreens.settingsLayout(
            "Troubleshooting",
            activity.settingsScreens::settings,
            "System"
        );
        body.addView(
            activity.muted(
                android.os.Build.MANUFACTURER +
                    " " +
                    android.os.Build.MODEL +
                    " · Android " +
                    android.os.Build.VERSION.RELEASE +
                    " · " +
                    Runtime.getRuntime().maxMemory() / (1024 * 1024) +
                    " MB for MovieHub",
                14
            )
        );
        speedTest();
        connections();
        playback();
        recentErrors();
    }

    // ---- Internet speed test ------------------------------------------------------------------

    private void speedTest() {
        heading("Internet speed");
        row("Streaming connection quality", testing ? "Testing download, latency and jitter…" : speedResult);
        actions(new String[] { "Run speed test" }, new Runnable[] { this::runSpeedTest });
        body.addView(
            activity.muted(
                "Tests download speed, HTTP latency, jitter and failed HTTP probes against Cloudflare, not your IPTV provider. Failed requests are not an ICMP packet-loss measurement. Upload speed is not yet tested. Ratings are indicative, not a guarantee of smooth playback. Pause playback first.",
                13
            )
        );
    }

    void runSpeedTest() {
        if (testing) return;
        testing = true;
        show();
        activity.async(
            () -> {
                try {
                    SpeedTest.Result r = SpeedTest.run();
                    speedResult = String.format(
                        Locale.ROOT,
                        "%s for streaming  ·  Download %.1f Mb/s  ·  HTTP latency %s  ·  Jitter %s  ·  Failed probes %d/5  ·  %.1f MB tested",
                        r.pingMs < 0
                            ? "Unrated (latency unavailable)"
                            : SpeedTest.rating(r.mbps, r.pingMs, r.jitterMs),
                        r.mbps,
                        r.pingMs < 0 ? "unavailable" : r.pingMs + " ms",
                        r.jitterMs < 0 ? "unavailable" : r.jitterMs + " ms",
                        r.failedProbes,
                        r.bytes / (1024f * 1024f)
                    );
                } catch (Exception e) {
                    Diagnostics.recovered("Speed test", e);
                    speedResult = "Failed: " + ErrorMessages.explain(e).title;
                } finally {
                    testing = false;
                }
                return speedResult;
            },
            text -> show()
        );
    }

    // ---- Connection checks --------------------------------------------------------------------

    /** One service to check: its name and a request that throws when it does not answer properly. */
    interface Check {
        void run() throws Exception;
    }

    private void connections() {
        heading("Connections");
        if (checking) row("Checking…", "Each service is asked once; this can take up to a minute");
        else if (checkResults.isEmpty()) row("Not checked yet", "Checks each service you have connected");
        else for (String[] item : checkResults) row(item[0], item[1]);
        actions(new String[] { "Check connections" }, new Runnable[] { this::runChecks });
    }

    void runChecks() {
        if (checking) return;
        checking = true;
        show();
        List<String> names = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        addChecks(names, checks);
        activity.async(
            () -> {
                List<String[]> out = new ArrayList<>();
                try {
                    for (int i = 0; i < checks.size(); i++) {
                        long started = System.nanoTime();
                        String outcome;
                        try {
                            checks.get(i).run();
                            outcome = "Working  ·  " + (System.nanoTime() - started) / 1_000_000L + " ms";
                        } catch (Exception e) {
                            Diagnostics.recovered("Checking " + names.get(i), e);
                            outcome = "Not working: " + ErrorMessages.explain(e).title;
                        }
                        out.add(new String[] { names.get(i), outcome });
                    }
                    checkResults = out;
                } finally {
                    checking = false;
                }
                return out;
            },
            results -> show()
        );
    }

    /** The services this box uses, by name only (never their addresses or keys). */
    private void addChecks(List<String> names, List<Check> checks) {
        Secrets secrets = activity.secrets;
        boolean http = activity.http();
        names.add("Internet");
        checks.add(() -> Network.bytes(SpeedTest.URL + "0", null, 1024, false));
        if (secrets.has("tmdb")) {
            names.add("TMDB (films and series)");
            checks.add(() -> activity.media.tmdb("configuration"));
        }
        if (secrets.has("premiumize")) {
            names.add("Premiumize");
            checks.add(() -> activity.media.premiumize("account/info", secrets.get("premiumize")));
        }
        List<String> sources = new ArrayList<>();
        sources.add("https://torrentio.strem.fun");
        for (String manifest : DefaultSourceProviders.backups())
            sources.add(manifest.replaceAll("/manifest\\.json$", "").replaceAll("/+$", ""));
        for (String source : sources) {
            // Only the host is shown: a provider's address can carry its settings.
            names.add("Stream sources: " + java.net.URI.create(source).getHost());
            checks.add(() -> Network.bytes(source + "/manifest.json", null, 1024 * 1024, false));
        }
        // Account details are read inside each check (reading can fail), not on the screen thread.
        if (secrets.has("xtream")) {
            names.add("TV provider (Xtream)");
            checks.add(() -> {
                JSONObject account = new JSONObject(secrets.get("xtream"));
                MediaRepository.connectXtream(
                    account.getString("server"),
                    account.getString("username"),
                    account.getString("password"),
                    http
                );
            });
        }
        if (secrets.has("m3u")) {
            names.add("TV provider (playlist)");
            checks.add(() -> {
                String start = new String(firstBytes(secrets.get("m3u"), 512, http), "UTF-8").trim();
                if (start.startsWith("\uFEFF")) start = start.substring(1);
                if (!start.startsWith("#EXTM3U")) throw new java.io.IOException("Not a playlist");
            });
        }
        if (!EpgImporter.guideUrl.isEmpty()) {
            names.add("Programme guide");
            checks.add(() -> {
                if (firstBytes(EpgImporter.guideUrl, 2, false).length == 0) throw new java.io.IOException(
                    "Empty guide"
                );
            });
        }
    }

    /** The first {@code count} bytes of a response (playlists and guides are far too big to fetch). */
    private static byte[] firstBytes(String url, int count, boolean http) throws Exception {
        try (java.io.InputStream in = Network.open(url, null, Long.MAX_VALUE, http)) {
            byte[] out = new byte[count];
            int read = 0,
                n;
            while (read < count && (n = in.read(out, read, count - read)) != -1) read += n;
            return java.util.Arrays.copyOf(out, read);
        }
    }

    // ---- Playback -----------------------------------------------------------------------------

    /** Timings measured since MovieHub started: names, durations and counts only. */
    private void playback() {
        heading("Playback");
        body.addView(
            activity.muted(
                "Measured since MovieHub started. Browse, open a title and play something, then come back here.",
                13
            )
        );
        float mbps = Timings.lastMbps;
        row(
            "Last download speed while playing",
            mbps < 0 ? "Nothing played yet" : String.format(Locale.ROOT, "%.1f Mb/s", mbps)
        );
        List<Timings.Stat> stats = Timings.snapshot();
        if (stats.isEmpty()) row("Nothing measured yet", "Use the app, then open this page again");
        for (Timings.Stat stat : stats)
            row(
                stat.name,
                seconds(stat.last) +
                    " last  ·  " +
                    seconds(stat.average()) +
                    " average  ·  " +
                    seconds(stat.max) +
                    " slowest  ·  " +
                    stat.count +
                    (stat.count == 1 ? " time" : " times")
            );
        actions(
            new String[] { "Refresh", "Reset timings" },
            new Runnable[] {
                this::show,
                () -> {
                    Timings.clear();
                    show();
                },
            }
        );
    }

    // ---- Recent errors ------------------------------------------------------------------------

    private void recentErrors() {
        heading("Recent errors");
        List<ErrorLog.Entry> entries = ErrorLog.snapshot();
        if (entries.isEmpty()) row(
            "No errors since MovieHub started",
            "Problems the app recovered from appear here"
        );
        java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("HH:mm:ss", Locale.UK);
        for (ErrorLog.Entry entry : entries)
            row(entry.where, clock.format(new java.util.Date(entry.time)) + "  ·  " + entry.type);
        actions(
            new String[] { "Clear errors" },
            new Runnable[] {
                () -> {
                    ErrorLog.clear();
                    show();
                },
            }
        );
    }

    // ---- Layout -------------------------------------------------------------------------------

    private void heading(String title) {
        TextView label = activity.compactText(title, 23);
        label.setPadding(0, activity.dp(18), 0, activity.dp(2));
        body.addView(label);
    }

    /** Use the same card, typography, background and spacing as the parent Settings page. */
    private TextView row(String name, String value) {
        return activity.settingsScreens.settingCard(body, name, value, null, false, null, null);
    }

    private void actions(String[] labels, Runnable[] actions) {
        LinearLayout line = activity.row();
        line.setPadding(0, activity.dp(10), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            Button button = activity.button(labels[i], actions[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, activity.dp(38));
            lp.rightMargin = activity.dp(12);
            lp.bottomMargin = activity.dp(8);
            line.addView(button, lp);
        }
        body.addView(line);
    }

    private static String seconds(long millis) {
        return millis < 1000 ? millis + " ms" : String.format(Locale.ROOT, "%.1f s", millis / 1000f);
    }
}
