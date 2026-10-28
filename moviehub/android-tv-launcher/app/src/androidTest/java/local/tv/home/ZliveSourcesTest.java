package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ZliveSourcesTest {

    @Test
    public void advertisedServersAreLabelledDeduplicatedAndKeepPrimaryIdentity() throws Exception {
        JSONArray options = new JSONArray()
            .put(new JSONObject().put("label", "Main HD").put("resolverSlug", "sky-sports-main-event"))
            .put(
                new JSONObject()
                    .put("label", "Duplicate")
                    .put("url", "https://iptv.zlive.st/sky-sports-main-event")
            )
            .put(
                new JSONObject()
                    .put("label", "Backup")
                    .put("resolverUrl", "https://iptv.zlive.st/sky-main-backup")
                    .put(
                        "headers",
                        new JSONObject().put("Referer", "https://zlive.st/").put("Authorization", "private")
                    )
            )
            .put(new JSONObject().put("label", "Browser option").put("key", "not-a-public-route"));
        JSONArray rows = ZliveSources.expand(
            new JSONArray().put(
                new JSONObject()
                    .put("channelId", "sky-sports-main-event")
                    .put("name", "Sky Sports Main Event")
                    .put("region", "GB")
                    .put("sources", options)
            )
        );
        assertEquals(2, rows.length());
        assertEquals("sky-sports-main-event", rows.getJSONObject(0).getString("channelId"));
        assertEquals("Main HD", rows.getJSONObject(0).getString("sourceLabel"));
        assertEquals("Backup", rows.getJSONObject(1).getString("sourceLabel"));
        assertEquals("sky-main-backup", rows.getJSONObject(1).getString("resolverSlug"));
        assertEquals("sky-sports-main-event.uk", rows.getJSONObject(1).getString("guideChannelId"));
        assertFalse(rows.getJSONObject(1).getJSONObject("sourceHeaders").has("Authorization"));
        assertEquals(
            "https://zlive.st/",
            rows.getJSONObject(1).getJSONObject("sourceHeaders").getString("Referer")
        );
        assertTrue(rows.getJSONObject(1).getString("channelId").contains(ZliveSources.VARIANT));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            ZliveSources.save(context, "fixture module", rows);
            String variant = rows.getJSONObject(1).getString("channelId");
            assertEquals(
                "Backup",
                ZliveSources.hint(context, "fixture module", variant).getString("sourceLabel")
            );
            assertNull(ZliveSources.hint(context, "different module", variant));
        } finally {
            new Secrets(context).put(ZliveSources.HINTS, "");
        }
    }

    @Test
    public void unsupportedWebsiteChoicesAreNotInventedAsResolverRoutes() throws Exception {
        JSONArray options = new JSONArray()
            .put(new JSONObject().put("key", "protected").put("label", "Encrypted browser player"))
            .put(new JSONObject().put("resolverUrl", "https://iptv.zlive.st/channel?token=private"))
            .put(new JSONObject().put("resolverSlug", "other").put("drm", true));
        JSONArray rows = ZliveSources.expand(
            new JSONArray().put(new JSONObject().put("channelId", "channel").put("sources", options))
        );
        assertEquals(1, rows.length());
        assertEquals("channel", rows.getJSONObject(0).getString("resolverSlug"));
        assertEquals("Website-selected", rows.getJSONObject(0).getString("sourceLabel"));
        assertEquals(3, rows.getJSONObject(0).getInt("siteOnlySourceCount"));
    }
}
