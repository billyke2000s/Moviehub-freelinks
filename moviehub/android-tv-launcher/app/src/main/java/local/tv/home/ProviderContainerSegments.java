package local.tv.home;

import java.io.*;
import java.util.zip.GZIPInputStream;

/** Public media carrier formats used by ZLive's player; no token generation or access-control changes. */
final class ProviderContainerSegments {

    private ProviderContainerSegments() {}

    static boolean matches(byte[] head, int size) {
        return (
            ProviderPngSegments.matches(head, size) ||
            ascii(head, size, 0, "TIKTIKRAW") ||
            ascii(head, size, 0, "TIKTIKTSGZ") ||
            (ascii(head, size, 0, "RIFF") && ascii(head, size, 8, "WEBP"))
        );
    }

    private static boolean ascii(byte[] bytes, int size, int offset, String value) {
        if (offset < 0 || offset + value.length() > size) return false;
        for (int i = 0; i < value.length(); i++) if (
            bytes[offset + i] != (byte) value.charAt(i)
        ) return false;
        return true;
    }

    static void write(InputStream source, OutputStream target) throws IOException {
        PushbackInputStream input = new PushbackInputStream(source, 16);
        byte[] head = new byte[16];
        int size = 0,
            count;
        while (size < head.length && (count = input.read(head, size, head.length - size)) != -1)
            size += count;
        input.unread(head, 0, size);
        if (ProviderPngSegments.matches(head, size)) {
            ProviderPngSegments.write(input, target);
            return;
        }
        if (ascii(head, size, 0, "TIKTIKRAW")) {
            new DataInputStream(input).readFully(new byte[9]);
            ProviderPngSegments.writeTs(input, target);
            return;
        }
        if (ascii(head, size, 0, "TIKTIKTSGZ")) {
            new DataInputStream(input).readFully(new byte[10]);
            try (GZIPInputStream gzip = new GZIPInputStream(input, 32768)) {
                ProviderPngSegments.writeTs(gzip, target);
            }
            return;
        }
        // A WebP EXIF chunk contains exactly the transport stream, excluding trailing RIFF data.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > ProviderPngSegments.MAX_ENCODED) throw new IOException(
                "WebP segment limit"
            );
            bytes.write(buffer, 0, count);
        }
        byte[] webp = bytes.toByteArray();
        if (
            !ascii(webp, webp.length, 0, "RIFF") || !ascii(webp, webp.length, 8, "WEBP")
        ) throw new IOException("Unknown media carrier");
        for (int at = 12, chunks = 0; at + 8 <= webp.length; ) {
            if (++chunks > 4096) throw new IOException("WebP chunk limit");
            long length =
                (webp[at + 4] & 255L) |
                ((webp[at + 5] & 255L) << 8) |
                ((webp[at + 6] & 255L) << 16) |
                ((webp[at + 7] & 255L) << 24);
            if (length > webp.length - at - 8) throw new IOException("Truncated WebP chunk");
            if (ascii(webp, webp.length, at, "EXIF")) {
                ProviderPngSegments.writeTs(new ByteArrayInputStream(webp, at + 8, (int) length), target);
                return;
            }
            at += 8 + (int) length + (int) (length & 1);
        }
        throw new IOException("WebP transport payload missing");
    }
}
