package local.tv.home;

public class SkyNumbersTest {

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check(SkyNumbers.number("BBCOne.uk@HD", "BBC One (1080p)", true) == 101, "BBC One from iptv-org");
        check(SkyNumbers.number("", "BBC Two HD", true) == 102, "no id, quality word");
        check(SkyNumbers.number("", "UK: ITV1", true) == 103, "provider prefix");
        check(SkyNumbers.number("", "UK | Channel 4 HD", true) == 104, "provider prefix with bar");
        check(SkyNumbers.number("Channel5.uk", "Channel 5", true) == 105, "Channel 5");
        check(SkyNumbers.number("", "Sky Sports Main Event", true) == 401, "sports");
        check(SkyNumbers.number("Gold.it", "Gold", false) == 0, "another country's Gold keeps its own place");
        check(SkyNumbers.number("", "Channel 00", true) == 0, "unknown channel");
        check(SkyNumbers.number(null, null, true) == 0, "nothing to match");
        check(
            SkyNumbers.number("France24English.fr", "France 24", false) == 510,
            "foreign news the UK guide carries"
        );
        check(SkyNumbers.section(101, "BBC One", "General").equals("Entertainment"), "100s");
        check(SkyNumbers.section(503, "BBC News", "").equals("News"), "news by name");
        check(SkyNumbers.section(607, "CBBC", "").equals("Kids"), "kids");
        check(SkyNumbers.section(1001, "Some Channel", "Movies").equals("Movies"), "section from group");
        check(SkyNumbers.section(1002, "Some Channel", "UK").equals("Entertainment"), "default section");
        check(SkyNumbers.section(1003, "Euro Sport 2", "").equals("Sports"), "sports by name");
        check(!SkyNumbers.section(1004, "Nickname TV", "").equals("Kids"), "whole words only");
        System.out.println("17 satellite numbering checks passed");
    }
}
