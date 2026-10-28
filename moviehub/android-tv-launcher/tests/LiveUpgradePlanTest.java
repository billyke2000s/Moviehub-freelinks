package local.tv.home;

/** Moving a live channel to a better copy, falling back and cooling down (LiveUpgradePlan). */
public final class LiveUpgradePlanTest {

    private static int checks;

    // Copies 0..3 in guide order: SD, HD, 1080p, unnamed (SD).
    private static final int[] LINES = { 480, 720, 1080, 480 };
    private static final String[] KEYS = { "sd", "hd", "fhd", "plain" };

    public static void main(String[] args) {
        theBestBetterCopyIsChosenOnly();
        aCopyThatFailsToBeFoundChangesNothing();
        aTrialThatHoldsIsKeptAndCanBeUpgradedAgain();
        aBufferingUpgradeFallsBackAndCoolsDown();
        cooldownsSurviveAChannelChangeAndDouble();
        pictureLines();
        System.out.println(checks + " live upgrade checks passed");
    }

    private static boolean[] untried(boolean... values) {
        return values;
    }

    private static void theBestBetterCopyIsChosenOnly() {
        LiveUpgradePlan plan = new LiveUpgradePlan(new LiveUpgradePlan.Cooldowns(), 60_000);
        boolean[] all = untried(false, true, true, true);
        same("best claim", 2, plan.choose(0, 480, LINES, KEYS, all, 0));
        same("one at a time", -1, plan.choose(0, 480, LINES, KEYS, all, 0));

        LiveUpgradePlan measured = new LiveUpgradePlan(new LiveUpgradePlan.Cooldowns(), 60_000);
        // The copy playing is named SD but measured at 1080 lines: nothing is better.
        same("measured picture", -1, measured.choose(0, 1080, LINES, KEYS, all, 0));
        // An unnamed copy (SD by default) is never an upgrade over SD.
        same(
            "equal is not better",
            -1,
            measured.choose(1, 720, LINES, KEYS, untried(true, false, false, true), 0)
        );
        // Tried copies are not chosen again this visit.
        same("tried", -1, measured.choose(0, 480, LINES, KEYS, untried(false, false, false, true), 0));
        same("not playing", -1, measured.choose(-1, 0, LINES, KEYS, all, 0));
    }

    private static void aCopyThatFailsToBeFoundChangesNothing() {
        LiveUpgradePlan plan = new LiveUpgradePlan(new LiveUpgradePlan.Cooldowns(), 60_000);
        same("looking for", 2, plan.choose(0, 480, LINES, KEYS, untried(false, true, true, true), 0));
        check("looking", plan.looking());
        plan.notFound();
        check("idle again", plan.idle());
        same("no fallback without a trial", -1, plan.fallBack(1));
        // The glue marks the unreachable copy tried; the next best is looked for.
        same("next best", 1, plan.choose(0, 480, LINES, KEYS, untried(false, true, false, true), 2));
    }

    private static void aTrialThatHoldsIsKeptAndCanBeUpgradedAgain() {
        LiveUpgradePlan plan = new LiveUpgradePlan(new LiveUpgradePlan.Cooldowns(), 60_000);
        same("720 found first", 1, plan.choose(0, 480, LINES, KEYS, untried(false, true, false, true), 0));
        plan.started(1000);
        check("on trial", plan.trial());
        same("previous", 0, plan.previous());
        check("not yet held", !plan.held(60_999));
        check("held", plan.held(61_000));
        check("idle", plan.idle());
        // The 1080p turns up later: from 720 it is still an upgrade.
        same("then 1080", 2, plan.choose(1, 720, LINES, KEYS, untried(false, false, true, true), 62_000));
    }

    private static void aBufferingUpgradeFallsBackAndCoolsDown() {
        LiveUpgradePlan.Cooldowns cooldowns = new LiveUpgradePlan.Cooldowns();
        LiveUpgradePlan plan = new LiveUpgradePlan(cooldowns, 60_000);
        boolean[] afterTrying = untried(false, false, false, true);
        same("upgrade", 2, plan.choose(0, 480, LINES, KEYS, untried(false, false, true, true), 0));
        plan.started(0);
        same("falls back to SD", 0, plan.fallBack(5000));
        check("cooling", cooldowns.active("fhd", 5000));
        check("idle after fallback", plan.idle());
        // Not chosen again while it cools down, so the picture cannot flip back and forth…
        same("cooling down", -1, plan.choose(0, 480, LINES, KEYS, afterTrying, 6000));
        same(
            "still cooling",
            -1,
            plan.choose(0, 480, LINES, KEYS, afterTrying, 5000 + LiveUpgradePlan.COOLDOWN_MS - 1)
        );
        // …and tried again once it has.
        same("cooled", 2, plan.choose(0, 480, LINES, KEYS, afterTrying, 5000 + LiveUpgradePlan.COOLDOWN_MS));
    }

    private static void cooldownsSurviveAChannelChangeAndDouble() {
        LiveUpgradePlan.Cooldowns cooldowns = new LiveUpgradePlan.Cooldowns();
        LiveUpgradePlan plan = new LiveUpgradePlan(cooldowns, 60_000);
        plan.choose(0, 480, LINES, KEYS, untried(false, false, true, true), 0);
        plan.started(0);
        plan.fallBack(0);
        // A channel change starts a new visit (every copy untried) but keeps the cooldown.
        plan.reset();
        same(
            "new visit, still cooling",
            1,
            plan.choose(0, 480, LINES, KEYS, untried(false, true, true, true), 1000)
        );
        plan.started(1000);
        plan.held(61_000);
        // Second fallback of the same copy: twice as long.
        long t = LiveUpgradePlan.COOLDOWN_MS;
        plan.reset();
        plan.choose(0, 480, LINES, KEYS, untried(false, false, true, true), t);
        plan.started(t);
        plan.fallBack(t);
        check("doubled", cooldowns.active("fhd", t + 2 * LiveUpgradePlan.COOLDOWN_MS - 1));
        check("doubled ends", !cooldowns.active("fhd", t + 2 * LiveUpgradePlan.COOLDOWN_MS));
        // Never longer than the maximum.
        for (int i = 0; i < 10; i++) cooldowns.start("fhd", 0);
        check("capped", !cooldowns.active("fhd", LiveUpgradePlan.MAX_COOLDOWN_MS));
        // A copy that holds through its trial starts short again.
        cooldowns.held("fhd");
        cooldowns.start("fhd", 0);
        check("short again", !cooldowns.active("fhd", LiveUpgradePlan.COOLDOWN_MS));
    }

    private static void pictureLines() {
        same("1080", 1080, LiveUpgradePlan.lines(1920, 1080));
        same("scope", 1080, LiveUpgradePlan.lines(1920, 800));
        same("720", 720, LiveUpgradePlan.lines(1280, 720));
        same("576", 576, LiveUpgradePlan.lines(720, 576));
        same("fixture", 180, LiveUpgradePlan.lines(320, 180));
        same("unknown", 0, LiveUpgradePlan.lines(-1, -1));
    }

    private static void same(String name, int expected, int actual) {
        checks++;
        if (expected != actual) {
            throw new AssertionError(
                "Live upgrade " + checks + " (" + name + "): expected " + expected + " got " + actual
            );
        }
    }

    private static void check(String name, boolean value) {
        checks++;
        if (!value) throw new AssertionError("Live upgrade " + checks + " (" + name + ")");
    }
}
