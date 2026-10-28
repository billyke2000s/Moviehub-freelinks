package local.tv.home;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

public final class ProviderFileRangesTest {

    private static int checks;

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        byte[] data = new byte[3500];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 17);
        List<String> requests = new ArrayList<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/redirect")) {
                    exchange.getResponseHeaders().set("Location", "https://other.example/file");
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                if (path.equals("/downgrade")) {
                    exchange.getResponseHeaders().set("Location", "http://other.example/file");
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                String header = exchange.getRequestHeaders().getFirst("Range");
                Matcher match = Pattern.compile("bytes=([0-9]+)-([0-9]+)").matcher(
                    header == null ? "" : header
                );
                if (!match.matches()) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                synchronized (requests) {
                    requests.add(header);
                }
                int first = Integer.parseInt(match.group(1)),
                    last = Math.min(data.length - 1, Integer.parseInt(match.group(2)));
                if (first > last) {
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
                if (Long.parseLong(match.group(2)) - first + 1 > 4 * 1024 * 1024) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                int size = data.length + (path.equals("/changed") && first > 0 ? 1 : 0);
                String range = path.equals("/bad")
                    ? "bytes invalid"
                    : "bytes " + first + "-" + last + "/" + size;
                exchange.getResponseHeaders().set("Content-Range", range);
                exchange
                    .getResponseHeaders()
                    .set("ETag", path.equals("/etag") && first > 0 ? "changed" : "fixture");
                exchange.getResponseHeaders().set("Content-Type", "video/x-matroska");
                exchange.sendResponseHeaders(206, last - first + 1);
                int count = last - first + 1;
                if (path.equals("/retry") && interrupted.compareAndSet(false, true)) count /= 2;
                exchange.getResponseBody().write(data, first, count);
            } catch (IOException ignored) {
                // The deliberate early-EOF fixture closes a response before its advertised end.
            } finally {
                exchange.close();
            }
        });
        server.start();
        AtomicInteger released = new AtomicInteger();
        List<String> hosts = new ArrayList<>();
        ProviderFileRanges.Open open = target -> {
            hosts.add(target.getHost());
            HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getAddress().getPort() + target.getPath()
            ).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            return connection;
        };
        ProviderFileRanges.Release release = (connection, complete) -> {
            released.incrementAndGet();
            connection.disconnect();
        };
        try {
            try (
                ProviderFileRanges file = new ProviderFileRanges(
                    URI.create("https://public.example/file"),
                    null,
                    open,
                    release,
                    () -> false,
                    1024
                )
            ) {
                check(!file.ranged && file.length() == 3500, "Full-file length remains available");
                check(file.contentType.equals("video/x-matroska"), "MIME preserved");
                check(
                    Arrays.equals(data, file.readAllBytes()),
                    "Consecutive finite requests produce the complete file"
                );
            }
            check(requests.size() == 4, "File is fetched in bounded parts");
            try (
                ProviderFileRanges file = new ProviderFileRanges(
                    URI.create("https://public.example/file"),
                    null,
                    open,
                    release,
                    () -> false
                )
            ) {
                check(
                    Arrays.equals(data, file.readAllBytes()),
                    "Default parts stay within the observed host's 4 MiB limit"
                );
            }
            try (
                ProviderFileRanges file = new ProviderFileRanges(
                    URI.create("https://public.example/file"),
                    "bytes=1800-",
                    open,
                    release,
                    () -> false,
                    1024
                )
            ) {
                check(file.ranged && file.length() == 1700, "Open-ended seek retains remaining length");
                check(
                    file.contentRange().equals("bytes 1800-3499/3500"),
                    "Client range covers the requested remainder"
                );
                check(
                    Arrays.equals(Arrays.copyOfRange(data, 1800, 3500), file.readAllBytes()),
                    "Midpoint seeking returns exact bytes"
                );
            }
            try (
                ProviderFileRanges file = new ProviderFileRanges(
                    URI.create("https://public.example/file"),
                    "bytes=10-59",
                    open,
                    release,
                    () -> false,
                    1024
                )
            ) {
                check(
                    file.length() == 50 &&
                        Arrays.equals(Arrays.copyOfRange(data, 10, 60), file.readAllBytes()),
                    "Finite client range preserved"
                );
            }
            try (
                ProviderFileRanges file = new ProviderFileRanges(
                    URI.create("https://public.example/file"),
                    "bytes=-7",
                    open,
                    release,
                    () -> false,
                    1024
                )
            ) {
                check(
                    file.contentRange().equals("bytes 3493-3499/3500"),
                    "Suffix offset comes from actual entity size"
                );
                check(
                    Arrays.equals(Arrays.copyOfRange(data, 3493, 3500), file.readAllBytes()),
                    "Suffix data exact"
                );
            }
            try (
                ProviderFileRanges file = new ProviderFileRanges(
                    URI.create("https://public.example/retry"),
                    null,
                    open,
                    release,
                    () -> false,
                    1024
                )
            ) {
                check(
                    Arrays.equals(data, file.readAllBytes()),
                    "An interrupted part resumes at the next unread byte without duplication"
                );
            }
            try (
                ProviderFileRanges file = new ProviderFileRanges(
                    URI.create("https://public.example/redirect"),
                    null,
                    open,
                    release,
                    () -> false,
                    1024
                )
            ) {
                check(
                    Arrays.equals(data, file.readAllBytes()) && hosts.contains("other.example"),
                    "Redirects use the guarded opener too"
                );
            }
            for (String path : new String[] { "/bad", "/changed", "/etag", "/downgrade" }) {
                boolean rejected = false;
                try (
                    ProviderFileRanges file = new ProviderFileRanges(
                        URI.create("https://public.example" + path),
                        null,
                        open,
                        release,
                        () -> false,
                        1024
                    )
                ) {
                    file.readAllBytes();
                } catch (IOException expected) {
                    rejected = true;
                }
                check(rejected, "Reject invalid metadata, changed entity size or HTTPS downgrade: " + path);
            }
            AtomicBoolean cancelled = new AtomicBoolean();
            try (
                ProviderFileRanges file = new ProviderFileRanges(
                    URI.create("https://public.example/file"),
                    null,
                    open,
                    release,
                    cancelled::get,
                    1024
                )
            ) {
                check(file.read() == (data[0] & 255), "Single-byte stream reads work");
                cancelled.set(true);
                boolean rejected = false;
                try {
                    file.read();
                } catch (IOException expected) {
                    rejected = true;
                }
                check(rejected, "Cancelled playback stops reading");
            }
            check(released.get() > 10, "Every completed or abandoned connection is released");
            System.out.println(checks + " finite-range playback checks passed");
        } finally {
            server.stop(0);
        }
    }
}
