package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Checks for, offers and installs signed app updates from the public release feed. */
final class UpdatePrompt {

    private final MainActivity activity;

    UpdatePrompt(MainActivity activity) {
        this.activity = activity;
    }

    AppUpdates.Release availableUpdate;
    long lastUpdateCheck;
    private boolean updateBusy;
    private long promptedUpdateVersion;

    void checkForUpdate() {
        checkForUpdate(false);
    }

    void checkForUpdate(boolean manual) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastUpdateCheck != 0 && now - lastUpdateCheck < 6 * 60 * 60 * 1000L) return;
        lastUpdateCheck = now;
        activity.io.execute(() -> {
            AppUpdates.Release result = null;
            boolean success = false;
            try {
                result = AppUpdates.check(activity.getApplicationContext());
                success = true;
            } catch (Exception e) {
                Diagnostics.recovered("Checking for an update", e);
            }
            final boolean checked = success;
            final AppUpdates.Release release = result;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                availableUpdate = release;
                if (manual) activity.toast(
                    !checked
                        ? "Could not reach the update server. Try again later."
                        : release == null
                          ? "You have the latest version"
                          : "Update available · " + release.name
                );
                if (activity.updateButton != null) activity.updateButton.setVisibility(
                    release == null ? View.GONE : View.VISIBLE
                );
                if (
                    release != null &&
                    (manual ||
                        (activity.prefs.getBoolean("autoUpdates", true) &&
                            promptedUpdateVersion != release.version))
                ) {
                    promptedUpdateVersion = release.version;
                    offerUpdate();
                }
            });
        });
    }

    void offerUpdate() {
        if (availableUpdate == null || updateBusy) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            SidePanel.of(activity, "Allow app updates")
                .message(
                    "Android needs your permission once before MovieHub can install its own updates. Allow it in the next screen, then select Update again."
                )
                .action("Open settings", () -> {
                    try {
                        activity.startActivity(
                            new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:" + activity.getPackageName())
                            )
                        );
                    } catch (Exception e) {
                        activity.toast("Open Android Settings to allow app installation");
                    }
                })
                .action("Cancel", null)
                .show();
            return;
        }
        final AppUpdates.Release release = availableUpdate;
        SidePanel.of(activity, "MovieHub " + release.name + " is ready")
            .message(
                release.notes.isEmpty()
                    ? "Install the new version now? MovieHub restarts when it finishes."
                    : "What's new\n\n" + release.notes + "\n\nMovieHub restarts when the update finishes."
            )
            .action("Update now", () -> {
                updateBusy = true;
                if (activity.updateButton != null) {
                    activity.updateButton.setEnabled(false);
                    activity.updateButton.setText("Downloading…");
                }
                activity.io.execute(() -> {
                    try {
                        AppUpdates.download(activity.getApplicationContext(), release);
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            try {
                                android.net.Uri uri = android.net.Uri.parse(
                                    "content://" + activity.getPackageName() + ".updates/update.apk"
                                );
                                Intent install = new Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                install.setClipData(ClipData.newRawUri("Update", uri));
                                activity.startActivity(install);
                            } catch (Exception e) {
                                activity.toast("Android could not open the update installer");
                            }
                            resetUpdateButton();
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            activity.toast("Update could not be downloaded or verified. Try again later.");
                            resetUpdateButton();
                        });
                    }
                });
            })
            .action("Later", null)
            .show();
    }

    private void resetUpdateButton() {
        updateBusy = false;
        if (activity.updateButton != null) {
            activity.updateButton.setEnabled(true);
            activity.updateButton.setText("Update");
        }
    }
}
