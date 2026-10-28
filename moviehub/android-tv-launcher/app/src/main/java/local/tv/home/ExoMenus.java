package local.tv.home;

import android.widget.Toast;
import java.util.List;

/** The Live TV side panels when the {@link ExoLive} player is playing. */
final class ExoMenus implements LiveMenus {

    private final LiveScreen screen;
    private final Runnable reconnect, backToLive;

    ExoMenus(LiveScreen screen, Runnable reconnect, Runnable backToLive) {
        this.screen = screen;
        this.reconnect = reconnect;
        this.backToLive = backToLive;
    }

    @Override
    public void audioAndSubtitles() {
        screen.dialogOpen(true);
        SidePanel.of(screen.activity(), "Audio & Subtitles")
            .option("Audio", this::audio)
            .option("Subtitles", this::subtitles)
            .onDismiss(screen::panelClosed)
            .show();
    }

    private void subtitles() {
        List<String> tracks = ExoLiveTracks.subtitleTracks();
        screen.dialogOpen(true);
        if (tracks.isEmpty()) {
            SidePanel.of(screen.activity(), "Subtitles")
                .message("This channel has no subtitles.")
                .action("Close", null)
                .onDismiss(screen::panelClosed)
                .show();
            return;
        }
        String[] names = new String[tracks.size() + 1];
        names[0] = "Off";
        for (int i = 0; i < tracks.size(); i++) names[i + 1] = tracks.get(i);
        SidePanel.of(screen.activity(), "Subtitles")
            .choices(names, ExoLiveTracks.selectedSubtitleTrack() + 1, which ->
                ExoLiveTracks.selectSubtitleTrack(which - 1)
            )
            .onDismiss(screen::panelClosed)
            .show();
    }

    private void audio() {
        List<String> tracks = ExoLiveTracks.audioTracks();
        screen.dialogOpen(true);
        if (tracks.size() < 2) {
            SidePanel.of(screen.activity(), "Audio")
                .message(
                    tracks.isEmpty()
                        ? "No audio tracks reported yet."
                        : "This channel has one audio track: " + tracks.get(0)
                )
                .action("Close", null)
                .onDismiss(screen::panelClosed)
                .show();
            return;
        }
        SidePanel.of(screen.activity(), "Audio")
            .choices(
                tracks.toArray(new String[0]),
                ExoLiveTracks.selectedAudioTrack(),
                ExoLiveTracks::selectAudioTrack
            )
            .onDismiss(screen::panelClosed)
            .show();
    }

    @Override
    public void chooseSource() {
        LiveZapper zapper = screen.zapper();
        if (zapper == null) return;
        List<ChannelDatabase.Row> copies = zapper.copies();
        String[] names = new String[copies.size() + 1];
        String playing = zapper.sourceProvider();
        names[0] =
            "Automatic · " +
            (zapper.automaticSources() && !playing.isEmpty() ? "now " + playing + " · " : "") +
            "switch if a source fails";
        for (int i = 0; i < copies.size(); i++) {
            String provider = AdapterSources.sourceName(copies.get(i));
            names[i + 1] =
                (i == zapper.selectedCopy() ? "▶ " : "") +
                "Source " +
                (i + 1) +
                "  ·  " +
                (provider.isEmpty() ? "" : provider + "  ·  ") +
                copies.get(i).name;
        }
        int current = zapper.automaticSources() ? 0 : zapper.selectedCopy() + 1;
        screen.dialogOpen(true);
        SidePanel.of(screen.activity(), "Sources")
            .choices(names, current, which -> {
                if (which == 0) zapper.automaticSources(true);
                else zapper.useCopy(which - 1, false);
            })
            .onDismiss(screen::panelClosed)
            .show();
    }

    /** What the player recorded, newest first (decoder, formats, dropped frames, freezes, errors). */
    private void eventLog() {
        screen.dialogOpen(true);
        SidePanel.of(screen.activity(), "Player event log")
            .message("ExoPlayer · " + ExoLive.status() + "\n\n" + ExoLive.eventLog())
            .action("Close", null)
            .onDismiss(screen::panelClosed)
            .show();
    }

    @Override
    public void liveOptions() {
        LiveZapper zapper = screen.zapper();
        if (zapper == null) return;
        List<String> labels = new java.util.ArrayList<>();
        if (ExoLive.behindLive()) labels.add("Back to live");
        java.util.Collections.addAll(
            labels,
            ExoLive.isPlaying() ? "Pause" : "Play",
            "Audio & subtitles",
            "Programme information",
            zapper.sourceText(),
            "Reconnect channel",
            "Player event log",
            "Player status · ExoPlayer · " + ExoLive.status()
        );
        screen.dialogOpen(true);
        SidePanel panel = SidePanel.of(screen.activity(), "Options").onDismiss(screen::panelClosed);
        for (String label : labels)
            panel.option(label, () -> {
                if (label.equals("Back to live")) backToLive.run();
                else if (label.equals("Pause") || label.equals("Play")) screen.toggle();
                else if (label.equals("Audio & subtitles")) audioAndSubtitles();
                else if (label.equals("Player event log")) eventLog();
                else if (label.equals("Programme information")) zapper.information();
                else if (label.startsWith("Sources")) chooseSource();
                else if (label.equals("Reconnect channel")) reconnect.run();
                else Toast.makeText(screen.activity(), ExoLive.status(), Toast.LENGTH_LONG).show();
            });
        panel.show();
    }
}
