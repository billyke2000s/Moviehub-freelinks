package local.tv.home;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;

/**
 * Settings → Free channels (its own sidebar tab): every free source in one place, in plain sections.
 *
 * <ul>
 *   <li>Public channel lists (Free-TV and iptv-org): which country, and whether they stay when a TV
 *       provider is connected.
 *   <li>Free services (Pluto TV, FreeLiveSports and the provider-engine services): one card each,
 *       on or off, with how many channels came from it.
 *   <li>Advanced: hiding US-only services, Masqueradarr, the ZLive domain and provider packages.
 * </ul>
 *
 * Only where the settings are shown changed; every preference keeps its key and meaning.
 */
final class FreeChannelsPage {

    private final MainActivity activity;
    private final SettingsScreens settings;

    FreeChannelsPage(MainActivity activity) {
        this.activity = activity;
        this.settings = activity.settingsScreens;
    }

    /** The countries offered for the public lists, as FreeChannels preference values. */
    /** The Settings sidebar tab this page fills. */
    static final String TAB = "Free channels";

    static final String[][] REGIONS = {
        { "off", "Off" },
        { "uk", "United Kingdom" },
        { "ie", "Ireland" },
        { "us", "United States" },
        { "ca", "Canada" },
        { "au", "Australia" },
        { "auto", "All countries" },
    };

    /** The public lists' choice as shown on the page: "auto" and "all" are both all countries. */
    static String regionLabel(String choice) {
        if (choice.equals("all") || choice.equals("auto")) return "All countries";
        for (String[] region : REGIONS) if (region[0].equals(choice)) return region[1];
        return FreeChannels.label(choice);
    }

    /** How many free services are on, for the Free channels card in Accounts. */
    static int servicesOn(SharedPreferences prefs) {
        int on = prefs.getBoolean(NativeSources.PLUTO, true) ? 1 : 0;
        if (prefs.getBoolean(EngineSources.PREF, false)) on++;
        boolean hideUsOnly = prefs.getBoolean(ProviderOutage.HIDE_US_ONLY, false);
        for (String id : ProviderBroker.IDS)
            if (
                prefs.getBoolean(AdapterSources.pref(id), false) && !ProviderOutage.hidden(hideUsOnly, id)
            ) on++;
        return on;
    }

    /** Total number of services the page lists. */
    static int servicesTotal() {
        return 2 + ProviderBroker.IDS.length;
    }

    /** Opens Settings on the Free channels tab. */
    void show() {
        show(null);
    }

    /** Opens Settings on the Free channels tab; {@code focusTitle} puts the remote on that card. */
    void show(String focusTitle) {
        LinearLayout body = settings.settingsLayout("Settings", activity.homeScreen::home, TAB);
        fill(body, focusTitle);
    }

    /** The Free channels tab's cards, in {@code body} (the Settings tab area). */
    void fill(LinearLayout body, String focusTitle) {
        body.removeAllViews();
        SharedPreferences prefs = activity.prefs;
        Button[] focus = new Button[1];
        settings.heading(body, "Free live TV");

        // At the top: what is on, and the two things people do most.
        ChannelImporter.Status sync = ChannelImporter.status(activity);
        String summary =
            sync.running || sync.queued
                ? "Updating channels now"
                : sync.lastSuccess == 0
                  ? "Not updated yet"
                  : "Last updated " + when(sync.lastSuccess);
        track(
            focus,
            focusTitle,
            "Refresh channels",
            settings.settingCard(
                body,
                "Refresh channels",
                "Fetch the latest channels from everything turned on below",
                summary,
                sync.lastSuccess > 0,
                "Refresh",
                () -> {
                    ChannelSync.refreshNow(activity);
                    activity.toast("Refreshing channels…");
                    show("Refresh channels");
                }
            )
        );
        track(
            focus,
            focusTitle,
            "Turn on every service",
            settings.settingCard(
                body,
                "Turn on every service",
                "Switch on all the free services below in one go",
                servicesOn(prefs) + " of " + servicesTotal() + " on",
                servicesOn(prefs) > 0,
                "Turn all on",
                () -> {
                    ProviderSettings.enableAll(activity);
                    activity.toast("Adding free services' channels…");
                    show("Turn on every service");
                }
            )
        );

        // Public lists: Free-TV and iptv-org.
        settings.heading(body, "Public channel lists");
        String choice = FreeChannels.choice(activity);
        track(
            focus,
            focusTitle,
            "Channel lists",
            settings.settingCard(
                body,
                "Channel lists",
                "Free-to-air channels from the Free-TV and iptv-org lists",
                regionLabel(choice),
                !choice.equals("off"),
                "Change",
                this::chooseRegion
            )
        );
        if (choice.equals("auto") || choice.equals("all")) {
            boolean always = choice.equals("all");
            track(
                focus,
                focusTitle,
                "With a TV provider",
                settings.settingCard(
                    body,
                    "With a TV provider",
                    always
                        ? "The lists stay when your Xtream or M3U account is connected"
                        : "The lists are left out while your Xtream or M3U account is connected",
                    always ? "Keep them" : "Leave them out",
                    always,
                    always ? "Leave out" : "Keep",
                    () -> {
                        prefs
                            .edit()
                            .putString(FreeChannels.PREF, always ? "auto" : "all")
                            .apply();
                        ChannelSync.refreshNow(activity);
                        show("With a TV provider");
                    }
                )
            );
        }

        // Free services: Pluto, FreeLiveSports and the provider-engine services.
        settings.heading(body, "Free services");
        SharedPreferences channelImport = activity.getSharedPreferences("channelImport", 0);
        boolean pluto = prefs.getBoolean(NativeSources.PLUTO, true);
        service(
            body,
            focus,
            focusTitle,
            "Pluto TV",
            channels(channelImport.getInt("nativePlutoCount", 0), 0, null),
            pluto,
            () -> prefs.edit().putBoolean(NativeSources.PLUTO, !pluto).apply()
        );
        SharedPreferences status = activity.getSharedPreferences("providerEngine", 0);
        boolean fls = prefs.getBoolean(EngineSources.PREF, false);
        service(
            body,
            focus,
            focusTitle,
            "FreeLiveSports",
            channels(status.getInt("flsCount", 0), status.getLong("flsRefresh", 0), null),
            fls,
            () -> prefs.edit().putBoolean(EngineSources.PREF, !fls).apply()
        );
        boolean hideUsOnly = prefs.getBoolean(ProviderOutage.HIDE_US_ONLY, false);
        for (int i = 0; i < ProviderBroker.IDS.length; i++) {
            String id = ProviderBroker.IDS[i];
            boolean enabled = prefs.getBoolean(AdapterSources.pref(id), false);
            String detail = ProviderOutage.hidden(hideUsOnly, id)
                ? "Hidden: \"Hide US-only services\" is on (see Advanced)"
                : channels(
                      status.getInt(id + "Count", 0),
                      status.getLong(id + "Refresh", 0),
                      status.getString(id + "Error", null)
                  );
            service(body, focus, focusTitle, AdapterSources.LABELS[i], detail, enabled, () -> {
                prefs.edit().putBoolean(AdapterSources.pref(id), !enabled).apply();
                AdapterSources.invalidate();
            });
        }

        // Advanced: things most people never need to change.
        settings.heading(body, "Advanced");
        track(
            focus,
            focusTitle,
            "Hide US-only services",
            settings.settingCard(
                body,
                "Hide US-only services",
                "Services marked \"US only\" do not work outside the US",
                hideUsOnly ? "Hidden" : "Shown",
                hideUsOnly,
                hideUsOnly ? "Show them" : "Hide them",
                () -> {
                    prefs.edit().putBoolean(ProviderOutage.HIDE_US_ONLY, !hideUsOnly).apply();
                    AdapterSources.invalidate();
                    ChannelSync.refreshNow(activity);
                    show("Hide US-only services");
                }
            )
        );
        boolean masqueradarr = activity.secrets.has("masqueradarr");
        track(
            focus,
            focusTitle,
            "Masqueradarr",
            settings.settingCard(
                body,
                "Masqueradarr",
                "Your own Masqueradarr server's playlist, if you run one",
                masqueradarr ? "Connected" : "Not set up",
                masqueradarr,
                masqueradarr ? "Manage" : "Set up",
                () -> settings.accountScreen("Masqueradarr")
            )
        );
        String domain = prefs.getString("engineZliveDomain", "");
        track(
            focus,
            focusTitle,
            "ZLive address",
            settings.settingCard(
                body,
                "ZLive address",
                "Only change this if ZLive moves to a new web address",
                domain.isEmpty() ? "Default" : domain,
                true,
                "Change",
                () -> zliveDomain(domain)
            )
        );
        boolean trust = ProviderPackages.configured(activity);
        boolean auto = prefs.getBoolean("providerAutoUpdates", true);
        track(
            focus,
            focusTitle,
            "Service updates",
            settings.settingCard(
                body,
                "Service updates",
                trust
                    ? "Signed updates to the free services, checked when channels refresh"
                    : "Not available until a signing key is set up",
                trust ? (auto ? "Automatic" : "Off") : "Not set up",
                trust && auto,
                "Manage",
                () -> packages(trust, auto)
            )
        );
        activity.settingsStyle(body);
        if (focus[0] != null) focus[0].requestFocus();
    }

    /** One free service: on or off, and what it last delivered. */
    private void service(
        LinearLayout body,
        Button[] focus,
        String focusTitle,
        String name,
        String detail,
        boolean enabled,
        Runnable toggle
    ) {
        track(
            focus,
            focusTitle,
            name,
            settings.settingCard(
                body,
                name,
                detail,
                enabled ? "On" : "Off",
                enabled,
                enabled ? "Turn off" : "Turn on",
                () -> {
                    toggle.run();
                    ChannelSync.refreshNow(activity);
                    show(name);
                }
            )
        );
    }

    /** "312 channels · updated 25 Oct 14:10", or the failure summary (fixed text, no addresses). */
    static String channels(int count, long refreshed, String error) {
        if (error != null && !error.isEmpty()) return (
            "Not working at the last refresh: " +
            error +
            (count > 0 ? ". Keeping " + count + " channels" : "")
        );
        if (count == 0 && refreshed == 0) return "No channels loaded yet";
        return count + " channels" + (refreshed > 0 ? " · updated " + when(refreshed) : "");
    }

    private static String when(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time));
    }

    /** Remembers the button of the card titled {@code title} when it is the one to focus. */
    private static void track(Button[] focus, String focusTitle, String title, TextView status) {
        if (focusTitle == null || !focusTitle.equals(title) || status == null) return;
        if (!(status.getParent() instanceof ViewGroup)) return;
        ViewGroup card = (ViewGroup) status.getParent();
        View last = card.getChildAt(card.getChildCount() - 1);
        if (last instanceof Button) focus[0] = (Button) last;
    }

    private void chooseRegion() {
        String current = FreeChannels.choice(activity);
        String[] names = new String[REGIONS.length];
        int selected = REGIONS.length - 1;
        for (int i = 0; i < REGIONS.length; i++) {
            names[i] = REGIONS[i][1];
            if (REGIONS[i][0].equals(current)) selected = i;
        }
        if (current.equals("all")) selected = REGIONS.length - 1;
        SidePanel.of(activity, "Channel lists")
            .message("Which country's free-to-air channels to add. Off adds none.")
            .choices(names, selected, which -> {
                String value = REGIONS[which][0];
                // All countries keeps the current "with a TV provider" setting.
                if (value.equals("auto") && current.equals("all")) value = "all";
                activity.prefs.edit().putString(FreeChannels.PREF, value).apply();
                ChannelSync.refreshNow(activity);
                show("Channel lists");
            })
            .show();
    }

    private void packages(boolean trust, boolean auto) {
        SidePanel panel = SidePanel.of(activity, "Service updates");
        if (trust) panel.option(auto ? "Automatic updates · On" : "Automatic updates · Off", () -> {
            activity.prefs.edit().putBoolean("providerAutoUpdates", !auto).apply();
            show("Service updates");
        });
        panel
            .option("Check for updates now", () -> {
                if (!ProviderPackages.configured(activity)) {
                    activity.toast("Updates are not set up");
                    return;
                }
                activity.async(() -> {
                    ProviderUpdates.check(activity, true);
                    return "Update check finished";
                }, activity::toast);
            })
            .option("Go back to the previous version", () ->
                activity.async(
                    () -> {
                        ProviderPackages.rollback(activity);
                        return "Previous version restored; automatic updates paused";
                    },
                    message -> {
                        activity.toast(message);
                        show("Service updates");
                    }
                )
            )
            .option("Show version", () ->
                activity.async(
                    () -> ProviderPackages.current(activity).getLong("version"),
                    version -> activity.toast("Service package version " + version)
                )
            )
            .message("Going back keeps your channels and favourites.")
            .show();
    }

    private void zliveDomain(String domain) {
        LinearLayout form = activity.vertical();
        android.widget.EditText field = activity.field(form, "Web address (optional)", false, domain);
        android.app.Dialog dialog = activity.form("ZLive address", form);
        form.addView(
            activity.button("Save", () -> {
                try {
                    String value = field.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                    if (!value.isEmpty()) {
                        java.net.URI u = java.net.URI.create(
                            ProviderUrls.playback(value.contains("://") ? value : "https://" + value)
                        );
                        if (
                            u.getUserInfo() != null ||
                            u.getPort() != -1 ||
                            u.getQuery() != null ||
                            u.getFragment() != null ||
                            !(u.getPath().isEmpty() || u.getPath().equals("/"))
                        ) throw new IllegalArgumentException();
                        value = u.getHost();
                        if (
                            !value.matches("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z][a-z0-9-]+") ||
                            value.endsWith(".local") ||
                            value.endsWith(".localhost")
                        ) throw new IllegalArgumentException();
                    }
                    activity.prefs.edit().putString("engineZliveDomain", value).apply();
                    AdapterSources.invalidate();
                    ChannelSync.refreshNow(activity);
                    dialog.dismiss();
                    show("ZLive address");
                } catch (Exception e) {
                    activity.toast("Enter an HTTPS address without an account, port or path");
                }
            })
        );
        form.addView(activity.button("Cancel", dialog::dismiss));
    }
}
