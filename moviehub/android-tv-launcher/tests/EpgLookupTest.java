package local.tv.home;

public class EpgLookupTest {

    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(EpgMatch.guideNameKey("GoldRadio.uk", "Gold").isEmpty(), "radio does not steal TV Gold");
        check(EpgMatch.guideNameKey("Gold.uk", "Gold").equals("gold"), "TV Gold keeps its name");
        check(
            EpgMatch.guideNameKey("BBCRadio4.uk", "BBC Radio 4").equals("bbcradio4"),
            "explicit radio names still match"
        );
        check(
            EpgLookup.keys("enginedlhd:12", "Sky Witness HD", "Entertainment", "enginedlhd", "uk").contains(
                "skywitness"
            ),
            "provider channel can find Sky XMLTV by name"
        );
        check(
            !EpgLookup.keys("enginedlhd:12", "Sky Witness HD", "Entertainment", "enginedlhd", "uk").contains(
                "enginedlhd12"
            ),
            "internal provider id is not requested as an XMLTV name"
        );
        check(
            EpgLookup.keys("engineplex:abc", "Example", "United States", "engineplex", "uk").isEmpty(),
            "known US channel cannot borrow UK schedule"
        );
        check(
            EpgLookup.keys("engineplex:abc", "Example", "United States", "engineplex", "us").contains(
                "example"
            ),
            "known US channel can use US schedule"
        );
        check(
            EpgLookup.keys("engineplex:abc", "Example", "General", "engineplex", "uk").isEmpty(),
            "unknown native country does not default to UK"
        );
        check(
            EpgLookup.keys("enginesamsung:abc", "Example", "General", "enginesamsung", "gb").contains(
                "example"
            ),
            "fixed GB provider country is used"
        );
        check(
            EpgLookup.keys("Example.de", "Example", "General", "free", "uk").isEmpty(),
            "country without an imported guide stays separate"
        );
        check(
            EpgLookup.keys("Example.ie", "Example", "General", "free", "ie").contains("example"),
            "Irish XMLTV identity"
        );
        check(
            EpgLookup.keys("", "UK: U&Dave HD", "General", "free", "uk").contains("dave"),
            "country prefix and UKTV alias"
        );
        check(
            EpgLookup.keys("BBCOne.uk", "BBC One", "General", "free", "gb").contains("bbcone"),
            "GB and UK are equivalent"
        );
        check(
            !EpgLookup.scope("uk", "example").equals(EpgLookup.scope("us", "example")),
            "country scopes differ"
        );
        check(
            EpgLookup.scope("uk", "example").equals(EpgLookup.scope("gb", "example")),
            "scope normalizes GB"
        );
        check(
            EpgLookup.country("engineplex:abc", "Example", "United States", "engineplex", "uk").equals("us"),
            "lookup selects the channel's country"
        );
        check(
            EpgLookup.keys("", "Example +1", "General", "free", "uk").contains("example+1"),
            "timeshift stays separate"
        );
        check(
            EpgLookup.keys("BBCOne.uk@Scotland", "BBC One", "UK", "free", "uk").contains("bbconescotland"),
            "explicit regional suffix is retained"
        );
        check(
            !EpgLookup.keys("BBCOne.uk@Scotland", "BBC One", "UK", "free", "uk").contains("bbcone"),
            "regional channel cannot borrow national programmes"
        );
        check(
            EpgLookup.keys("BBCOne.uk@HD", "BBC One", "UK", "free", "uk").contains("bbcone"),
            "quality suffix still matches"
        );
        System.out.println(checks + " XMLTV lookup checks passed");
    }
}
