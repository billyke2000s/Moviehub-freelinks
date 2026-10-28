package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real HTTP, gzip, conditional requests and SQLite. No live upstream/provider traffic. */
@RunWith(AndroidJUnit4.class)
public class CombinedEpgTest {

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private ChannelDatabase db;
    private ServerSocket server;
    private volatile boolean closed;
    private volatile int status = 200;
    private volatile byte[] body;
    private volatile String conditional = "",
        target = "";
    private final AtomicInteger requests = new AtomicInteger();
    private File cache;

    @Before
    public void prepare() throws Exception {
        db = ChannelDatabase.get(context);
        db.clear();
        context.getSharedPreferences("combinedEpg", 0).edit().clear().commit();
        cache = new File(context.getFilesDir(), "programme-guide");
        clearFiles();
        clearGuide();
        ChannelDatabase.Writer writer = db.writer("m3u", () -> {}, n -> {});
        try {
            writer.accept(
                new Playlist.Channel("BBC One", "https://example.test/combined-bbc", "UK", "", "BBCOne.uk")
            );
            writer.commit();
        } finally {
            writer.abort();
        }
        long now = System.currentTimeMillis();
        body = gzip(xml("xmltv:uk:bbcone", "News &amp; Weather", now - 60000, now + 3600000));
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        CombinedEpg.testUrl = "http://127.0.0.1:" + server.getLocalPort() + "/epg.xml.gz";
        new Thread(() -> {
            while (!closed)
                try {
                    serve(server.accept());
                } catch (IOException ignored) {}
        }, "combined-epg-fixture").start();
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            BufferedReader input = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII)
            );
            target = input.readLine();
            conditional = "";
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty())
                if (line.toLowerCase(Locale.ROOT).startsWith("if-none-match:")) conditional = line;
            requests.incrementAndGet();
            byte[] bytes = status == 304 ? new byte[0] : body;
            OutputStream output = connection.getOutputStream();
            output.write(
                (
                    "HTTP/1.1 " +
                    status +
                    " Result\r\nContent-Length: " +
                    bytes.length +
                    "\r\nETag: \"guide-one\"\r\nConnection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII)
            );
            output.write(bytes);
            output.flush();
        } catch (IOException ignored) {}
    }

    private static String xml(String id, String title, long start, long stop) {
        SimpleDateFormat date = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT);
        date.setTimeZone(TimeZone.getTimeZone("UTC"));
        return (
            "<tv><channel id=\"" +
            id +
            "\"><display-name>Fixture</display-name></channel><programme channel=\"" +
            id +
            "\" start=\"" +
            date.format(new Date(start)) +
            "\" stop=\"" +
            date.format(new Date(stop)) +
            "\"><title>" +
            title +
            "</title></programme></tv>"
        );
    }

    private static byte[] gzip(String xml) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private String current() {
        long now = System.currentTimeMillis();
        List<ChannelDatabase.Programme> found = db.programmes(
            db.byKey(ChannelQuery.key("https://example.test/combined-bbc")),
            "uk",
            now,
            now + 3600000
        );
        return found.isEmpty() ? "" : found.get(0).title;
    }

    private void clearFiles() {
        File[] files = cache.listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    private void clearGuide() {
        ChannelDatabase.GuideWriter writer = db.new GuideWriter(null, true);
        try {
            writer.commit();
        } finally {
            writer.finish();
        }
    }

    @After
    public void cleanup() throws Exception {
        CombinedEpg.testUrl = null;
        closed = true;
        if (server != null) server.close();
        clearFiles();
        context.getSharedPreferences("combinedEpg", 0).edit().clear().commit();
        clearGuide();
        db.clear();
    }

    @Test
    public void onlyCombinedUrlIsFetchedAnd304ReusesTheValidatedCache() throws Exception {
        assertEquals(1, CombinedEpg.refresh(context, db, () -> {}).channels);
        assertEquals("News & Weather", current());
        assertEquals("GET /epg.xml.gz HTTP/1.1", target);
        status = 304;
        assertEquals(1, CombinedEpg.refresh(context, db, () -> {}).channels);
        assertTrue(conditional.contains("guide-one"));
        assertEquals(2, requests.get());
        assertEquals("News & Weather", current());
    }

    @Test
    public void invalidExpiredAndFailedUpdatesRetainTheCacheAndDatabase() throws Exception {
        CombinedEpg.refresh(context, db, () -> {});
        byte[] saved = java.nio.file.Files.readAllBytes(new File(cache, "epg.xml.gz").toPath());
        for (String invalid : new String[] {
            "<tv><broken>",
            xml("xmltv:uk:bbcone", "Expired", 1, 100),
            xml(
                "xmltv:uk:bbcone",
                "",
                System.currentTimeMillis() - 1000,
                System.currentTimeMillis() + 100000
            ),
            "<!DOCTYPE tv [<!ENTITY x 'bad'>]><tv>&x;</tv>",
        }) {
            body = gzip(invalid);
            CombinedEpg.refresh(context, db, () -> {});
            assertEquals("News & Weather", current());
            assertArrayEquals(
                saved,
                java.nio.file.Files.readAllBytes(new File(cache, "epg.xml.gz").toPath())
            );
        }
        status = 503;
        CombinedEpg.refresh(context, db, () -> {});
        assertEquals("News & Weather", current());
    }

    @Test
    public void healthyCombinedUpdateRetainsUnexpiredListingsMissingFromFailedSources() throws Exception {
        long now = System.currentTimeMillis();
        ChannelDatabase.GuideWriter old = db.new GuideWriter("enginexumo:");
        try {
            old.match(EpgMatch.key("enginexumo:cached"), "enginexumo:cached");
            old.programme("enginexumo:cached", now - 60000, now + 3600000, "Cached native", "", "", "");
            old.commit();
        } finally {
            old.finish();
        }
        CombinedEpg.refresh(context, db, () -> {});
        assertEquals("News & Weather", current());
        ChannelDatabase.Row row = new ChannelDatabase.Row(
            0,
            "Cached",
            "US",
            "",
            "",
            "enginexumo:cached",
            "",
            0,
            "",
            "enginexumo"
        );
        assertEquals("Cached native", db.programmes(row, "us", now, now + 3600000).get(0).title);
    }

    @Test
    public void invalidFirstDownloadDoesNotEraseExistingListings() throws Exception {
        long now = System.currentTimeMillis();
        ChannelDatabase.GuideWriter old = db.new GuideWriter();
        try {
            old.match("xmltv:uk:bbcone", "previous");
            old.programme("previous", now - 60000, now + 3600000, "Previous programme", "", "", "");
            old.commit();
        } finally {
            old.finish();
        }
        body = gzip("<tv>invalid");
        try {
            CombinedEpg.refresh(context, db, () -> {});
            fail("Invalid first guide accepted");
        } catch (Exception expected) {
            assertEquals("Previous programme", current());
        }
    }

    @Test
    public void nativeIdsAndCountryFallbackComeFromTheSameFileWithNativePriority() throws Exception {
        ChannelDatabase.Writer channels = db.writer("enginezlive", () -> {}, n -> {});
        try {
            channels.accept(
                new Playlist.Channel(
                    "Sky Witness UK",
                    "https://example.test/witness",
                    "UK",
                    "",
                    "enginezlive:fixture"
                )
            );
            channels.commit();
        } finally {
            channels.abort();
        }
        long now = System.currentTimeMillis();
        String generic = xml("xmltv:uk:skywitness", "Fallback current", now - 60000, now + 3600000);
        String nativeGuide = xml("enginezlive:fixture", "Native current", now - 60000, now + 3600000);
        String combined =
            generic
                .replace("</tv>", "")
                .replace(
                    "<programme",
                    nativeGuide.substring(
                        nativeGuide.indexOf("<channel"),
                        nativeGuide.indexOf("<programme")
                    ) + "<programme"
                ) + nativeGuide.substring(nativeGuide.indexOf("<programme"));
        body = gzip(combined);
        CombinedEpg.refresh(context, db, () -> {});
        ChannelDatabase.Row row = db.byKey(ChannelQuery.key("https://example.test/witness"));
        assertEquals("Native current", db.programmes(row, "uk", now, now + 3600000).get(0).title);
        // A future-only native schedule cannot hide a currently available XMLTV programme.
        String upcoming = xml("enginezlive:fixture", "Native upcoming", now + 1800000, now + 3600000);
        body = gzip(
            generic
                .replace("</tv>", "")
                .replace(
                    "<programme",
                    upcoming.substring(upcoming.indexOf("<channel"), upcoming.indexOf("<programme")) +
                        "<programme"
                ) + upcoming.substring(upcoming.indexOf("<programme"))
        );
        CombinedEpg.refresh(context, db, () -> {});
        assertEquals("Fallback current", db.programmes(row, "uk", now, now + 3600000).get(0).title);
    }
}
