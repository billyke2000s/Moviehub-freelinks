package local.tv.home;

import java.io.*;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

/** Synthetic public carrier fixtures, never a live stream or access token. */
public final class ProviderContainerSegmentsTest {

    private static int checks;

    private static byte[] join(byte[] a, byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(a);
        out.write(b);
        return out.toByteArray();
    }

    private static void decoded(byte[] carrier, byte[] expected) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(carrier)) {
            public int read(byte[] b, int at, int n) throws IOException {
                return super.read(b, at, Math.min(2, n));
            }
        };
        ProviderContainerSegments.write(fragmented, out);
        if (!Arrays.equals(expected, out.toByteArray())) throw new AssertionError("Carrier payload changed");
        checks++;
    }

    private static void rejected(byte[] carrier) throws Exception {
        try {
            ProviderContainerSegments.write(new ByteArrayInputStream(carrier), new ByteArrayOutputStream());
            throw new AssertionError("Invalid carrier accepted");
        } catch (IOException expected) {
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] ts = new byte[188 * 7];
        for (int at = 0; at < ts.length; at += 188) ts[at] = 0x47;
        decoded(join("TIKTIKRAW".getBytes("US-ASCII"), ts), ts);
        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzip)) {
            out.write(ts);
        }
        decoded(join("TIKTIKTSGZ".getBytes("US-ASCII"), gzip.toByteArray()), ts);
        decoded(ProviderPngSegmentsTest.png(ts, 2, 4), ts);
        ByteArrayOutputStream webp = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(webp);
        out.writeBytes("RIFF");
        out.writeInt(Integer.reverseBytes(12 + ts.length));
        out.writeBytes("WEBPEXIF");
        out.writeInt(Integer.reverseBytes(ts.length));
        out.write(ts);
        out.writeBytes("JUNK");
        out.writeInt(0);
        decoded(webp.toByteArray(), ts);
        rejected(Arrays.copyOf(webp.toByteArray(), 21));
        rejected("TIKTIKRAWbad".getBytes("US-ASCII"));
        rejected("TIKTIKTSGZbad".getBytes("US-ASCII"));
        rejected("RIFFxxxxWEBP".getBytes("US-ASCII"));
        rejected(new byte[32]);
        System.out.println(checks + " media carrier checks passed");
    }
}
