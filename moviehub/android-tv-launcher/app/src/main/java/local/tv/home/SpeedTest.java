package local.tv.home;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The internet speed test in Troubleshooting: downloads from Cloudflare's public speed-test service
 * for up to {@link #MAX_MS} or {@link #MAX_BYTES}, whichever comes first. Runs only when asked; it
 * sends nothing about the viewer or their accounts. Plain Java (the arithmetic is in the core
 * checks).
 */
final class SpeedTest {

    static final String URL = "https://speed.cloudflare.com/__down?bytes=";
    static final long MAX_BYTES = 50L * 1024 * 1024;
    static final long MAX_MS = 10_000;

    static final class Result {

        /** Megabits per second, and how long the server took to start answering. */
        final float mbps;
        final long answerMs;
        final long bytes;
        final long pingMs;
        final long jitterMs;
        final int failedProbes;

        Result(float mbps, long answerMs, long bytes, long pingMs, long jitterMs, int failedProbes) {
            this.mbps = mbps;
            this.answerMs = answerMs;
            this.bytes = bytes;
            this.pingMs = pingMs;
            this.jitterMs = jitterMs;
            this.failedProbes = failedProbes;
        }
    }

    private SpeedTest() {}

    static String rating(float mbps, long ping, long jitter) {
        if (ping < 0 || jitter < 0) return "Unrated";
        if (mbps < 5 || (ping >= 0 && ping > 250) || (jitter >= 0 && jitter > 100)) return "Poor";
        if (mbps < 15 || (ping >= 0 && ping > 100) || (jitter >= 0 && jitter > 40)) return "Fair";
        if (mbps < 25 || (ping >= 0 && ping > 60) || (jitter >= 0 && jitter > 20)) return "Good";
        return "Excellent";
    }

    // HTTP latency is measured against the speed-test endpoint, not ICMP ping or an IPTV server.
    private static long[] latency() {
        long[] values = new long[5];
        int count = 0;
        long changes = 0, previous = -1;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(URL + "0&nonce=" + System.nanoTime()).openConnection();
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                connection.setUseCaches(false);
                long start = System.nanoTime();
                try (InputStream stream = connection.getInputStream()) { stream.read(); }
                long sample = (System.nanoTime() - start) / 1_000_000L;
                values[count++] = sample;
                if (previous >= 0) changes += Math.abs(sample - previous);
                previous = sample;
            } catch (Exception ignored) {
                // A failed probe must not invalidate a successful download test.
            } finally { if (connection != null) connection.disconnect(); }
        }
        if (count < 2) return new long[] {-1, -1, 5 - count};
        java.util.Arrays.sort(values, 0, count);
        return new long[] { values[count / 2], changes / (count - 1), 5 - count };
    }


    /** Megabits per second for {@code bytes} in {@code millis}; 0 when nothing was timed. */
    static float mbps(long bytes, long millis) {
        if (bytes <= 0 || millis <= 0) return 0;
        return (bytes * 8f) / (millis * 1000f);
    }

    /** Runs the test on this thread (not the screen's). */
    static Result run() throws Exception {
        long[] latency = latency();
        long asked = System.nanoTime();
        try (InputStream in = Network.open(URL + MAX_BYTES, null, MAX_BYTES + 1, false)) {
            long answered = System.nanoTime();
            byte[] buffer = new byte[64 * 1024];
            long bytes = 0;
            long deadline = answered + MAX_MS * 1_000_000L;
            int n;
            while (System.nanoTime() < deadline && (n = in.read(buffer)) != -1) bytes += n;
            long millis = (System.nanoTime() - answered) / 1_000_000L;
            return new Result(mbps(bytes, millis), (answered - asked) / 1_000_000L, bytes, latency[0], latency[1], (int) latency[2]);
        }
    }
}
