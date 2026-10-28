package local.tv.home;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/** Public release feed; credentials are never sent to the update server. */
final class AppUpdates {

    /** A separate test build (-Pexperimental) has its own feed, so it never offers or installs the normal app. */
    static final String ROOT = BuildConfig.EXPERIMENTAL
        ? "https://github.com/billyke2000s/moviehub/releases/download/android-tv-experimental/"
        : "https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/";
    static final long MAX_BYTES = 300L * 1024 * 1024;

    static final class Release {

        final long version;
        final String name, sha;
        /** "What's new" text from the feed, plain text; empty for feeds published before it existed. */
        final String notes;

        Release(long version, String name, String sha) {
            this(version, name, sha, "");
        }

        Release(long version, String name, String sha, String notes) {
            this.version = version;
            this.name = name;
            this.sha = sha;
            this.notes = notes;
        }
    }

    static Release check(Context context) throws Exception {
        JSONObject json = new JSONObject(Network.text(ROOT + "update.json", null, false));
        if (!context.getPackageName().equals(json.getString("packageName"))) throw new IOException(
            "Wrong package"
        );
        long version = json.getLong("versionCode");
        String sha = json.getString("sha256");
        if (!sha.matches("[a-fA-F0-9]{64}")) throw new IOException("Invalid checksum");
        long installed = context
            .getPackageManager()
            .getPackageInfo(context.getPackageName(), 0)
            .getLongVersionCode();
        String notes = json.optString("notes", "");
        if (notes.length() > 2000) notes = notes.substring(0, 2000) + "…";
        return version > installed ? new Release(version, json.getString("versionName"), sha, notes) : null;
    }

    static File download(Context context, Release release) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot prepare update");
        File part = new File(directory, "update.part"),
            apk = new File(directory, "update.apk");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            URI current = URI.create(ROOT + "home.apk");
            boolean downloaded = false;
            for (int redirects = 0; redirects < 6; redirects++) {
                if (
                    !"https".equalsIgnoreCase(current.getScheme()) || current.getUserInfo() != null
                ) throw new IOException("Invalid update URL");
                HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("Invalid redirect");
                        current = current.resolve(location);
                        continue;
                    }
                    if (status != 200 || connection.getContentLengthLong() > MAX_BYTES) throw new IOException(
                        "Update download failed"
                    );
                    try (
                        InputStream input = connection.getInputStream();
                        OutputStream output = new FileOutputStream(part)
                    ) {
                        byte[] buffer = new byte[65536];
                        long size = 0;
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                            size += n;
                            if (size > MAX_BYTES) throw new IOException("Update too large");
                            digest.update(buffer, 0, n);
                            output.write(buffer, 0, n);
                        }
                    }
                    downloaded = true;
                    break;
                } finally {
                    connection.disconnect();
                }
            }
            if (!downloaded || !hex(digest.digest()).equalsIgnoreCase(release.sha)) throw new IOException(
                "Checksum mismatch"
            );
            PackageManager manager = context.getPackageManager();
            PackageInfo candidate = manager.getPackageArchiveInfo(
                    part.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES
                ),
                installed = manager.getPackageInfo(
                    context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES
                );
            if (
                candidate == null ||
                !context.getPackageName().equals(candidate.packageName) ||
                candidate.getLongVersionCode() != release.version ||
                candidate.getLongVersionCode() <= installed.getLongVersionCode()
            ) throw new IOException("Invalid update version");
            if (
                candidate.signingInfo == null ||
                installed.signingInfo == null ||
                !certificates(candidate).equals(certificates(installed))
            ) throw new IOException("Update signing key does not match this installation");
            if (apk.exists() && !apk.delete()) throw new IOException("Cannot replace cached update");
            if (!part.renameTo(apk)) throw new IOException("Cannot prepare APK");
            return apk;
        } finally {
            if (part.exists()) part.delete();
        }
    }

    private static Set<String> certificates(PackageInfo info) throws Exception {
        Set<String> result = new HashSet<>();
        for (Signature signature : info.signingInfo.getApkContentsSigners())
            result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
