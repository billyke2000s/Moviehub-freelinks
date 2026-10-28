package local.tv.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Matches channels to programme-guide channels. Free channel lists and free guides name channels
 * differently ("BBC One (1080p)" with id "BBCOne.uk@HD" against "BBC.One.HD.uk" / "BBC One HD"), so
 * both sides are reduced to a plain key ("bbcone"): lower case, without bracketed notes, quality
 * words, the country suffix and punctuation.
 */
final class EpgMatch {

    private static final Set<String> QUALITY = new HashSet<>(
        Arrays.asList("hd", "uhd", "fhd", "sd", "4k", "hq", "sdi", "1080p", "720p", "576p", "480p", "360p")
    );

    /** UKTV channels by their old names, which the "U&" names in the guides stand for. */
    private static final List<String> UKTV = Arrays.asList(
        "dave",
        "davejavu",
        "gold",
        "drama",
        "alibi",
        "eden",
        "yesterday",
        "w"
    );

    private EpgMatch() {}

    /** A radio channel called "Gold" must not claim the TV channel's plain "gold" key. */
    static String guideNameKey(String channelId, String name) {
        String plain = key(name);
        return key(channelId).contains("radio") && !plain.contains("radio") ? "" : plain;
    }

    /**
     * For a regional guide (Freeview), the national key a regional channel also answers to, or "":
     * the London feeds stand for the national channel ("bbconelondon" is "bbcone"), as online
     * streams of these channels usually carry London, and Freeview's "5 HD" is Channel 5. Radio
     * stations are left alone ("BBC Radio London" is not "BBC Radio").
     */
    static String national(String key) {
        if (key == null || key.contains("radio")) return "";
        if (key.equals("5")) return "channel5";
        // UKTV renamed its channels in 2024 ("U&Dave"); playlists still say "Dave". The guide's
        // name gives "udave", its Freeview id "uanddave".
        for (String uktv : UKTV) {
            if (key.equals("u" + uktv) || key.equals("uand" + uktv)) return uktv;
        }
        if (key.endsWith("london") && key.length() > "london".length()) {
            return key.substring(0, key.length() - "london".length());
        }
        return "";
    }

    /** The plain key for a channel name or guide id, or "" when nothing is left. */
    static String key(String value) {
        if (value == null) return "";
        String text = value.toLowerCase(Locale.ROOT);
        int at = text.indexOf('@');
        if (at >= 0) text = text.substring(0, at); // iptv-org feed suffix: "bbcone.uk@hd"
        text = text.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ");
        text = text.replaceAll("\\.[a-z]{2}$", ""); // country suffix: ".uk"
        List<String> tokens = new ArrayList<>();
        for (String token : text.split("[^a-z0-9+]+"))
            if (!token.isEmpty() && !QUALITY.contains(token)) tokens.add(token);
        // A trailing "UK" word in a name ("Gold UK", "ITV 1 UK") is a country note, not the channel.
        if (tokens.size() > 1 && tokens.get(tokens.size() - 1).equals("uk")) tokens.remove(tokens.size() - 1);
        return String.join("", tokens);
    }

    /** The country of an id like "BBCOne.uk@HD" ("uk"), or "" when it has none. */
    static String country(String id) {
        if (id == null) return "";
        String text = id.toLowerCase(Locale.ROOT);
        int at = text.indexOf('@');
        if (at >= 0) text = text.substring(0, at);
        int dot = text.lastIndexOf('.');
        return dot >= 0 && text.length() - dot == 3 ? text.substring(dot + 1) : "";
    }

    /**
     * Keys a channel can be found under in a guide for {@code guideCountry}, or none when the
     * channel's id names another country (so "Gold" in Italy does not get UK Gold's listings).
     */
    static List<String> keys(String epgId, String name, String guideCountry) {
        List<String> out = new ArrayList<>();
        String country = country(epgId);
        if (!country.isEmpty() && !sameCountry(country, guideCountry)) return out;
        for (String candidate : new String[] { key(epgId), key(name) })
            if (!candidate.isEmpty() && !out.contains(candidate)) out.add(candidate);
        return out;
    }

    private static boolean sameCountry(String a, String b) {
        String x = a.equals("gb") ? "uk" : a,
            y = b.equals("gb") ? "uk" : b;
        return x.equals(y);
    }

    /**
     * An XMLTV time ("20261018200000 +0100", or without the zone for UTC) in milliseconds, read
     * with plain arithmetic: the guide has tens of thousands of these. Returns
     * {@link Long#MIN_VALUE} for any other shape or an out-of-range field, so the caller can fall
     * back to a full date parser.
     */
    static long time(String value) {
        if (value == null) return Long.MIN_VALUE;
        String text = value.trim();
        int length = text.length();
        if (length != 14 && length != 20) return Long.MIN_VALUE;
        for (int i = 0; i < 14; i++) if (!Character.isDigit(text.charAt(i))) return Long.MIN_VALUE;
        int offsetMinutes = 0;
        if (length == 20) {
            char sign = text.charAt(15);
            if (text.charAt(14) != ' ' || (sign != '+' && sign != '-')) return Long.MIN_VALUE;
            for (int i = 16; i < 20; i++) if (!Character.isDigit(text.charAt(i))) return Long.MIN_VALUE;
            int hours = number(text, 16, 18),
                minutes = number(text, 18, 20);
            if (minutes > 59) return Long.MIN_VALUE;
            offsetMinutes = (sign == '-' ? -1 : 1) * (hours * 60 + minutes);
        }
        int year = number(text, 0, 4),
            month = number(text, 4, 6),
            day = number(text, 6, 8),
            hour = number(text, 8, 10),
            minute = number(text, 10, 12),
            second = number(text, 12, 14);
        if (
            month < 1 ||
            month > 12 ||
            day < 1 ||
            day > daysInMonth(year, month) ||
            hour > 23 ||
            minute > 59 ||
            second > 59
        ) return Long.MIN_VALUE;
        long seconds = ((epochDay(year, month, day) * 24 + hour) * 60 + minute) * 60 + second;
        return (seconds - offsetMinutes * 60L) * 1000L;
    }

    private static int number(String text, int from, int to) {
        int out = 0;
        for (int i = from; i < to; i++) out = out * 10 + (text.charAt(i) - '0');
        return out;
    }

    private static int daysInMonth(int year, int month) {
        if (month == 2) return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28;
        return month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
    }

    /** Days from 1970-01-01 to this date in the proleptic Gregorian calendar. */
    private static long epochDay(int year, int month, int day) {
        long y = month <= 2 ? year - 1 : year;
        long era = Math.floorDiv(y, 400);
        long yearOfEra = y - era * 400;
        long dayOfYear = (153L * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146097 + dayOfEra - 719468;
    }
}
