package local.tv.home;

import java.io.IOException;
import java.net.URI;

/** Upstream macro fills can contain literal spaces (notably browser User-Agent values). */
final class ProviderUrls {

    private ProviderUrls() {}

    static String playback(String raw) throws IOException {
        if (raw.length() > 16384 || raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0) {
            throw new IOException("Provider URL rejected");
        }
        try {
            URI uri = URI.create(raw.replace(" ", "%20").replace("{", "%7B").replace("}", "%7D"));
            if (
                !"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.getHost() == null ||
                uri.getUserInfo() != null
            ) {
                throw new IllegalArgumentException();
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            throw new IOException("Provider URL rejected");
        }
    }
}
