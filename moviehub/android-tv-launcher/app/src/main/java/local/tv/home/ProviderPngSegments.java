package local.tv.home;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Bounded PNG containers carrying MPEG-TS after IEND or gzip bytes in RGB pixels. */
final class ProviderPngSegments {

    static final int MAX_ENCODED = 16 * 1024 * 1024;
    static final int MAX_DECODED = 24 * 1024 * 1024;
    private static final byte[] MAGIC = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };

    private ProviderPngSegments() {}

    static boolean matches(byte[] bytes, int size) {
        if (size < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (bytes[i] != MAGIC[i]) return false;
        return true;
    }

    static void write(InputStream source, OutputStream target) throws IOException {
        byte[] png = bounded(source, MAX_ENCODED);
        if (!matches(png, png.length)) throw new IOException("Invalid segment container");
        int width = 0,
            height = 0,
            channels = 0,
            end = -1;
        List<InputStream> idats = new ArrayList<>();
        for (int at = 8, chunks = 0; at + 12 <= png.length; ) {
            if (++chunks > 4096) throw new IOException("PNG chunk limit");
            long length = unsigned(png, at);
            if (length > png.length - at - 12) throw new IOException("Truncated PNG chunk");
            int size = (int) length;
            String type = new String(png, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(png, at + 4, size + 4);
            if (crc.getValue() != unsigned(png, at + 8 + size)) throw new IOException("Invalid PNG checksum");
            if (type.equals("IHDR")) {
                if (at != 8 || size != 13) throw new IOException("Invalid PNG header");
                long w = unsigned(png, at + 8),
                    h = unsigned(png, at + 12);
                int color = png[at + 17] & 255;
                channels = color == 2 ? 3 : color == 6 ? 4 : 0;
                if (
                    w < 1 ||
                    h < 1 ||
                    w > 8192 ||
                    h > 8192 ||
                    channels == 0 ||
                    png[at + 16] != 8 ||
                    png[at + 18] != 0 ||
                    png[at + 19] != 0 ||
                    png[at + 20] != 0 ||
                    (w * channels + 1) * h > MAX_DECODED
                ) throw new IOException("Unsupported PNG dimensions or layout");
                width = (int) w;
                height = (int) h;
            } else if (type.equals("IDAT")) {
                if (width == 0) throw new IOException("Missing PNG header");
                idats.add(new ByteArrayInputStream(png, at + 8, size));
            } else if (type.equals("IEND")) {
                if (size != 0) throw new IOException("Invalid PNG end");
                end = at + 12;
                break;
            }
            at += size + 12;
        }
        if (end < 0 || width == 0) throw new IOException("Incomplete PNG segment");
        if (end < png.length) {
            writeTs(new ByteArrayInputStream(png, end, png.length - end), target);
            return;
        }
        if (idats.isEmpty()) throw new IOException("Missing PNG pixels");
        byte[] gzip = pixels(
            new SequenceInputStream(Collections.enumeration(idats)),
            width,
            height,
            channels
        );
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip), 32768)) {
            writeTs(in, target);
        }
    }

    private static byte[] pixels(InputStream compressed, int width, int height, int channels)
        throws IOException {
        int stride = width * channels;
        byte[] previous = new byte[stride],
            row = new byte[stride],
            rgb = channels == 3 ? null : new byte[width * 3],
            header = new byte[12],
            gzip = null;
        int found = 0,
            written = 0;
        Inflater inflater = new Inflater();
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(compressed, inflater, 32768))) {
            for (int y = 0; y < height; y++) {
                int filter = in.read();
                if (filter < 0 || filter > 4) throw new IOException("Invalid PNG filter");
                in.readFully(row);
                // Filter 0 is already the final row. Do not walk every byte to add zero.
                for (int i = 0; filter != 0 && i < stride; i++) {
                    int a = i < channels ? 0 : row[i - channels] & 255;
                    int b = previous[i] & 255;
                    int c = i < channels ? 0 : previous[i - channels] & 255;
                    int add =
                        filter == 1
                            ? a
                            : filter == 2
                              ? b
                              : filter == 3
                                ? (a + b) / 2
                                : filter == 4
                                  ? paeth(a, b, c)
                                  : 0;
                    row[i] = (byte) ((row[i] & 255) + add);
                }
                byte[] payload = row;
                if (channels == 4) {
                    for (int x = 0; x < width; x++) System.arraycopy(row, x * 4, rgb, x * 3, 3);
                    payload = rgb;
                }
                int offset = 0;
                if (found < header.length) {
                    int count = Math.min(header.length - found, width * 3);
                    System.arraycopy(payload, 0, header, found, count);
                    found += count;
                    offset = count;
                    if (found == header.length) {
                        if (
                            !new String(header, 0, 8, java.nio.charset.StandardCharsets.US_ASCII).equals(
                                "TIKTIKPX"
                            )
                        ) throw new IOException("Unknown PNG payload");
                        long n = unsigned(header, 8);
                        if (
                            n < 2 || n > MAX_ENCODED || n + 12 > (long) width * height * 3
                        ) throw new IOException("Invalid PNG payload length");
                        gzip = new byte[(int) n];
                    }
                }
                if (gzip != null && written < gzip.length) {
                    int count = Math.min(gzip.length - written, width * 3 - offset);
                    System.arraycopy(payload, offset, gzip, written, count);
                    written += count;
                }
                byte[] swap = previous;
                previous = row;
                row = swap;
            }
            if (in.read() != -1) throw new IOException("Excess PNG pixel data");
        } finally {
            inflater.end();
        }
        if (
            gzip == null || written != gzip.length || (gzip[0] & 255) != 31 || (gzip[1] & 255) != 139
        ) throw new IOException("Incomplete compressed segment");
        return gzip;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c,
            pa = Math.abs(p - a),
            pb = Math.abs(p - b),
            pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    static void writeTs(InputStream in, OutputStream out) throws IOException {
        byte[] head = new byte[5 * 188];
        new DataInputStream(in).readFully(head);
        for (int i = 0; i < 5; i++) if (head[i * 188] != 0x47) throw new IOException("Invalid TS payload");
        out.write(head);
        int total = head.length,
            count;
        byte[] buffer = new byte[32768];
        while ((count = in.read(buffer)) != -1) {
            if (total + count > MAX_DECODED) throw new IOException("Decoded segment limit");
            out.write(buffer, 0, count);
            total += count;
        }
        if (total % 188 != 0) throw new IOException("Truncated TS packet");
    }

    private static byte[] bounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > limit) throw new IOException("Encoded segment limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static long unsigned(byte[] bytes, int offset) {
        return (
            ((bytes[offset] & 255L) << 24) |
            ((bytes[offset + 1] & 255L) << 16) |
            ((bytes[offset + 2] & 255L) << 8) |
            (bytes[offset + 3] & 255L)
        );
    }
}
