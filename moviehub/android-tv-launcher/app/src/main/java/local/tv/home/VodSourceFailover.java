package local.tv.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * On-demand source failover policy. No dependency on LiveZapper or live-TV thresholds.
 * The caller owns player lifecycle, buffering callbacks and position restoration.
 */
final class VodSourceFailover {

    static final long STALL_MS = 4000L;
    static final long WINDOW_MS = 60000L;
    static final int STALLS_PER_WINDOW = 2;

    static final class Source {

        final String url;
        /** Shown in the Sources menu; corrected once the real picture size is known. */
        volatile String label;
        final String provider;
        /** Quality read from the file or stream (a tier such as 1080), 0 when only claimed. */
        int confirmed;
        /** What the website claimed, and which website, for judging its labels. */
        String claimed = "",
            site = "";
        /** A streaming playlist, whose player may pick a lower step than it offers. */
        boolean playlist;
        /** The real picture tier once this source has played for a while; 0 until then. */
        volatile int actual;

        Source(String url, String label, String provider) {
            this.url = url;
            this.label = label;
            this.provider = provider;
        }

        /** The best known quality: what played, else what the file said, else the claim. */
        int knownQuality() {
            if (actual > 0) return actual;
            if (confirmed > 0) return confirmed;
            return claimed.isEmpty() ? 0 : SourceRanking.resolution(claimed);
        }
    }

    private final List<Source> sources = new ArrayList<>();
    private final Set<Integer> failed = new HashSet<>();
    private final List<Long> recentStalls = new ArrayList<>();
    private int current = -1;
    private long bufferingSince = -1;
    private boolean hasPlayed;
    private boolean seeking;
    private boolean userPaused;
    private String pinnedProvider;

    void setSources(List<Source> ordered) {
        sources.clear();
        if (ordered != null) for (Source source : ordered) {
            if (source != null && source.url != null && !source.url.trim().isEmpty()) {
                boolean duplicate = false;
                for (Source existing : sources)
                    if (existing.url.equals(source.url)) {
                        duplicate = true;
                        break;
                    }
                if (!duplicate) sources.add(source);
            }
        }
        failed.clear();
        recentStalls.clear();
        current = sources.isEmpty() ? -1 : 0;
        resetPlayback();
        pinnedProvider = null;
    }

    /** Append late discoveries without resetting stall history, failed sources or manual pinning. */
    void appendSources(List<Source> discovered) {
        for (Source source : discovered) {
            if (source == null || source.url == null || source.url.trim().isEmpty()) continue;
            boolean duplicate = false;
            for (Source existing : sources)
                if (existing.url.equals(source.url)) {
                    duplicate = true;
                    break;
                }
            if (!duplicate) sources.add(source);
        }
    }

    List<Source> sources() {
        return Collections.unmodifiableList(sources);
    }

    Source current() {
        return current < 0 ? null : sources.get(current);
    }

    int currentIndex() {
        return current;
    }

    /** A user-selected Premiumize stream must not silently fall back to another provider. */
    boolean select(int index, boolean pinProvider) {
        if (index < 0 || index >= sources.size()) return false;
        current = index;
        failed.remove(index);
        pinnedProvider = pinProvider ? sources.get(index).provider : null;
        recentStalls.clear();
        resetPlayback();
        return true;
    }

    /** Keep an already playing source out of a manual replacement attempt. */
    void exclude(int index) {
        failed.add(index);
    }

    /** Failed, or already played and left; not worth suggesting again. */
    boolean failed(int index) {
        return failed.contains(index);
    }

    void onPlaying() {
        hasPlayed = true;
        bufferingSince = -1;
    }

    void setSeeking(boolean value) {
        seeking = value;
        if (value) bufferingSince = -1;
    }

    void setUserPaused(boolean value) {
        userPaused = value;
        if (value) bufferingSince = -1;
    }

    /** Call only on a transition into buffering, not on every percentage update. */
    boolean onBufferingStarted(long nowMs) {
        if (!hasPlayed || seeking || userPaused || bufferingSince >= 0) return false;
        bufferingSince = nowMs;
        recentStalls.removeIf(at -> nowMs - at > WINDOW_MS);
        recentStalls.add(nowMs);
        return recentStalls.size() >= STALLS_PER_WINDOW;
    }

    boolean isStalled(long nowMs) {
        return bufferingSince >= 0 && !seeking && !userPaused && nowMs - bufferingSince >= STALL_MS;
    }

    void onBufferingEnded() {
        bufferingSince = -1;
    }

    /** Return the next eligible source after marking the current one as failed. */
    Source failover() {
        if (current < 0) return null;
        failed.add(current);
        for (int i = 0; i < sources.size(); i++) {
            if (i == current || failed.contains(i)) continue;
            Source candidate = sources.get(i);
            if (pinnedProvider != null && !pinnedProvider.equals(candidate.provider)) continue;
            current = i;
            recentStalls.clear();
            resetPlayback();
            return candidate;
        }
        bufferingSince = -1;
        return null;
    }

    private void resetPlayback() {
        hasPlayed = false;
        seeking = false;
        userPaused = false;
        bufferingSince = -1;
    }
}
