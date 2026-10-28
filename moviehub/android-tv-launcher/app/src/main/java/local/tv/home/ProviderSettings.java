package local.tv.home;

/** The first-start question about free providers. Their settings are on FreeChannelsPage. */
final class ProviderSettings {

    private ProviderSettings() {}

    /** Set once the first-start question about free providers has been answered. */
    static final String ASKED = "freeProvidersAsked";

    /**
     * Asks once whether to turn on the free TV providers, then runs {@code next} (the safety
     * notice). Back closes the question without answering, so it is asked again next time.
     * Existing installs that never saw it are asked too.
     */
    static void askOnce(MainActivity activity, Runnable next) {
        if (activity.prefs.getBoolean(ASKED, false)) {
            next.run();
            return;
        }
        SidePanel.of(activity, "Free TV providers")
            .message(
                "MovieHub can add free live channels from FreeLiveSports, DaddyLive, Samsung TV Plus, " +
                    "Pluto TV, Tubi, Xumo and other free services. They run inside the app, with no " +
                    "server. They join your guide sections and act as backups when a channel fails.\n\n" +
                    "Some services may not work in your region. You can turn each one off in " +
                    "Settings → Free channels."
            )
            .action("Turn them all on", () -> {
                enableAll(activity);
                activity.toast("Adding free providers' channels…");
            })
            .action("Not now", () -> activity.prefs.edit().putBoolean(ASKED, true).apply())
            .onDismiss(next)
            .show();
    }

    /** Turns on every free provider and refreshes the channel list. */
    static void enableAll(MainActivity activity) {
        android.content.SharedPreferences.Editor edit = activity.prefs
            .edit()
            .putBoolean(ASKED, true)
            .putBoolean(EngineSources.PREF, true)
            .putBoolean(NativeSources.PLUTO, true);
        for (String id : ProviderBroker.IDS) edit.putBoolean(AdapterSources.pref(id), true);
        edit.apply();
        AdapterSources.invalidate();
        ChannelSync.refreshNow(activity);
    }
}
