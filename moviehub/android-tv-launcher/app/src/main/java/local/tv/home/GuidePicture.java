package local.tv.home;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The live picture in the corner of the TV guide, as on a satellite box: the channel you were
 * last watching keeps playing while you browse. It shares the one live player with the full
 * screen ({@link ExoLive}, drawn by {@link LiveStage}), so going between them moves the picture
 * without reconnecting.
 * Stopped when the guide closes, unless the full screen has taken the player over.
 */
final class GuidePicture {

    private FrameLayout video;
    /** Playing, for tests. */
    volatile boolean playing;

    /** The box the picture is shown in; a newer guide frame replaces it. */
    private FrameLayout box;
    /** The one-screen Live TV that draws the picture over the box. */
    private MainActivity host;
    private TextView label;

    /**
     * Shows the live picture in {@code holder} with the channel's number and name in
     * {@code label}. A stream already playing (coming back from the full screen) carries on;
     * otherwise the last channel watched starts, unless the picture is turned off or the channel
     * is locked or gone. Never shows an error on the guide.
     */
    void show(MainActivity activity, FrameLayout holder, TextView label) {
        box = holder;
        host = activity;
        this.label = label;
        boolean on = activity.prefs.getBoolean(LiveTvScreen.PICTURE, true);
        holder.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            stop();
            label.setText("");
            return;
        }
        // Take over at once (before the full-screen player stops), so the stream never pauses.
        boolean carryOn = ExoLive.active();
        if (carryOn) {
            attach(activity, holder);
            ExoLive.resume();
            playing = carryOn;
        } else removeVideo();
        String key = activity.prefs.getString(LiveTvScreen.LAST_CHANNEL, "");
        if (key.isEmpty()) {
            label.setText(carryOn ? "" : "No channel yet");
            return;
        }
        ChannelDatabase db = ChannelDatabase.get(activity);
        activity.async(
            () -> {
                try {
                    ChannelDatabase.Row row = db.byKey(key);
                    if (row != null) return new Object[] { row, carryOn ? null : db.url(row.id, row.key) };
                } catch (Exception e) {
                    Diagnostics.recovered("Finding the guide picture's channel", e);
                }
                return new Object[] { null, null };
            },
            result -> {
                ChannelDatabase.Row row = (ChannelDatabase.Row) result[0];
                if (holder != box) return;
                if (row == null) {
                    if (!carryOn) label.setText("No channel yet");
                    return;
                }
                String name = row.number + " " + row.name;
                label.setText(name);
                if (carryOn) return;
                if (ParentalLock.locked(activity, row)) {
                    label.setText(name + "  ·  Locked");
                    return;
                }
                try {
                    start(activity, holder, Playlist.networkUrl((String) result[1], activity.http()));
                } catch (Exception e) {
                    label.setText(name + "  ·  No picture");
                }
            }
        );
    }

    /** Plays {@code url} in {@code holder} on the shared live player. */
    void start(Context context, FrameLayout holder, String url) {
        try {
            ExoLive.play(context, url, false);
            attach(context, holder);
        } catch (RuntimeException e) {
            Diagnostics.recovered("Starting the guide picture", e);
            stop();
        }
    }

    private void attach(Context context, FrameLayout holder) {
        if (video == null || video.getParent() != holder) {
            removeVideo();
            video = new FrameLayout(context);
            holder.addView(video, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        // The one Live TV picture is placed over this box.
        if (host != null && host.stage != null) host.stage.picture(box, label);
    }

    /** The guide closed: stops the live player unless the full screen has taken it over. */
    void stop() {
        playing = false;
        if (host != null && host.stage != null && !host.stage.full()) host.stage.pictureStopped();
        removeVideo();
    }

    private void removeVideo() {
        if (video != null && video.getParent() instanceof ViewGroup) {
            ((ViewGroup) video.getParent()).removeView(video);
        }
        video = null;
    }
}
