package local.tv.home;

import android.util.Log;

/**
 * Records failures that the app deliberately recovers from, so they can be found with
 * {@code adb logcat -s MovieHub}. Only the location and the exception type are logged: exception
 * messages can contain provider URLs with account details, so they are never written to the log.
 */
final class Diagnostics {

    static final String TAG = "MovieHub";

    private Diagnostics() {}

    /** Logs a recovered failure. Never throws, so it is safe inside any catch block. */
    static void recovered(String where, Throwable error) {
        try {
            ErrorLog.add(where, error); // shown in Settings → System → Troubleshooting
            Log.w(TAG, where + " failed: " + error.getClass().getName());
        } catch (Throwable ignored) {
            // Logging must never affect the app, including in plain JVM unit tests.
        }
    }
}
