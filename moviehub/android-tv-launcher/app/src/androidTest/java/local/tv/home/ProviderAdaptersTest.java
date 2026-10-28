package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ProviderAdaptersTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @After
    public void cleanup() {
        ProviderRelay.testConnections = null;
        NativeHttp.testExchangeConnections = null;
        AdapterSources.invalidate();
        new Secrets(context).remove(AdapterSources.TCL_HINTS);
        new Secrets(context).remove(ProviderSessions.KEY);
    }

    @Test
    public void upstreamPostResolverRunsInIsolatedProcess() throws Exception {
        String module = ProviderPackages.module(context, "stirr");
        AtomicInteger requests = new AtomicInteger();
        JSONObject stream = (JSONObject) ProviderBroker.run(
            module,
            "resolve",
            new JSONObject().put("channelId", "42"),
            (source, op, payload) -> ProviderBridge.call(context, source, op, payload),
            request -> {
                requests.incrementAndGet();
                assertEquals("POST", request.getString("method"));
                assertTrue(ProviderBroker.allowed("stirr", request.getString("url")));
                return new JSONObject()
                    .put("status", 200)
                    .put("url", request.getString("url"))
                    .put("headers", new JSONObject())
                    .put("text", "{\"data\":[{\"media\":[\"https://8.8.8.8/live.m3u8?nonce=[vx_nonce]\"]}]}");
            },
            new NativeHttp()
        );
        assertEquals(1, requests.get());
        assertFalse(stream.getString("url").contains("[vx_nonce]"));
    }

    @Test
    public void failedCatalogueReportsHttpAndNetworkCausesWithoutPrivateAddresses() throws Exception {
        String module = ProviderPackages.module(context, "dlhd");
        ProviderBroker.Executor engine = (source, op, payload) ->
            ProviderBridge.call(context, source, op, payload);
        try {
            ProviderBroker.run(
                module,
                "catalogue",
                null,
                engine,
                request ->
                    new JSONObject()
                        .put("status", 403)
                        .put("url", request.getString("url"))
                        .put("headers", new JSONObject())
                        .put("text", "private response"),
                new NativeHttp()
            );
            fail("Rejected directory accepted");
        } catch (ProviderFailure error) {
            assertEquals("Provider returned HTTP 403", error.summary);
        }
        try {
            ProviderBroker.run(
                module,
                "catalogue",
                null,
                engine,
                request -> {
                    throw new UnknownHostException("private provider address");
                },
                new NativeHttp()
            );
            fail("Unreachable directory accepted");
        } catch (ProviderFailure error) {
            assertEquals("DNS lookup failed", error.summary);
        }
    }

    @Test
    public void cancelledInterpreterStepIsNotConvertedIntoAProviderFailure() throws Exception {
        try {
            ProviderBroker.run(
                "fixture",
                "catalogue",
                null,
                (source, operation, input) -> {
                    throw new InterruptedIOException("Cancelled");
                },
                request -> {
                    throw new AssertionError("Cancelled step reached transport");
                },
                new NativeHttp()
            );
            fail("Cancelled operation completed");
        } catch (InterruptedIOException expected) {
            assertEquals("Cancelled", expected.getMessage());
        }
    }

    @Test
    public void capabilitiesRejectForeignHostsAndCredentials() {
        assertTrue(ProviderBroker.allowed("stirr", "https://stirr.com/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "https://stirr.com.evil.example/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "https://user:password@stirr.com/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "http://stirr.com/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "https://127.0.0.1/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "https://stirr.com:1234/api/videos/list/"));
        assertTrue(
            ProviderBroker.allowed(
                "samsung",
                "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/refs/heads/master/SamsungTVPlus/.channels.json.gz"
            )
        );
        assertFalse(
            ProviderBroker.allowed(
                "samsung",
                "https://raw.githubusercontent.com/other/repository/master/.channels.json.gz"
            )
        );
        assertTrue(ProviderBroker.allowed("tubi", "https://c.tubi1.apollo.adrise.io/live/master.m3u8"));
        assertFalse(ProviderBroker.allowed("tubi", "https://adrise.io.evil.example/live/master.m3u8"));
    }

    @Test
    public void upstreamChannelIdsRoundTripWithoutChangingFavourites() throws Exception {
        String id = "US:channel/compound+id";
        String url = AdapterSources.locator("lg", id);
        assertTrue(EngineSources.locator(url));
        assertEquals("lg", AdapterSources.provider(url));
        assertEquals(id, AdapterSources.channel(url));
        assertEquals(url, AdapterSources.locator("lg", id));
        assertFalse(AdapterSources.locator("https://native.moviehub.invalid/engine/untrusted/a"));
    }

    @Test
    public void coldResolversAvoidCatalogueAndTclHintsAreEncryptedAndCompact() throws Exception {
        for (String id : Arrays.asList(
            "stirr",
            "xumo",
            "roku",
            "dlhd",
            "tubi",
            "samsung",
            "localnow",
            "zlive"
        )) {
            JSONObject row = AdapterSources.resolverRow(context, id, "42", new NativeHttp());
            assertEquals("42", row.getString("channelId"));
            assertEquals(1, row.length());
        }
        String media = "https://media.example/signed.m3u8?token=fixture";
        AdapterSources.saveTclHints(
            context,
            ProviderPackages.module(context, "tcl"),
            new JSONArray().put(
                new JSONObject()
                    .put("channelId", "42")
                    .put("source", "fixture-source")
                    .put("media", media)
                    .put("programs", new JSONArray().put("not a resolver input"))
            )
        );
        JSONObject hint = AdapterSources.resolverRow(context, "tcl", "42", new NativeHttp());
        assertEquals(media, hint.getString("media"));
        assertEquals("fixture-source", hint.getString("source"));
        assertFalse(hint.has("programs"));
        String disk = context.getSharedPreferences("accounts", 0).getString(AdapterSources.TCL_HINTS, "");
        assertFalse(disk.contains("fixture-source"));
        assertFalse(disk.contains("media.example"));
    }

    @Test
    public void apiRedirectsKeepPlaybackContextAndDropCredentialsAndPostBodies() throws Exception {
        List<HttpURLConnection> connections = new ArrayList<>();
        AtomicInteger redirectStatus = new AtomicInteger(302);
        NativeHttp.testExchangeConnections = uri -> {
            boolean first = connections.isEmpty();
            HttpURLConnection connection = new HttpURLConnection(uri.toURL()) {
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
                    return first ? redirectStatus.get() : 200;
                }

                @Override
                public String getHeaderField(String key) {
                    return first && key.equalsIgnoreCase("Location") ? "https://8.8.4.4/final" : null;
                }

                @Override
                public Map<String, List<String>> getHeaderFields() {
                    return first
                        ? Collections.singletonMap(
                              "Location",
                              Collections.singletonList("https://8.8.4.4/final")
                          )
                        : Collections.emptyMap();
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public OutputStream getOutputStream() {
                    return new ByteArrayOutputStream();
                }
            };
            connections.add(connection);
            return connection;
        };
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "fixture-player");
        headers.put("Origin", "https://provider.example");
        headers.put("Authorization", "Bearer fixture");
        headers.put("Cookie", "session=fixture");
        headers.put("x-access-token", "fixture");
        new NativeHttp().exchange("https://8.8.8.8/start", "GET", headers, "", target -> true);
        assertEquals(2, connections.size());
        HttpURLConnection finalHop = connections.get(1);
        assertEquals("fixture-player", finalHop.getRequestProperty("User-Agent"));
        assertEquals("https://provider.example", finalHop.getRequestProperty("Origin"));
        assertNull(finalHop.getRequestProperty("Authorization"));
        assertNull(finalHop.getRequestProperty("Cookie"));
        assertNull(finalHop.getRequestProperty("x-access-token"));
        connections.clear();
        JSONObject manual = new NativeHttp().exchange(
            "https://8.8.8.8/start",
            "GET",
            headers,
            "",
            target -> true,
            true
        );
        assertEquals(302, manual.getInt("status"));
        assertEquals(1, connections.size());
        assertEquals("https://8.8.4.4/final", manual.getJSONObject("headers").getString("location"));
        connections.clear();
        redirectStatus.set(307);
        try {
            new NativeHttp().exchange(
                "https://8.8.8.8/start",
                "POST",
                headers,
                "private-body",
                target -> true
            );
            fail("Cross-authority POST body was forwarded");
        } catch (IOException expected) {}
        assertEquals(1, connections.size());
    }

    @Test
    public void zliveSessionProtocolReusesEncryptedHistoryAcrossIsolatedCalls() throws Exception {
        String module = ProviderPackages.module(context, "zlive");
        AtomicInteger contacts = new AtomicInteger();
        long expiry = System.currentTimeMillis() / 1000 + 9000;
        String location =
            "https://cdn.example/main/secure/" +
            new String(new char[64]).replace('\0', 'a') +
            "/" +
            expiry +
            "/fixture.m3u8";
        ProviderBroker.Transport transport = request -> {
            contacts.incrementAndGet();
            String target = request.getString("url");
            JSONObject response = new JSONObject().put("url", target).put("headers", new JSONObject());
            if (target.endsWith("/session/ticket")) {
                assertEquals("GET", request.getString("method"));
                return response.put("status", 200).put("text", "{\"w\":\"fixture-ticket\"}");
            }
            assertEquals("https://iptv.zlive.st/resolve", target);
            assertEquals("POST", request.getString("method"));
            JSONObject body = new JSONObject(request.getString("body"));
            assertEquals("fixture-ticket", body.getString("e"));
            assertEquals(12, java.util.Base64.getDecoder().decode(body.getString("b")).length);
            assertEquals(16, java.util.Base64.getDecoder().decode(body.getString("c")).length);
            return response
                .put("status", 200)
                .put("text", new JSONObject().put("location", location).toString());
        };
        ProviderBroker.Executor engine = (source, op, payload) ->
            ProviderBridge.call(context, source, op, payload);
        JSONObject row = new JSONObject().put("channelId", "fixture");
        JSONObject first = ProviderSessions.resolve(
            context,
            module,
            row,
            engine,
            transport,
            new NativeHttp()
        );
        assertEquals(location, first.getString("url"));
        assertEquals(2, contacts.get());
        JSONObject second = ProviderSessions.resolve(
            context,
            module,
            row,
            engine,
            transport,
            new NativeHttp()
        );
        assertEquals(location, second.getString("url"));
        assertEquals(2, contacts.get());
        try {
            ProviderSessions.resolve(
                context,
                module,
                new JSONObject().put("channelId", "aborted"),
                engine,
                request -> {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("fixture cancel");
                },
                new NativeHttp()
            );
            fail("Cancelled resolve completed");
        } catch (InterruptedIOException expected) {
        } finally {
            Thread.interrupted();
        }
        JSONObject next = ProviderSessions.resolve(
            context,
            module,
            new JSONObject().put("channelId", "other"),
            engine,
            transport,
            new NativeHttp()
        );
        assertEquals(location, next.getString("url"));
        assertEquals(4, contacts.get());
        String disk = context.getSharedPreferences("accounts", 0).getString(ProviderSessions.KEY, "");
        assertFalse(disk.contains("cdn.example"));
        assertFalse(disk.contains("fixture.m3u8"));
    }

    @Test
    public void relayRewritesHlsAndSendsRequiredHeadersThenCloses() throws Exception {
        List<String> origins = Collections.synchronizedList(new ArrayList<>());
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
                    origins.add(getRequestProperty("Origin"));
                    return 200;
                }

                @Override
                public String getContentType() {
                    return "application/octet-stream";
                }

                @Override
                public InputStream getInputStream() {
                    String data = uri.getPath().endsWith("m3u8")
                        ? "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\nsegment.ts\n"
                        : "fixture-media";
                    return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public synchronized int read(byte[] buffer, int offset, int length) {
                            return super.read(buffer, offset, Math.min(2, length));
                        }
                    };
                }
            };
        EngineSources.Stream stream = new EngineSources.Stream(
            new JSONObject()
                .put("url", "https://8.8.8.8/master.m3u8")
                .put("headers", new JSONObject().put("Origin", "https://provider.example"))
                .put("userAgent", "")
                .put("referer", "")
        );
        assertTrue(ProviderRelay.needed(stream));
        String address;
        try (ProviderRelay relay = new ProviderRelay(stream)) {
            address = relay.url;
            String master = read(address);
            assertTrue(master.contains("URI=\"http://127.0.0.1:"));
            String child = Arrays.stream(master.split("\n"))
                .filter(s -> s.startsWith("http://"))
                .findFirst()
                .get();
            long playlistBytes = relay.incomingBytes();
            assertTrue("Count upstream manifest bytes", playlistBytes > 0);
            assertEquals("fixture-media", read(child));
            assertEquals(
                "Count media once, including small upstream reads",
                13,
                relay.incomingBytes() - playlistBytes
            );
            assertEquals(Arrays.asList("https://provider.example", "https://provider.example"), origins);
            assertTrue(master.indexOf("8.8.8.8") < 0);
        }
        try {
            read(address);
            fail("Closed relay accepted connection");
        } catch (IOException expected) {}
    }

    private static String read(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
