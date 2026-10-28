package local.tv.home;

import java.io.*;
import java.net.*;
import javax.net.ssl.SSLException;

/** Safe diagnostics survive local and sanitized Binder exceptions without copying sensitive messages. */
public final class ProviderFailureTest {

    private static int checks;
    private static final String PRIVATE = "https://provider.example/path?token=secret";

    private static void check(String expected, Throwable error) {
        String actual = ProviderFailure.describe(error);
        if (!expected.equals(actual)) throw new AssertionError(actual);
        if (actual.contains("secret") || actual.contains("provider.example")) throw new AssertionError(
            "Sensitive diagnostic"
        );
        checks++;
    }

    public static void main(String[] args) {
        check("DNS lookup failed", new UnknownHostException(PRIVATE));
        check("Connection timed out", new SocketTimeoutException(PRIVATE));
        check("TLS connection failed", new SSLException(PRIVATE));
        check("Connection failed", new ConnectException(PRIVATE));
        check("DNS lookup failed", new IOException(PRIVATE, new UnknownHostException(PRIVATE)));
        check(
            "Provider processing timed out",
            new IllegalStateException("Provider operation failed / IOException at X / Deadline at Y")
        );
        check("Provider ran out of memory", new OutOfMemoryError(PRIVATE));
        check("Android denied provider access", new SecurityException(PRIVATE));
        check("Provider refresh timed out", new IOException("Provider operation timeout"));
        check("Provider process did not start", new IOException("Provider startup failed"));
        check("Provider process unavailable", new IOException("Provider service unavailable"));
        check("Provider response exceeded size limit", new IOException("Provider aggregate limit"));
        check("Provider returned HTTP 403", ProviderFailure.http(403, new IOException(PRIVATE)));
        check("Provider error (IOException)", ProviderFailure.http(200, new IOException(PRIVATE)));
        check("Provider error (IOException)", new IOException(PRIVATE));
        check("Connection failed", ProviderFailure.transport("Connection failed", new IOException(PRIVATE)));
        check("Provider error (IOException)", ProviderFailure.transport(PRIVATE, new IOException(PRIVATE)));
        System.out.println(checks + " provider failure checks passed");
    }
}
