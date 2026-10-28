package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/** Deterministic provider contract fixtures; not a claim of live account verification. */
@RunWith(AndroidJUnit4.class)
public class ChannelImporterTest {

    private Context context;
    private Secrets secrets;
    private ChannelDatabase db;
    private ServerSocket server;
    private volatile boolean closed, broken, rejected;
    private String root;
    private boolean oldHttp;
    private String oldFree;

    @Before
    public void prepare() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        secrets = new Secrets(context);
        db = ChannelDatabase.get(context);
        db.clear();
        oldHttp = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", true).commit();
        // These tests cover provider imports; the free public list would add thousands of channels.
        oldFree = context.getSharedPreferences("ui", 0).getString(FreeChannels.PREF, null);
        context.getSharedPreferences("ui", 0).edit().putString(FreeChannels.PREF, "off").commit();
        EpgImporter.guideUrl = ""; // no real programme-guide download in these tests
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        root = "http://127.0.0.1:" + server.getLocalPort();
        new Thread(() -> {
            while (!closed)
                try {
                    serve(server.accept());
                } catch (IOException ignored) {}
        }, "provider-fixture").start();
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII)
            );
            String request = reader.readLine(),
                line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {}
            String body;
            if (broken) body = "invalid provider response";
            else if (request.contains("get_live_categories")) body =
                "[{\"category_id\":\"7\",\"category_name\":\"Sports\"}]";
            else if (request.contains("get_live_streams")) body =
                "[{\"stream_id\":101,\"name\":\"Test live channel\",\"category_id\":\"7\",\"stream_icon\":null}]";
            else if (request.contains("player_api.php")) body = rejected
                ? "{\"user_info\":{\"auth\":0}}"
                : "{\"user_info\":{\"auth\":1,\"status\":\"Active\",\"allowed_output_formats\":[\"m3u8\"]}}";
            else body =
                "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Playlist channel\n" + root + "/stream.m3u8\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = connection.getOutputStream();
            output.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Length: " +
                    bytes.length +
                    "\r\nConnection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII)
            );
            output.write(bytes);
            output.flush();
        } catch (Exception ignored) {}
    }

    @Test
    public void validatesXtreamLoginAndNegotiatesHls() throws Exception {
        JSONObject account = MediaRepository.connectXtream(root, "user", "pass", true);
        assertEquals("m3u8", account.getString("format"));
        rejected = true;
        boolean failed = false;
        try {
            MediaRepository.connectXtream(root, "wrong", "pass", true);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue("Reject invalid credentials", failed);
    }

    @Test
    public void importsBothXtreamFormatsAndM3uAndKeepsFailedRefresh() throws Exception {
        for (String format : new String[] { "ts", "m3u8" }) {
            secrets.put(
                "xtream",
                new JSONObject()
                    .put("server", root)
                    .put("username", "user /+")
                    .put("password", "pass?&")
                    .put("format", format)
                    .toString()
            );
            assertTrue(ChannelImporter.run(context, () -> {}));
            assertEquals(1, db.count());
            ChannelDatabase.Row row = db.all(0).get(0);
            assertEquals(
                ChannelQuery.xtreamUrl(root, "user /+", "pass?&", "101", format),
                db.url(row.id, row.key)
            );
            assertEquals("Sports", row.group);
        }
        secrets.put("m3u", root + "/list.m3u");
        assertTrue(ChannelImporter.run(context, () -> {}));
        // A finished refresh records this app version, so the next update refreshes once.
        assertEquals(
            BuildConfig.VERSION_CODE,
            context.getSharedPreferences("channelImport", 0).getInt("refreshedVersion", -1)
        );
        assertTrue(
            "A finished refresh is recorded even if a source failed",
            context.getSharedPreferences("channelImport", 0).getLong("lastFinished", 0) > 0
        );
        assertFalse(ChannelSync.updatedSinceRefresh(BuildConfig.VERSION_CODE, BuildConfig.VERSION_CODE));
        assertTrue(ChannelSync.updatedSinceRefresh(BuildConfig.VERSION_CODE - 1, BuildConfig.VERSION_CODE));
        assertTrue("A version never recorded counts as updated", ChannelSync.updatedSinceRefresh(-1, 7));
        assertEquals(2, db.count());
        broken = true;
        assertFalse(ChannelImporter.run(context, () -> {}));
        assertEquals(2, db.count());
        secrets.remove("m3u");
        secrets.remove("xtream");
        broken = false;
        assertTrue(ChannelImporter.run(context, () -> {}));
        assertEquals(0, db.count());
    }

    @Test
    public void legacyCacheMigratesWithoutLosingFavouriteIdentity() throws Exception {
        Playlist.Channel channel = new Playlist.Channel(
            "Legacy channel",
            "https://legacy.example/live/user/password/42.ts",
            "News",
            "",
            ""
        );
        try (ChannelStore old = new ChannelStore(context)) {
            old.replace(java.util.Collections.singletonList(channel));
        }
        String key = ChannelQuery.key(channel.url);
        java.util.Set<String> previous = new java.util.HashSet<>(
            context.getSharedPreferences("ui", 0).getStringSet("favourites", java.util.Collections.emptySet())
        );
        try {
            context
                .getSharedPreferences("ui", 0)
                .edit()
                .putStringSet("favourites", java.util.Collections.singleton(key))
                .commit();
            secrets.put("m3u", root + "/broken.m3u");
            broken = true;
            assertFalse(ChannelImporter.run(context, () -> {}));
            assertFalse(context.getDatabasePath("channels.db").exists());
            assertEquals(1, db.count());
            ChannelDatabase.Row row = db.all(0).get(0);
            assertEquals(key, row.key);
            assertEquals(channel.url, db.url(row.id, key));
            // The old database is already gone: another outage must still retain its migrated copy.
            assertFalse(ChannelImporter.run(context, () -> {}));
            assertEquals(1, db.count());
            assertNotNull(db.byKey(key));
            assertTrue(
                context
                    .getSharedPreferences("ui", 0)
                    .getStringSet("favourites", java.util.Collections.emptySet())
                    .contains(key)
            );
        } finally {
            context.getSharedPreferences("ui", 0).edit().putStringSet("favourites", previous).commit();
            context.deleteDatabase("channels.db");
        }
    }

    @Test
    public void disabledFreeSourceIsRemovedEvenWhenAnotherProviderFails() throws Exception {
        ChannelDatabase.Writer writer = db.writer("loophost", () -> {}, n -> {});
        try {
            writer.accept(new Playlist.Channel("Disabled", "https://example.org/live.m3u8", "", "", ""));
            writer.commit();
        } finally {
            writer.abort();
        }
        secrets.put("m3u", root + "/broken.m3u");
        broken = true;
        assertFalse(ChannelImporter.run(context, () -> {}));
        assertEquals("A failed replacement cannot reactivate the disabled FreeLinks source", 0, db.count());
    }

    @After
    public void cleanup() throws Exception {
        EpgImporter.guideUrl = EpgImporter.COMBINED_GUIDE;
        SharedPreferences.Editor restore = context.getSharedPreferences("ui", 0).edit();
        if (oldFree == null) restore.remove(FreeChannels.PREF);
        else restore.putString(FreeChannels.PREF, oldFree);
        restore.commit();
        closed = true;
        if (server != null) server.close();
        secrets.remove("xtream");
        secrets.remove("m3u");
        db.clear();
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", oldHttp).commit();
    }
}
