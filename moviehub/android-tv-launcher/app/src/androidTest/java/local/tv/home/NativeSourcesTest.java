package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Provider fixtures verify API parsing and persistence, not real provider availability. */
@RunWith(AndroidJUnit4.class)
public class NativeSourcesTest {

    private static final String ID = "5ad8d3a31b95267e225e4e09";

    private String channel(String id, String name) throws Exception {
        return new JSONObject()
            .put("id", id)
            .put("name", name)
            .put("isStitched", true)
            .put(
                "images",
                new JSONArray().put(
                    new JSONObject()
                        .put("type", "colorLogoPNG")
                        .put("url", "https://images.pluto.tv/logo.png")
                )
            )
            .toString();
    }

    private List<Playlist.Channel> catalogue() throws Exception {
        return PlutoSource.parseCatalogue("{\"data\":[" + channel(ID, "Example") + "]}", "uk");
    }

    @Test
    public void catalogueUsesStableRegionalIdsAndDeduplicates() throws Exception {
        String entry = channel(ID, "Example");
        List<Playlist.Channel> rows = PlutoSource.parseCatalogue(
            "{\"data\":[" + entry + "," + entry + "," + channel("invalid", "Bad") + "]}",
            "uk"
        );
        assertEquals(1, rows.size());
        Playlist.Channel row = rows.get(0);
        assertEquals(PlutoSource.ROOT + ID, row.url);
        assertEquals("pluto:" + ID + ".uk", row.epgId);
        assertEquals("https://images.pluto.tv/logo.png", row.logo);
        assertFalse(row.url.contains("jwt"));
        assertEquals(ID, PlutoSource.channelId(row.url));
    }

    @Test
    public void incompleteAndEmptyCataloguesCannotReplaceCache() throws Exception {
        for (String raw : new String[] {
            "{}",
            "{\"data\":[]}",
            "{\"data\":[" + channel(ID, "Example") + "],\"meta\":{\"totalCount\":2}}",
        }) {
            try {
                PlutoSource.parseCatalogue(raw, "uk");
                fail("Rejected unusable catalogue");
            } catch (IOException | JSONException expected) {}
        }
    }

    @Test
    public void sessionAcceptsOnlyProviderHttpsEndpointsAndCapsCache() throws Exception {
        JSONObject boot = new JSONObject()
            .put("sessionToken", "anonymous-fixture")
            .put(
                "servers",
                new JSONObject()
                    .put("channels", "https://service-channels.clusters.pluto.tv")
                    .put("stitcher", "https://stitcher.pluto.tv")
            )
            .put("session", new JSONObject().put("countryCode", "GB"))
            .put("stitcherParams", "serverSideAds=true")
            .put("refreshInSec", 86400);
        PlutoSource.Session session = new PlutoSource.Session(boot, 1000);
        assertEquals("uk", session.country);
        assertEquals(601000, session.expires);
        assertTrue(PlutoSource.stream(session, ID).contains("serverSideAds=true"));
        assertTrue(PlutoSource.stream(session, ID).contains("jwt=anonymous-fixture"));
        for (String bad : new String[] {
            "https://pluto.tv.evil.example",
            "http://api.pluto.tv",
            "https://api.pluto.tv:444",
            "https://api.pluto.tv/other",
            "https://api.pluto.tv?x=1",
        }) {
            try {
                PlutoSource.endpoint(bad);
                fail("Rejected foreign endpoint");
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test
    public void nativeChannelsKeepFavouriteKeysAcrossRefreshAndRollback() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        Playlist.Channel channel = catalogue().get(0);
        try {
            for (int i = 0; i < 2; i++) {
                ChannelDatabase.Writer writer = db.writer("nativepluto", () -> {}, n -> {});
                try {
                    writer.accept(channel);
                    writer.commit();
                } finally {
                    writer.abort();
                }
                assertNotNull(db.byKey(ChannelQuery.key(channel.url)));
                assertEquals(
                    1,
                    db
                        .browse("favourites", "", Collections.singleton(ChannelQuery.key(channel.url)), 0, 10)
                        .size()
                );
            }
            ChannelDatabase.Writer failed = db.writer("nativepluto", () -> {}, n -> {});
            failed.abort();
            ChannelDatabase.Row saved = db.byKey(ChannelQuery.key(channel.url));
            assertNotNull(saved);
            assertEquals(channel.url, db.url(saved.id, saved.key));
        } finally {
            db.clear();
        }
    }

    @Test
    public void nativeGuideAndXmltvRefreshKeepEachOther() throws Exception {
        ChannelDatabase db = ChannelDatabase.get(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        db.clear();
        for (boolean nativeGuide : new boolean[] { false, true, false }) {
            ChannelDatabase.GuideWriter writer = db.new GuideWriter(nativeGuide);
            try {
                String id = nativeGuide ? "nativepluto:" + ID : "xmltv";
                writer.match(nativeGuide ? "plutofixture" : "xmlfixture", id);
                writer.programme(id, 1, 100, "Example", "", "", "");
                writer.commit();
            } finally {
                writer.finish();
            }
        }
        try (
            android.database.Cursor cursor = db
                .getReadableDatabase()
                .rawQuery("SELECT COUNT(*) FROM programmes", null)
        ) {
            assertTrue(cursor.moveToFirst());
            assertEquals(2, cursor.getInt(0));
        } finally {
            db.clear();
        }
    }

    @org.junit.After
    public void clearNativeGuideFixture() {
        ChannelDatabase db = ChannelDatabase.get(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        for (boolean nativeGuide : new boolean[] { false, true }) {
            ChannelDatabase.GuideWriter cleanup = db.new GuideWriter(nativeGuide);
            try {
                cleanup.commit();
            } finally {
                cleanup.finish();
            }
        }
    }

    @Test
    public void nativeGuideUsesItsOwnRegionInsteadOfMatchingUkChannelNames() throws Exception {
        ChannelDatabase db = ChannelDatabase.get(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        String epg = "pluto:" + ID + ".us";
        for (boolean nativeGuide : new boolean[] { false, true }) {
            ChannelDatabase.GuideWriter writer = db.new GuideWriter(nativeGuide);
            try {
                String channel = nativeGuide ? "nativepluto:" + ID : "xmltv";
                writer.match(nativeGuide ? EpgMatch.key(epg) : EpgLookup.scope("uk", "example"), channel);
                writer.programme(
                    channel,
                    1,
                    100,
                    nativeGuide ? "Native programme" : "Other region",
                    "",
                    "",
                    ""
                );
                writer.commit();
            } finally {
                writer.finish();
            }
        }
        ChannelDatabase.Row row = new ChannelDatabase.Row(
            0,
            "Example",
            "Pluto TV",
            "",
            "",
            epg,
            "",
            1001,
            "International"
        );
        List<ChannelDatabase.Programme> found = db.programmes(row, "uk", 0, 200);
        assertEquals(1, found.size());
        assertEquals("Native programme", found.get(0).title);
    }
}
