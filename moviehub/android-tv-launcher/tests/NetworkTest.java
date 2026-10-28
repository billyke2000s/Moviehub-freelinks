package local.tv.home;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class NetworkTest {

    static int checks;

    static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }

    interface Request {
        void run() throws Exception;
    }

    static void rejected(Request request, String name) throws Exception {
        boolean failed = false;
        try {
            request.run();
        } catch (Exception e) {
            failed = true;
        }
        check(failed, name);
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/ok", exchange -> {
            byte[] b = "playlist".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.createContext("/auth", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] b = (header == null ? "absent" : "present").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[65536];
            java.util.Arrays.fill(chunk, (byte) 'x');
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 320; i++) out.write(chunk);
            }
            exchange.close();
        });
        server.start();
        try {
            check(Network.text(root + "/ok", null, true).equals("playlist"), "Successful body");
            check(Network.text(root + "/redirect", null, true).equals("playlist"), "Relative redirect");
            check(Network.text(root + "/auth", "fake-key", true).equals("absent"), "No bearer over HTTP");
            rejected(() -> Network.bytes(root + "/ok", null, 3, true), "Response size bound");
            rejected(() -> Network.text(root + "/loop", null, true), "Redirect loop bound");
            rejected(() -> Network.text(root + "/error", null, true), "HTTP error");
            rejected(() -> Network.text(root + "/ok", null, false), "HTTP requires opt-in");
            long read = 0;
            try (java.io.InputStream in = Network.open(root + "/large", null, Network.CATALOGUE_MAX, true)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) read += n;
            }
            check(read == 320L * 65536, "Streams responses larger than the old 8 MiB limit");
            rejected(() -> {
                try (java.io.InputStream in = Network.open(root + "/large", null, 1024 * 1024, true)) {
                    byte[] b = new byte[8192];
                    while (in.read(b) != -1) {}
                }
            }, "Streamed response bound");
            System.out.println(checks + " network checks passed");
        } finally {
            server.stop(0);
        }
    }
}
