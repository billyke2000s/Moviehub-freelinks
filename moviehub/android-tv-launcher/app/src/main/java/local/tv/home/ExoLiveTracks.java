package local.tv.home;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.ArrayList;
import java.util.List;

/** Audio and subtitle tracks of the Live TV channel playing on {@link ExoLive}'s player. */
@OptIn(markerClass = UnstableApi.class)
final class ExoLiveTracks {

    private ExoLiveTracks() {}

    /** Audio tracks of the channel playing, as "Language · codec · channels". */
    static List<String> audioTracks() {
        List<String> names = new ArrayList<>();
        for (Format format : formats(C.TRACK_TYPE_AUDIO)) {
            String language = format.language == null ? "Unknown language" : format.language;
            String codec =
                format.sampleMimeType == null ? "" : " · " + format.sampleMimeType.replace("audio/", "");
            String layout = format.channelCount > 0 ? " · " + format.channelCount + " ch" : "";
            names.add(language + codec + layout);
        }
        return names;
    }

    static int selectedAudioTrack() {
        return selected(C.TRACK_TYPE_AUDIO);
    }

    static void selectAudioTrack(int wanted) {
        select(C.TRACK_TYPE_AUDIO, wanted);
    }

    /** Subtitle tracks, as "Language" (with a label when the stream gives one). */
    static List<String> subtitleTracks() {
        List<String> names = new ArrayList<>();
        for (Format format : formats(C.TRACK_TYPE_TEXT)) {
            String language = format.language == null ? "Unknown language" : format.language;
            names.add(format.label == null ? language : language + " · " + format.label);
        }
        return names;
    }

    /** The subtitle track shown, or -1 when subtitles are off. */
    static int selectedSubtitleTrack() {
        ExoPlayer player = ExoLive.player();
        if (
            player == null ||
            player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        ) {
            return -1;
        }
        return selected(C.TRACK_TYPE_TEXT);
    }

    /** Shows subtitle track {@code wanted}, or turns subtitles off when it is -1. */
    static void selectSubtitleTrack(int wanted) {
        ExoPlayer player = ExoLive.player();
        if (player == null) return;
        if (wanted < 0) {
            player.setTrackSelectionParameters(
                player
                    .getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            );
            return;
        }
        player.setTrackSelectionParameters(
            player
                .getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        );
        select(C.TRACK_TYPE_TEXT, wanted);
    }

    /**
     * The best picture the playing stream offers, in lines (see LiveUpgradePlan.lines): the
     * largest of its video tracks, so an adaptive stream counts at its best quality rather than
     * the one it has climbed to so far. 0 before the tracks are known.
     */
    static int bestVideoLines() {
        int best = 0;
        for (Format format : formats(C.TRACK_TYPE_VIDEO)) {
            best = Math.max(best, LiveUpgradePlan.lines(format.width, format.height));
        }
        return best;
    }

    private static List<Format> formats(int type) {
        List<Format> formats = new ArrayList<>();
        ExoPlayer player = ExoLive.player();
        if (player == null) return formats;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) formats.add(group.getTrackFormat(i));
        }
        return formats;
    }

    private static int selected(int type) {
        ExoPlayer player = ExoLive.player();
        if (player == null) return -1;
        int index = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++, index++) if (group.isTrackSelected(i)) return index;
        }
        return -1;
    }

    private static void select(int type, int wanted) {
        ExoPlayer player = ExoLive.player();
        if (player == null) return;
        int index = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++, index++) {
                if (index != wanted) continue;
                player.setTrackSelectionParameters(
                    player
                        .getTrackSelectionParameters()
                        .buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i))
                        .build()
                );
                return;
            }
        }
    }
}
