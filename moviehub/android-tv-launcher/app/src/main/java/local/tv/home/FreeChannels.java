package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Free live channels for Live TV when no Xtream or M3U provider is connected: Free-TV/IPTV
 * (https://github.com/Free-TV/IPTV, free-to-air and free ad-supported channels) and the public
 * iptv-org playlists (https://github.com/iptv-org/iptv). Both list publicly available streams
 * and host no video themselves.
 */
final class FreeChannels {

    static final String ROOT = "https://iptv-org.github.io/iptv/";
    /** Preference: "auto" (on only without a provider), "off", "all", or an iptv-org country code. */
    static final String PREF = "freeChannels";

    /** Choices offered in Settings: preference value and label. */
    static final String[][] CHOICES = {
        { "auto", "All channels, only without a TV provider" },
        { "off", "Off" },
        { "uk", "United Kingdom" },
        { "ie", "Ireland" },
        { "us", "United States" },
        { "ca", "Canada" },
        { "au", "Australia" },
        { "all", "All channels, always" },
    };

    private FreeChannels() {}

    static String choice(Context context) {
        return prefs(context).getString(PREF, "auto");
    }

    static String label(String value) {
        for (String[] item : CHOICES) if (item[0].equals(value)) return item[1];
        return value.toUpperCase(Locale.ROOT);
    }

    /** A free channel list: its source id in the database, name, address and optional first group. */
    static final class Source {

        final String id, name, url, firstGroup;

        Source(String id, String name, String url, String firstGroup) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.firstGroup = firstGroup;
        }
    }

    /** Free-TV/IPTV: a curated list of free-to-air and free ad-supported channels. */
    static final String FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8";

    /**
     * The free lists to import. Free-TV comes first (its UK channels lead, so BBC One is 101) and
     * iptv-org adds the rest; a channel in both appears once and the other copy is its backup.
     * A country choice narrows iptv-org to that country; "off" imports nothing.
     */
    static List<Source> sources(Context context, boolean providerConnected) {
        List<Source> out = new ArrayList<>();
        String iptvOrg = playlist(context, providerConnected);
        if (iptvOrg.isEmpty()) return out;
        out.add(new Source("fta", "Free-TV", FREE_TV, "UK"));
        out.add(new Source("free", "iptv-org", iptvOrg, null));
        for (String[] list : EXTRA) {
            if (list.length != 3 || !list[0].matches("[a-z][a-z0-9_-]{1,31}")) continue;
            if (list[0].equals("fta") || list[0].equals("free") ||
                list[0].equals("m3u") || list[0].equals("xtream") ||
                list[0].equals("masqueradarr")) continue;
            try {
                String url = Playlist.networkUrl(list[2], false);
                out.add(new Source(list[0], list[1], url, null));
            } catch (IllegalArgumentException ignored) {
                // A malformed or insecure optional source must not block Live TV startup.
            }
        }
        return out;
    }

    /**
     * ADD NEW TV CHANNEL LISTS HERE: one M3U playlist per line, as { id, name, https URL }, for
     * example { "mylist", "My list", "https://example.org/playlist.m3u8" }. The id is stored with
     * each channel, so keep it short, lower case, unique and never change it; do not use "xtream",
     * "m3u", "fta" or "free". Lists are imported after Free-TV and iptv-org (a channel already in
     * those becomes a backup copy) whenever free channels are on. Empty means no extra lists.
     */
    static final String[][] EXTRA = {
        // { "mylist", "My list", "https://example.org/playlist.m3u8" },
        // Retained for later use; disabled while testing Masqueradarr.
        // { "loophost", "The Loop (Pluto TV)", "https://raw.githubusercontent.com/billyke2000s/Moviehub-freelinks/main/output/theloop.m3u" },
    };

    /**
     * The iptv-org playlist, or "" when free channels are off. "auto" is the full list, used only
     * while no provider is connected. A country uses its own list (iptv-org calls the United
     * Kingdom "uk", not "gb").
     */
    static String playlist(Context context, boolean providerConnected) {
        String choice = choice(context);
        if (choice.equals("off") || (choice.equals("auto") && providerConnected)) return "";
        if (choice.equals("all") || choice.equals("auto")) return ROOT + "index.m3u";
        String country = choice.toLowerCase(Locale.ROOT);
        if (country.equals("gb")) country = "uk";
        if (!country.matches("[a-z]{2}")) country = "uk";
        return ROOT + "countries/" + country + ".m3u";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }
}
