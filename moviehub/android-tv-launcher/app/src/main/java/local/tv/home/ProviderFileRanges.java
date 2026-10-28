package local.tv.home;

import java.io.*;
import java.net.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Turns a host's finite byte-range responses into one seekable, continuous file response. */
final class ProviderFileRanges extends InputStream {

    interface Open {
        HttpURLConnection get(URI target) throws Exception;
    }

    interface Release {
        void close(HttpURLConnection connection, boolean complete);
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile(
        "bytes ([0-9]{1,15})-([0-9]{1,15})/([0-9]{1,15})"
    );
    private final Open open;
    private final Release release;
    private final BooleanSupplier cancelled;
    private final int chunk;
    private URI target;
    private HttpURLConnection connection;
    private InputStream input;
    private long cursor,
        remaining,
        total = -1;
    private boolean closed;
    final long start, end;
    final boolean ranged;
    String contentType;
    private String entityTag;

    ProviderFileRanges(URI target, String range, Open open, Release release, BooleanSupplier cancelled)
        throws IOException {
        this(target, range, open, release, cancelled, 4 * 1024 * 1024);
    }

    ProviderFileRanges(
        URI target,
        String range,
        Open open,
        Release release,
        BooleanSupplier cancelled,
        int chunk
    ) throws IOException {
        this.target = target;
        this.open = open;
        this.release = release;
        this.cancelled = cancelled;
        this.chunk = chunk;
        ranged = range != null;
        long first = 0,
            last = Long.MAX_VALUE;
        try {
            if (chunk < 1 || chunk > 4 * 1024 * 1024) throw new IOException("File range size rejected");
            if (range != null) {
                Matcher match = Pattern.compile("bytes=([0-9]{0,15})-([0-9]{0,15})").matcher(range);
                if (
                    !match.matches() || (match.group(1).isEmpty() && match.group(2).isEmpty())
                ) throw new IOException("File range rejected");
                if (match.group(1).isEmpty()) {
                    long suffix = Long.parseLong(match.group(2));
                    if (suffix <= 0) throw new IOException("File suffix rejected");
                    part(0, 0);
                    dispose(false);
                    first = Math.max(0, total - suffix);
                    last = total - 1;
                } else {
                    first = Long.parseLong(match.group(1));
                    if (!match.group(2).isEmpty()) last = Long.parseLong(match.group(2));
                    if (last < first) throw new IOException("File range reversed");
                }
            }
            cursor = first;
            part(first, Math.min(last, first + chunk - 1));
            start = first;
            end = Math.min(last, total - 1);
            if (end < start) throw new IOException("File range outside entity");
        } catch (Exception error) {
            dispose(false);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("File range failed", error);
        }
    }

    long length() {
        return end - start + 1;
    }

    String contentRange() {
        return "bytes " + start + "-" + end + "/" + total;
    }

    private void part(long first, long last) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            check();
            connection = open.get(target);
            connection.setRequestProperty("Range", "bytes=" + first + "-" + last);
            if (entityTag != null) connection.setRequestProperty("If-Range", entityTag);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Missing file redirect");
                target = URI.create(ProviderUrls.playback(target.resolve(location).toString()));
                dispose(false);
                continue;
            }
            if (status != 206) throw new IOException("Finite file range unavailable");
            String range = connection.getHeaderField("Content-Range");
            Matcher match = CONTENT_RANGE.matcher(range == null ? "" : range);
            if (!match.matches()) throw new IOException("Missing file range metadata");
            long actualStart = Long.parseLong(match.group(1)),
                actualEnd = Long.parseLong(match.group(2)),
                size = Long.parseLong(match.group(3));
            if (
                actualStart != first ||
                actualEnd < first ||
                actualEnd > last ||
                actualEnd >= size ||
                (total >= 0 && total != size)
            ) throw new IOException("Inconsistent file range");
            String tag = connection.getHeaderField("ETag");
            if (
                tag != null && (tag.length() > 1024 || tag.contains("\r") || tag.contains("\n"))
            ) throw new IOException("File identity rejected");
            if (entityTag != null && tag != null && !entityTag.equals(tag)) throw new IOException(
                "File changed during playback"
            );
            if (entityTag == null && tag != null && !tag.startsWith("W/")) entityTag = tag;
            total = size;
            remaining = actualEnd - actualStart + 1;
            long length = connection.getContentLengthLong();
            if (length >= 0 && length != remaining) throw new IOException("File range length mismatch");
            contentType = connection.getContentType();
            if (contentType == null) contentType = "application/octet-stream";
            if (contentType.contains("\r") || contentType.contains("\n")) throw new IOException(
                "File type rejected"
            );
            input = connection.getInputStream();
            return;
        }
        throw new IOException("File redirect limit");
    }

    private void check() throws IOException {
        if (closed || cancelled.getAsBoolean()) throw new IOException("File range closed");
    }

    @Override
    public int read() throws IOException {
        byte[] value = new byte[1];
        return read(value, 0, 1) < 0 ? -1 : value[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int count) throws IOException {
        if (offset < 0 || count < 0 || offset > bytes.length - count) throw new IndexOutOfBoundsException();
        if (count == 0) return 0;
        check();
        if (cursor > end) return -1;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (input == null || remaining == 0) {
                    dispose(true);
                    part(cursor, Math.min(end, cursor + chunk - 1));
                }
                int read = input.read(
                    bytes,
                    offset,
                    (int) Math.min(count, Math.min(remaining, end - cursor + 1))
                );
                if (read < 0) throw new EOFException("Incomplete file range");
                cursor += read;
                remaining -= read;
                return read;
            } catch (Exception error) {
                dispose(false);
                if (attempt == 1 || cancelled.getAsBoolean()) {
                    if (error instanceof IOException) throw (IOException) error;
                    throw new IOException("File range read failed", error);
                }
            }
        }
        throw new IOException("File range read failed");
    }

    private void dispose(boolean complete) {
        if (input != null) try {
            input.close();
        } catch (IOException ignored) {}
        input = null;
        if (connection != null) release.close(connection, complete && remaining == 0);
        connection = null;
    }

    @Override
    public void close() {
        closed = true;
        dispose(remaining == 0);
    }
}
