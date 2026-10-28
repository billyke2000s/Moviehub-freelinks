package local.tv.home;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

/** Opt-in public-network audit. Ordinary device tests never depend on third-party availability. */
@RunWith(AndroidJUnit4.class)
public final class FreeVodLiveAuditTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private File output;

    @Test
    public void auditEveryDiscoveredPlaybackSource() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Explicit live audit only", "true".equals(arguments.getString("vodLiveAudit")));
        int shard = Integer.parseInt(arguments.getString("vodShard", "0"));
        int shards = Integer.parseInt(arguments.getString("vodShards", "1"));
        output = new File(context.getFilesDir(), "vod-live-audit.jsonl");
        try (FileWriter clear = new FileWriter(output, false)) {
            clear.write("");
        }
        boolean series = Boolean.parseBoolean(arguments.getString("vodSeries", "false"));
        List<String> sites = FreeVodProviders.ids(context, series);
        String providers = arguments.getString("vodProviders", "");
        if (!providers.isEmpty()) sites.removeIf(
            site -> !java.util.Arrays.asList(providers.split(",")).contains(site)
        );
        org.junit.Assert.assertFalse("No matching enabled movie websites selected", sites.isEmpty());
        for (int index = shard; index < sites.size(); index += shards) {
            String site = sites.get(index);
            // Full, unambiguous catalogue identities. Runtime tolerance allows credits and alternate cuts.
            JSONObject[] films = {
                film("Inception", "2010", "tt1375666", 27205, 148),
                film("Night of the Living Dead", "1968", "tt0063350", 10331, 96),
                film("Spider-Man: Brand New Day", "2026", "tt22084616", 969681, 0),
            };
            if (series) films = new JSONObject[] {
                film("Breaking Bad", "2008", "tt0903747", 1396, 58)
                    .put("season", 1)
                    .put("episode", 1)
                    .put("premiered", "2008-01-20")
                    .put("episodeTitle", "Pilot"),
            };
            if (!series && site.equals("scrubs:rarefilmm_com")) films = new JSONObject[] {
                film("Anna", "1967", "", 45486, 87),
            };
            for (JSONObject film : films) {
                List<SourceRepository.Source> sources = new ArrayList<>();
                AtomicBoolean cancelled = new AtomicBoolean();
                java.util.Timer timer = new java.util.Timer(true);
                timer.schedule(
                    new java.util.TimerTask() {
                        @Override
                        public void run() {
                            cancelled.set(true);
                        }
                    },
                    150000
                );
                JSONObject lookup = new JSONObject()
                    .put("kind", "lookup")
                    .put("website", site)
                    .put("tmdb", film.getInt("tmdb"))
                    .put("season", film.optInt("season", -1))
                    .put("episode", film.optInt("episode", -1))
                    .put("title", film.getString("title"));
                try {
                    JSONObject report = new JSONObject(
                        FreeVodProviders.search(context, site, film, cancelled, sources::add)
                    );
                    lookup.put("status", report.optString("status")).put("sources", sources.size());
                } catch (Exception error) {
                    lookup.put("status", error.getClass().getSimpleName());
                } finally {
                    cancelled.set(true);
                    timer.cancel();
                }
                record(lookup);
                String sourceNames = arguments.getString("vodSourceNames", "");
                if (!sourceNames.isEmpty()) sources.removeIf(source -> {
                    String label = SourcesScreen.provider(source.title);
                    for (String name : sourceNames.split(",")) {
                        if (label.contains(" · " + name)) return false;
                    }
                    return true;
                });
                Set<String> keys = new HashSet<>();
                for (SourceRepository.Source source : sources) {
                    boolean separate = keys.add(VodSearch.sourceKey(source));
                    JSONObject result = new JSONObject()
                        .put("kind", "playback")
                        .put("website", site)
                        .put("provider", SourcesScreen.provider(source.title))
                        .put("advertisedQuality", SourcesScreen.quality(source.title))
                        .put("tmdb", film.getInt("tmdb"))
                        .put("season", film.optInt("season", -1))
                        .put("episode", film.optInt("episode", -1))
                        .put("title", film.getString("title"))
                        .put("separateSource", separate);
                    try {
                        play(source, film.optInt("runtimeMinutes"), result);
                    } catch (Exception error) {
                        // Exception messages and native logs may contain signed URLs: retain type only.
                        result.put("status", error.getClass().getSimpleName());
                    }
                    record(result);
                }
                if (!sources.isEmpty()) break;
            }
        }
        record(new JSONObject().put("kind", "finished").put("shard", shard).put("shards", shards));
    }

    private JSONObject film(String title, String year, String imdb, int tmdb, int minutes) throws Exception {
        return new JSONObject()
            .put("title", title)
            .put("year", year)
            .put("imdb", imdb)
            .put("tmdb", tmdb)
            .put("runtimeMinutes", minutes)
            .put("season", -1)
            .put("episode", -1)
            .put("http", false);
    }

    private void record(JSONObject row) throws Exception {
        try (FileWriter writer = new FileWriter(output, true)) {
            writer.write(row.toString());
            writer.write('\n');
        }
    }

    private void play(SourceRepository.Source source, int expectedMinutes, JSONObject result)
        throws Exception {
        VodSources.Session session = new VodSources.Session(
            java.util.Collections.singletonList(
                new VodSourceFailover.Source("audit", "Public source audit", "Audit")
            ),
            index -> {
                throw new IllegalStateException("No automatic fallback in individual audit");
            },
            true
        );
        session.free = true;
        session.transport = index -> source.stream;
        VlcPlaybackActivity activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", source.uri)
                .putExtra("title", "Individual source audit")
                .putExtra("vodSession", VodSources.remember(session))
        );
        ErrorLog.clear();
        try {
            long deadline = SystemClock.elapsedRealtime() + 45000;
            long[] sample = new long[7];
            boolean decoded = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                snapshot(activity, sample);
                if (sample[0] > 0 && sample[1] > 500 && sample[2] > 5) {
                    decoded = true;
                    break;
                }
                SystemClock.sleep(250);
            }
            long duration = sample[0];
            boolean plausible =
                expectedMinutes > 0 &&
                duration >= expectedMinutes * 60000L * .85 &&
                duration <= expectedMinutes * 60000L * 1.15;
            result
                .put("durationSeconds", duration / 1000)
                .put("expectedMinutes", expectedMinutes)
                .put("decodedFrames", sample[2])
                .put("decodedAudio", sample[5])
                .put("selectedAudioLanguage", audioLanguage(activity))
                .put("width", sample[3])
                .put("height", sample[4])
                .put("decoded", decoded)
                .put("plausibleRuntime", plausible);
            boolean seek = false;
            if (decoded && plausible) {
                long target = duration / 2;
                long previousFrames = sample[2];
                instrumentation.runOnMainSync(() -> {
                    if (activity.player != null && activity.player.isSeekable()) activity.player.setTime(
                        target
                    );
                });
                deadline = SystemClock.elapsedRealtime() + 20000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    snapshot(activity, sample);
                    if (Math.abs(sample[1] - target) < 15000 && sample[2] > previousFrames + 5) {
                        seek = true;
                        break;
                    }
                    SystemClock.sleep(250);
                }
            }
            result
                .put("decodedFrames", sample[2])
                .put("decodedAudio", sample[5])
                .put("playedAudioBuffers", sample[6])
                .put("receivedBytes", activity.vod == null ? 0 : activity.vod.incomingBytes())
                .put("selectedAudioLanguage", audioLanguage(activity))
                .put("width", sample[3])
                .put("height", sample[4])
                .put("midpointDecoded", seek)
                .put("measured4k", decoded && sample[3] >= 3800 && sample[4] >= 1500)
                .put(
                    "status",
                    !decoded
                        ? "no_decoded_video"
                        : sample[5] == 0 && sample[6] == 0
                          ? "audio_unverified"
                          : expectedMinutes == 0
                            ? "runtime_unverified"
                            : !plausible
                              ? "runtime_mismatch"
                              : !seek
                                ? "seek_unverified"
                                : "verified"
                );
        } finally {
            org.json.JSONArray errors = new org.json.JSONArray();
            java.util.Set<String> uniqueErrors = new java.util.LinkedHashSet<>();
            for (ErrorLog.Entry error : ErrorLog.snapshot())
                uniqueErrors.add(error.where + ": " + error.type);
            for (String error : uniqueErrors) errors.put(error);
            result.put("transportErrors", errors);
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (!activity.isDestroyed() && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(100);
        }
    }

    private String audioLanguage(VlcPlaybackActivity activity) {
        String[] language = { "" };
        instrumentation.runOnMainSync(() -> {
            MediaPlayer player = activity.player;
            if (player == null) return;
            IMedia media = player.getMedia();
            if (media == null) return;
            try {
                for (int index = 0; index < media.getTrackCount(); index++) {
                    IMedia.Track track = media.getTrack(index);
                    if (track instanceof IMedia.AudioTrack && track.id == player.getAudioTrack()) {
                        language[0] = (track.language == null ? "" : track.language).replaceAll(
                            "[^a-zA-Z-]",
                            ""
                        );
                        if (language[0].length() > 20) language[0] = "";
                    }
                }
            } finally {
                media.release();
            }
        });
        return language[0];
    }

    private void snapshot(VlcPlaybackActivity activity, long[] sample) {
        instrumentation.runOnMainSync(() -> {
            MediaPlayer player = activity.player;
            if (player == null) return;
            sample[0] = player.getLength();
            sample[1] = player.getTime();
            IMedia media = player.getMedia();
            if (media == null) return;
            try {
                IMedia.Stats stats = media.getStats();
                sample[2] = stats == null ? 0 : stats.decodedVideo;
                sample[5] = stats == null ? 0 : stats.decodedAudio;
                sample[6] = stats == null ? 0 : stats.playedAbuffers;
                for (int index = 0; index < media.getTrackCount(); index++) if (
                    media.getTrack(index) instanceof IMedia.VideoTrack
                ) {
                    IMedia.VideoTrack video = (IMedia.VideoTrack) media.getTrack(index);
                    sample[3] = video.width;
                    sample[4] = video.height;
                }
            } finally {
                media.release();
            }
        });
    }
}
