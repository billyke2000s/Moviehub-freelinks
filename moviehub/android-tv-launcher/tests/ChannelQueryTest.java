package local.tv.home;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class ChannelQueryTest {

    private static int checks;

    static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        check(ChannelQuery.fts("BBC One HD").equals("bbc* one* hd*"), "Words become prefix terms");
        check(
            ChannelQuery.fts("  sky-sports\"main OR* (event)").equals("sky* sports* main* or* event*"),
            "Operators and quotes cannot reach FTS"
        );
        check(ChannelQuery.fts("Télé 5").equals("télé* 5*"), "Non-ASCII letters kept");
        check(
            ChannelQuery.fts("!!!").isEmpty() &&
                ChannelQuery.fts("").isEmpty() &&
                ChannelQuery.fts(null).isEmpty(),
            "Nothing searchable"
        );
        check(ChannelQuery.fts("a b c d e f g h i j").split(" ").length == 8, "Word count bounded");
        check(ChannelQuery.key("https://host/1").matches("[0-9a-f]{64}"), "Key is SHA-256 hex");
        check(
            ChannelQuery.key("https://host/1").equals(legacyKey("https://host/1")),
            "Key matches favourites saved by earlier versions"
        );
        check(
            ChannelQuery.xtreamUrl("https://p.example", "us er", "p/w", "42", "ts").equals(
                "https://p.example/live/us%20er/p%2Fw/42.ts"
            ),
            "Xtream TS URL"
        );
        check(
            ChannelQuery.xtreamUrl("https://p.example", "u", "p", "42", "m3u8").endsWith("/42.m3u8"),
            "Xtream HLS URL"
        );
        check(
            ChannelQuery.xtreamUrl("https://p.example", "u", "p", "42", "anything").endsWith("/42.ts"),
            "Unknown format falls back to TS"
        );
        boolean rejected = false;
        try {
            ChannelQuery.xtreamUrl("https://p.example", "u", "p", "../1", "ts");
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check(rejected, "Non-numeric stream id rejected");

        // Large playlists stream through the parser without a size or count cap.
        int total = 250000;
        int[] seen = { 0 };
        String[] last = { "" };
        Reader big = new Reader() {
            int line = 0;
            StringReader current = new StringReader("#EXTM3U\n");

            @Override
            public int read(char[] b, int off, int len) throws IOException {
                int n = current.read(b, off, len);
                while (n < 0) {
                    if (line >= total) return -1;
                    line++;
                    current = new StringReader(
                        "#EXTINF:-1 group-title=\"Group " +
                            (line % 300) +
                            "\",Channel " +
                            line +
                            "\nhttps://cdn.example/live/" +
                            line +
                            ".ts\n"
                    );
                    n = current.read(b, off, len);
                }
                return n;
            }

            @Override
            public void close() {}
        };
        int count = Playlist.parse(big, "https://cdn.example/list.m3u", false, c -> {
            seen[0]++;
            last[0] = c.name;
        });
        check(count == total && seen[0] == total, "Quarter-million channel playlist fully parsed");
        check(last[0].equals("Channel " + total), "Last channel reached");
        System.out.println(checks + " channel query and large playlist checks passed");
    }

    static String legacyKey(String url) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(
            url.getBytes(StandardCharsets.UTF_8)
        );
        StringBuilder b = new StringBuilder();
        for (byte n : d) b.append(String.format(java.util.Locale.ROOT, "%02x", n & 255));
        return b.toString();
    }
}
