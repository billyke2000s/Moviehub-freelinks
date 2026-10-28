package local.tv.home;

import java.util.*;

/** Pure helpers for channel search and identity, kept free of Android APIs so they can be tested on the JVM. */
final class ChannelQuery {

    private ChannelQuery() {}

    /**
     * Builds an FTS4 MATCH expression from user text. Only letter and digit runs are kept, so the
     * expression can never contain FTS operators or quotes. Each word is a prefix match and all words
     * must match. Returns an empty string when there is nothing to search for.
     */
    static String fts(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        StringBuilder word = new StringBuilder();
        int words = 0;
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i <= lower.length(); ) {
            int cp = i < lower.length() ? lower.codePointAt(i) : -1;
            if (cp >= 0 && Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp);
                i += Character.charCount(cp);
                continue;
            }
            if (word.length() > 0 && words < 8) {
                if (out.length() > 0) out.append(' ');
                out.append(word).append('*');
                words++;
            }
            word.setLength(0);
            i += cp < 0 ? 1 : Character.charCount(cp);
        }
        return out.toString();
    }

    /** Stable channel identity. Matches the key used for favourites before the database existed. */
    static String key(String url) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(
                url.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            StringBuilder b = new StringBuilder(64);
            for (byte n : d)
                b.append(Character.forDigit((n >> 4) & 15, 16)).append(Character.forDigit(n & 15, 16));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds an Xtream live stream URL. Credentials are path-encoded; the stream id must be numeric. */
    static String xtreamUrl(String server, String username, String password, String streamId, String format) {
        if (streamId == null || !streamId.matches("[0-9]+")) throw new IllegalArgumentException(
            "Invalid stream id"
        );
        String extension = "m3u8".equals(format) ? "m3u8" : "ts";
        return (
            server +
            "/live/" +
            Network.path(username) +
            "/" +
            Network.path(password) +
            "/" +
            streamId +
            "." +
            extension
        );
    }
}
