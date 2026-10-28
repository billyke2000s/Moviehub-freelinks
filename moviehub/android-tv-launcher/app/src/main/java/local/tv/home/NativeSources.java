package local.tv.home;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.*;

/** Native free TV shares the existing channel store, refresh worker, guide and live player. */
final class NativeSources {

    static final String PLUTO = "nativePluto";

    private NativeSources() {}

    /**
     * Pluto is on by default together with the free lists. Turned on explicitly (the first-start
     * "Turn them all on", or Settings), it imports whatever the free-list choice is, as the other
     * providers do.
     */
    static boolean enabled(Context context, boolean providerConnected) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        if (!prefs.getBoolean(PLUTO, true)) return false;
        return prefs.contains(PLUTO) || !FreeChannels.playlist(context, providerConnected).isEmpty();
    }

    static int refresh(Context context, ChannelDatabase db, ChannelDatabase.Cancel cancel) throws Exception {
        NativeHttp http = new NativeHttp();
        cancel.check();
        // Fetch before opening the SQLite write transaction: browsing stays responsive offline.
        List<Playlist.Channel> channels = PlutoSource.catalogue(http);
        cancel.check();
        ChannelDatabase.Writer writer = db.writer("nativepluto", cancel, n -> {});
        try {
            for (Playlist.Channel channel : channels) writer.accept(channel);
            cancel.check();
            writer.commit();
        } finally {
            writer.abort();
        }
        context
            .getSharedPreferences("channelImport", 0)
            .edit()
            .putInt("nativePlutoCount", channels.size())
            .putLong("nativePlutoUpdated", System.currentTimeMillis())
            .apply();
        return channels.size();
    }
}
