package local.tv.home;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small, cancellable HTTPS requests for native provider APIs. Never logs URLs or responses. */
final class NativeHttp implements PlutoSource.Http {

    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;
    ChannelDatabase.Cancel externalCancel;
    long deadlineNanos = Long.MAX_VALUE;

    private int remainingTimeout() throws InterruptedIOException {
        check();
        if (deadlineNanos == Long.MAX_VALUE) return 8000;
        return (int) Math.max(1, Math.min(8000, (deadlineNanos - System.nanoTime()) / 1000000));
    }

    interface ExchangeConnections {
        HttpURLConnection open(URI target) throws Exception;
    }

    /** Instrumentation fixture only; never assigned by app code. */
    static ExchangeConnections testExchangeConnections;

    void cancel() {
        cancelled = true;
        HttpURLConnection current = connection;
        if (current != null) current.disconnect();
    }

    void check() throws InterruptedIOException {
        if (externalCancel != null) try {
            externalCancel.check();
        } catch (Exception e) {
            cancel();
            throw new InterruptedIOException("Cancelled");
        }

        if (System.nanoTime() >= deadlineNanos) throw new InterruptedIOException("Provider deadline");
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException(
            "Cancelled"
        );
    }

    public String get(String url, String bearer) throws Exception {
        Map<String, String> headers = new HashMap<>();
        if (bearer != null) headers.put("Authorization", "Bearer " + bearer);
        return getHeaders(url, headers);
    }

    String getHeaders(String url, Map<String, String> headers) throws Exception {
        URI current = URI.create(Playlist.networkUrl(url, false));
        String authority = current.getAuthority();
        for (int redirects = 0; redirects < 4; redirects++) {
            check();
            HttpURLConnection c = (HttpURLConnection) current.toURL().openConnection();
            connection = c;
            c.setConnectTimeout(remainingTimeout());
            c.setReadTimeout(remainingTimeout());
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", PlutoSource.USER_AGENT);
            if (authority.equalsIgnoreCase(current.getAuthority())) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getValue().indexOf('\r') >= 0 || header.getValue().indexOf('\n') >= 0) {
                        throw new IOException("Invalid provider header");
                    }
                    c.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            try {
                check();
                int status = c.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect");
                    current = URI.create(Playlist.networkUrl(current.resolve(location).toString(), false));
                    continue;
                }
                if (status != 200) throw new IOException("Provider unavailable (" + status + ")");
                try (
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()
                ) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        check();
                        if (out.size() + count > 8 * 1024 * 1024) throw new IOException(
                            "Catalogue too large"
                        );
                        out.write(buffer, 0, count);
                    }
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            } finally {
                c.disconnect();
                connection = null;
            }
        }
        throw new IOException("Too many redirects");
    }

    org.json.JSONObject exchange(
        String url,
        String method,
        Map<String, String> headers,
        String body,
        java.util.function.Predicate<String> allowed
    ) throws Exception {
        return exchange(url, method, headers, body, allowed, false);
    }

    org.json.JSONObject exchange(
        String url,
        String method,
        Map<String, String> headers,
        String body,
        java.util.function.Predicate<String> allowed,
        boolean manualRedirect
    ) throws Exception {
        URI current = URI.create(ProviderUrls.playback(url));
        String authority = current.getAuthority();
        if (!method.equals("GET") && !method.equals("POST")) throw new IOException(
            "Provider method rejected"
        );
        if (body.length() > 64 * 1024 || headers.size() > 40) throw new IOException("Provider request limit");
        for (int redirects = 0; redirects < 5; redirects++) {
            check();
            if (!allowed.test(current.toString())) throw new IOException("Provider HTTP capability rejected");
            // Validate each redirect as well as the initial target. No localhost/LAN destinations.
            ProviderBroker.publicHost(current.getHost());
            if (
                method.equals("POST") && !authority.equalsIgnoreCase(current.getAuthority())
            ) throw new IOException("Cross-authority provider POST rejected");
            HttpURLConnection c =
                testExchangeConnections == null
                    ? (HttpURLConnection) current.toURL().openConnection()
                    : testExchangeConnections.open(current);
            connection = c;
            try {
                c.setConnectTimeout(remainingTimeout());
                c.setReadTimeout(remainingTimeout());
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod(method);
                c.setRequestProperty("Accept-Encoding", "gzip");
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if (
                        !h.getKey().matches("[A-Za-z0-9-]{1,80}") ||
                        h.getValue().length() > 8192 ||
                        h.getValue().contains("\r") ||
                        h.getValue().contains("\n")
                    ) throw new IOException("Invalid provider header");
                    if (
                        java.util.Arrays.asList(
                            "host",
                            "connection",
                            "content-length",
                            "transfer-encoding"
                        ).contains(h.getKey().toLowerCase(java.util.Locale.ROOT))
                    ) throw new IOException("Restricted header");
                    // Preserve public playback context on approved CDN redirects; never forward tokens/cookies.
                    if (
                        authority.equalsIgnoreCase(current.getAuthority()) ||
                        java.util.Arrays.asList(
                            "user-agent",
                            "accept",
                            "accept-language",
                            "origin",
                            "referer"
                        ).contains(h.getKey().toLowerCase(java.util.Locale.ROOT))
                    ) c.setRequestProperty(h.getKey(), h.getValue());
                }
                if (method.equals("POST")) {
                    c.setDoOutput(true);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int status = c.getResponseCode();
                if (status >= 300 && status < 400 && !manualRedirect) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect");
                    current = current.resolve(location);
                    if (status == 303 || ((status == 301 || status == 302) && method.equals("POST"))) {
                        method = "GET";
                        body = "";
                    }
                    continue;
                }
                org.json.JSONObject responseHeaders = new org.json.JSONObject();
                org.json.JSONArray cookies = new org.json.JSONArray();
                for (Map.Entry<String, java.util.List<String>> h : c.getHeaderFields().entrySet()) {
                    if (h.getKey() == null) continue;
                    String key = h.getKey().toLowerCase(java.util.Locale.ROOT);
                    responseHeaders.put(key, String.join(", ", h.getValue()));
                    if (key.equals("set-cookie")) for (String v : h.getValue()) cookies.put(v);
                }
                InputStream raw = status >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = "",
                    binary = "";
                if (raw != null) try (
                    InputStream in = "gzip".equalsIgnoreCase(c.getContentEncoding())
                        ? new java.util.zip.GZIPInputStream(raw)
                        : raw;
                    ByteArrayOutputStream out = new ByteArrayOutputStream()
                ) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        check();
                        // Tubi's guide (3.7 MB) and Plex's catalogue (3.1 MB) were over 3 MB.
                        if (out.size() + n > 6 * 1024 * 1024) throw new IOException(
                            "Provider response limit"
                        );
                        out.write(buf, 0, n);
                    }
                    text = out.toString(StandardCharsets.UTF_8.name());
                    if (current.getPath().endsWith(".gz")) binary =
                        java.util.Base64.getEncoder().encodeToString(out.toByteArray());
                }
                return new org.json.JSONObject()
                    .put("status", status)
                    .put("url", current.toString())
                    .put("headers", responseHeaders)
                    .put("cookies", cookies)
                    .put("text", text)
                    .put("bytes", binary);
            } finally {
                c.disconnect();
                connection = null;
            }
        }
        throw new IOException("Too many provider redirects");
    }
}
