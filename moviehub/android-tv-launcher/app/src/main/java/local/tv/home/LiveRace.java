package local.tv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds a live channel's link by looking up several of its copies at once (LiveRacePlan has the
 * rules) and hands the chosen one to the player already resolved, so starting it or moving to a
 * standby needs no new lookup. Copies found but never played are closed (a relay holds a local
 * server). Every callback runs on the main thread; a cancelled race calls nothing.
 */
final class LiveRace {

    interface Listener {
        /** Play copy {@code index}, whose link is {@code locator}, already resolved. */
        void play(int index, String locator, LiveStream.Resolution source);

        /** No copy is playing yet and the next one is still being looked up. */
        void stillLooking();

        /** Every copy failed. */
        void exhausted();
    }

    private static final ExecutorService lookups = Executors.newFixedThreadPool(LiveRacePlan.PARALLEL);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final ChannelDatabase db;
    private final List<ChannelDatabase.Row> copies;
    private final boolean allowHttp;
    private final Listener listener;
    private final LiveRacePlan plan;
    private final Map<Integer, LiveStream.Resolution> ready = new HashMap<>();
    private final Map<Integer, String> locators = new HashMap<>();
    private final Map<Integer, NativeHttp> requests = new HashMap<>();
    private final Map<Integer, Future<?>> tasks = new HashMap<>();
    private final Runnable tieEnded = this::choose;
    private boolean cancelled,
        choosing = true;
    private int current = -1;

    /** {@code candidates} are indices into {@code copies}, in the guide's copy order. */
    LiveRace(
        Context context,
        ChannelDatabase db,
        List<ChannelDatabase.Row> copies,
        int[] candidates,
        boolean allowHttp,
        Listener listener
    ) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.copies = copies;
        this.allowHttp = allowHttp;
        this.listener = listener;
        int[] quality = new int[candidates.length];
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < candidates.length; i++) {
            ChannelDatabase.Row row = copies.get(candidates[i]);
            // A copy that fell back from an upgrade recently goes last (LiveUpgrade's cooldown).
            quality[i] = LiveUpgrade.cooling(row.key, now) ? 0 : SourceRanking.resolution(row.name);
        }
        plan = new LiveRacePlan(candidates, quality, SystemClock.elapsedRealtime());
    }

    void start() {
        launch();
        main.postDelayed(tieEnded, Math.max(0, plan.tieEnds() - SystemClock.elapsedRealtime()));
    }

    /** Whether a copy found by the race is playing (not still choosing one). */
    boolean settled() {
        return !cancelled && !choosing && current >= 0;
    }

    /** Whether copy {@code index} is being looked up now (LiveUpgrade waits for it). */
    boolean lookingUp(int index) {
        return !cancelled && plan.lookingUp(index);
    }

    /** Whether copy {@code index} was found and waits unused. */
    boolean ready(int index) {
        return !cancelled && ready.containsKey(index);
    }

    /**
     * LiveUpgrade plays copy {@code index} now: hands over its link if this race found it
     * (the caller then owns it), else null. Either way the race treats it as the copy playing.
     */
    Found take(int index) {
        if (cancelled) return null;
        LiveStream.Resolution source = ready.remove(index);
        String locator = locators.get(index);
        Future<?> task = tasks.remove(index);
        if (task != null) task.cancel(true);
        NativeHttp http = requests.remove(index);
        if (http != null) http.cancel();
        plan.playing(index);
        current = index;
        choosing = false;
        main.removeCallbacks(tieEnded);
        return source == null ? null : new Found(locator, source);
    }

    /** The upgraded copy {@code failed} did not hold: copy {@code previous} plays again. */
    void fellBack(int previous, int failed) {
        if (cancelled) return;
        plan.failed(failed);
        current = previous;
    }

    /** Whether another copy could still be tried after the one playing. */
    boolean hasAlternative() {
        return !cancelled && plan.remaining();
    }

    /** The copy playing did not work: play the next one found, or wait for one. */
    void next() {
        if (cancelled) return;
        if (current >= 0) plan.failed(current);
        current = -1;
        choosing = true;
        launch();
        choose();
        if (choosing && !cancelled) listener.stillLooking();
    }

    /** Stops every lookup and closes the links found but not played. */
    void cancel() {
        if (cancelled) return;
        cancelled = true;
        main.removeCallbacks(tieEnded);
        for (NativeHttp http : requests.values()) http.cancel();
        for (Future<?> task : tasks.values()) task.cancel(true);
        for (LiveStream.Resolution source : ready.values()) close(source);
        requests.clear();
        tasks.clear();
        ready.clear();
    }

    private void launch() {
        for (int index : plan.launch()) lookUp(index);
    }

    private void lookUp(int index) {
        ChannelDatabase.Row row = copies.get(index);
        NativeHttp http = new NativeHttp();
        requests.put(index, http);
        tasks.put(
            index,
            lookups.submit(() -> {
                String locator = null;
                LiveStream.Resolution source = null;
                Exception failure = null;
                try {
                    Found found = find(context, db, row, allowHttp, http);
                    locator = found.locator;
                    source = found.source;
                } catch (Exception e) {
                    failure = e;
                }
                String found = locator;
                LiveStream.Resolution result = source;
                Exception error = failure;
                main.post(() -> arrived(index, found, result, error));
            })
        );
    }

    /** A copy's address and its playable link. */
    static final class Found {

        final String locator;
        final LiveStream.Resolution source;

        Found(String locator, LiveStream.Resolution source) {
            this.locator = locator;
            this.source = source;
        }
    }

    /** Looks up one copy's link. Blocks: call it off the main thread. */
    static Found find(
        Context context,
        ChannelDatabase db,
        ChannelDatabase.Row row,
        boolean allowHttp,
        NativeHttp http
    ) throws Exception {
        String locator = Playlist.networkUrl(db.url(row.id, row.key), allowHttp);
        // Plain stream addresses need no lookup; provider channels do (as ExoLive.open).
        LiveStream.Resolution source =
            PlutoSource.nativeUrl(locator) || EngineSources.locator(locator)
                ? LiveStream.resolve(context, locator, http, ExoLive.testResolver)
                : new LiveStream.Resolution(null, locator, null);
        return new Found(locator, source);
    }

    private void arrived(int index, String locator, LiveStream.Resolution source, Exception failure) {
        requests.remove(index);
        tasks.remove(index);
        if (cancelled || !plan.lookingUp(index)) {
            // Cancelled, or LiveUpgrade took this copy meanwhile and looked it up itself.
            close(source);
            return;
        }
        if (failure != null || source == null) {
            if (failure != null) Diagnostics.recovered("Live source race lookup", failure);
            plan.failed(index);
        } else {
            locators.put(index, locator);
            ready.put(index, source);
            plan.resolved(index);
        }
        launch();
        choose();
    }

    private void choose() {
        if (cancelled || !choosing) return;
        int index = plan.pick(SystemClock.elapsedRealtime());
        if (index >= 0) {
            choosing = false;
            current = index;
            main.removeCallbacks(tieEnded);
            listener.play(index, locators.get(index), ready.remove(index));
        } else if (!plan.remaining()) {
            choosing = false;
            listener.exhausted();
        }
    }

    private static void close(LiveStream.Resolution source) {
        if (source != null && source.relay != null) source.relay.close();
    }
}
