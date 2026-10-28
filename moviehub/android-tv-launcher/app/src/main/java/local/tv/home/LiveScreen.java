package local.tv.home;

import android.app.Activity;
import android.os.Handler;
import android.widget.FrameLayout;

/**
 * Where full-screen Live TV is shown: what the satellite banner ({@link SkyOverlay}) and channel
 * changing ({@link LiveZapper}) need: the one-screen Live TV inside the guide ({@link LiveStage}).
 */
interface LiveScreen {
    /** Owns a {@link LiveScreen} that may currently be showing (for side-panel key routing). */
    interface Owner {
        LiveScreen liveScreen();
    }

    Activity activity();

    Handler overlayHandler();

    FrameLayout video();

    LiveZapper zapper();

    boolean dialogOpen();

    void dialogOpen(boolean open);

    boolean liveFailed();

    int dp(int n);

    LiveMenus liveMenus();

    /** Plays another stream in place: the next channel, or a backup copy of this one. */
    void switchTo(String title, String newUrl, String newId);

    String playbackUrl();

    /** A side panel over the video closed: show the controls again. */
    void panelClosed();

    /** The current copy could not be opened; move on to a backup or say so. */
    void liveSourceFailed();

    /** Pause or carry on. */
    void toggle();

    /** Leave full screen: back to the guide. */
    void leave();

    /** The channel title the screen was opened with. */
    String title();

    /** The picture playing (size, frame rate, codec, bitrate), or "" before the first frame. */
    String videoDetails();
}
