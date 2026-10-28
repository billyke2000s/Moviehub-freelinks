package local.tv.home;

public class TroubleshootingTest {

    private static int checks;

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    public static void main(String[] args) {
        // Speed: 12.5 MB in one second is 100 Mb/s.
        check(Math.abs(SpeedTest.mbps(12_500_000L, 1000) - 100f) < 0.01f, "100 Mb/s");
        check(Math.abs(SpeedTest.mbps(1_000_000L, 8000) - 1f) < 0.01f, "1 Mb/s");
        check(SpeedTest.mbps(0, 1000) == 0, "nothing downloaded");
        check(SpeedTest.mbps(1000, 0) == 0, "no time measured");

        // Recent errors: newest first, at most 50, and never the message.
        ErrorLog.clear();
        ErrorLog.add("First place", new IllegalStateException("https://provider.example/user/secret"));
        ErrorLog.add("Second place", new java.io.IOException("password=hunter2"));
        java.util.List<ErrorLog.Entry> entries = ErrorLog.snapshot();
        check(entries.size() == 2, "two entries");
        check(entries.get(0).where.equals("Second place"), "newest first");
        check(entries.get(0).type.equals("IOException"), "exception type kept");
        for (ErrorLog.Entry entry : entries)
            check(
                !(entry.where + entry.type).contains("secret") &&
                    !(entry.where + entry.type).contains("hunter2"),
                "messages are never kept"
            );
        for (int i = 0; i < 70; i++) ErrorLog.add("Place " + i, new RuntimeException());
        check(ErrorLog.snapshot().size() == ErrorLog.CAPACITY, "at most 50 kept");
        check(ErrorLog.snapshot().get(0).where.equals("Place 69"), "the newest kept");
        ErrorLog.add(null, null);
        check(ErrorLog.snapshot().get(0).type.equals("Unknown"), "no error given");
        ErrorLog.clear();
        check(ErrorLog.snapshot().isEmpty(), "clear");
        System.out.println(checks + " troubleshooting checks passed");
    }
}
