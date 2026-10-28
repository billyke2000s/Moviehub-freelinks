package local.tv.home;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Synthetic data only: validates PNG unfiltering, transport sync and bounded failure cases. */
public final class ProviderPngSegmentsTest {

    private static int checks;

    private static void check(boolean value) {
        if (!value) throw new AssertionError("PNG segment check " + checks);
        checks++;
    }

    private static void rejected(byte[] png) throws Exception {
        try {
            ProviderPngSegments.write(new ByteArrayInputStream(png), new ByteArrayOutputStream());
            throw new AssertionError("Invalid PNG accepted");
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void chunk(DataOutputStream out, String type, byte[] bytes) throws IOException {
        byte[] name = type.getBytes("US-ASCII");
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(bytes);
        out.writeInt(bytes.length);
        out.write(name);
        out.write(bytes);
        out.writeInt((int) crc.getValue());
    }

    static byte[] png(byte[] payload, int color, int filter) throws Exception {
        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzip)) {
            out.write(payload);
        }
        ByteArrayOutputStream rgb = new ByteArrayOutputStream();
        DataOutputStream values = new DataOutputStream(rgb);
        values.writeBytes("TIKTIKPX");
        values.writeInt(gzip.size());
        values.write(gzip.toByteArray());
        int width = 7,
            height = (rgb.size() + width * 3 - 1) / (width * 3),
            channels = color == 6 ? 4 : 3;
        byte[] data = Arrays.copyOf(rgb.toByteArray(), width * height * 3);
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        byte[] previous = new byte[width * channels];
        for (int y = 0, at = 0; y < height; y++) {
            byte[] row = new byte[width * channels];
            for (int x = 0; x < width; x++) {
                for (int c = 0; c < 3; c++) row[x * channels + c] = data[at++];
                if (channels == 4) row[x * channels + 3] = (byte) 255;
            }
            pixels.write(filter);
            for (int i = 0; i < row.length; i++) {
                int a = i < channels ? 0 : row[i - channels] & 255;
                int b = previous[i] & 255,
                    c = i < channels ? 0 : previous[i - channels] & 255;
                int p = a + b - c;
                int predictor =
                    Math.abs(p - a) <= Math.abs(p - b) && Math.abs(p - a) <= Math.abs(p - c)
                        ? a
                        : Math.abs(p - b) <= Math.abs(p - c)
                          ? b
                          : c;
                int add =
                    filter == 1
                        ? a
                        : filter == 2
                          ? b
                          : filter == 3
                            ? (a + b) / 2
                            : filter == 4
                              ? predictor
                              : 0;
                pixels.write((row[i] & 255) - add);
            }
            previous = row;
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(compressed)) {
            pixels.writeTo(out);
        }
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(image);
        out.write(new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 });
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(ihdr);
        header.writeInt(width);
        header.writeInt(height);
        header.write(new byte[] { 8, (byte) color, 0, 0, 0 });
        chunk(out, "IHDR", ihdr.toByteArray());
        byte[] zipped = compressed.toByteArray();
        int half = zipped.length / 2;
        chunk(out, "IDAT", Arrays.copyOfRange(zipped, 0, half));
        chunk(out, "IDAT", Arrays.copyOfRange(zipped, half, zipped.length));
        chunk(out, "IEND", new byte[0]);
        return image.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        byte[] ts = new byte[188 * 20];
        new Random(42).nextBytes(ts);
        for (int at = 0; at < ts.length; at += 188) ts[at] = 0x47;
        for (int color : new int[] { 2, 6 })
            for (int filter = 0; filter < 5; filter++) {
                byte[] png = png(ts, color, filter);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ProviderPngSegments.write(new ByteArrayInputStream(png), out);
                check(Arrays.equals(ts, out.toByteArray()));
            }
        byte[] png = png(ts, 2, 0);
        ByteArrayOutputStream appended = new ByteArrayOutputStream();
        appended.write(png);
        appended.write(ts);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProviderPngSegments.write(new ByteArrayInputStream(appended.toByteArray()), out);
        check(Arrays.equals(ts, out.toByteArray()));
        byte[] corrupt = png.clone();
        corrupt[40] ^= 1;
        rejected(corrupt);
        rejected(Arrays.copyOf(png, png.length - 3));
        rejected(new byte[] { 1, 2, 3 });
        rejected(png(new byte[188 * 6], 2, 0));
        byte[] partial = Arrays.copyOf(ts, ts.length - 1);
        rejected(png(partial, 2, 0));
        // The dimensions must be rejected before inflation/allocation, even with a valid header CRC.
        byte[] huge = png.clone();
        huge[16] = 0x7f;
        huge[17] = (byte) 255;
        huge[18] = (byte) 255;
        huge[19] = (byte) 255;
        CRC32 crc = new CRC32();
        crc.update(huge, 12, 17);
        long sum = crc.getValue();
        for (int i = 0; i < 4; i++) huge[29 + i] = (byte) (sum >>> (24 - i * 8));
        rejected(huge);
        System.out.println(checks + " PNG segment checks passed");
    }
}
