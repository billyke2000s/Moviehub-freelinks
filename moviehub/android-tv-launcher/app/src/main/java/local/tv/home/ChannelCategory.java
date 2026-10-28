package local.tv.home;

import java.util.Locale;

/**
 * Files provider channels into the guide's existing sections ({@link SkyNumbers#SECTIONS}) instead
 * of provider-specific groups. The provider's own category label is used first, then whole words
 * in the channel name. Words are matched whole, so "TF1" is not Formula 1 and "Transport" is not
 * sport. The guide still applies its own rules on top ({@link SkyNumbers#section}): a channel's
 * name and satellite number win over this group. Plain Java for the core checks.
 */
final class ChannelCategory {

    private ChannelCategory() {}

    /** The guide section for a provider channel: "Sports", "News" … or "Entertainment". */
    static String of(String providerCategory, String name) {
        String fromLabel = label(providerCategory);
        if (fromLabel != null) return fromLabel;
        String fromName = words(name);
        return fromName != null ? fromName : "Entertainment";
    }

    /** A provider's category label ("Sports", "Local News", "Kids & Family" …), or null if unknown. */
    private static String label(String value) {
        String text = pad(value);
        if (text.trim().isEmpty()) return null;
        if (has(text, "radio")) return GuideOrder.RADIO;
        if (
            has(
                text,
                "sport",
                "sports",
                "deportes",
                "football",
                "soccer",
                "racing",
                "motorsport",
                "motorsports",
                "fight",
                "fights",
                "combat",
                "golf",
                "tennis",
                "cricket",
                "rugby"
            )
        ) return "Sports";
        if (has(text, "news", "noticias", "weather", "business", "finance", "politics")) return "News";
        if (has(text, "kids", "children", "cartoons", "cartoon", "animation", "infantil")) return "Kids";
        if (has(text, "movie", "movies", "film", "films", "cinema", "peliculas")) return "Movies";
        if (has(text, "music", "musica")) return "Music";
        if (
            has(text, "documentary", "documentaries", "history", "science", "nature", "education", "learning")
        ) return "Documentaries";
        if (has(text, "faith", "religion", "religious", "christian", "gospel")) return "Religion";
        if (has(text, "shopping", "shop")) return "Shopping";
        // Comedy, drama, reality, crime, food, lifestyle, classic TV …: general entertainment.
        return "Entertainment";
    }

    /** Words in a channel name that name its section; null when none do. */
    private static String words(String value) {
        String text = pad(value);
        if (
            has(
                text,
                "sport",
                "sports",
                "football",
                "soccer",
                "racing",
                "golf",
                "tennis",
                "cricket",
                "rugby",
                "f1",
                "nfl",
                "nba",
                "nhl",
                "mlb",
                "nascar",
                "ufc",
                "wwe",
                "espn",
                "dazn",
                "bein",
                "eurosport"
            )
        ) return "Sports";
        if (has(text, "news", "weather", "bloomberg", "cnbc")) return "News";
        if (has(text, "movie", "movies", "film", "films", "cinema")) return "Movies";
        if (has(text, "kids", "children", "cartoon", "cartoons", "cbbc", "cbeebies")) return "Kids";
        if (has(text, "music", "vevo", "mtv")) return "Music";
        if (has(text, "documentary", "documentaries", "history", "nature", "science")) return "Documentaries";
        return null;
    }

    private static String pad(String value) {
        if (value == null) return " ";
        return " " + value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
    }

    private static boolean has(String text, String... words) {
        for (String word : words) if (text.contains(" " + word + " ")) return true;
        return false;
    }
}
