package local.tv.home;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Keeps downloaded artwork bytes on disk (in the app's cache folder, which Android may clear), so
 * posters and backdrops seen before load instantly after scrolling back or restarting the app,
 * without downloading them again. Holds at most {@link #MAX_BYTES}; the least recently used files
 * go first. Only public artwork URLs are stored, never API responses.
 */
final class ArtworkDiskCache {

    static final long MAX_BYTES = 80L * 1024 * 1024;
    private static final int TRIM_EVERY = 40;
    static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final File folder;
    private final long maxBytes;
    private int writes;

    ArtworkDiskCache(File folder, long maxBytes) {
        this.folder = folder;
        this.maxBytes = maxBytes;
    }

    /** The stored bytes for this URL, or null. Marks the file as recently used. */
    synchronized byte[] get(String url) {
        File file = file(url);
        if (!file.isFile()) return null;
        long limit = Math.min(MAX_IMAGE_BYTES, maxBytes / 4);
        if (file.length() > limit) {
            file.delete();
            return null;
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (out.size() + read > limit) {
                    file.delete();
                    return null;
                }
                out.write(buffer, 0, read);
            }
            byte[] bytes = out.toByteArray();
            file.setLastModified(System.currentTimeMillis());
            return bytes;
        } catch (Exception e) {
            Diagnostics.recovered("Reading cached artwork", e);
            file.delete();
            return null;
        }
    }

    synchronized void put(String url, byte[] bytes) {
        if (
            bytes == null || bytes.length == 0 || bytes.length > Math.min(MAX_IMAGE_BYTES, maxBytes / 4)
        ) return;
        if (!folder.isDirectory() && !folder.mkdirs()) return;
        File target = file(url),
            part = new File(folder, target.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(bytes);
        } catch (Exception e) {
            Diagnostics.recovered("Saving cached artwork", e);
            part.delete();
            return;
        }
        if (!part.renameTo(target)) part.delete();
        if (++writes % TRIM_EVERY == 1) trim();
    }

    /** Deletes the least recently used files until the folder is within its size limit. */
    synchronized void trim() {
        File[] files = folder.listFiles();
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (total <= maxBytes) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : files) {
            if (total <= (maxBytes * 3) / 4) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    private File file(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
            char[] name = new char[32];
            for (int i = 0; i < 16; i++) {
                name[i * 2] = HEX[(hash[i] & 255) >>> 4];
                name[i * 2 + 1] = HEX[hash[i] & 15];
            }
            return new File(folder, new String(name));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
