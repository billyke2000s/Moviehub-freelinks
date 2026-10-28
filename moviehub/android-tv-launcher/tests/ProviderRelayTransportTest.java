package local.tv.home;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import org.json.JSONObject;

/** Real TCP client and production relay, with only the upstream HTTP transport replaced. */
public final class ProviderRelayTransportTest {

    private static void rawTsWithImageMime(byte[] ts) throws Exception {
        AtomicReference<HttpURLConnection> requested = new AtomicReference<>();
        ProviderRelay.testConnections = uri -> {
            HttpURLConnection response = new HttpURLConnection(uri.toURL()) {
                public void connect() {}

                public void disconnect() {}

                public boolean usingProxy() {
                    return false;
                }

                public int getResponseCode() {
                    return 200;
                }

                public InputStream getInputStream() {
                    return new ByteArrayInputStream(ts);
                }

                public String getContentType() {
                    return "image/jpeg";
                }

                public long getContentLengthLong() {
                    return ts.length;
                }
            };
            requested.set(response);
            return response;
        };
        EngineSources.Stream stream = new EngineSources.Stream(
            new JSONObject()
                .put("url", "https://8.8.8.8/segment.jpg")
                .put("headers", new JSONObject())
                .put("segmentContainers", true)
        );
        try (ProviderRelay relay = new ProviderRelay(stream)) {
            HttpURLConnection client = (HttpURLConnection) URI.create(relay.url).toURL().openConnection();
            client.setRequestProperty("Range", "bytes=0-");
            try (InputStream input = client.getInputStream()) {
                if (!"video/mp2t".equals(client.getContentType())) throw new AssertionError(
                    "A whole TS entity retained its misleading image MIME type"
                );
                if (!Arrays.equals(ts, input.readAllBytes())) throw new AssertionError("TS payload changed");
                if (requested.get().getRequestProperty("Range") != null) throw new AssertionError(
                    "Whole-entity media sent a carrier byte range"
                );
            } finally {
                client.disconnect();
            }
        } finally {
            ProviderRelay.testConnections = null;
        }
    }

    private static void chunk(DataOutputStream out, String name, byte[] data) throws Exception {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        out.writeInt(data.length);
        out.write(type);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }

    public static void main(String[] args) throws Exception {
        byte[] ts = new byte[188 * 5];
        for (int at = 0; at < ts.length; at += 188) ts[at] = 0x47;
        rawTsWithImageMime(ts);
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        DataOutputStream png = new DataOutputStream(image);
        png.write(new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 });
        ByteArrayOutputStream dimensions = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(dimensions);
        header.writeInt(1);
        header.writeInt(1);
        header.write(new byte[] { 8, 2, 0, 0, 0 });
        chunk(png, "IHDR", dimensions.toByteArray());
        chunk(png, "IEND", new byte[0]);
        png.write(ts);
        AtomicReference<HttpURLConnection> requested = new AtomicReference<>();
        ProviderRelay.testConnections = uri -> {
            HttpURLConnection response = new HttpURLConnection(uri.toURL()) {
                public void connect() {}

                public void disconnect() {}

                public boolean usingProxy() {
                    return false;
                }

                public int getResponseCode() {
                    return 200;
                }

                public InputStream getInputStream() {
                    return new ByteArrayInputStream(image.toByteArray());
                }

                public String getContentType() {
                    return "image/png";
                }
            };
            requested.set(response);
            return response;
        };
        JSONObject session = new JSONObject()
            .put("endpoint", "https://8.8.8.8/api/playlist-token?slug=channel")
            .put("slug", "channel")
            .put("token", "fixture")
            .put("expiresAtMs", System.currentTimeMillis() + 90000);
        EngineSources.Stream stream = new EngineSources.Stream(
            new JSONObject()
                .put("url", "https://8.8.8.8/channel/segment.png")
                .put("headers", new JSONObject())
                .put("segmentContainers", true)
        );
        try (ProviderRelay relay = new ProviderRelay(stream)) {
            URI address = URI.create(relay.url);
            try (Socket client = new Socket("127.0.0.1", address.getPort())) {
                client.setSoTimeout(5000);
                client
                    .getOutputStream()
                    .write(
                        (
                            "GET " +
                            address.getRawPath() +
                            " HTTP/1.1\r\nHost: localhost\r\nRange: bytes=0-\r\n\r\n"
                        ).getBytes(StandardCharsets.US_ASCII)
                    );
                byte[] answer = client.getInputStream().readAllBytes();
                String response = new String(answer, StandardCharsets.ISO_8859_1);
                int boundary = response.indexOf("\r\n\r\n");
                if (boundary < 0 || !response.startsWith("HTTP/1.1 200 ")) throw new AssertionError(
                    "Whole-entity request rejected"
                );
                if (
                    !Arrays.equals(ts, Arrays.copyOfRange(answer, boundary + 4, answer.length))
                ) throw new AssertionError("Decoded bytes changed");
                if (requested.get().getRequestProperty("Range") != null) throw new AssertionError(
                    "Decoded offsets sent to container"
                );
                if (response.substring(0, boundary).contains("Content-Range")) throw new AssertionError(
                    "Container range exposed for decoded entity"
                );
            }
        } finally {
            ProviderRelay.testConnections = null;
        }
        byte[] movie = "fixture-mp4-payload".getBytes(StandardCharsets.US_ASCII);
        ProviderRelay.testConnections = uri ->
            new HttpURLConnection(uri.toURL()) {
                public void connect() {}

                public void disconnect() {}

                public boolean usingProxy() {
                    return false;
                }

                public int getResponseCode() {
                    return 206;
                }

                public InputStream getInputStream() {
                    return new ByteArrayInputStream(movie);
                }

                public String getContentType() {
                    return "video/mp4";
                }

                public long getContentLengthLong() {
                    return movie.length;
                }

                public String getHeaderField(String name) {
                    if (name.equalsIgnoreCase("Content-Range")) return "bytes 0-18/19";
                    if (name.equalsIgnoreCase("Accept-Ranges")) return "bytes";
                    return null;
                }
            };
        EngineSources.Stream file = new EngineSources.Stream(
            new JSONObject().put("url", "https://8.8.8.8/movie.mp4").put("headers", new JSONObject())
        );
        try (ProviderRelay relay = new ProviderRelay(file)) {
            if (relay.url.endsWith(".m3u8")) throw new AssertionError("File advertised as HLS");
            HttpURLConnection c = (HttpURLConnection) new URL(relay.url).openConnection();
            c.setRequestProperty("Range", "bytes=0-");
            c.setReadTimeout(5000);
            try (InputStream input = c.getInputStream()) {
                if (
                    c.getContentLengthLong() != movie.length ||
                    !"bytes".equals(c.getHeaderField("Accept-Ranges"))
                ) throw new AssertionError("File seek headers lost");
                if (!Arrays.equals(movie, input.readAllBytes())) throw new AssertionError(
                    "File payload changed"
                );
                if (relay.incomingBytes() != movie.length) throw new AssertionError(
                    "Incoming file bytes miscounted"
                );
            } finally {
                c.disconnect();
            }
        } finally {
            ProviderRelay.testConnections = null;
        }
        System.out.println("9 real TCP file, raw-TS and wrapped-segment range checks passed");
    }
}
