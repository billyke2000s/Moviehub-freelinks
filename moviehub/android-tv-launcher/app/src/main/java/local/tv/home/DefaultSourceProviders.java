package local.tv.home;

import java.util.*;

/**
 * Stream-source providers for films and series, tried after Torrentio (see
 * {@link SourceRepository#torrentio}). Manifest endpoints observed on 2026-10-14; runtime
 * availability is variable.
 */
final class DefaultSourceProviders {

    /**
     * ADD NEW FILM AND SERIES SOURCES HERE: one Stremio-style manifest URL per line, ending in
     * "/manifest.json", over https, for example "https://example.org/manifest.json". They are tried
     * in this order after the built-in ones below, and Troubleshooting checks each one. Empty means
     * only the built-in sources are used.
     */
    static final List<String> EXTRA = Arrays.asList(
        // Separate lines with commas, with no comma after the last one:
        // "https://example.org/manifest.json",
        // "https://example.net/manifest.json"
    );

    static List<String> backups() {
        List<String> out = new ArrayList<>(
            Arrays.asList(
                "https://mediafusion.elfhosted.com/manifest.json",
                "https://comet.elfhosted.com/manifest.json"
            )
        );
        out.addAll(EXTRA);
        return out;
    }
}
