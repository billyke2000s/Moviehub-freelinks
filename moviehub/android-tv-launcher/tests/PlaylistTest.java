package local.tv.home;

import java.util.*;

public final class PlaylistTest {

    private static int checks;

    static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    static void rejected(String url) {
        boolean failed = false;
        try {
            Playlist.networkUrl(url, false);
        } catch (IllegalArgumentException e) {
            failed = true;
        }
        check(failed, "Reject " + url);
    }

    public static void main(String[] args) {
        String text =
            "\uFEFF#EXTM3U\r\n#EXTINF:-1 tvg-id=\"abc\" group-title=\"Sports, UK\" tvg-logo=\"https://cdn.example/logo.png\",Live Sport\r\n../live/1.m3u8\r\n#EXTINF:-1,News\nhttps://news.example/live.m3u8\n# comment\n#EXTINF:-1,Unsafe\njavascript:alert(1)\n";
        List<Playlist.Channel> channels = Playlist.parse(
            text,
            "https://provider.example/playlists/list.m3u",
            false
        );
        check(channels.size() == 2, "Accepted channels only");
        check(channels.get(0).name.equals("Live Sport"), "Title after comma outside quotes");
        check(channels.get(0).group.equals("Sports, UK"), "Group comma preserved");
        check(channels.get(0).url.equals("https://provider.example/live/1.m3u8"), "Relative resolution");
        check(channels.get(0).epgId.equals("abc"), "EPG identity");
        check(channels.get(1).group.isEmpty(), "No metadata leakage to next channel");
        for (String url : new String[] {
            "http://host/live",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://user:pass@host/live",
            "https:///live",
            "content://host/live",
        })
            rejected(url);
        check(
            Playlist.networkUrl("http://host/live", true).equals("http://host/live"),
            "Explicit HTTP opt-in"
        );
        check(
            Playlist.parse("#EXTINF:-1,One\n#EXTGRP:Sports\nhttps://host/1\n", "https://host/list", false)
                .get(0)
                .group.equals("Sports"),
            "EXTGRP"
        );
        check(
            Playlist.parse("https://host/1\nhttps://host/2\n", "https://host/list", false)
                .get(1)
                .name.equals("Channel 2"),
            "Unnamed channels"
        );
        check(Network.q("a b&c").equals("a+b%26c"), "Query encoding");
        check(Network.path("a b/c").equals("a%20b%2Fc"), "Path encoding");
        System.out.println(checks + " playlist and URL checks passed");
    }
}
