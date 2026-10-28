package local.tv.home;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Session-scoped loopback HLS transport for headers LibVLC cannot send itself. */
final class ProviderRelay implements AutoCloseable {

    private final ServerSocket server;
    private final String secret = UUID.randomUUID().toString();
    private final Map<String, String> targets = new LinkedHashMap<>();
    private final Set<String> playlists = new HashSet<>();
    private final Map<String, String> headers;
    private final String credentialAuthority;
    private final PlaylistSession playlistSession;
    private final boolean segmentContainers;
    private final boolean boundedRanges;
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    // Four, so a slow segment download never holds up the playlist refresh behind it.
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
        4,
        4,
        0,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4)
    );
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicLong received =
        new java.util.concurrent.atomic.AtomicLong();
    private final StreamRate incoming = new StreamRate();

    long incomingBytes() {
        return received.get();
    }

    void resetIncomingRate() {
        incoming.reset(System.nanoTime() / 1000000, received.get());
    }

    float incomingMbps() {
        return incoming.sample(System.nanoTime() / 1000000, received.get());
    }

    private InputStream counted(InputStream input) {
        return new FilterInputStream(input) {
            @Override
            public int read() throws IOException {
                int value = in.read();
                if (value >= 0) received.incrementAndGet();
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = in.read(buffer, offset, length);
                if (count > 0) received.addAndGet(count);
                return count;
            }
        };
    }

    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws Exception;
    }

    /** Instrumentation fixture only; never assigned by app code. */
    static ConnectionFactory testConnections;
    final String url;

    ProviderRelay(EngineSources.Stream stream) throws Exception {
        incoming.sample(System.nanoTime() / 1000000, 0);
        credentialAuthority = URI.create(stream.url).getAuthority();
        playlistSession =
            stream.playlistSession == null ? null : new PlaylistSession(stream.url, stream.playlistSession);
        segmentContainers = stream.segmentContainers || playlistSession != null;
        boundedRanges = stream.boundedRanges;
        headers = new LinkedHashMap<>();
        for (Iterator<String> keys = stream.headers.keys(); keys.hasNext(); ) {
            String key = keys.next(),
                value = stream.headers.getString(key);
            if (
                !key.matches("[A-Za-z0-9-]{1,80}") ||
                value.length() > 8192 ||
                value.contains("\r") ||
                value.contains("\n")
            ) throw new IOException("Invalid stream header");
            if (
                Arrays.asList("host", "connection", "content-length", "transfer-encoding").contains(
                    key.toLowerCase(Locale.ROOT)
                )
            ) throw new IOException("Restricted stream header");
            headers.put(key, value);
        }
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        boolean manifest =
            stream.playlistSession != null ||
            URI.create(stream.url).getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8");
        url = register(stream.url, manifest);
        Thread accept = new Thread(() -> {
            while (!closed)
                try {
                    Socket client = server.accept();
                    clients.add(client);
                    try {
                        workers.execute(() -> serve(client));
                    } catch (RejectedExecutionException e) {
                        clients.remove(client);
                        client.close();
                    }
                } catch (IOException e) {
                    if (!closed) close();
                }
        }, "provider-relay");
        accept.setDaemon(true);
        accept.start();
    }

    static boolean needed(EngineSources.Stream stream) {
        if (stream.playlistSession != null || stream.segmentContainers || stream.boundedRanges) return true;
        for (Iterator<String> keys = stream.headers.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (
                !key.equalsIgnoreCase("User-Agent") &&
                !key.equalsIgnoreCase("Referer") &&
                !key.equalsIgnoreCase("Accept")
            ) return true;
        }
        return false;
    }

    private synchronized String register(String address, boolean playlist) throws IOException {
        String safe = ProviderUrls.playback(address);
        String token = null;
        for (Map.Entry<String, String> entry : targets.entrySet())
            if (entry.getValue().equals(safe)) {
                token = entry.getKey();
                break;
            }
        if (token == null) {
            // Live playlists rotate indefinitely; evict old grants rather than grow with programme length.
            if (targets.size() >= 2048) {
                String oldest = null;
                for (String key : targets.keySet())
                    if (!playlists.contains(key)) {
                        oldest = key;
                        break;
                    }
                if (oldest == null) throw new IOException("Playlist grant limit");
                targets.remove(oldest);
            }
            token = UUID.randomUUID().toString() + (playlist ? ".m3u8" : ".bin");
            targets.put(token, safe);
        }
        if (playlist) playlists.add(token);
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + secret + "/" + token;
    }

    private static String readLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1 && ch != '\n') {
            if (line.length() >= 8192) throw new IOException("Relay line limit");
            if (ch != '\r') line.append((char) ch);
        }
        return ch == -1 && line.length() == 0 ? null : line.toString();
    }

    private void serve(Socket client) {
        HttpURLConnection upstream = null;
        // True once a response body was read to the end: the upstream connection is then left
        // for Android to reuse instead of being closed, saving a TLS handshake per segment.
        boolean reusable = false;
        // A provider server that does not answer in time gets one more try before VLC is told.
        boolean retried = false;
        try (Socket socket = client) {
            socket.setSoTimeout(10000);
            BufferedReader request = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
            );
            String first = readLine(request);
            if (
                first == null || first.length() > 2048 || !first.startsWith("GET /" + secret + "/")
            ) throw new IOException("Invalid relay request");
            String path = first.split(" ")[1],
                token = path.substring(("/" + secret + "/").length()),
                target;
            synchronized (this) {
                target = targets.get(token);
            }
            if (target == null) throw new IOException("Unknown relay grant");
            String range = null,
                line;
            int total = first.length();
            while ((line = readLine(request)) != null && !line.isEmpty()) {
                if ((total += line.length()) > 8192) throw new IOException("Relay headers limit");
                if (line.toLowerCase(Locale.ROOT).startsWith("range:")) {
                    range = line.substring(6).trim();
                    if (!range.matches("bytes=[0-9]{0,15}-[0-9]{0,15}")) throw new IOException(
                        "Invalid byte range"
                    );
                }
            }
            URI current = URI.create(target);
            if (boundedRanges) {
                serveFileRanges(socket, current, range);
                return;
            }
            boolean playlistRequest;
            synchronized (this) {
                playlistRequest = playlists.contains(token);
            }
            boolean renewed = false;
            for (int hop = 0; hop < 5; hop++) {
                if (closed) throw new IOException("Closed relay");
                URI requestUri =
                    playlistSession == null
                        ? current
                        : playlistSession.authorize(current, playlistRequest, false, this::playlistToken);
                upstream = open(requestUri);
                // A zero-to-end request asks for the whole decoded entity. Never apply that
                // range to the PNG container, whose offsets differ from the transport stream.
                boolean wholeEntity = segmentContainers && "bytes=0-".equals(range);
                if (range != null && !wholeEntity) upstream.setRequestProperty("Range", range);
                int status;
                try {
                    status = upstream.getResponseCode();
                } catch (SocketTimeoutException e) {
                    active.remove(upstream);
                    upstream.disconnect();
                    upstream = null;
                    if (retried || closed) throw e;
                    retried = true;
                    hop--;
                    continue;
                }
                if (
                    status == 403 &&
                    playlistSession == null &&
                    !segmentContainers &&
                    (range == null || range.matches("bytes=[0-9]+-")) &&
                    current.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|webm|mov|m4v|avi)$")
                ) {
                    // Discovery proved a finite media range exists. Some hosts reject the
                    // player's open-ended read; retry with validated, finite file ranges.
                    active.remove(upstream);
                    upstream.disconnect();
                    upstream = null;
                    serveFileRanges(socket, current, range);
                    return;
                }
                if (
                    status == 403 &&
                    !renewed &&
                    playlistSession != null &&
                    playlistSession.applies(current, playlistRequest)
                ) {
                    active.remove(upstream);
                    upstream.disconnect();
                    upstream = null;
                    playlistSession.authorize(current, true, true, this::playlistToken);
                    renewed = true;
                    continue;
                }
                if (status >= 300 && status < 400) {
                    String next = upstream.getHeaderField("Location");
                    if (next == null) throw new IOException("Missing stream redirect");
                    URI resolved = current.resolve(next);
                    current = URI.create(ProviderUrls.playback(resolved.toString()));
                    if (playlistSession != null) current = PlaylistSession.withoutToken(current);
                    active.remove(upstream);
                    upstream.disconnect();
                    upstream = null;
                    continue;
                }
                if (status != 200 && status != 206) throw new IOException("Upstream stream unavailable");
                OutputStream out = socket.getOutputStream();
                try (
                    PushbackInputStream in = new PushbackInputStream(counted(upstream.getInputStream()), 1024)
                ) {
                    byte[] prefix = new byte[16];
                    int n = 0,
                        sniffCount;
                    while (n < prefix.length && (sniffCount = in.read(prefix, n, prefix.length - n)) != -1)
                        n += sniffCount;
                    if (n > 0) in.unread(prefix, 0, n);
                    String signature = new String(prefix, 0, n, StandardCharsets.UTF_8);
                    boolean playlist =
                        signature.startsWith("#EXTM3U") || signature.startsWith("\uFEFF#EXTM3U");
                    boolean wrappedContainer =
                        segmentContainers && ProviderContainerSegments.matches(prefix, n);
                    if (wrappedContainer && range != null && !wholeEntity) throw new IOException(
                        "Wrapped segment ranges unsupported"
                    );
                    byte[] head = new byte[ProviderSegments.HEAD];
                    int held = 0,
                        skip = 0;
                    if (!playlist && !wrappedContainer && (range == null || wholeEntity)) {
                        while (held < head.length) {
                            int count = in.read(head, held, head.length - held);
                            if (count < 0) break;
                            held += count;
                        }
                        skip = ProviderSegments.prefix(head, held);
                    }
                    if (playlist) synchronized (this) {
                        playlists.add(token);
                    }
                    String type = playlist
                        ? "application/vnd.apple.mpegurl"
                        : wrappedContainer || skip > 0 || (held > 0 && head[0] == 0x47)
                          ? "video/mp2t"
                          : upstream.getContentType();
                    if (type == null || type.contains("\r") || type.contains("\n")) type =
                        "application/octet-stream";
                    String extra = "";
                    String contentRange = upstream.getHeaderField("Content-Range");
                    if (
                        status == 206 && contentRange != null && contentRange.matches("bytes [0-9-]+/[0-9*]+")
                    ) extra = "Content-Range: " + contentRange + "\r\n";
                    // Files retain their byte length and range support. Rewritten playlists
                    // and stripped segments have different offsets and must not inherit them.
                    if (!playlist && !wrappedContainer && skip == 0) {
                        long length = upstream.getContentLengthLong();
                        if (length >= 0) extra += "Content-Length: " + length + "\r\n";
                        if (
                            status == 206 ||
                            "bytes".equalsIgnoreCase(upstream.getHeaderField("Accept-Ranges"))
                        ) {
                            extra += "Accept-Ranges: bytes\r\n";
                        }
                    }
                    out.write(
                        (
                            "HTTP/1.1 " +
                            status +
                            " OK\r\nContent-Type: " +
                            type +
                            "\r\nCache-Control: no-store\r\nConnection: close\r\n" +
                            extra +
                            "\r\n"
                        ).getBytes(StandardCharsets.US_ASCII)
                    );
                    if (playlist) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int count;
                        while ((count = in.read(buf)) != -1) {
                            if (bytes.size() + count > 1024 * 1024) throw new IOException("Playlist limit");
                            bytes.write(buf, 0, count);
                        }
                        out.write(
                            rewrite(bytes.toString(StandardCharsets.UTF_8.name()), current).getBytes(
                                StandardCharsets.UTF_8
                            )
                        );
                    } else if (wrappedContainer) {
                        ProviderContainerSegments.write(in, out);
                    } else {
                        if (held > skip) out.write(head, skip, held - skip);
                        byte[] buf = new byte[32768];
                        int count;
                        while (!closed && (count = in.read(buf)) != -1) out.write(buf, 0, count);
                    }
                    out.flush();
                }
                reusable = !closed;
                return;
            }
            throw new IOException("Stream redirect limit");
        } catch (Exception e) {
            if (!closed) Diagnostics.recovered("Provider relay", e);
        } finally {
            clients.remove(client);
            if (upstream != null) {
                active.remove(upstream);
                if (!reusable) upstream.disconnect();
            }
        }
    }

    private void serveFileRanges(Socket socket, URI target, String range) throws Exception {
        try (
            ProviderFileRanges file = new ProviderFileRanges(
                target,
                range,
                this::open,
                (connection, complete) -> {
                    active.remove(connection);
                    if (!complete) connection.disconnect();
                },
                () -> closed
            );
            InputStream input = counted(file)
        ) {
            OutputStream output = socket.getOutputStream();
            String extra = file.ranged ? "Content-Range: " + file.contentRange() + "\r\n" : "";
            output.write(
                (
                    "HTTP/1.1 " +
                    (file.ranged ? "206 Partial Content" : "200 OK") +
                    "\r\nContent-Type: " +
                    file.contentType +
                    "\r\nContent-Length: " +
                    file.length() +
                    "\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n" +
                    extra +
                    "\r\n"
                ).getBytes(StandardCharsets.US_ASCII)
            );
            byte[] buffer = new byte[32768];
            int count;
            while (!closed && (count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
        }
    }

    private HttpURLConnection open(URI target) throws Exception {
        if (closed) throw new IOException("Closed relay");
        ProviderBroker.publicHost(target.getHost());
        HttpURLConnection connection =
            testConnections == null
                ? (HttpURLConnection) target.toURL().openConnection()
                : testConnections.open(target);
        active.add(connection);
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                boolean sensitive =
                    header.getKey().equalsIgnoreCase("Authorization") ||
                    header.getKey().equalsIgnoreCase("Cookie");
                if (
                    !sensitive || credentialAuthority.equalsIgnoreCase(target.getAuthority())
                ) connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (closed) throw new IOException("Closed relay");
            return connection;
        } catch (Exception e) {
            active.remove(connection);
            connection.disconnect();
            throw e;
        }
    }

    private org.json.JSONObject playlistToken(URI endpoint, org.json.JSONObject body) throws Exception {
        HttpURLConnection connection = open(endpoint);
        try {
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }
            if (connection.getResponseCode() != 200) throw new IOException("Playlist session unavailable");
            try (
                InputStream in = connection.getInputStream();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()
            ) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (closed) throw new IOException("Closed relay");
                    if (bytes.size() + count > 16 * 1024) throw new IOException(
                        "Playlist token response limit"
                    );
                    bytes.write(buffer, 0, count);
                }
                return new org.json.JSONObject(bytes.toString("UTF-8"));
            }
        } finally {
            active.remove(connection);
            connection.disconnect();
        }
    }

    String rewrite(String playlist, URI base) throws IOException {
        StringBuilder result = new StringBuilder();
        Pattern attributes = Pattern.compile("URI=\"([^\"]+)\"");
        boolean variant = false;
        for (String line : playlist.split("\\r?\\n")) {
            if (!line.startsWith("#") && !line.trim().isEmpty()) line = register(
                base.resolve(line.trim()).toString(),
                variant || line.contains(".m3u8")
            );
            else {
                Matcher m = attributes.matcher(line);
                StringBuffer rewritten = new StringBuffer();
                while (m.find())
                    m.appendReplacement(
                        rewritten,
                        Matcher.quoteReplacement(
                            "URI=\"" +
                                register(
                                    base.resolve(m.group(1)).toString(),
                                    line.startsWith("#EXT-X-MEDIA:") ||
                                        line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
                                        line.startsWith("#EXT-X-RENDITION-REPORT:") ||
                                        m.group(1).contains(".m3u8")
                                ) +
                                "\""
                        )
                    );
                m.appendTail(rewritten);
                line = rewritten.toString();
            }
            variant = line.startsWith("#EXT-X-STREAM-INF:");
            result.append(line).append('\n');
        }
        return result.toString();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {}
        for (HttpURLConnection c : active) c.disconnect();
        active.clear();
        for (Socket s : clients)
            try {
                s.close();
            } catch (IOException ignored) {}
        clients.clear();
        workers.shutdownNow();
        synchronized (this) {
            targets.clear();
            playlists.clear();
        }
    }
}
