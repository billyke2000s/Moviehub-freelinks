package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import org.json.*;
import org.junit.*;

public final class ProviderEngineDeviceTest {

    private Context context;

    @Before
    public void before() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        clean();
    }

    @After
    public void clean() {
        ProviderPackages.testTrust = null;
        EngineSources.testCatalogue = null;
        EngineSources.invalidate();
        if (context == null) return;
        for (String slot : new String[] { "active", "previous" }) {
            new android.util.AtomicFile(
                new File(context.getFilesDir(), "provider-" + slot + ".json")
            ).delete();
        }
        context.getSharedPreferences("providerEngine", 0).edit().clear().commit();
        context.getSharedPreferences("ui", 0).edit().remove("providerAutoUpdates").commit();
    }

    @Test
    public void isolatedBridgeRunsActualCompiledAdapterAndRejectsJava() throws Exception {
        String source = ProviderPackages.module(context);
        JSONArray rows = new JSONArray(
            ProviderBridge.call(
                context,
                source,
                "catalogue",
                "[{\"_id\":\"a\",\"name\":\"Sport\",\"url\":\"https://example.com/a.m3u8\"}]"
            )
        );
        assertEquals("a", rows.getJSONObject(0).getString("channelId"));
        String large =
            "[{\"_id\":\"large\",\"name\":\"Sport\",\"url\":\"https://example.com/a.m3u8\",\"unused\":\"" +
            new String(new char[1200000]).replace('\0', 'x') +
            "\"}]";
        assertEquals(
            "large",
            new JSONArray(ProviderBridge.call(context, source, "catalogue", large))
                .getJSONObject(0)
                .getString("channelId")
        );
        JSONObject resolved = new JSONObject(
            ProviderBridge.call(context, source, "resolve", "https://example.com/a.m3u8?cb=[CB]")
        );
        assertFalse(resolved.getString("url").contains("[CB]"));
        try {
            ProviderBridge.call(
                context,
                "function invoke(){return java.lang.System.getProperties();}",
                "",
                ""
            );
            fail("Java access accepted");
        } catch (Exception expected) {}
        // A failed operation must not poison the next isolated process.
        assertTrue(new JSONObject(ProviderBridge.call(context, source, "metadata", "")).has("url"));
    }

    @Test
    public void signedInstallRejectsTamperingAndDowngradeAndRollsBack() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair keys = generator.generateKeyPair();
        ProviderPackages.testTrust = new JSONObject().put(
            "publicKey",
            Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())
        );
        byte[] second = candidate(2),
            third = candidate(3);
        ProviderPackages.install(context, second, signature(keys, second));
        assertEquals(2, ProviderPackages.current(context).getLong("version"));
        try {
            ProviderPackages.install(context, third, signature(keys, second));
            fail("Tampering accepted");
        } catch (GeneralSecurityException expected) {}
        assertEquals(2, ProviderPackages.current(context).getLong("version"));
        ProviderPackages.install(context, third, signature(keys, third));
        assertEquals(3, ProviderPackages.current(context).getLong("version"));
        ProviderPackages.rollback(context);
        assertEquals(2, ProviderPackages.current(context).getLong("version"));
        assertFalse(context.getSharedPreferences("ui", 0).getBoolean("providerAutoUpdates", true));
        assertEquals(3, context.getSharedPreferences("providerEngine", 0).getLong("highWater", 0));
        try {
            ProviderPackages.install(context, second, signature(keys, second));
            fail("Downgrade accepted");
        } catch (java.io.IOException expected) {}
        // Corrupted active disk content falls back to the independently verified previous slot.
        try (
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                new File(context.getFilesDir(), "provider-active.json")
            )
        ) {
            out.write(1);
        }
        assertEquals(2, ProviderPackages.current(context).getLong("version"));
    }

    @Test
    public void incompatiblePackageAndInvalidLocatorAreRejected() throws Exception {
        JSONObject pkg = new JSONObject(ProviderPackages.asset(context, "builtin.json"));
        pkg.put("runtime", 3);
        try {
            ProviderPackages.validate(pkg.toString().getBytes(StandardCharsets.UTF_8));
            fail("Runtime accepted");
        } catch (java.io.IOException expected) {}
        try {
            EngineSources.id(EngineSources.ROOT + "../bad");
            fail("Locator accepted");
        } catch (IllegalArgumentException expected) {}
    }

    private static String xmltvTime(long millis) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
            "yyyyMMddHHmmss Z",
            java.util.Locale.ROOT
        );
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis));
    }

    private static String plexGuide(long now, String... ids) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><tv>");
        for (String id : ids)
            xml.append("<channel id=\"").append(id).append("\"><display-name>X</display-name></channel>");
        for (String id : ids)
            xml.append("<programme channel=\"")
                .append(id)
                .append("\" start=\"")
                .append(xmltvTime(now - 600_000))
                .append("\" stop=\"")
                .append(xmltvTime(now + 3_000_000))
                .append("\"><title>Show on ")
                .append(id.substring(id.lastIndexOf('-') + 1))
                .append("</title></programme>");
        return xml.append("</tv>").toString();
    }

    @Test
    public void plexListingsMatchByStableIdAndFallBackToTheUsGuide() throws Exception {
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String source = AdapterSources.source("plex");
        long now = System.currentTimeMillis();
        java.util.List<String> asked = new java.util.ArrayList<>();
        try {
            ChannelDatabase.Writer channels = db.writer(source, () -> {}, n -> {});
            try {
                for (String id : new String[] { "aaa111", "bbb222" })
                    channels.accept(
                        new Playlist.Channel(
                            "Plex " + id,
                            AdapterSources.locator("plex", id),
                            "Movies",
                            "",
                            source + ":" + id
                        )
                    );
                channels.commit();
            } finally {
                channels.abort();
            }
            JSONArray rows = new JSONArray()
                .put(new JSONObject().put("channelId", "aaa111").put("name", "Plex aaa111"))
                .put(new JSONObject().put("channelId", "bbb222").put("name", "Plex bbb222"));
            // The UK file has neither channel (plus an unrelated one); the US file has both, under a
            // different server prefix than the catalogue would use.
            ProviderGuide.opener = region -> {
                asked.add(region);
                String xml = region.equals("gb")
                    ? plexGuide(now, "srv-zzz999")
                    : plexGuide(now, "usserver-aaa111", "usserver-bbb222", "usserver-ccc333");
                return new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            };
            assertEquals(2, ProviderGuide.refresh(ProviderGuide.PLEX, db, rows, () -> {}));
            assertEquals(java.util.Arrays.asList("gb", "us"), asked);
            for (String id : new String[] { "aaa111", "bbb222" }) {
                ChannelDatabase.Row row = db.byKey(ChannelQuery.key(AdapterSources.locator("plex", id)));
                assertNotNull(row);
                assertEquals(
                    "Show on " + id,
                    db.programmes(row, "uk", now - 60_000, now + 60_000).get(0).title
                );
            }
            // A UK file covering the lineup is enough: the US file is not downloaded.
            asked.clear();
            ProviderGuide.opener = region -> {
                asked.add(region);
                return new java.io.ByteArrayInputStream(
                    plexGuide(now, "gbserver-aaa111", "gbserver-bbb222").getBytes(StandardCharsets.UTF_8)
                );
            };
            assertEquals(2, ProviderGuide.refresh(ProviderGuide.PLEX, db, rows, () -> {}));
            assertEquals(java.util.Collections.singletonList("gb"), asked);
            assertEquals("bbb222", ProviderGuide.stableId("64305451fc3be5947773c339-bbb222"));
        } finally {
            ProviderGuide.opener = null;
            db.clear();
            ChannelDatabase.GuideWriter guide = db.new GuideWriter(source + ":");
            try {
                guide.commit();
            } finally {
                guide.finish();
            }
        }
    }

    @Test
    public void samsungListingsMatchTheStoredChannelIdsUnchanged() throws Exception {
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String source = AdapterSources.source("samsung");
        long now = System.currentTimeMillis();
        try {
            ChannelDatabase.Writer channels = db.writer(source, () -> {}, n -> {});
            try {
                channels.accept(
                    new Playlist.Channel(
                        "Samsung fixture",
                        AdapterSources.locator("samsung", "GBBC2300003ZQ"),
                        "Entertainment",
                        "",
                        source + ":GBBC2300003ZQ"
                    )
                );
                channels.commit();
            } finally {
                channels.abort();
            }
            assertSame(ProviderGuide.SAMSUNG, ProviderGuide.forProvider("samsung"));
            assertNull(
                "A provider with its own listings needs no separate guide",
                ProviderGuide.forProvider("tubi")
            );
            ProviderGuide.opener = region -> {
                assertEquals("gb", region);
                return new java.io.ByteArrayInputStream(
                    plexGuide(now, "GBBC2300003ZQ", "GBXX0000000AA").getBytes(StandardCharsets.UTF_8)
                );
            };
            JSONArray rows = new JSONArray().put(
                new JSONObject().put("channelId", "GBBC2300003ZQ").put("name", "S")
            );
            assertEquals(1, ProviderGuide.refresh(ProviderGuide.SAMSUNG, db, rows, () -> {}));
            ChannelDatabase.Row row = db.byKey(
                ChannelQuery.key(AdapterSources.locator("samsung", "GBBC2300003ZQ"))
            );
            assertEquals(
                "Show on GBBC2300003ZQ",
                db.programmes(row, "uk", now - 60_000, now + 60_000).get(0).title
            );
        } finally {
            ProviderGuide.opener = null;
            db.clear();
            ChannelDatabase.GuideWriter guide = db.new GuideWriter(source + ":");
            try {
                guide.commit();
            } finally {
                guide.finish();
            }
        }
    }

    @Test
    public void realBridgeRefreshPreservesStableChannelsFavouritesAndUsesCombinedGuide() throws Exception {
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        EngineSources.testCatalogue =
            "[{\"_id\":\"sport\",\"name\":\"Sport\",\"url\":\"https://example.com/a.m3u8?cb=[CB]\",\"epg\":{\"entries\":[{\"title\":\"Live Sport\",\"start\":\"2026-10-19T00:00:00Z\",\"stop\":\"2026-10-19T01:00:00Z\"}]}}]";
        try {
            assertEquals(1, EngineSources.refresh(context, db, () -> {}));
            String key = ChannelQuery.key(EngineSources.ROOT + "sport");
            ChannelDatabase.Row row = db.byKey(key);
            assertNotNull(row);
            assertEquals(EngineSources.ROOT + "sport", db.url(row.id, row.key));
            assertEquals(1, db.browse("favourites", "", java.util.Collections.singleton(key), 0, 10).size());
            long now = System.currentTimeMillis();
            assertTrue(
                "Catalogue import must not write a second built-in guide",
                db.programmes(row, "uk", now, now + 3600000).isEmpty()
            );
            java.io.File file = new java.io.File(context.getCacheDir(), "fls-combined-fixture.gz");
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                "yyyyMMddHHmmss Z",
                java.util.Locale.ROOT
            );
            String xml =
                "<tv><channel id=\"enginefls:sport\"><display-name>Sport</display-name></channel><programme channel=\"enginefls:sport\" start=\"" +
                format.format(new java.util.Date(now - 60000)) +
                "\" stop=\"" +
                format.format(new java.util.Date(now + 3600000)) +
                "\"><title>Live Sport</title></programme></tv>";
            try (
                java.util.zip.GZIPOutputStream output = new java.util.zip.GZIPOutputStream(
                    new java.io.FileOutputStream(file)
                )
            ) {
                output.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try {
                assertEquals(1, CombinedEpg.install(file, db, () -> {}).channels);
                assertEquals("Live Sport", db.programmes(row, "uk", now, now + 3600000).get(0).title);
                assertEquals(1, EngineSources.refresh(context, db, () -> {}));
                assertEquals(
                    "Channel catalogue refresh retains the combined schedule",
                    "Live Sport",
                    db.programmes(row, "uk", now, now + 3600000).get(0).title
                );
            } finally {
                file.delete();
            }
            EngineSources.testCatalogue = "[]";
            try {
                EngineSources.refresh(context, db, () -> {});
                fail("Empty catalogue accepted");
            } catch (java.io.IOException expected) {}
            assertNotNull(db.byKey(key));
        } finally {
            db.clear();
            for (String prefix : new String[] { "enginefls:", null }) {
                ChannelDatabase.GuideWriter guide = db.new GuideWriter(prefix);
                try {
                    guide.commit();
                } finally {
                    guide.finish();
                }
            }
        }
    }

    private byte[] candidate(long version) throws Exception {
        return new JSONObject(ProviderPackages.asset(context, "builtin.json"))
            .put("version", version)
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] signature(KeyPair keys, byte[] payload) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(keys.getPrivate());
        s.update(payload);
        return s.sign();
    }
}
