package local.tv.home;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Schedules channel imports with WorkManager so they run off the UI thread and survive leaving the screen. */
final class ChannelSync {

    private ChannelSync() {}

    static final long REFRESH_HOURS = 12;
    private static final String PERIODIC = "channel-refresh",
        NOW = "channel-import";

    private static Constraints network() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    /** Keeps an automatic refresh scheduled. Safe to call repeatedly. */
    static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            Job.class,
            REFRESH_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(network())
            .setInitialDelay(REFRESH_HOURS, TimeUnit.HOURS)
            .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        );
    }

    /** Starts an import now, replacing one that is queued or running. */
    static void refreshNow(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(Job.class)
            .setConstraints(network())
            .build();
        ChannelImporter.markQueued();
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request);
    }

    /**
     * Imports now if channels were never imported or the last success is older than the refresh
     * interval. Free channels alone are imported only when Live TV is opened ({@code liveTvOpened}),
     * so the full public list is not downloaded by people who never use Live TV.
     */
    static void refreshIfStale(Context context, boolean liveTvOpened) {
        Secrets secrets = new Secrets(context);
        boolean provider = secrets.has("m3u") || secrets.has("xtream") || secrets.has("masqueradarr");
        if (!provider && (!liveTvOpened || FreeChannels.playlist(context, false).isEmpty())) return;
        ChannelImporter.Status status = ChannelImporter.status(context);
        // A refresh that finished counts, even when some services failed: lastSuccess needs no
        // failures at all, so with a US-only service failing from the UK it was never set, and
        // every home or Live TV visit started a new full refresh.
        long last = Math.max(
            status.lastSuccess,
            context.getSharedPreferences("channelImport", 0).getLong("lastFinished", 0)
        );
        // Failed regional services must not trigger a new full import on every guide visit.
        long attempted = context.getSharedPreferences("channelImport", 0).getLong("lastAttempt", 0);
        if (System.currentTimeMillis() - attempted < TimeUnit.MINUTES.toMillis(10)) return;
        boolean updated = updatedSinceRefresh(
            context.getSharedPreferences("channelImport", 0).getInt("refreshedVersion", -1),
            BuildConfig.VERSION_CODE
        );
        if (
            !status.running &&
            !status.queued &&
            (updated || System.currentTimeMillis() - last > TimeUnit.HOURS.toMillis(REFRESH_HOURS))
        ) refreshNow(context);
    }

    /**
     * Whether this app version has not finished a refresh yet: an update brings new guide
     * sources and fixes, which apply only once channels are refreshed. Unknown (-1) counts as
     * updated, so the first version that records it refreshes once too.
     */
    static boolean updatedSinceRefresh(int refreshedVersion, int currentVersion) {
        return refreshedVersion != currentVersion;
    }

    public static final class Job extends Worker {

        public Job(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            boolean ok = ChannelImporter.run(getApplicationContext(), () -> {
                if (isStopped()) throw new CancellationException();
            });
            return ok ? Result.success() : getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }
}
