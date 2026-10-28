package local.tv.home;

import android.os.SystemClock;
import java.util.List;
import org.videolan.libvlc.Media;

/**
 * Checks a playing film's real picture against its label, and mentions a better source when one
 * whose quality was read from the file or stream turns up later. It never switches by itself:
 * the viewer chooses in More → Sources, which prepares the new source in the background at the
 * current position before replacing the picture. Main thread only.
 */
final class VodQuality {

    /**
     * Long enough for a streaming playlist to settle on its picture size after starting. Device
     * tests shorten it.
     */
    static long settleMs = 15_000;

    private final VlcPlaybackActivity activity;
    private final List<VodSourceFailover.Source> sources;
    private final VodSourceFailover queue;
    private final boolean free;
    private final int preferred;
    private int timedIndex = -1,
        measuredIndex = -1,
        announced;
    private long playingSince;
    private String mismatch;

    VodQuality(
        VlcPlaybackActivity activity,
        List<VodSourceFailover.Source> sources,
        VodSourceFailover queue,
        boolean free
    ) {
        this.activity = activity;
        this.sources = sources;
        this.queue = queue;
        this.free = free;
        preferred = activity
            .getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            .getInt(AutoPlayback.QUALITY, 1080);
    }

    /** Called every second with the source on screen and whether it is playing. */
    void tick(int active, boolean playing) {
        if (active < 0 || active >= sources.size()) return;
        if (active != timedIndex) {
            timedIndex = active;
            playingSince = 0;
        }
        if (!playing) {
            playingSince = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (playingSince == 0) playingSince = now;
        if (measuredIndex != active && now - playingSince >= settleMs) measure(active);
        if (measuredIndex == active) announce(active);
    }

    private void measure(int active) {
        Media.VideoTrack track = activity.player == null ? null : activity.player.getCurrentVideoTrack();
        // No picture size yet: try again on the next tick.
        if (track == null || track.width <= 0 || track.height <= 0) return;
        measuredIndex = active;
        VodSourceFailover.Source source = sources.get(active);
        int tier = VideoDetails.tier(track.width, track.height);
        source.actual = tier;
        source.label = QualityClaims.playingLabel(
            source.provider,
            tier,
            source.confirmed,
            source.claimed,
            source.playlist
        );
        // Only a file's real size judges the website: a playlist may play below what it offers
        // because of the connection, and a size already read from the file was judged then.
        if (free && source.confirmed == 0 && !source.playlist) {
            FreeVodProviders.judge(activity, source.site, source.claimed, tier);
        }
        mismatch =
            source.confirmed == 0 &&
            !source.playlist &&
            QualityClaims.verdict(source.claimed, tier) == QualityClaims.OVERSTATED
                ? "Playing " +
                  QualityClaims.name(tier) +
                  " · it was labelled " +
                  QualityClaims.name(SourceRanking.resolution(source.claimed))
                : null;
    }

    /** The best confirmed quality already pointed out, 0 when none. */
    int announced() {
        return announced;
    }

    private void announce(int active) {
        int count = sources.size();
        int[] confirmed = new int[count];
        boolean[] skipped = new boolean[count];
        for (int i = 0; i < count; i++) {
            confirmed[i] = sources.get(i).confirmed;
            skipped[i] = i == active || queue.failed(i);
        }
        int better = QualityClaims.better(
            confirmed,
            skipped,
            sources.get(active).knownQuality(),
            preferred,
            announced
        );
        String found =
            better > 0 ? QualityClaims.name(better) + " source found · More → Sources to switch" : null;
        if (better > 0) announced = better;
        String message = mismatch == null ? found : found == null ? mismatch : mismatch + "\n" + found;
        mismatch = null;
        if (message != null) android.widget.Toast.makeText(
            activity,
            message,
            android.widget.Toast.LENGTH_LONG
        ).show();
    }
}
