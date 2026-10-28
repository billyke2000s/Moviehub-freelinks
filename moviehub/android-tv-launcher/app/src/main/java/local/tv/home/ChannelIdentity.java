package local.tv.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recognises the same TV channel across lists and providers ("Sky Sports F1 (720p)" from a free
 * list, "Sky Sports F1 UK" from a provider, "Sky Sports Formula 1 HD"), so the guide shows it once
 * and the other copies are backups. Plain Java, so the core checks can run it without Android.
 *
 * <p>A channel's identity is its country and a plain name key. Resolution words are dropped from
 * the key but kept as playback information ({@link #height}). Words that change what is shown are
 * kept: regions ("BBC One Scotland"), timeshifts ("+1") and UHD/4K variants, which can carry
 * different programmes, stay separate channels. Two copies whose country is known and different
 * are never merged.
 */
final class ChannelIdentity {

    /** Resolution and encoding words: dropped from the key (playback detail, not identity). */
    private static final Set<String> QUALITY = new HashSet<>(
        Arrays.asList(
            "hd",
            "fhd",
            "sd",
            "hq",
            "sdi",
            "1080p",
            "1080i",
            "720p",
            "576p",
            "576i",
            "540p",
            "480p",
            "360p",
            "25fps",
            "50fps",
            "60fps",
            "hevc",
            "h264",
            "h265",
            "backup",
            "alt"
        )
    );

    /** Words marking an ultra-HD variant; kept apart because some carry their own programmes. */
    private static final Set<String> UHD = new HashSet<>(Arrays.asList("uhd", "4k", "2160p"));

    /**
     * Country codes recognised as a trailing tag ("Sky Sports F1 UK") or a prefix ("UK: …").
     * Deliberately not every ISO code: "TV" (Tuvalu) and "FM" would misread channel names.
     */
    private static final Set<String> CODES = new HashSet<>(
        Arrays.asList(
            "uk",
            "gb",
            "us",
            "ie",
            "ca",
            "au",
            "nz",
            "de",
            "fr",
            "es",
            "it",
            "nl",
            "be",
            "pt",
            "pl",
            "at",
            "ch",
            "se",
            "no",
            "dk",
            "fi",
            "br",
            "mx",
            "ar",
            "in",
            "za",
            "ae",
            "tr",
            "gr",
            "ro",
            "hu",
            "cz",
            "sk",
            "hr",
            "rs",
            "bg"
        )
    );

    /**
     * Plain-key prefixes of UK broadcasters. A copy with no country information whose name is one
     * of these and is in the UK satellite table is taken as UK ("Sky Sports F1 (720p)" with no
     * guide id), so it can back up a UK copy. Other names are never given a country by guessing.
     */
    private static final List<String> UK_BRANDS = Arrays.asList(
        "sky",
        "bbc",
        "itv",
        "channel4",
        "channel5",
        "e4",
        "more4",
        "film4",
        "tntsports",
        "btsport",
        "premiersports",
        "s4c",
        "stv"
    );

    private static final java.util.regex.Pattern PREFIX = java.util.regex.Pattern.compile(
        "^\\s*([A-Za-z]{2,3})\\s*[:|]\\s*"
    );

    final String key, country;
    final boolean uhd;
    /** Picture height suggested by the name (2160, 1080, 720 …), or 0 when it says nothing. */
    final int height;

    private ChannelIdentity(String key, String country, boolean uhd, int height) {
        this.key = key;
        this.country = country;
        this.uhd = uhd;
        this.height = height;
    }

    /**
     * The identity of a channel from its guide id, name, group and the country its provider serves
     * ({@code sourceCountry}, "" when it serves several or is not known).
     */
    static ChannelIdentity of(String epg, String name, String group, String sourceCountry) {
        String original = name == null ? "" : name;
        String country = "";
        java.util.regex.Matcher prefix = PREFIX.matcher(original);
        if (prefix.find()) {
            String written = prefix.group(1).toLowerCase(Locale.ROOT);
            // "USA: …" is a prefix; a trailing "USA" is part of a name ("5 USA").
            String code = written.equals("usa") ? "us" : code(written);
            if (code != null) {
                country = code;
                original = original.substring(prefix.end());
            }
        }
        String text = original.toLowerCase(Locale.ROOT);
        int height = 0;
        boolean uhd = false;
        // Bracketed notes ("(720p)", "[Geo-blocked]") can carry the resolution.
        for (String token : text.split("[^a-z0-9]+")) {
            height = Math.max(height, height(token));
            if (UHD.contains(token)) uhd = true;
        }
        original = original.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ");
        List<String> tokens = new ArrayList<>();
        // Whether each token was written in capitals: "Sky Sports F1 UK" is tagged UK, while
        // "Love It" or "Stay In" end in ordinary words.
        List<Boolean> capitals = new ArrayList<>();
        // Letters of every script count, so names in other alphabets are not reduced to a prefix.
        for (String word : original.split("[^\\p{L}\\p{N}+]+")) {
            String token = word.toLowerCase(Locale.ROOT);
            if (token.isEmpty() || QUALITY.contains(token)) continue;
            if (UHD.contains(token)) continue;
            tokens.add(token);
            capitals.add(word.equals(word.toUpperCase(Locale.ROOT)));
        }
        aliases(tokens, capitals);
        // A trailing country tag ("… UK") or country name ("… Italy").
        if (tokens.size() > 1) {
            String last = tokens.get(tokens.size() - 1);
            String tagged = capitals.get(capitals.size() - 1) ? code(last) : null;
            // A country written out ("… Italy") gives the country but stays in the name: "Al-Hurra
            // Iraq" is a channel of its own, not "Al-Hurra" in Iraq.
            if (tagged == null && last.length() >= 4 && country.isEmpty()) {
                String named = GuideOrder.country("", "", last);
                if (!named.isEmpty()) country = named;
            }
            if (tagged != null) {
                if (country.isEmpty()) country = tagged;
                if (country.equals(tagged)) {
                    tokens.remove(tokens.size() - 1);
                    capitals.remove(capitals.size() - 1);
                }
            }
        }
        StringBuilder plain = new StringBuilder();
        for (String token : tokens) plain.append(token);
        String key = plain.toString();
        if (country.isEmpty()) country = normal(EpgMatch.country(epg));
        if (country.isEmpty()) country = normal(sourceCountry);
        if (country.isEmpty() && group != null && !group.isEmpty()) country = normal(
            GuideOrder.country("", "", group)
        );
        if (country.isEmpty() && ukBrand(key) && SkyNumbers.byKey(key) > 0) country = "uk";
        return new ChannelIdentity(key, country, uhd, height);
    }

    /** "formula 1" and "formula one" are written "f1" by most lists. */
    private static void aliases(List<String> tokens, List<Boolean> capitals) {
        for (int i = 0; i + 1 < tokens.size(); i++) if (
            tokens.get(i).equals("formula") &&
            (tokens.get(i + 1).equals("1") || tokens.get(i + 1).equals("one"))
        ) {
            tokens.set(i, "f1");
            tokens.remove(i + 1);
            capitals.remove(i + 1);
        }
    }

    private static boolean ukBrand(String key) {
        for (String brand : UK_BRANDS)
            if (key.startsWith(brand) && key.length() > brand.length()) return true;
        return false;
    }

    private static String code(String token) {
        if (!CODES.contains(token)) return null;
        return normal(token);
    }

    private static String normal(String country) {
        if (country == null) return "";
        String c = country.toLowerCase(Locale.ROOT);
        return c.equals("gb") ? "uk" : c;
    }

    /** The picture height a name word suggests: "1080p" 1080, "4K" 2160, "HD" 720, else 0. */
    static int height(String token) {
        switch (token) {
            case "2160p":
            case "4k":
            case "uhd":
                return 2160;
            case "1080p":
            case "1080i":
            case "fhd":
                return 1080;
            case "720p":
            case "hd":
                return 720;
            case "576p":
            case "576i":
            case "sd":
                return 576;
            case "540p":
                return 540;
            case "480p":
                return 480;
            case "360p":
                return 360;
            default:
                return 0;
        }
    }

    /**
     * The duplicate key stored with each channel: copies with the same key are one channel in the
     * guide. "" when the name has nothing usable (never merged).
     */
    String duplicateKey() {
        if (key.isEmpty()) return "";
        return country + ":" + key + (uhd ? "#uhd" : "");
    }

    static String duplicateKey(String epg, String name, String group, String sourceCountry) {
        return of(epg, name, group, sourceCountry).duplicateKey();
    }

    /** The country each source serves, when the whole source serves one ("" otherwise). */
    static String sourceCountry(String source) {
        if (source == null) return "";
        switch (source) {
            case "enginesamsung":
                return "uk"; // Samsung TV Plus (GB)
            case "enginelocalnow":
                return "us"; // Local Now (US)
            default:
                return "";
        }
    }
}
