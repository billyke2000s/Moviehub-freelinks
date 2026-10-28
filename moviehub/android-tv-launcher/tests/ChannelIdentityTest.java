package local.tv.home;

/** Cross-provider channel matching (duplicate keys) and provider categories. */
public class ChannelIdentityTest {

    private static int checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    static String key(String epg, String name, String group, String source) {
        return ChannelDatabase_duplicateKey(epg, name, group, source);
    }

    /** Same rule as ChannelDatabase.duplicateKey, which needs Android to compile. */
    static String ChannelDatabase_duplicateKey(String epg, String name, String group, String source) {
        return ChannelIdentity.duplicateKey(epg, name, group, ChannelIdentity.sourceCountry(source));
    }

    static void same(String what, String[] a, String[] b) {
        String x = key(a[0], a[1], a[2], a[3]),
            y = key(b[0], b[1], b[2], b[3]);
        check(!x.isEmpty() && x.equals(y), what + ": " + x + " vs " + y);
    }

    static void different(String what, String[] a, String[] b) {
        String x = key(a[0], a[1], a[2], a[3]),
            y = key(b[0], b[1], b[2], b[3]);
        check(!x.equals(y), what + ": both " + x);
    }

    static String[] ch(String epg, String name, String group, String source) {
        return new String[] { epg, name, group, source };
    }

    public static void main(String[] args) {
        // The reported case: iptv-org's copy and a provider's copy of Sky Sports F1.
        String[] iptvOrg = ch("SkySportsF1.uk", "Sky Sports F1 (720p)", "Sports", "free");
        same("provider UK tag", iptvOrg, ch("enginedlhd:123", "Sky Sports F1 UK", "Sports", "enginedlhd"));
        same("HD suffix", iptvOrg, ch("", "Sky Sports F1 HD", "", "m3u"));
        same("no guide id, UK brand", iptvOrg, ch("", "Sky Sports F1", "Sports", "enginedlhd"));
        same("Formula 1 spelled out", iptvOrg, ch("", "Sky Sports Formula 1", "", "m3u"));
        same("Formula One", iptvOrg, ch("", "Sky Sports Formula One HD", "", "m3u"));
        same("country prefix", iptvOrg, ch("", "UK: Sky Sports F1 FHD", "UK | Sports", "xtream"));
        same(
            "Free-TV country group",
            ch("", "BBC One", "UK", "fta"),
            ch("BBCOne.uk@HD", "BBC One (1080p)", "", "free")
        );
        same(
            "gb and uk are the same country",
            ch("pluto:abc.gb", "Pluto Movies", "", "x"),
            ch("x.uk", "Pluto Movies", "", "y")
        );
        // Things that look alike but are not the same channel.
        different("other country", iptvOrg, ch("", "Sky Sports F1 DE", "", "enginedlhd"));
        different("other country by name", iptvOrg, ch("", "Sky Sports F1 Italy", "", "enginedlhd"));
        different("+1 timeshift", ch("", "Channel 4", "UK", "fta"), ch("", "Channel 4 +1", "UK", "fta"));
        different("region", ch("", "BBC One", "UK", "fta"), ch("", "BBC One Scotland", "UK", "fta"));
        different("UHD variant", iptvOrg, ch("", "Sky Sports F1 UHD", "", "m3u"));
        different("similar network", ch("", "Sky Sports News", "UK", "fta"), ch("", "Sky News", "UK", "fta"));
        different("Italian Sky Sport", iptvOrg, ch("SkySportF1.it", "Sky Sport F1", "", "free"));
        different(
            "unknown country never guessed",
            ch("", "Gold", "", "enginexumo"),
            ch("Gold.uk", "Gold", "", "free")
        );
        // Ordinary words at the end of a name are not country tags.
        check(ChannelIdentity.of("", "Love It", "", "").country.isEmpty(), "Love It is not Italy");
        check(ChannelIdentity.of("", "5 USA", "", "").key.equals("5usa"), "5 USA keeps its name");
        // Resolution is kept as playback information.
        check(ChannelIdentity.of("", "Sky Sports F1 (720p)", "", "").height == 720, "720p height");
        check(ChannelIdentity.of("", "BBC One FHD", "", "").height == 1080, "FHD height");
        check(ChannelIdentity.of("", "Sky Sports F1 4K", "", "").uhd, "4K marked");
        check(key("", "(HD)", "", "").isEmpty(), "nothing usable is never merged");

        // Provider spellings keep the UK satellite number; other feeds do not take it.
        check(SkyNumbers.number("enginedlhd:60", "Sky Sports F1 UK", true) == 407, "F1 UK numbered");
        check(SkyNumbers.number("", "Sky Sports Formula 1", true) == 407, "Formula 1 numbered");
        check(SkyNumbers.number("", "Sky Sports F1 DE", true) == 0, "German feed not numbered");

        // Provider categories go into the guide's existing sections.
        check(ChannelCategory.of("Sports", "Fight Network").equals("Sports"), "provider label");
        check(ChannelCategory.of("Local News", "ABC7").equals("News"), "local news");
        check(ChannelCategory.of("Kids & Family", "Toon Goggles").equals("Kids"), "kids label");
        check(ChannelCategory.of("True Crime", "Court TV").equals("Entertainment"), "crime is entertainment");
        check(ChannelCategory.of("", "Sky Sports F1 (720p)").equals("Sports"), "name words");
        check(ChannelCategory.of("", "NFL Channel").equals("Sports"), "league names");
        check(ChannelCategory.of("", "TF1").equals("Entertainment"), "TF1 is not Formula 1");
        check(ChannelCategory.of("", "Transport Today").equals("Entertainment"), "transport is not sport");
        check(ChannelCategory.of("", "Modern Family").equals("Entertainment"), "family is not kids");
        check(ChannelCategory.of("", "Bloomberg TV").equals("News"), "business news");
        check(ChannelCategory.of("Radio", "Capital").equals(GuideOrder.RADIO), "radio label");
        System.out.println(checks + " channel matching and category checks passed");
    }
}
