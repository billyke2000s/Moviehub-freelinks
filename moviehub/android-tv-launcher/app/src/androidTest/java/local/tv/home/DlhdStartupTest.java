package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/** Actual isolated Binder lookups, with local HTTP responses and no live provider dependency. */
@RunWith(AndroidJUnit4.class)
public final class DlhdStartupTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    private JSONObject answer(JSONObject request) throws Exception {
        String url = request.getString("url"),
            text;
        if (url.contains("stream-42.php")) text = "<iframe src=\"https://player.example/embed/42\"></iframe>";
        else if (url.contains("player.example")) text =
            "source: atob(\"" +
            java.util.Base64.getEncoder().encodeToString(
                "https://media.example/live.m3u8".getBytes("UTF-8")
            ) +
            "\")";
        else text = "#EXTM3U\n#EXTINF:4,\nhttps://media.example/segment.ts\n";
        return new JSONObject()
            .put("status", 200)
            .put("url", url)
            .put("headers", new JSONObject())
            .put("text", text);
    }

    @After
    public void cleanup() {
        DlhdPageCache.clear();
    }

    @Test
    public void oneIsolatedProcessPerLookupAndWarmPageSkipsOneRequest() throws Exception {
        String module = ProviderPackages.module(context, "dlhd");
        AtomicInteger requests = new AtomicInteger();
        ProviderBroker.Transport transport = request ->
            DlhdPageCache.request("fixture-version", request, q -> {
                requests.incrementAndGet();
                JSONObject response = answer(q);
                if (q.getString("url").contains("stream-42.php")) response.put(
                    "text",
                    new String(new char[500000]).replace('\0', 'x') + response.getString("text")
                );
                return response;
            });
        int bindings = ProviderSessionBridge.bindings.get();
        for (int n = 0; n < 2; n++) {
            JSONObject value = (JSONObject) ProviderSessionBridge.run(
                context,
                module,
                new JSONObject().put("channelId", "42"),
                transport,
                new NativeHttp()
            );
            assertEquals(1, value.getInt("playerIndex"));
            assertEquals("https://media.example/live.m3u8", value.getString("url"));
            assertEquals(
                "Exactly one isolated binding for the complete walk",
                bindings + n + 1,
                ProviderSessionBridge.bindings.get()
            );
        }
        assertEquals("Three cold requests, two warm: embeds and playlists remain fresh", 5, requests.get());
    }

    @Test
    public void sessionReusesCodeButNeverScriptGlobals() throws Exception {
        String module =
            "if(typeof secret==='undefined')secret=0;function invoke(op,payload){secret++;var p=JSON.parse(payload);" +
            "if(p.responses.length)return {done:true,value:secret};return {done:false,requests:[{url:'https://fixture.example/page',method:'GET',headers:{},body:''}]};}";
        AtomicInteger requests = new AtomicInteger();
        Object value = ProviderSessionBridge.run(
            context,
            module,
            new JSONObject(),
            q -> {
                requests.incrementAndGet();
                return answer(q);
            },
            new NativeHttp()
        );
        assertEquals(1, ((Number) value).intValue());
        assertEquals(1, requests.get());
    }

    @Test
    public void aSocketTimeoutAdvancesButCancellationDoesNot() throws Exception {
        String module =
            "function invoke(op,payload){var p=JSON.parse(payload);" +
            "if(p.responses.length)return {done:true,value:p.responses[0].error};" +
            "return {done:false,requests:[{url:'https://fixture.example/page',method:'GET',headers:{},body:''}]};}";
        Object value = ProviderSessionBridge.run(
            context,
            module,
            new JSONObject(),
            q -> {
                throw new java.net.SocketTimeoutException("fixture timeout");
            },
            new NativeHttp()
        );
        assertEquals(Boolean.TRUE, value);
        NativeHttp cancelled = new NativeHttp();
        try {
            ProviderSessionBridge.run(
                context,
                module,
                new JSONObject(),
                q -> {
                    cancelled.cancel();
                    throw new java.net.SocketTimeoutException("cancelled");
                },
                cancelled
            );
            fail("Cancellation cannot become a provider retry");
        } catch (java.io.InterruptedIOException expected) {}
    }

    @Test
    public void deadlineCancelsBeforeBindingAndNetwork() throws Exception {
        NativeHttp http = new NativeHttp();
        http.deadlineNanos = System.nanoTime() - 1;
        int bindings = ProviderSessionBridge.bindings.get();
        try {
            ProviderSessionBridge.run(
                context,
                "",
                new JSONObject(),
                q -> {
                    throw new AssertionError("No network after cancellation");
                },
                http
            );
            fail("Expired lookup must fail");
        } catch (java.io.InterruptedIOException expected) {
            assertEquals(bindings, ProviderSessionBridge.bindings.get());
        }
    }

    @Test
    public void mirrorCompactionPreservesEveryUpstreamLocatorPattern() throws Exception {
        String html =
            new String(new char[500000]).replace('\0', 'x') +
            "<iframe src=\"//player.example/embed/42\"></iframe>" +
            "data-url='https://mirror.example/plus/stream-42.php' " +
            "https://player.example/premiumtv/daddy4.php?id=42";
        JSONObject response = new JSONObject().put("status", 200).put("text", html);
        String compact = DlhdPageCache.compact(response).getString("text");
        assertTrue(compact.length() < 500);
        assertTrue(compact.contains("//player.example/embed/42"));
        assertTrue(compact.contains("/plus/stream-42.php"));
        assertTrue(compact.contains("/premiumtv/daddy4.php?id=42"));
        response.put("text", html + "/api/playlist-token");
        assertEquals(response.getString("text"), DlhdPageCache.compact(response).getString("text"));
    }

    @Test
    public void anIndependentProviderRevisionReceivesItsWholePage() throws Exception {
        JSONObject request = new JSONObject()
            .put("url", "https://mirror.example/stream/stream-42.php")
            .put("method", "GET")
            .put("headers", new JSONObject())
            .put("body", "");
        String page = "other parser needs this markup <iframe src=\"https://player.example/42\"></iframe>";
        ProviderBroker.Transport network = q -> new JSONObject().put("status", 200).put("text", page);
        DlhdPageCache.requestAt("same-module", request, network, 1, true);
        assertEquals(
            page,
            DlhdPageCache.requestAt("same-module", request, network, 2, false).getString("text")
        );
    }

    @Test
    public void cacheRejectsTokensFailuresCredentialsAndOldOrDifferentModules() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ProviderBroker.Transport network = q -> {
            requests.incrementAndGet();
            JSONObject response = answer(q);
            if (q.getString("url").contains("stream-42.php")) {
                response.put(
                    "text",
                    new String(new char[500000]).replace('\0', 'x') + response.getString("text")
                );
            }
            return response;
        };
        JSONObject page = new JSONObject()
            .put("url", "https://mirror.example/stream/stream-42.php")
            .put("method", "GET")
            .put("headers", new JSONObject())
            .put("body", "");
        DlhdPageCache.requestAt("v1", page, network, 100);
        DlhdPageCache.requestAt("v1", page, network, 101);
        assertEquals(1, requests.get());
        DlhdPageCache.requestAt("v1", page, network, 100 + DlhdPageCache.TTL_MS);
        DlhdPageCache.requestAt("v2", page, network, 100 + DlhdPageCache.TTL_MS);
        assertEquals(3, requests.get());
        JSONObject token = new JSONObject(page.toString()).put(
            "url",
            "https://mirror.example/api/playlist-token?slug=42"
        );
        DlhdPageCache.requestAt("v1", token, network, 101);
        DlhdPageCache.requestAt("v1", token, network, 102);
        assertEquals(5, requests.get());
        page.getJSONObject("headers").put("Cookie", "private");
        DlhdPageCache.requestAt("v1", page, network, 101);
        DlhdPageCache.requestAt("v1", page, network, 102);
        assertEquals(7, requests.get());
        page.getJSONObject("headers").remove("Cookie");
        DlhdPageCache.clear();
        ProviderBroker.Transport failing = q -> {
            requests.incrementAndGet();
            return new JSONObject().put("status", 403).put("text", "blocked");
        };
        DlhdPageCache.requestAt("v1", page, failing, 101);
        DlhdPageCache.requestAt("v1", page, failing, 102);
        assertEquals(9, requests.get());
    }
}
