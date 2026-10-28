package local.tv.home;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.videolan.libvlc.MediaPlayer;

/** Serial native joins per LibVLC player (films, through VodNative), off main. A window is retained until its reader stops. */
final class LiveNative {

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<MediaPlayer, Job> waiting = java.util.Collections.synchronizedMap(
        new IdentityHashMap<>()
    );
    private static final ExecutorService workers = new ThreadPoolExecutor(
        2,
        2,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16),
        task -> {
            Thread thread = new Thread(task, "live-native-stop");
            thread.setDaemon(true);
            return thread;
        }
    );

    private static final class Job {

        final boolean stopping;
        final List<Runnable> callbacks = new ArrayList<>();

        Job(boolean stopping) {
            this.stopping = stopping;
        }
    }

    interface Stopper {
        void stop(MediaPlayer player);
    }

    static Stopper testStopper;

    static boolean busy(MediaPlayer player) {
        return player != null && waiting.containsKey(player);
    }

    static void whenIdle(MediaPlayer player, Runnable completed) {
        Job existing = waiting.get(player);
        if (existing == null) completed.run();
        else existing.callbacks.add(completed);
    }

    static void videoOff(MediaPlayer player, Runnable completed) {
        if (busy(player)) {
            waiting.get(player).callbacks.add(() -> videoOff(player, completed));
            return;
        }
        submit(player, false, () -> player.setVideoTrack(-1), completed);
    }

    static void stop(MediaPlayer player, Runnable completed) {
        if (player == null) {
            completed.run();
            return;
        }
        Job existing = waiting.get(player);
        if (existing != null) {
            existing.callbacks.add(existing.stopping ? completed : () -> stop(player, completed));
            return;
        }
        submit(
            player,
            true,
            () -> {
                Stopper fixture = testStopper;
                if (fixture == null) player.stop();
                else fixture.stop(player);
            },
            completed
        );
    }

    private static void submit(MediaPlayer player, boolean stopping, Runnable action, Runnable completed) {
        Job job = new Job(stopping);
        job.callbacks.add(completed);
        waiting.put(player, job);
        Runnable slow = () -> {
            if (waiting.get(player) == job) Diagnostics.recovered(
                "Slow live native shutdown",
                new TimeoutException()
            );
        };
        main.postDelayed(slow, 5000);
        try {
            workers.execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException error) {
                    main.post(() -> Diagnostics.recovered("Stopping live native playback", error));
                    return; // Failed shutdown never makes releasing the window safe.
                }
                main.post(() -> {
                    main.removeCallbacks(slow);
                    waiting.remove(player);
                    for (Runnable callback : job.callbacks) callback.run();
                });
            });
        } catch (RejectedExecutionException error) {
            main.removeCallbacks(slow);
            Diagnostics.recovered("Live native stop queue exhausted", error);
            // Quarantine; do not destroy a window whose native reader could still be using it.
        }
    }
}
