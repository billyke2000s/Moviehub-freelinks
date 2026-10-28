package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PlaylistTransportTest {

    private static final String ROOT = "https://8.8.8.8/premium42/";

    private static JSONObject session(long expiry) throws Exception {
        return new JSONObject()
            .put("endpoint", "https://8.8.8.8/api/playlist-token?slug=premium42")
            .put("slug", "premium42")
            .put("token", "old-token")
            .put("expiresAtMs", expiry);
    }

    @After
    public void cleanup() {
        ProviderRelay.testConnections = null;
    }

    @Test
    public void expiredTokenRenewsWithoutLeakingIntoSegmentsOrOtherOrigins() throws Exception {
        PlaylistSession session = new PlaylistSession(ROOT + "index.m3u8", session(0));
        AtomicInteger calls = new AtomicInteger();
        PlaylistSession.Fetch fetch = (uri, body) -> {
            assertNull("DaddyLive renewal remains a body-free GET", body);
            calls.incrementAndGet();
            return new JSONObject().put("token", "fresh-token").put("ttl", 120);
        };
        URI authorized = session.authorize(
            URI.create(ROOT + "index.m3u8?q=keep&t=stale"),
            true,
            false,
            fetch
        );
        assertEquals("q=keep&t=fresh-token", authorized.getRawQuery());
        assertEquals(1, calls.get());
        session.authorize(URI.create(ROOT + "variant.m3u8"), true, false, fetch);
        assertEquals(1, calls.get());
        for (URI foreign : Arrays.asList(
            URI.create("https://8.8.4.4/premium42/index.m3u8"),
            URI.create("https://8.8.8.8/another/index.m3u8"),
            URI.create(ROOT + "segment.png")
        )) {
            boolean playlist = foreign.getPath().endsWith("m3u8");
            assertEquals(foreign, session.authorize(foreign, playlist, false, fetch));
        }
        assertEquals(1, calls.get());
        try {
            new PlaylistSession(
                ROOT + "index.m3u8",
                session(0).put("endpoint", "https://8.8.4.4/api/playlist-token?slug=premium42")
            );
            fail("Cross-origin token endpoint accepted");
        } catch (IOException expected) {}
    }

    @Test
    public void relayRefreshesARejectedTokenAndUnwrapsTheSameSessionsSegment() throws Exception {
        AtomicInteger mints = new AtomicInteger(),
            refused = new AtomicInteger();
        byte[] ts = new byte[188 * 5];
        for (int at = 0; at < ts.length; at += 188) ts[at] = 0x47;
        byte[] png = appended(ts);
        List<URI> requests = Collections.synchronizedList(new ArrayList<>());
        ProviderRelay.testConnections = uri ->
            new HttpURLConnection(uri.toURL()) {
                @Override
                public void connect() {}

                @Override
                public void disconnect() {}

                @Override
                public boolean usingProxy() {
                    return false;
                }

                @Override
                public int getResponseCode() {
                    requests.add(uri);
                    assertEquals("https://player.example", getRequestProperty("Origin"));
                    if (uri.getPath().endsWith("playlist-token")) {
                        mints.incrementAndGet();
                        return 200;
                    }
                    if (
                        (uri.getPath().endsWith("m3u8") || uri.getPath().endsWith("/level")) &&
                        !"t=fresh-token".equals(uri.getRawQuery())
                    ) {
                        refused.incrementAndGet();
                        return 403;
                    }
                    return 200;
                }

                @Override
                public String getContentType() {
                    return "application/octet-stream";
                }

                @Override
                public InputStream getInputStream() {
                    byte[] body = uri.getPath().endsWith("playlist-token")
                        ? "{\"token\":\"fresh-token\",\"ttl\":120}".getBytes(StandardCharsets.UTF_8)
                        : uri.getPath().endsWith("m3u8")
                          ? "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nlevel\n".getBytes(
                                StandardCharsets.UTF_8
                            )
                          : uri.getPath().endsWith("/level")
                            ? "#EXTM3U\n#EXTINF:4,\nsegment.png\n".getBytes(StandardCharsets.UTF_8)
                            : png;
                    return new ByteArrayInputStream(body) {
                        @Override
                        public synchronized int read(byte[] b, int offset, int length) {
                            return super.read(b, offset, Math.min(length, 2));
                        }
                    };
                }
            };
        EngineSources.Stream stream = new EngineSources.Stream(
            new JSONObject()
                .put("url", ROOT + "index.m3u8")
                .put("headers", new JSONObject().put("Origin", "https://player.example"))
                .put("playlistSession", session(System.currentTimeMillis() + 120_000))
        );
        assertTrue(ProviderRelay.needed(stream));
        try (ProviderRelay relay = new ProviderRelay(stream)) {
            String playlist = new String(read(relay.url), StandardCharsets.UTF_8);
            assertFalse(playlist.contains("fresh-token"));
            String child = Arrays.stream(playlist.split("\n"))
                .filter(s -> s.startsWith("http://"))
                .findFirst()
                .get();
            String media = new String(read(child), StandardCharsets.UTF_8);
            String segment = Arrays.stream(media.split("\n"))
                .filter(s -> s.startsWith("http://"))
                .findFirst()
                .get();
            assertArrayEquals(ts, read(segment));
        }
        assertEquals(1, refused.get());
        assertEquals(1, mints.get());
        assertTrue(
            requests
                .stream()
                .anyMatch(u -> u.getPath().endsWith("/level") && "t=fresh-token".equals(u.getRawQuery()))
        );
        assertTrue(
            requests.stream().anyMatch(u -> u.getPath().endsWith("segment.png") && u.getRawQuery() == null)
        );
    }

    @Test
    public void relayRetriesASlowProviderOnceThenGivesUp() throws Exception {
        byte[] ts = new byte[188 * 5];
        for (int at = 0; at < ts.length; at += 188) ts[at] = 0x47;
        AtomicInteger attempts = new AtomicInteger(),
            timeouts = new AtomicInteger(1);
        ProviderRelay.testConnections = uri ->
            new HttpURLConnection(uri.toURL()) {
                @Override
                public void connect() {}

                @Override
                public void disconnect() {}

                @Override
                public boolean usingProxy() {
                    return false;
                }

                @Override
                public int getResponseCode() throws IOException {
                    attempts.incrementAndGet();
                    if (timeouts.getAndDecrement() > 0) throw new SocketTimeoutException("fixture");
                    return 200;
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(ts);
                }
            };
        EngineSources.Stream stream = new EngineSources.Stream(
            new JSONObject()
                .put("url", ROOT + "segment.ts")
                .put("headers", new JSONObject().put("Origin", "https://player.example"))
        );
        try (ProviderRelay relay = new ProviderRelay(stream)) {
            assertArrayEquals("One timeout is retried", ts, read(relay.url));
            assertEquals(2, attempts.get());
            attempts.set(0);
            timeouts.set(2);
            try {
                read(relay.url);
                fail("A second timeout must reach the player");
            } catch (IOException expected) {}
            assertEquals("Only one retry", 2, attempts.get());
        }
    }

    private static byte[] read(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try (
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] appended(byte[] ts) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 });
        chunk(out, "IHDR", new byte[] { 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0 });
        chunk(out, "IEND", new byte[0]);
        out.write(ts);
        return bytes.toByteArray();
    }

    private static void chunk(DataOutputStream out, String type, byte[] bytes) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(name);
        crc.update(bytes);
        out.writeInt(bytes.length);
        out.write(name);
        out.write(bytes);
        out.writeInt((int) crc.getValue());
    }
}
