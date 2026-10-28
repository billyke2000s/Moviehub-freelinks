package local.tv.home;

import android.view.ViewGroup;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Keep the decoder's window and engine alive until its off-main native stop returns. */
final class VodNative {

    private VodNative() {}

    static void retire(MediaPlayer player, VLCVideoLayout view, LibVLC engine, Runnable completed) {
        if (player == null) {
            if (view != null && view.getParent() instanceof ViewGroup) (
                (ViewGroup) view.getParent()
            ).removeView(view);
            completed.run();
            return;
        }
        VodSurface surface = VodSurface.retain(view);
        player.setEventListener(null);
        // Activity destruction may release its reference while this native reader is stopping.
        engine.retain();
        LiveNative.stop(player, () -> {
            player.detachViews();
            if (surface != null) surface.stopped();
            player.release();
            if (view != null && view.getParent() instanceof ViewGroup) (
                (ViewGroup) view.getParent()
            ).removeView(view);
            engine.release();
            completed.run();
        });
    }
}
