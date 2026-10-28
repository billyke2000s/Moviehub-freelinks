package local.tv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Moves the full-screen Live TV channel to a better copy as soon as one is found (rules in
 * LiveUpgradePlan, core-tested). Once a second it picks the best untried copy whose name claims
 * a better picture than the one playing, uses the source race's link for it when the race already
 * found it, else looks it up (and checks a plain address answers), then hands it to LiveStage.
 * While the new copy is on trial, ExoLive's buffering rules see another source waiting, so a copy
 * that keeps buffering or fails is reported at once and LiveStage returns to the copy before.
 * Main thread only, except the lookup.
 */
final class LiveUpgrade {

    /** Copies cooling down after falling back, kept across channels for the app session. */
    static final LiveUpgradePlan.Cooldowns cooldowns = new LiveUpgradePlan.Cooldowns();
    /** How long an upgraded copy is on trial; device tests may shorten it. */
    static long trialMs = LiveUpgradePlan.TRIAL_MS;
    static final long TICK_MS = 1000;
    /** A plain address must answer within this to count as found. */
    static final int PROBE_MS = 5000;

    private static final ExecutorService lookups = Executors.newSingleThreadExecutor();
    private final LiveStage stage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LiveUpgradePlan plan = new LiveUpgradePlan(cooldowns, trialMs);
    private final Runnable tick = this::tick;
    private int ticket;
    private NativeHttp http;
    private Future<?> task;
    /** The address of the copy playing before the upgrade, to go back to. */
    private String previousUrl;

    LiveUpgrade(LiveStage stage) {
        this.stage = stage;
    }

    /** Whether {@code key} fell back recently and should not be chosen first. */
    static boolean cooling(String key, long now) {
        return cooldowns.active(key, now);
    }

    /** A new channel or a copy chosen another way: forget any upgrade, and keep watching. */
    void reset() {
        cancelLookup();
        plan = new LiveUpgradePlan(cooldowns, trialMs);
        previousUrl = null;
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK_MS);
    }

    /** Full screen ended or MovieHub left: stop looking. */
    void stop() {
        cancelLookup();
        plan = new LiveUpgradePlan(cooldowns, trialMs);
        previousUrl = null;
        main.removeCallbacks(tick);
    }

    /** An upgraded copy is on trial: another source (the one before) is waiting. */
    boolean trial() {
        return plan.trial();
    }

    /**
     * The playing copy failed (an error, a missed start or the buffering rules). During a trial
     * this goes back to the copy before and returns true; otherwise it returns false and the
     * usual failover applies.
     */
    boolean failed() {
        if (!plan.trial()) return false;
        int failedCopy = plan.target();
        int back = plan.fallBack(SystemClock.elapsedRealtime());
        String url = previousUrl;
        previousUrl = null;
        if (back < 0 || url == null) return false;
        ExoLiveLog.event("Better source did not hold; back to source " + (back + 1));
        stage.upgradeFellBack(back, failedCopy, url);
        return true;
    }

    private void cancelLookup() {
        ticket++;
        if (http != null) http.cancel();
        if (task != null) task.cancel(true);
        http = null;
        task = null;
    }

    private void tick() {
        main.postDelayed(tick, TICK_MS);
        if (!stage.upgradeWatching()) return;
        long now = SystemClock.elapsedRealtime();
        if (plan.held(now)) ExoLiveLog.event("Better source kept");
        if (!plan.idle()) return;
        LiveZapper zapper = stage.zapper();
        if (zapper == null || !zapper.automaticSources()) return;
        List<ChannelDatabase.Row> copies = zapper.copies();
        int current = zapper.selectedCopy();
        if (copies.size() <= 1 || current < 0 || current >= copies.size()) return;
        LiveRace race = stage.race();
        // While the race is still choosing a copy to play, there is nothing to upgrade yet.
        if (race != null && !race.settled()) return;
        int[] lines = new int[copies.size()];
        String[] keys = new String[copies.size()];
        boolean[] untried = new boolean[copies.size()];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = SourceRanking.resolution(copies.get(i).name);
            keys[i] = copies.get(i).key;
            // A copy the race is still looking up is left to it: its link arrives as a standby.
            untried[i] = zapper.untriedCopy(i) && (race == null || !race.lookingUp(i));
        }
        int playing = Math.max(lines[current], ExoLiveTracks.bestVideoLines());
        int index = plan.choose(current, playing, lines, keys, untried, now);
        if (index < 0) return;
        LiveRace.Found standby = race != null && race.ready(index) ? race.take(index) : null;
        if (standby != null) {
            ExoLiveLog.event("Better source " + (index + 1) + " already found by the race");
            begin(index, standby);
        } else lookUp(index, copies.get(index), zapper);
    }

    private void lookUp(int index, ChannelDatabase.Row row, LiveZapper zapper) {
        int mine = ++ticket;
        Context context = stage.activity().getApplicationContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        boolean allowHttp = stage.allowHttp();
        NativeHttp request = new NativeHttp();
        http = request;
        ExoLiveLog.event("Looking up better source " + (index + 1));
        task = lookups.submit(() -> {
            LiveRace.Found found = null;
            Exception failure = null;
            try {
                found = LiveRace.find(context, db, row, allowHttp, request);
                if (plain(found) && !answers(found.locator)) throw new java.io.IOException("No answer");
            } catch (Exception e) {
                failure = e;
            }
            LiveRace.Found result = found;
            Exception error = failure;
            main.post(() -> arrived(mine, index, zapper, result, error));
        });
    }

    private void arrived(int mine, int index, LiveZapper zapper, LiveRace.Found found, Exception failure) {
        if (mine != ticket || !plan.looking() || plan.target() != index) {
            close(found);
            return;
        }
        http = null;
        task = null;
        if (failure != null || found == null) {
            close(found);
            if (failure != null) Diagnostics.recovered("Looking up a better live source", failure);
            ExoLiveLog.event("Better source " + (index + 1) + " could not be reached");
            plan.notFound();
            // Tried: neither upgrades nor failover try it again this visit.
            zapper.raceFailed(new int[] { index });
            return;
        }
        if (
            !stage.upgradeWatching() || stage.zapper() != zapper || zapper.selectedCopy() != plan.previous()
        ) {
            // The screen or the copy playing changed meanwhile: try again from what plays now.
            close(found);
            plan.notFound();
            return;
        }
        LiveRace race = stage.race();
        if (race != null) close(race.take(index));
        begin(index, found);
    }

    private void begin(int index, LiveRace.Found found) {
        previousUrl = stage.playbackUrl();
        plan.started(SystemClock.elapsedRealtime());
        stage.upgradeTo(index, found.locator, found.source);
    }

    private static void close(LiveRace.Found found) {
        if (found != null && found.source != null && found.source.relay != null) found.source.relay.close();
    }

    /** A plain stream address: nothing was looked up, so nothing yet shows it answers. */
    private static boolean plain(LiveRace.Found found) {
        return (
            found.source.provider == null &&
            found.source.relay == null &&
            !PlutoSource.nativeUrl(found.locator) &&
            !EngineSources.locator(found.locator)
        );
    }

    /**
     * Whether a plain address answers with stream data, so a dead copy is never switched to over
     * a working one: a successful status and its first bytes (an HLS playlist must look like one).
     */
    static boolean answers(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(PROBE_MS);
            connection.setReadTimeout(PROBE_MS);
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return false;
            byte[] start = new byte[16];
            int read = 0;
            try (InputStream in = connection.getInputStream()) {
                while (read < start.length) {
                    int n = in.read(start, read, start.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            if (read == 0) return false;
            String lower = address.toLowerCase(Locale.ROOT);
            if (lower.contains(".m3u8")) {
                return new String(start, 0, read, StandardCharsets.UTF_8).trim().startsWith("#EXTM3U");
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
