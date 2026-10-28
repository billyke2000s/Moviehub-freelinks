package local.tv.home;

import java.util.HashMap;
import java.util.Map;

/** One colour action per physical press, shared by the player and its changing menu windows. */
final class ColourPress {

    private static final class State {

        long downTime,
            handledTime = Long.MIN_VALUE;
        boolean held;
    }

    private final Map<Long, State> presses = new HashMap<>();

    boolean accept(int device, char colour, int action, long downTime, boolean cancelled) {
        long key = ((long) device << 32) | colour;
        State state = presses.computeIfAbsent(key, ignored -> new State());
        if (cancelled) {
            state.held = false;
            state.handledTime = downTime;
            return false;
        }
        if (action == 0) {
            // The first event we receive may already have a nonzero Android repeat count.
            if (state.held && state.downTime == downTime) return false;
            state.held = true;
            state.downTime = state.handledTime = downTime;
            return true;
        }
        if (action == 1) {
            if (state.held) {
                state.held = false;
                return false;
            }
            // A release can be the only event delivered to this window/session.
            if (state.handledTime == downTime) return false;
            state.handledTime = downTime;
            return true;
        }
        if (action == 2 && state.handledTime != downTime) {
            state.handledTime = downTime;
            return true;
        }
        return false;
    }
}
