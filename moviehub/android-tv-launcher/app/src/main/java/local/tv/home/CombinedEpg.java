package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.xmlpull.v1.XmlPullParser;

/** One cached server-generated XMLTV file. Called only by the background channel importer. */
final class CombinedEpg {

    static final String URL = "https://raw.githubusercontent.com/billyke2000s/Moviehub-epg/main/epg.xml.gz";
    static final long CHECK_MS = 6L * 3600_000;
    static final long MAX_COMPRESSED = 64L * 1024 * 1024;
    static final long MAX_EXPANDED = 1024L * 1024 * 1024;
    /** Loopback HTTP fixture only; app code never assigns this. */
    static volatile String testUrl;

    private CombinedEpg() {}

    static EpgImporter.Result refresh(Context context, ChannelDatabase db, ChannelDatabase.Cancel cancel)
        throws Exception {
        File directory = new File(context.getFilesDir(), "programme-guide");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Guide cache unavailable");
        File cached = new File(directory, "epg.xml.gz"),
            candidate = new File(directory, "candidate.gz");
        SharedPreferences prefs = context.getSharedPreferences("combinedEpg", 0);
        long now = System.currentTimeMillis();
        if (
            cached.isFile() && now - prefs.getLong("checked", 0) < CHECK_MS && testUrl == null
        ) return install(cached, db, cancel);
        try {
            Map<String, String> headers = fetch(cached, candidate, prefs, cancel);
            File chosen = candidate.isFile() ? candidate : cached;
            int current = validate(chosen, cancel);
            int previous = prefs.getInt("currentChannels", 0);
            if (candidate.isFile() && previous > 20 && current < previous * 0.7) throw new IOException(
                "Combined guide coverage fell unexpectedly"
            );
            EpgImporter.Result result = install(chosen, db, cancel);
            if (candidate.isFile()) Files.move(
                candidate.toPath(),
                cached.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
            SharedPreferences.Editor edit = prefs
                .edit()
                .putLong("checked", now)
                .putInt("currentChannels", current);
            for (Map.Entry<String, String> header : headers.entrySet())
                edit.putString(header.getKey(), header.getValue());
            edit.apply();
            return result;
        } catch (Exception failure) {
            cancel.check();
            Diagnostics.recovered("Combined programme guide update", failure);
            if (cached.isFile()) return install(cached, db, cancel);
            throw failure;
        } finally {
            if (candidate.exists() && !candidate.delete()) candidate.deleteOnExit();
        }
    }

    private static Map<String, String> fetch(
        File cached,
        File candidate,
        SharedPreferences prefs,
        ChannelDatabase.Cancel cancel
    ) throws Exception {
        if (candidate.exists() && !candidate.delete()) throw new IOException("Guide staging unavailable");
        String address = testUrl == null ? URL : testUrl;
        if (testUrl != null && !address.startsWith("http://127.0.0.1:")) throw new IOException(
            "Only loopback guide fixtures are supported"
        );
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (cached.isFile()) {
            String etag = prefs.getString("etag", ""),
                modified = prefs.getString("modified", "");
            if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
            if (!modified.isEmpty()) connection.setRequestProperty("If-Modified-Since", modified);
        }
        try {
            cancel.check();
            int status = connection.getResponseCode();
            if (status == 304 && cached.isFile()) return Collections.emptyMap();
            // The fixed raw GitHub URL is direct. Never follow a redirect to an unconfigured feed.
            if (status != 200) throw new IOException("Combined guide returned HTTP " + status);
            if (connection.getContentLengthLong() > MAX_COMPRESSED) throw new IOException(
                "Combined guide too large"
            );
            try (
                InputStream input = connection.getInputStream();
                OutputStream output = new FileOutputStream(candidate)
            ) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    cancel.check();
                    total += count;
                    if (total > MAX_COMPRESSED) throw new IOException("Combined guide too large");
                    output.write(buffer, 0, count);
                }
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("etag", Objects.toString(connection.getHeaderField("ETag"), ""));
            headers.put("modified", Objects.toString(connection.getHeaderField("Last-Modified"), ""));
            return headers;
        } finally {
            connection.disconnect();
        }
    }

    private static InputStream xml(File file) throws IOException {
        return new FilterInputStream(
            new GZIPInputStream(new BufferedInputStream(new FileInputStream(file)), 65536)
        ) {
            long bytes;

            private void count(long value) throws IOException {
                if (value > 0 && (bytes += value) > MAX_EXPANDED) throw new IOException(
                    "Expanded guide too large"
                );
            }

            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0) count(1);
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int value = in.read(buffer, offset, length);
                count(value);
                return value;
            }
        };
    }

    /** Full validation before touching the DB or replacing the previous cache. */
    static int validate(File file, ChannelDatabase.Cancel cancel) throws Exception {
        long now = System.currentTimeMillis();
        Set<String> current = new HashSet<>(),
            channels = new HashSet<>();
        int programmes = 0;
        try (InputStream input = xml(file)) {
            XmlPullParser parser = android.util.Xml.newPullParser();
            parser.setInput(input, null);
            boolean root = false;
            boolean inProgramme = false,
                inTitle = false,
                hasTitle = false;
            for (
                int event = parser.getEventType();
                event != XmlPullParser.END_DOCUMENT;
                event = parser.nextToken()
            ) {
                cancel.check();
                if (
                    event == XmlPullParser.DOCDECL ||
                    (event == XmlPullParser.ENTITY_REF &&
                        !Arrays.asList("amp", "lt", "gt", "quot", "apos").contains(parser.getName()) &&
                        !parser.getName().startsWith("#"))
                ) throw new IOException("Guide entities are not supported");
                if (
                    inTitle &&
                    (event == XmlPullParser.TEXT ||
                        event == XmlPullParser.CDSECT ||
                        event == XmlPullParser.ENTITY_REF) &&
                    parser.getText() != null &&
                    !parser.getText().trim().isEmpty()
                ) hasTitle = true;
                if (event == XmlPullParser.END_TAG) {
                    if ("title".equals(parser.getName())) inTitle = false;
                    if ("programme".equals(parser.getName())) {
                        if (!hasTitle) throw new IOException("Combined programme has no title");
                        inProgramme = false;
                    }
                }
                if (event != XmlPullParser.START_TAG) continue;
                if (!root) {
                    if (!"tv".equals(parser.getName())) throw new IOException("Invalid combined guide");
                    root = true;
                }
                if ("channel".equals(parser.getName())) {
                    String id = parser.getAttributeValue(null, "id");
                    if (
                        id == null || id.length() > 500 || !channels.add(id) || channels.size() > 100000
                    ) throw new IOException("Invalid combined channel identity");
                } else if ("programme".equals(parser.getName())) {
                    inProgramme = true;
                    hasTitle = false;
                    String id = parser.getAttributeValue(null, "channel");
                    long start = EpgImporter.time(parser.getAttributeValue(null, "start"));
                    long stop = EpgImporter.time(parser.getAttributeValue(null, "stop"));
                    if (
                        !channels.contains(id) || stop <= start || start <= 0 || ++programmes > 5_000_000
                    ) throw new IOException("Invalid combined programme");
                    if (start <= now && now < stop) current.add(id);
                } else if (inProgramme && "title".equals(parser.getName())) {
                    inTitle = true;
                }
            }
        }
        if (current.isEmpty() || programmes == 0) throw new IOException("Combined guide is empty or expired");
        return current.size();
    }

    static EpgImporter.Result install(File file, ChannelDatabase db, ChannelDatabase.Cancel cancel)
        throws Exception {
        validate(file, cancel);
        Map<String, String> wanted = db.combinedGuideKeys();
        ChannelDatabase.GuideWriter writer = db.new GuideWriter(null, true);
        try (InputStream input = xml(file)) {
            EpgImporter.Result result = EpgImporter.parse(
                input,
                new HashSet<>(wanted.values()),
                System.currentTimeMillis() - EpgImporter.BEHIND_MS,
                System.currentTimeMillis() + EpgImporter.AHEAD_MS,
                new EpgImporter.Sink() {
                    public void match(String key, String channel) throws Exception {
                        cancel.check();
                        writer.match(key, "combined:" + channel);
                    }

                    public void programme(
                        String channel,
                        long start,
                        long stop,
                        String title,
                        String description,
                        String category,
                        String flags
                    ) throws Exception {
                        cancel.check();
                        writer.programme(
                            "combined:" + channel,
                            start,
                            stop,
                            title,
                            description,
                            category,
                            flags
                        );
                    }
                },
                id -> wanted.getOrDefault(id, "")
            );
            // A valid public guide may lack this viewer's private/unsupported channels. Do not
            // erase an existing schedule merely because there are no matching identities yet.
            if (result.programmes == 0) return result;
            writer.retainMissing();
            writer.commit();
            return result;
        } finally {
            writer.finish();
        }
    }
}
