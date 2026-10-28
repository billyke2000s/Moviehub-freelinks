package local.tv.home;

import android.os.SystemClock;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

/**
 * The player's side panels: audio and subtitle tracks, video track, playback speed, picture size,
 * subtitle timing and the sleep timer. Split out of {@link VlcPlaybackActivity}.
 */
final class PlayerMenus {

    private final VlcPlaybackActivity a;

    PlayerMenus(VlcPlaybackActivity activity) {
        a = activity;
    }

    private boolean ready() {
        return a.player != null;
    }

    /** One panel for both, as streaming apps do: pick which one to change. */
    void audioAndSubtitles() {
        a.dialogOpen = true;
        SidePanel.of(a, "Audio & Subtitles")
            .option("Audio", () -> tracks(false))
            .option("Subtitles", () -> tracks(true))
            .onDismiss(a::panelClosed)
            .show();
    }

    void resolution() {
        if (!ready()) return;
        Media.VideoTrack track = a.player.getCurrentVideoTrack();
        String size = track == null ? "Resolution unavailable" : track.width + " × " + track.height;
        MediaPlayer.TrackDescription[] choices = a.player.getVideoTracks();
        if (choices == null || choices.length < 2) {
            a.dialogOpen = true;
            SidePanel.of(a, "Video · " + size)
                .message(
                    "This source has one picture quality. Choose a different source for another resolution."
                )
                .action("Close", null)
                .onDismiss(a::panelClosed)
                .show();
            return;
        }
        chooseTracks(choices, a.player.getVideoTrack(), 0, "Video · " + size);
    }

    void tracks(boolean subtitles) {
        if (!ready()) return;
        MediaPlayer.TrackDescription[] choices = subtitles
            ? a.player.getSpuTracks()
            : a.player.getAudioTracks();
        if (choices == null || choices.length == 0) {
            Toast.makeText(a, "No tracks available", Toast.LENGTH_SHORT).show();
            return;
        }
        chooseTracks(
            choices,
            subtitles ? a.player.getSpuTrack() : a.player.getAudioTrack(),
            subtitles ? 2 : 1,
            subtitles ? "Subtitles" : "Audio"
        );
    }

    void chooseTracks(MediaPlayer.TrackDescription[] choices, int current, int type, String title) {
        String[] names = new String[choices.length];
        int selected = -1;
        for (int i = 0; i < names.length; i++) {
            names[i] = choices[i].id < 0 ? "Off" : choices[i].name;
            if (choices[i].id == current) selected = i;
        }
        a.dialogOpen = true;
        SidePanel.of(a, title)
            .choices(names, selected, which -> {
                if (ready()) {
                    int choice = choices[which].id;
                    if (type == 2) a.player.setSpuTrack(choice);
                    else if (type == 1) a.player.setAudioTrack(choice);
                    else a.player.setVideoTrack(choice);
                }
            })
            .onDismiss(a::panelClosed)
            .show();
    }

    /** The More button: the film's playback options. */
    void moreControls() {
        List<String> labels = new ArrayList<>();
        labels.add("Playback speed · " + a.playbackRate + "×");
        labels.add("Picture size");
        labels.add("Subtitle timing");
        labels.add(
            a.sleepAt > 0
                ? "Sleep timer · " +
                      Math.max(1, (a.sleepAt - SystemClock.elapsedRealtime()) / 60000) +
                      " min left"
                : "Sleep timer"
        );
        if (a.vod != null && a.vod.hasChoices()) labels.add(0, "Sources");
        a.dialogOpen = true;
        SidePanel panel = SidePanel.of(a, "Playback options").onDismiss(a::panelClosed);
        for (String label : labels)
            panel.option(label, () -> {
                if (label.equals("Sources")) chooseVodSource();
                else if (label.startsWith("Playback speed")) chooseSpeed();
                else if (label.equals("Picture size")) choosePicture();
                else if (label.equals("Subtitle timing")) subtitleTiming();
                else chooseSleep();
            });
        panel.show();
    }

    void options(String title, String[] labels, int selected, java.util.function.IntConsumer action) {
        a.dialogOpen = true;
        SidePanel.of(a, title).choices(labels, selected, action).onDismiss(a::panelClosed).show();
    }

    void chooseSpeed() {
        float[] rates = { .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f };
        int selected = 2;
        for (int i = 0; i < rates.length; i++) if (rates[i] == a.playbackRate) selected = i;
        options(
            "Playback speed",
            new String[] { "0.5×", "0.75×", "Normal · 1×", "1.25×", "1.5×", "1.75×", "2×" },
            selected,
            which -> {
                a.playbackRate = rates[which];
                if (ready()) a.player.setRate(a.playbackRate);
            }
        );
    }

    void choosePicture() {
        String[] aspects = { null, "16:9", "4:3", "21:9" };
        int selected = 0;
        for (int i = 1; i < aspects.length; i++) if (aspects[i].equals(a.aspect)) selected = i;
        options("Picture size", new String[] { "Original · Fit", "16:9", "4:3", "21:9" }, selected, which -> {
            a.aspect = aspects[which];
            if (ready()) {
                a.player.setScale(0);
                a.player.setAspectRatio(a.aspect);
            }
        });
    }

    void subtitleTiming() {
        if (!ready()) return;
        long delay = a.player.getSpuDelay();
        options(
            "Subtitle timing · " + delay / 1000 + " ms",
            new String[] { "Earlier by 250 ms", "Later by 250 ms", "Reset timing" },
            -1,
            which -> {
                if (ready()) a.player.setSpuDelay(which == 2 ? 0 : delay + (which == 0 ? -250000 : 250000));
            }
        );
    }

    void chooseSleep() {
        options(
            "Sleep timer",
            new String[] { "Off", "15 minutes", "30 minutes", "60 minutes", "90 minutes" },
            a.sleepAt == 0 ? 0 : -1,
            which -> {
                a.overlayHandler.removeCallbacks(a.sleep);
                int[] minutes = { 0, 15, 30, 60, 90 };
                a.sleepAt = which == 0 ? 0 : SystemClock.elapsedRealtime() + minutes[which] * 60000L;
                if (a.sleepAt > 0) a.overlayHandler.postDelayed(a.sleep, minutes[which] * 60000L);
            }
        );
    }

    /** Automatic is the default; a manual choice stays selected until Auto or another channel. */
    void chooseVodSource() {
        if (a.vod == null) return;
        var entries = a.vod.queue.sources();
        String report = a.vod.providerReport();
        String[] names = new String[entries.size() + (report.isEmpty() ? 1 : 2)];
        if (!report.isEmpty()) names[names.length - 1] = "Provider search details";
        names[0] = "Automatic · switch if a source fails";
        for (int i = 0; i < entries.size(); i++) names[i + 1] =
            (i == a.vod.activeIndex() ? "▶ " : "") + entries.get(i).label;
        options("Sources", names, a.vod.automaticSources() ? 0 : a.vod.activeIndex() + 1, which -> {
            if (a.vod == null) return;
            if (which > entries.size()) SidePanel.of(a, "Provider search details")
                .message(a.vod.providerReport())
                .show();
            else if (which == 0) a.vod.automatic();
            else a.vod.select(which - 1);
        });
    }
}
