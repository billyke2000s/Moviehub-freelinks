package local.tv.home;

public final class QualityClaimsTest {

    private static int checks;

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Quality claim check " + checks);
        checks++;
    }

    public static void main(String[] args) {
        // The reported case: labelled 1080p, really 854×480.
        check(QualityClaims.verdict("1080p", VideoDetails.tier(854, 480)) == QualityClaims.OVERSTATED);
        check(QualityClaims.verdict("1080p", VideoDetails.tier(1920, 800)) == QualityClaims.HONEST);
        check(QualityClaims.verdict("720p", VideoDetails.tier(1920, 1080)) == QualityClaims.HONEST);
        check(QualityClaims.verdict("HD", VideoDetails.tier(640, 360)) == QualityClaims.OVERSTATED);
        check(QualityClaims.verdict("4K", VideoDetails.tier(1920, 1080)) == QualityClaims.OVERSTATED);
        // "SD", no label and an unknown picture are not judged.
        check(QualityClaims.verdict("SD", 240) == QualityClaims.NO_CLAIM);
        check(QualityClaims.verdict("", 240) == QualityClaims.NO_CLAIM);
        check(QualityClaims.verdict(null, 240) == QualityClaims.NO_CLAIM);
        check(QualityClaims.verdict("1080p", 0) == QualityClaims.NO_CLAIM);

        String history = "";
        history = QualityClaims.record(history, QualityClaims.OVERSTATED);
        check(history.equals("1"));
        check(!QualityClaims.distrusted(history));
        history = QualityClaims.record(history, QualityClaims.NO_CLAIM);
        check(history.equals("1"));
        history = QualityClaims.record(history, QualityClaims.HONEST);
        history = QualityClaims.record(history, QualityClaims.OVERSTATED);
        check(history.equals("101"));
        check(QualityClaims.distrusted(history));
        // Only the last five are remembered, so a site that fixes its labels is believed again.
        for (int i = 0; i < 4; i++) history = QualityClaims.record(history, QualityClaims.HONEST);
        check(history.equals("10000"));
        check(!QualityClaims.distrusted(history));
        check(QualityClaims.record("junk", QualityClaims.HONEST).equals("0"));
        check(!QualityClaims.distrusted(null));

        // A distrusted site's claim ranks as SD; a size read from the file is always used.
        check(QualityClaims.effective("1080p", false, "11").equals("SD"));
        check(QualityClaims.effective("1080p", false, "10").equals("1080p"));
        check(QualityClaims.effective("1080p", true, "11").equals("1080p"));
        check(QualityClaims.effective("1080p", false, null).equals("1080p"));

        // Labels once a source has played.
        check(QualityClaims.name(2160).equals("4K") && QualityClaims.name(480).equals("480p"));
        check(
            QualityClaims.playingLabel("site.example", 480, 0, "1080p", false).equals(
                "site.example · 480p ✓ (said 1080p)"
            )
        );
        check(
            QualityClaims.playingLabel("site.example", 1080, 0, "1080p", false).equals(
                "site.example · 1080p ✓"
            )
        );
        check(
            QualityClaims.playingLabel("site.example", 720, 720, "1080p", false).equals(
                "site.example · 720p ✓"
            )
        );
        check(
            QualityClaims.playingLabel("site.example", 480, 1080, "1080p", true).equals(
                "site.example · playing 480p of up to 1080p"
            )
        );

        // Better-source suggestions: only confirmed HD within the preferred quality, once per step.
        int[] confirmed = { 480, 1080, 0, 2160, 720 };
        boolean[] skipped = { true, false, false, false, false };
        check(QualityClaims.better(confirmed, skipped, 480, 1080, 0) == 1080);
        check(QualityClaims.better(confirmed, skipped, 480, 1080, 1080) == 0);
        check(QualityClaims.better(confirmed, skipped, 1080, 1080, 0) == 0);
        check(QualityClaims.better(confirmed, skipped, 480, 2160, 1080) == 2160);
        check(QualityClaims.better(confirmed, skipped, 480, 720, 0) == 720);
        skipped[1] = true;
        check(QualityClaims.better(confirmed, skipped, 480, 1080, 0) == 720);
        check(QualityClaims.better(new int[] { 576 }, new boolean[] { false }, 360, 1080, 0) == 0);
        System.out.println(checks + " quality claim checks passed");
    }
}
