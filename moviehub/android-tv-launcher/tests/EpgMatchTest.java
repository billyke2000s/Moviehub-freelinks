package local.tv.home;

public class EpgMatchTest {

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check(EpgMatch.key("BBC One (1080p)").equals("bbcone"), "name with quality");
        check(EpgMatch.key("BBCOne.uk@HD").equals("bbcone"), "iptv-org id");
        check(EpgMatch.key("BBC.One.HD.uk").equals("bbcone"), "guide id");
        check(EpgMatch.key("BBC One HD").equals("bbcone"), "guide display name");
        check(EpgMatch.key("5 USA").equals("5usa"), "numbers kept");
        check(EpgMatch.key("ITV1 London").equals("itv1london"), "region kept");
        check(EpgMatch.country("Gold.it@SD").equals("it"), "country from id");
        check(EpgMatch.keys("Gold.it", "Gold", "uk").isEmpty(), "other countries are not matched");
        check(EpgMatch.keys("Gold.uk", "Gold (720p)", "uk").contains("gold"), "same country matched");
        check(EpgMatch.keys("", "Dave", "uk").contains("dave"), "no id: matched by name");
        // XMLTV times match the full date parser the importer used before.
        check(sameTime("20261018200000 +0100"), "time with a zone");
        check(sameTime("20261018200000"), "time without a zone is UTC");
        check(sameTime("20260329005959 -0530"), "negative zone");
        check(sameTime("20240229235959 +0000"), "leap day");
        check(sameTime(" 19991231230000 +1400 "), "spaces trimmed");
        check(EpgMatch.time("20261018200000+0100") == Long.MIN_VALUE, "other shapes fall back");
        check(EpgMatch.time("20260230120000 +0000") == Long.MIN_VALUE, "impossible date falls back");
        java.util.Random random = new java.util.Random(7);
        boolean all = true;
        for (int i = 0; i < 2000; i++) {
            long when = 946684800000L + (long) (random.nextDouble() * 2_000_000_000_000L);
            int zone = (random.nextInt(29) - 14) * 60 + (random.nextBoolean() ? 30 : 0);
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat(
                "yyyyMMddHHmmss",
                java.util.Locale.ROOT
            );
            out.setTimeZone(new java.util.SimpleTimeZone(zone * 60000, "x"));
            String sign = zone < 0 ? "-" : "+";
            int abs = Math.abs(zone);
            String text =
                out.format(new java.util.Date(when)) +
                " " +
                sign +
                String.format(java.util.Locale.ROOT, "%02d%02d", abs / 60, abs % 60);
            all &= sameTime(text);
        }
        check(all, "2000 random times");
        // Freeview's regional London feeds stand for the national channels.
        check(EpgMatch.national(EpgMatch.key("BBC One London HD")).equals("bbcone"), "BBC One London");
        check(EpgMatch.national(EpgMatch.key("ITV1 London HD")).equals("itv1"), "ITV1 London");
        check(EpgMatch.national(EpgMatch.key("Channel 4 London HD")).equals("channel4"), "Channel 4 London");
        check(EpgMatch.national(EpgMatch.key("5 HD")).equals("channel5"), "5 HD is Channel 5");
        check(EpgMatch.national(EpgMatch.key("BBC Radio London")).isEmpty(), "radio left alone");
        check(EpgMatch.national(EpgMatch.key("BBC One East HD")).isEmpty(), "other regions stay regional");
        check(EpgMatch.national(EpgMatch.key("London")).isEmpty(), "London alone is not a channel");
        check(
            EpgMatch.key("BBC One Ⓖ").equals(EpgMatch.national(EpgMatch.key("BBC One London HD"))),
            "playlist name meets alias"
        );
        // UKTV's "U&" names answer to the old names playlists use.
        check(EpgMatch.national(EpgMatch.key("U&Dave HD")).equals("dave"), "U&Dave is Dave");
        check(EpgMatch.national(EpgMatch.key("U&Gold")).equals("gold"), "U&Gold is Gold");
        check(EpgMatch.national(EpgMatch.key("UAndDrama.uk")).equals("drama"), "Freeview id UAndDrama");
        check(EpgMatch.national(EpgMatch.key("U&W")).equals("w"), "U&W is W");
        check(EpgMatch.key("Gold UK").equals("gold"), "a trailing UK word is dropped");
        check(EpgMatch.key("ITV 1 UK").equals("itv1"), "ITV 1 UK is ITV1");
        check(EpgMatch.key("UK").equals("uk"), "UK alone stays");
        check(EpgMatch.national(EpgMatch.key("Universal")).isEmpty(), "a word starting with u is not UKTV");
        System.out.println("34 programme-guide matching checks passed");
    }

    private static boolean sameTime(String text) {
        try {
            String full = text.trim().length() == 14 ? text.trim() + " +0000" : text.trim();
            long expected = new java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT)
                .parse(full)
                .getTime();
            return EpgMatch.time(text) == expected;
        } catch (java.text.ParseException e) {
            return false;
        }
    }
}
