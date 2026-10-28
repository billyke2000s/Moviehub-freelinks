package local.tv.home;

/** The live recovery rules ExoLive uses, ported from the LibVLC live player (LiveStallRules). */
public final class LiveStallRulesTest {

    private static int checks;

    public static void main(String[] args) {
        startupIsNotCounted();
        aLongStallMovesOnWhenAnotherSourceWaits();
        threeStallsAMinuteMoveOnOnlyWithAnotherSource();
        aLongStallWithNoOtherSourceReconnects();
        pausingIsNeverBuffering();
        aDeadStreamIsRecoveredButAStillPictureWithSoundIsNot();
        reconnectsAreBoundedAndRefilledOnlyBySustainedPicture();
        System.out.println(checks + " live stall rule checks passed");
    }

    private static LiveStallRules watching() {
        LiveStallRules rules = new LiveStallRules();
        rules.newSelection();
        rules.firstPicture();
        return rules;
    }

    private static void startupIsNotCounted() {
        LiveStallRules rules = new LiveStallRules();
        rules.newSelection();
        check("startup buffering", !rules.buffering(0, true, true, true));
        check("startup long wait", !rules.tick(9000, 0, 0, false, true, true));
        // A reconnect opens again: its startup is not counted either, until its first picture.
        rules.firstPicture();
        rules.opened();
        check("reopen startup", !rules.buffering(10000, true, true, true));
        check("reopen long wait", !rules.tick(20000, 0, 0, false, true, true));
    }

    private static void aLongStallMovesOnWhenAnotherSourceWaits() {
        LiveStallRules rules = watching();
        check("stall starts", !rules.buffering(1000, true, true, true));
        check("under eight seconds", !rules.tick(8999, 0, 10, false, true, true));
        check("eight seconds", rules.tick(9000, 0, 10, false, true, true));
        check("reported as buffering", rules.lastWasBuffering());
        check("acted once", !rules.tick(10000, 0, 10, false, true, true));
    }

    private static void threeStallsAMinuteMoveOnOnlyWithAnotherSource() {
        LiveStallRules withBackup = watching();
        check("first stall", !withBackup.buffering(0, true, true, true));
        withBackup.buffering(500, false, true, true);
        check("second stall", !withBackup.buffering(20000, true, true, true));
        withBackup.buffering(20500, false, true, true);
        check("third stall moves on", withBackup.buffering(40000, true, true, true));

        LiveStallRules alone = watching();
        alone.buffering(0, true, true, false);
        alone.buffering(500, false, true, false);
        alone.buffering(20000, true, true, false);
        alone.buffering(20500, false, true, false);
        // Brief HLS stalls never tear down a channel that has nowhere else to go…
        check("third brief stall alone", !alone.buffering(40000, true, true, false));
        check("still brief", !alone.tick(41000, 0, 10, false, true, false));
        alone.buffering(41000, false, true, false);
        check("ended", !alone.tick(42000, 0, 10, true, true, false));
    }

    private static void aLongStallWithNoOtherSourceReconnects() {
        LiveStallRules rules = watching();
        rules.buffering(1000, true, true, false);
        check("seven seconds", !rules.tick(8000, 0, 10, false, true, false));
        check("long stall reconnects", rules.tick(9000, 0, 10, false, true, false));
        check("not again", !rules.tick(10000, 0, 10, false, true, false));
    }

    private static void pausingIsNeverBuffering() {
        LiveStallRules rules = watching();
        check("paused stall", !rules.buffering(0, true, false, true));
        check("paused long", !rules.tick(30000, 0, 10, false, false, true));
        // A stall that began while watching is dropped by pausing.
        rules.buffering(31000, true, true, true);
        check("pause mid-stall", !rules.tick(35000, 0, 10, false, false, true));
        check("resume", !rules.tick(36000, 0, 10, false, true, true));
    }

    private static void aDeadStreamIsRecoveredButAStillPictureWithSoundIsNot() {
        LiveStallRules dead = watching();
        dead.tick(0, 5000, 100, true, true, false);
        check("seven seconds still", !dead.tick(7999, 5000, 100, true, true, false));
        check("nothing moved for eight", dead.tick(8000, 5000, 100, true, true, false));
        check("reported as a dead stream", !dead.lastWasBuffering());

        LiveStallRules sound = watching();
        for (long t = 0; t <= 12000; t += 1000) {
            check("still picture with sound " + t, !sound.tick(t, 5000 + t, 100, true, true, false));
        }
    }

    private static void reconnectsAreBoundedAndRefilledOnlyBySustainedPicture() {
        LiveStallRules rules = watching();
        check("first", rules.reconnect());
        check("second", rules.reconnect());
        check("third refused", !rules.reconnect());
        // One frame does not refill the budget (the old ExoLive rule did, and could loop).
        rules.opened();
        rules.firstPicture();
        check("one frame", !rules.reconnect());
        long frames = 0;
        for (long t = 0; t < LiveRestartBudget.HEALTHY_MS; t += 1000) {
            rules.tick(t, t, (frames += 15), true, true, false);
        }
        check("29 s", !rules.reconnect());
        // The first sample sets the baseline; then 30 s of rising frames.
        for (long t = 0; t <= LiveRestartBudget.HEALTHY_MS + 1000; t += 1000) {
            rules.tick(t, t, (frames += 15), true, true, false);
        }
        check("30 s refills", rules.reconnect());
        check("count", rules.reconnects() == 1);
        rules.newSelection();
        check("new selection", rules.reconnects() == 0);
    }

    private static void check(String name, boolean value) {
        checks++;
        if (!value) throw new AssertionError("Live stall rule check " + checks + ": " + name);
    }
}
