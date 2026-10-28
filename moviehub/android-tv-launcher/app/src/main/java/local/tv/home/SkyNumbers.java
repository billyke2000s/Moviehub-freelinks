package local.tv.home;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * UK satellite-guide channel numbers (101 BBC One, 102 BBC Two, 103 ITV1 …) and guide sections
 * (Entertainment, Movies, Sports …), so Live TV numbers and sorts channels the way UK viewers
 * know. Channels are matched by their plain name key (see {@link EpgMatch}); channels the table
 * does not know are numbered after it.
 *
 * <p>Numbers follow the published UK satellite channel list as of June 2026. They are a
 * convenience for finding channels, not an official listing, and may drift as channels move.
 */
final class SkyNumbers {

    /** Guide sections, in the order the Sections panel lists them. */
    static final String[] SECTIONS = {
        "Entertainment",
        "Movies",
        "Music",
        "Sports",
        "News",
        "Documentaries",
        "Kids",
        "Religion",
        "Shopping",
        "Radio",
        "International",
    };

    private static final Map<String, Integer> NUMBERS = new HashMap<>();

    private static void add(int number, String... names) {
        for (String name : names) NUMBERS.put(EpgMatch.key(name), number);
    }

    static {
        add(101, "BBC One", "BBC One London", "BBC 1");
        add(102, "BBC Two", "BBC Two England", "BBC 2");
        add(103, "ITV1", "ITV", "ITV 1", "ITV1 London", "ITV London");
        add(104, "Channel 4");
        add(105, "Channel 5", "5");
        add(106, "Sky One");
        add(107, "Sky Witness");
        add(108, "Sky Atlantic");
        add(109, "U&alibi", "Alibi");
        add(110, "U&GOLD", "Gold");
        add(111, "U&Dave", "Dave");
        add(112, "Comedy Central");
        add(113, "Sky Comedy");
        add(114, "Sky Documentaries");
        add(115, "BBC Three");
        add(116, "BBC Four");
        add(118, "ITV2");
        add(119, "ITV3");
        add(120, "ITV4");
        add(121, "Sky Crime");
        add(122, "Sky Arts");
        add(123, "Sky History");
        add(124, "Sky Nature");
        add(125, "Discovery", "Discovery Channel");
        add(126, "MTV");
        add(128, "5STAR");
        add(129, "National Geographic", "Nat Geo");
        add(130, "Challenge");
        add(131, "ITV Quiz");
        add(132, "U&W");
        add(133, "TLC");
        add(134, "S4C");
        add(135, "E4");
        add(136, "More4");
        add(137, "4seven");
        add(138, "E4 Extra");
        add(139, "Crime+Investigation");
        add(140, "Quest");
        add(141, "5USA");
        add(142, "Really");
        add(143, "U&Drama", "Drama");
        add(144, "Food Network");
        add(146, "True Crime");
        add(147, "True Crime Xtra");
        add(148, "Legend");
        add(149, "Quest Red");
        add(150, "5ACTION");
        add(153, "5SELECT");
        add(155, "U&Yesterday", "Yesterday");
        add(156, "Blaze");
        add(157, "Great! TV");
        add(159, "DMAX");
        add(165, "Nat Geo Wild");
        add(166, "U&Eden", "Eden");
        add(169, "BBC Alba");
        add(170, "Together TV");
        add(171, "That's TV");
        add(173, "PBS America");
        add(176, "Court TV");
        add(180, "BBC Scotland");
        add(182, "Rewind TV");
        add(203, "ITV1+1", "ITV+1");
        add(204, "Channel 4+1");
        add(205, "Channel 5+1", "5+1");
        add(218, "ITV2+1");
        add(219, "ITV3+1");
        add(220, "ITV4+1");
        add(235, "E4+1");
        add(236, "More4+1");
        add(312, "Movies 24");
        add(313, "Film4");
        add(314, "Film4+1");
        add(316, "Legend Xtra");
        add(318, "Great! Action");
        add(320, "Great! Mystery");
        add(322, "Great! Romance");
        add(324, "Talking Pictures TV", "Talking Pictures");
        add(354, "Clubland TV");
        add(355, "NOW 70s");
        add(356, "NOW 80s");
        add(357, "NOW 90s and 00s", "NOW 90s");
        add(358, "NOW Rock");
        add(401, "Sky Sports Main Event");
        add(402, "Sky Sports Premier League");
        add(403, "Sky Sports Football");
        add(405, "Sky Sports Cricket");
        add(406, "Sky Sports Golf");
        add(407, "Sky Sports F1");
        add(408, "Sky Sports Tennis");
        add(409, "Sky Sports News");
        add(410, "TNT Sports 1");
        add(411, "TNT Sports 2");
        add(412, "Sky Sports Action");
        add(413, "TNT Sports 3");
        add(414, "TNT Sports 4");
        add(415, "Sky Sports Racing");
        add(416, "Sky Sports Mix");
        add(418, "MUTV");
        add(419, "Premier Sports 1");
        add(420, "Premier Sports 2");
        add(423, "LFCTV");
        add(424, "Racing TV");
        add(501, "Sky News");
        add(502, "Bloomberg", "Bloomberg TV");
        add(503, "BBC News");
        add(504, "BBC Parliament");
        add(505, "CNBC");
        add(506, "CNN", "CNN International");
        add(507, "NHK World", "NHK World Japan");
        add(508, "Euronews");
        add(510, "France 24", "France 24 English");
        add(511, "Al Jazeera English", "Al Jazeera");
        add(512, "GB News");
        add(513, "TRT World");
        add(516, "Arise News");
        add(580, "GOD Channel");
        add(581, "Revelation TV");
        add(582, "TBN UK");
        add(583, "Daystar");
        add(586, "EWTN");
        add(601, "Cartoon Network");
        add(603, "Boomerang");
        add(604, "Nickelodeon");
        add(605, "Nicktoons");
        add(606, "Nick Jr", "Nick Jr.");
        add(607, "CBBC");
        add(608, "CBeebies");
        add(609, "Sky Kids");
        add(610, "Cartoonito");
        add(612, "Nick Jr Too");
        add(613, "Disney Junior", "Disney Jr");
        add(623, "BabyTV", "Baby TV");
        add(660, "QVC");
        add(662, "TJC");
        add(663, "QVC Beauty");
        add(664, "QVC Style");
        add(665, "Gemporia");
        add(668, "Ideal World");
        add(674, "Jewellery Maker");
    }

    private SkyNumbers() {}

    /** The satellite number for a plain key ("skysportsf1"), or 0. */
    static int byKey(String key) {
        Integer found = NUMBERS.get(key);
        return found == null ? 0 : found;
    }

    /**
     * The satellite number for a channel, or 0. Channels from other countries ({@code home} false)
     * only get a number when it is a news channel the UK guide carries (CNN, France 24 …), so
     * another country's "Gold" or "Drama" keeps its own place. Provider prefixes such as "UK: "
     * or "UK | " are ignored.
     */
    static int number(String epgId, String name, boolean home) {
        String plain = name == null ? "" : name.replaceFirst("(?i)^\\s*(uk|gb)\\s*[:|\\-]\\s*", "");
        Integer found = NUMBERS.get(EpgMatch.key(plain));
        if (found == null && epgId != null && !epgId.isEmpty()) found = NUMBERS.get(EpgMatch.key(epgId));
        if (found == null) {
            // Provider spellings: "Sky Sports F1 UK", "Sky Sports Formula 1". A UHD variant or
            // another country's feed does not take the UK channel's number.
            ChannelIdentity identity = ChannelIdentity.of("", plain, "", "");
            if (!identity.uhd && (identity.country.isEmpty() || identity.country.equals("uk"))) found =
                NUMBERS.get(identity.key);
        }
        if (found == null) return 0;
        return home || (found >= 500 && found < 580) ? found : 0;
    }

    /**
     * The guide section for a channel: from words in its name, then its satellite number's range,
     * then words in its group, else Entertainment (as the satellite guide files general channels).
     */
    static String section(int number, String name, String group) {
        String fromName = keywords(name);
        if (fromName != null) return fromName;
        if (number >= 101 && number < 1000) {
            if (number < 300) return "Entertainment";
            if (number < 350) return "Movies";
            if (number < 400) return "Music";
            if (number < 500) return "Sports";
            if (number < 580) return "News";
            if (number < 600) return "Religion";
            if (number < 660) return "Kids";
            if (number < 700) return "Shopping";
        }
        String fromGroup = keywords(group);
        return fromGroup != null ? fromGroup : "Entertainment";
    }

    private static String keywords(String value) {
        if (value == null || value.isEmpty()) return null;
        String text = " " + value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        if (
            has(text, "sport", "sports", "football", "racing", "golf", "cricket", "tennis", "f1")
        ) return "Sports";
        if (has(text, "news", "parliament", "weather", "business")) return "News";
        if (has(text, "movie", "movies", "film", "films", "cinema")) return "Movies";
        if (
            has(
                text,
                "kids",
                "children",
                "cartoon",
                "cartoons",
                "animation",
                "cbbc",
                "cbeebies",
                "junior",
                "nick"
            )
        ) return "Kids";
        if (has(text, "music")) return "Music";
        if (
            has(
                text,
                "documentary",
                "documentaries",
                "history",
                "science",
                "nature",
                "discovery",
                "education",
                "geographic"
            )
        ) return "Documentaries";
        if (
            has(
                text,
                "religious",
                "religion",
                "faith",
                "church",
                "gospel",
                "christian",
                "catholic",
                "islamic"
            )
        ) return "Religion";
        if (has(text, "shop", "shopping")) return "Shopping";
        return null;
    }

    /** Whole-word match in a space-padded, lower-case text. */
    private static boolean has(String text, String... words) {
        for (String word : words) if (text.contains(" " + word + " ")) return true;
        return false;
    }
}
