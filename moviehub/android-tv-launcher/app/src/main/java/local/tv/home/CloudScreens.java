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

/** Premiumize cloud folders and transfers. */
final class CloudScreens {

    private final MainActivity activity;

    CloudScreens(MainActivity activity) {
        this.activity = activity;
    }

    void cloud(String folder, String kind) {
        cloud(folder, kind, "Cloud", new ArrayList<>());
    }

    void cloud(String folder, String kind, String folderName, List<String[]> parents) {
        if (!activity.secrets.has("premiumize")) {
            activity.settingsScreens.accountScreen("Premiumize");
            return;
        }
        Runnable goBack = () -> {
            if (parents.isEmpty()) activity.libraryScreens.library(kind);
            else {
                List<String[]> path = new ArrayList<>(parents);
                String[] parent = path.remove(path.size() - 1);
                cloud(parent[0], kind, parent[1], path);
            }
        };
        activity.screen(folderName, goBack);
        LinearLayout tools = activity.horizontal(activity.page);
        activity.chip(tools, "Transfers", () -> transfers(kind));
        activity.chip(tools, "Saved library", () -> activity.libraryScreens.savedLibrary(kind));
        TextView loading = activity.text("Loading files…", 16);
        activity.page.addView(loading);
        activity.async(
            () ->
                activity.media
                    .premiumize(
                        "folder/list" + (folder.isEmpty() ? "" : "?id=" + Network.q(folder)),
                        activity.secrets.get("premiumize")
                    )
                    .getJSONArray("content"),
            items -> {
                activity.page.removeView(loading);
                if (items.length() == 0) {
                    activity.emptyState(
                        activity.page,
                        "This folder is empty",
                        "Finished transfers appear in your cloud.",
                        "View transfers",
                        () -> transfers(kind)
                    );
                    return;
                }
                List<JSONObject> sorted = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) sorted.add(items.getJSONObject(i));
                sorted.sort(
                    Comparator.<JSONObject>comparingInt(item ->
                        item.optString("type").equals("folder") ? 0 : 1
                    ).thenComparing(item -> item.optString("name").toLowerCase(Locale.ROOT))
                );
                for (JSONObject file : sorted) {
                    String name = file.optString("name", "File"),
                        key = file.optString("id"),
                        type = file.optString("type");
                    boolean directory = type.equals("folder");
                    activity.libraryScreens.mediaRow(
                        activity.page,
                        name,
                        directory ? "Folder" : file.optString("mime_type", "Cloud file"),
                        directory ? "Open" : "Play",
                        () -> {
                            if (directory) {
                                List<String[]> path = new ArrayList<>(parents);
                                path.add(new String[] { folder, folderName });
                                cloud(key, kind, name, path);
                                return;
                            }
                            activity.async(
                                () ->
                                    activity.media.premiumize(
                                        "item/details?id=" + Network.q(key),
                                        activity.secrets.get("premiumize")
                                    ),
                                detail -> {
                                    String url = detail.optString("link");
                                    if (url.isEmpty()) {
                                        activity.toast("This file has no playback link");
                                        return;
                                    }
                                    activity.play(name, url, "pm:" + key, false);
                                }
                            );
                        },
                        directory
                            ? null
                            : () ->
                                  SidePanel.of(activity, name)
                                      .option("Add to " + kind, () -> {
                                          try {
                                              activity.media.addPremiumize(name, key, kind);
                                              activity.toast("Added to " + kind);
                                          } catch (Exception e) {
                                              activity.toast("Could not add this file");
                                          }
                                      })
                                      .show()
                    );
                }
            }
        );
    }

    void transfers(String kind) {
        activity.screen("Transfers", () -> activity.libraryScreens.library(kind));
        LinearLayout tools = activity.horizontal(activity.page);
        activity.chip(tools, "Refresh", () -> transfers(kind));
        activity.chip(tools, "Cloud", () -> cloud("", kind));
        TextView loading = activity.text("Loading transfers…", 16);
        activity.page.addView(loading);
        activity.async(
            () ->
                activity.media
                    .premiumize("transfer/list", activity.secrets.get("premiumize"))
                    .getJSONArray("transfers"),
            items -> {
                activity.page.removeView(loading);
                if (items.length() == 0) {
                    activity.emptyState(
                        activity.page,
                        "No transfers",
                        "Your queued files will appear here.",
                        "Browse catalogue",
                        () -> activity.libraryScreens.library(kind)
                    );
                    return;
                }
                for (int i = 0; i < items.length(); i++) {
                    JSONObject transfer = items.getJSONObject(i);
                    String status = transfer.optString("status", "unknown"),
                        folder = transfer.optString("folder_id", ""),
                        file = transfer.optString("file_id", "");
                    boolean ready = status.equals("finished") || status.equals("seeding");
                    activity.libraryScreens.mediaRow(
                        activity.page,
                        transfer.optString("name", "Transfer"),
                        (ready
                            ? "Ready"
                            : status.isEmpty()
                              ? "Unknown"
                              : status.substring(0, 1).toUpperCase(Locale.ROOT) + status.substring(1)) +
                            " · " +
                            Math.round(Math.max(0, Math.min(1, transfer.optDouble("progress", 0))) * 100) +
                            "%",
                        ready ? "Open" : "Status",
                        () -> {
                            if (!ready) {
                                activity.toast(transfer.optString("message", "Transfer is " + status));
                                return;
                            }
                            if (!file.isEmpty() && !file.equals("null")) {
                                activity.async(
                                    () ->
                                        activity.media.premiumize(
                                            "item/details?id=" + Network.q(file),
                                            activity.secrets.get("premiumize")
                                        ),
                                    detail ->
                                        activity.play(
                                            detail.optString("name", "File"),
                                            detail.getString("link"),
                                            "pm:" + file,
                                            false
                                        )
                                );
                            } else if (!folder.isEmpty() && !folder.equals("null")) cloud(folder, kind);
                        },
                        null,
                        ready ? -1 : (float) Math.max(0, Math.min(1, transfer.optDouble("progress", 0)))
                    );
                }
            }
        );
    }
}
