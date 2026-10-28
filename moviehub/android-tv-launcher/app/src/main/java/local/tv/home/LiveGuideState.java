package local.tv.home;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/** Per-player guide context that survives Activity/process recreation without a static session. */
final class LiveGuideState {

    static Bundle save(LiveZapper.Session session) {
        if (session == null) return null;
        Bundle state = new Bundle();
        state.putString("filter", session.filter);
        state.putString("search", session.search);
        state.putStringArrayList("favourites", new ArrayList<>(session.favourites));
        state.putInt("channelPosition", session.position);
        return state;
    }

    static LiveZapper.Session restore(Bundle state) {
        if (state == null) return new LiveZapper.Session("all", "", Collections.emptySet(), 0);
        ArrayList<String> favourites = state.getStringArrayList("favourites");
        return new LiveZapper.Session(
            state.getString("filter", "all"),
            state.getString("search", ""),
            favourites == null ? Collections.emptySet() : new HashSet<>(favourites),
            Math.max(0, state.getInt("channelPosition", 0))
        );
    }
}
