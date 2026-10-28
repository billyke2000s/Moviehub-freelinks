package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PlaylistSessionTest {

    private JSONObject session() throws Exception {
        return new JSONObject()
            .put("type", "freeshot")
            .put("slug", "sky")
            .put("endpoint", "https://wideiptv.top/api/refresh_token.php")
            .put("token", "old==.signature")
            .put("expiresAtMs", 0);
    }

    @Test
    public void renewalUsesOnlyTheFixedAnonymousEndpointAndStaysScopedToTheChannel() throws Exception {
        PlaylistSession grant = new PlaylistSession("https://ds1.bluetier.top/sky/index.m3u8", session());
        AtomicInteger calls = new AtomicInteger();
        PlaylistSession.Fetch fetch = (endpoint, body) -> {
            calls.incrementAndGet();
            assertEquals("https://wideiptv.top/api/refresh_token.php", endpoint.toString());
            assertEquals("sky", body.getString("channel"));
            assertEquals("old==.signature", body.getString("current_token"));
            return new JSONObject()
                .put("success", true)
                .put("token", "new==.signature")
                .put("expires_in", 300);
        };
        URI playlist = grant.authorize(
            URI.create("https://ds1.bluetier.top/sky/index.m3u8?token=stale"),
            true,
            false,
            fetch
        );
        assertEquals("token=new%3D%3D.signature", playlist.getRawQuery());
        URI segment = grant.authorize(URI.create("https://ds1.bluetier.top/sky/one.ts"), false, false, fetch);
        assertEquals("token=new%3D%3D.signature", segment.getRawQuery());
        assertEquals("Renewed once, then reused for segments", 1, calls.get());
        URI other = URI.create("https://ds2.bluetier.top/sky/index.m3u8");
        assertSame(other, grant.authorize(other, true, false, fetch));
        assertFalse(grant.applies(URI.create("https://ds1.bluetier.top/skylight/index.m3u8"), true));
        assertEquals(1, calls.get());
    }

    @Test
    public void untrustedRenewalDestinationsAndCdnScopesAreRejected() throws Exception {
        try {
            new PlaylistSession(
                "https://ds1.bluetier.top/sky/index.m3u8",
                session().put("endpoint", "https://example.com/api/refresh_token.php")
            );
            fail("Untrusted POST destination accepted");
        } catch (java.io.IOException expected) {}
        try {
            new PlaylistSession("https://example.com/sky/index.m3u8", session());
            fail("Untrusted CDN accepted");
        } catch (java.io.IOException expected) {}
    }
}
