package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Settings tabs, account setup and playback/language preferences. */
final class SettingsScreens {

    private final MainActivity activity;
    private String selectedTab = "Accounts";

    SettingsScreens(MainActivity activity) {
        this.activity = activity;
    }

    void addSource(String kind, String name, String link) {
        LinearLayout content = activity.vertical();
        EditText title = activity.field(content, "Title", false, name),
            url = activity.field(content, "Authorized media URL", false, link);
        CheckBox permission = new CheckBox(activity);
        permission.setText("I have permission to play this source");
        permission.setTextColor(0xfff8f8fc);
        content.addView(permission);
        Dialog dialog = activity.form("Add to " + kind, content);
        content.addView(
            activity.button("Save source", () -> {
                if (!permission.isChecked()) {
                    activity.toast("Confirm permission for this source");
                    return;
                }
                try {
                    activity.media.add(
                        title.getText().toString(),
                        url.getText().toString(),
                        kind,
                        activity.http()
                    );
                    dialog.dismiss();
                    activity.libraryScreens.library(kind);
                } catch (Exception e) {
                    activity.toast("Enter a title and valid media URL");
                }
            })
        );
    }

    void settings() {
        settingsTab(settingsLayout("Settings", activity.homeScreen::home, selectedTab), selectedTab);
    }

    /** Shared sidebar, viewport and content spacing for Settings and its Troubleshooting page. */
    LinearLayout settingsLayout(String title, Runnable back, String selected) {
        selectedTab = selected;
        activity.fixedScreen(title, back);
        LinearLayout layout = activity.row();
        layout.setGravity(Gravity.TOP);
        activity.page.addView(layout, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout navigation = activity.vertical();
        navigation.setPadding(0, activity.dp(8), activity.dp(22), 0);
        layout.addView(navigation, new LinearLayout.LayoutParams(activity.dp(190), -1));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout body = activity.vertical();
        body.setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8));
        scroll.addView(body);
        layout.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        List<Button> choices = new ArrayList<>();
        for (String tab : new String[] {
            "Accounts",
            FreeChannelsPage.TAB,
            "Appearance",
            "Playback",
            "System",
        }) {
            Button choice = activity.navigationButton(tab, () -> {});
            choices.add(choice);
            LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, activity.dp(44));
            navParams.bottomMargin = activity.dp(4);
            navigation.addView(choice, navParams);
            choice.setOnClickListener(v -> {
                selectedTab = tab;
                if (!title.equals("Settings")) {
                    settings();
                    return;
                }
                for (Button other : choices) {
                    other.setSelected(other == v);
                    activity.styleFocus(other, other.hasFocus());
                }
                settingsTab(body, tab);
            });
        }
        for (Button choice : choices) {
            boolean active = choice.getText().toString().equals(selected);
            choice.setSelected(active);
            activity.styleFocus(choice, false);
            if (active) choice.requestFocus();
        }
        return body;
    }

    private String versionName() {
        try {
            return (
                "Version " +
                activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName
            );
        } catch (Exception e) {
            return "";
        }
    }

    private String accountKey(String service) {
        return service.equals("Xtream")
            ? "xtream"
            : service.equals("M3U playlist")
              ? "m3u"
              : service.equals("Masqueradarr")
                ? "masqueradarr"
                : service.equals("TMDB")
                  ? "tmdb"
                  : "premiumize";
    }

    private void accountCard(LinearLayout body, String service, String description) {
        boolean connected = activity.secrets.has(accountKey(service));
        settingCard(
            body,
            service,
            description,
            connected ? "Connected" : "Not connected",
            connected,
            connected ? "Manage" : "Connect",
            () -> accountScreen(service)
        );
    }

    /**
     * One setting, in the style shared by every Settings tab: a bold title and short description on
     * the left, the current value on the right (green when on or connected), then one action button.
     * Returns the value label so callers can update it. {@code status} and {@code action} may be null.
     */
    TextView settingCard(
        LinearLayout body,
        String title,
        String description,
        String status,
        boolean positive,
        String action,
        Runnable onAction
    ) {
        LinearLayout card = activity.row();
        card.setPadding(activity.dp(18), activity.dp(9), activity.dp(14), activity.dp(9));
        card.setBackground(activity.box(0xff171d27));
        LinearLayout copy = activity.vertical();
        TextView name = activity.compactText(title, 19);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(name);
        TextView detail = activity.compactText(description, 13);
        detail.setTextColor(0xffaab4c4);
        detail.setMaxLines(4);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(detail);
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = activity.compactText(status == null ? "" : status, 13);
        statusColour(value, positive);
        if (status != null) card.addView(value);
        if (action != null) {
            Button button = activity.button(action, onAction);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(activity.dp(118), activity.dp(38));
            lp.leftMargin = activity.dp(18);
            card.addView(button, lp);
        }
        card.setMinimumHeight(activity.dp(78));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = activity.dp(8);
        body.addView(card, lp);
        return value;
    }

    private void statusColour(TextView value, boolean positive) {
        value.setTextColor(positive ? 0xff83ddb4 : 0xffaab4c4);
    }

    /** Section heading at the top of a Settings tab. */
    void heading(LinearLayout body, String text) {
        body.addView(activity.compactText(text, 23));
    }

    /** A single-purpose form keeps credentials separate from settings and other providers. */
    void accountScreen(String service) {
        String key = accountKey(service);
        boolean connected = activity.secrets.has(key);
        activity.screen(service, this::settings);
        LinearLayout layout = activity.row();
        layout.setGravity(Gravity.TOP);
        activity.page.addView(layout);
        LinearLayout explanation = activity.vertical();
        explanation.setPadding(0, activity.dp(10), activity.dp(40), 0);
        layout.addView(explanation, new LinearLayout.LayoutParams(activity.dp(290), -2));
        explanation.addView(activity.text(connected ? "Manage connection" : "Connect " + service, 26));
        String description = service.equals("Xtream")
            ? "Live TV\nEnter the server address, username and password supplied by your TV provider."
            : service.equals("Masqueradarr")
              ? "Live TV\nConnect your own Masqueradarr M3U export. Your other IPTV accounts remain separate."
              : service.equals("M3U playlist")
                ? "Live TV\nPaste the playlist link supplied by your TV provider. Channels refresh automatically."
                : service.equals("TMDB")
                  ? "Movies, series & artwork\nUse the API Key (v3 auth) from your TMDB account’s API settings."
                  : "Playback & cloud library\nUse the API key from your Premiumize account.";
        explanation.addView(activity.text(description, 16));
        if (service.equals("TMDB")) explanation.addView(activity.text("themoviedb.org/settings/api", 14));
        if (service.equals("Premiumize")) explanation.addView(activity.text("premiumize.me/account", 14));
        LinearLayout form = activity.vertical();
        form.setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(20));
        form.setBackground(activity.box(0xff131b27));
        layout.addView(form, new LinearLayout.LayoutParams(0, -2, 1));
        TextView status = activity.compactText(
            connected
                ? "Connected · saved credentials are kept until a replacement is verified"
                : "Not connected",
            14
        );
        status.setTextColor(0xffaab4c4);
        form.addView(status);
        List<EditText> inputs = new ArrayList<>();
        if (service.equals("Xtream")) {
            String server = "",
                user = "";
            try {
                if (connected) {
                    JSONObject saved = new JSONObject(activity.secrets.get(key));
                    server = saved.optString("server");
                    user = saved.optString("username");
                }
            } catch (Exception e) {
                Diagnostics.recovered("Reading saved Xtream account", e);
            }
            inputs.add(activity.field(form, "Server address", false, server));
            inputs.get(0).setHint("https://provider.example:port");
            inputs.add(activity.field(form, "Username", false, user));
            inputs.add(activity.field(form, "Password", true, ""));
        } else {
            String label =
                service.equals("M3U playlist") || service.equals("Masqueradarr")
                    ? "Playlist URL"
                    : service.equals("TMDB")
                      ? "API Key (v3 auth)"
                      : "API key";
            inputs.add(
                activity.field(
                    form,
                    label,
                    !(service.equals("M3U playlist") || service.equals("Masqueradarr")),
                    ""
                )
            );
            inputs
                .get(0)
                .setHint(
                    service.equals("M3U playlist") || service.equals("Masqueradarr")
                        ? "https://provider.example/playlist.m3u"
                        : "Enter your key"
                );
        }
        if (
            service.equals("Xtream") || service.equals("M3U playlist") || service.equals("Masqueradarr")
        ) formSwitch(form, "Allow HTTP provider connections", "http", false);
        if (!service.equals("M3U playlist") && !service.equals("Masqueradarr")) {
            CheckBox reveal = new CheckBox(activity);
            reveal.setText("Show " + (service.equals("Xtream") ? "password" : "key"));
            reveal.setTextColor(0xffaab4c4);
            EditText secret = inputs.get(inputs.size() - 1);
            reveal.setOnCheckedChangeListener((v, on) -> {
                int cursor = secret.getSelectionStart();
                secret.setTransformationMethod(
                    on ? null : android.text.method.PasswordTransformationMethod.getInstance()
                );
                if (cursor >= 0) secret.setSelection(Math.min(cursor, secret.length()));
            });
            form.addView(reveal);
        }
        Button save = activity.button(connected ? "Verify & save" : "Connect", () -> {});
        form.addView(save, new LinearLayout.LayoutParams(-1, activity.dp(44)));
        save.setOnClickListener(v -> {
            List<String> values = new ArrayList<>();
            for (EditText input : inputs) {
                String value = input.getText().toString();
                if (!(service.equals("Xtream") && inputs.indexOf(input) == 2)) value = value.trim();
                if (value.isEmpty()) {
                    input.setError("Required");
                    input.requestFocus();
                    return;
                }
                values.add(value);
            }
            if (service.equals("TMDB") && !values.get(0).matches("[0-9a-fA-F]{32}")) {
                inputs.get(0).setError("Use the 32-character API Key (v3 auth)");
                inputs.get(0).requestFocus();
                return;
            }
            save.setEnabled(false);
            save.setText("Connecting…");
            status.setText("Checking your connection…");
            int request = activity.generation;
            boolean allowHttp = activity.http();
            activity.io.execute(() -> {
                String result;
                boolean success = false;
                try {
                    String credential = values.get(0);
                    if (service.equals("Xtream")) credential = MediaRepository.connectXtream(
                        values.get(0),
                        values.get(1),
                        values.get(2),
                        allowHttp
                    ).toString();
                    else if (service.equals("M3U playlist") || service.equals("Masqueradarr")) {
                        credential = Playlist.networkUrl(credential, allowHttp);
                        if (!activity.liveTv.hasChannel(credential)) throw new Exception();
                    } else if (service.equals("TMDB")) new JSONObject(
                        Network.text(MediaRepository.tmdbUrl("trending/all/week", credential), null, false)
                    ).getJSONArray("results");
                    else activity.media.premiumize("account/info", credential);
                    activity.secrets.put(key, credential);
                    success = true;
                    result = "Connected";
                } catch (Exception error) {
                    result = service.equals("Xtream")
                        ? "Could not connect. Check the server address, username, password and HTTP setting."
                        : service.equals("M3U playlist") || service.equals("Masqueradarr")
                          ? "Could not read this playlist. Check the URL, provider access and HTTP setting."
                          : "Could not verify this key. Check the key and your internet connection.";
                }
                final boolean ok = success;
                final String message = result;
                activity.runOnUiThread(() -> {
                    if (
                        activity.isFinishing() || activity.isDestroyed() || activity.generation != request
                    ) return;
                    status.setText(message);
                    status.setTextColor(ok ? 0xff83ddb4 : 0xffffb4aa);
                    save.setEnabled(true);
                    save.setText("Verify & save");
                    if (ok) {
                        if (
                            service.equals("Xtream") ||
                            service.equals("M3U playlist") ||
                            service.equals("Masqueradarr")
                        ) ChannelSync.refreshNow(activity);
                        accountScreen(service);
                        activity.toast(
                            "Connected" +
                                (service.equals("Xtream") ||
                                service.equals("M3U playlist") ||
                                service.equals("Masqueradarr")
                                    ? " · importing channels"
                                    : "")
                        );
                    }
                });
            });
        });
        if (connected) form.addView(
            activity.button("Disconnect", () ->
                SidePanel.of(activity, "Disconnect " + service + "?")
                    .message("Remove this connection? Your other accounts and preferences will be kept.")
                    .action("Disconnect", () -> {
                        activity.secrets.remove(key);
                        if (
                            service.equals("Xtream") ||
                            service.equals("M3U playlist") ||
                            service.equals("Masqueradarr")
                        ) ChannelSync.refreshNow(activity);
                        settings();
                    })
                    .action("Cancel", null)
                    .show()
            )
        );
    }

    /** A plain on/off switch for use inside an account form. */
    private void formSwitch(LinearLayout parent, String title, String key, boolean fallback) {
        Switch s = new Switch(activity);
        s.setText(title);
        s.setTextColor(0xfff8f8fc);
        s.setChecked(activity.prefs.getBoolean(key, fallback));
        s.setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12));
        s.setOnCheckedChangeListener((v, checked) -> activity.prefs.edit().putBoolean(key, checked).apply());
        parent.addView(s);
    }

    private void toggle(LinearLayout parent, String title, String description, String key, boolean fallback) {
        boolean[] on = { activity.prefs.getBoolean(key, fallback) };
        TextView[] value = new TextView[1];
        Button[] button = new Button[1];
        value[0] = settingCard(
            parent,
            title,
            description,
            on[0] ? "On" : "Off",
            on[0],
            on[0] ? "Turn off" : "Turn on",
            () -> {
                on[0] = !on[0];
                activity.prefs.edit().putBoolean(key, on[0]).apply();
                value[0].setText(on[0] ? "On" : "Off");
                statusColour(value[0], on[0]);
                button[0].setText(on[0] ? "Turn off" : "Turn on");
            }
        );
        LinearLayout card = (LinearLayout) parent.getChildAt(parent.getChildCount() - 1);
        button[0] = (Button) card.getChildAt(card.getChildCount() - 1);
    }

    private void languagePreference(
        LinearLayout parent,
        String title,
        String key,
        String fallback,
        boolean subtitles
    ) {
        String[] names = subtitles
            ? new String[] {
                  "Off",
                  "English",
                  "Spanish",
                  "French",
                  "German",
                  "Italian",
                  "Portuguese",
                  "Dutch",
                  "Polish",
                  "Japanese",
                  "Korean",
                  "Chinese",
                  "Hindi",
                  "Arabic",
              }
            : new String[] {
                  "English",
                  "Original / default",
                  "Spanish",
                  "French",
                  "German",
                  "Italian",
                  "Portuguese",
                  "Dutch",
                  "Polish",
                  "Japanese",
                  "Korean",
                  "Chinese",
                  "Hindi",
                  "Arabic",
              };
        String[] codes = subtitles
            ? new String[] {
                  "none",
                  "eng,en",
                  "spa,es",
                  "fra,fre,fr",
                  "deu,ger,de",
                  "ita,it",
                  "por,pt",
                  "nld,dut,nl",
                  "pol,pl",
                  "jpn,ja",
                  "kor,ko",
                  "zho,chi,zh",
                  "hin,hi",
                  "ara,ar",
              }
            : new String[] {
                  "eng,en",
                  "",
                  "spa,es",
                  "fra,fre,fr",
                  "deu,ger,de",
                  "ita,it",
                  "por,pt",
                  "nld,dut,nl",
                  "pol,pl",
                  "jpn,ja",
                  "kor,ko",
                  "zho,chi,zh",
                  "hin,hi",
                  "ara,ar",
              };
        String value = activity.prefs.getString(key, fallback);
        int chosen = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(value)) chosen = i;
        TextView[] shown = new TextView[1];
        shown[0] = settingCard(
            parent,
            title,
            subtitles
                ? "Subtitle language chosen automatically when a video starts"
                : "Audio language chosen automatically when a video starts",
            names[chosen],
            !names[chosen].equals("Off"),
            "Change",
            () -> {
                String current = activity.prefs.getString(key, fallback);
                int selected = 0;
                for (int i = 0; i < codes.length; i++) if (codes[i].equals(current)) selected = i;
                SidePanel.of(activity, title)
                    .choices(names, selected, which -> {
                        activity.prefs.edit().putString(key, codes[which]).apply();
                        shown[0].setText(names[which]);
                        statusColour(shown[0], !names[which].equals("Off"));
                    })
                    .show();
            }
        );
    }

    private void settingsTab(LinearLayout body, String tab) {
        body.removeAllViews();
        if (tab.equals("Accounts")) {
            heading(body, "Your connections");
            accountCard(body, "Xtream", "Live channels from your TV provider");
            accountCard(body, "M3U playlist", "Your own IPTV playlist");
            accountCard(body, "TMDB", "Movie and series discovery, details and artwork");
            accountCard(body, "Premiumize", "Playback sources and your cloud library");
        } else if (tab.equals(FreeChannelsPage.TAB)) {
            new FreeChannelsPage(activity).fill(body, null);
        } else if (tab.equals("Appearance")) {
            heading(body, "Look and feel");
            toggle(body, "Featured row", "Artwork and a poster shelf on Home", "featured", true);
            toggle(body, "24-hour clock", "Show the time as 21:30 instead of 9:30 PM", "clock24", true);
            String[] accentNames = { "Violet", "Blue", "Teal" };
            int[] accents = { 0xff8a9eff, 0xff55aaff, 0xff45c9ac };
            int current = 0;
            for (int i = 0; i < accents.length; i++) if (accents[i] == activity.accent()) current = i;
            TextView[] accentValue = new TextView[1];
            accentValue[0] = settingCard(
                body,
                "Accent colour",
                "Outline colour around the selected item",
                accentNames[current],
                true,
                "Change",
                () -> {
                    int selected = 0;
                    for (int i = 0; i < accents.length; i++) if (accents[i] == activity.accent()) selected =
                        i;
                    SidePanel.of(activity, "Accent colour")
                        .choices(accentNames, selected, which -> {
                            activity.prefs.edit().putInt("accent", accents[which]).apply();
                            accentValue[0].setText(accentNames[which]);
                            accentValue[0].setTextColor(accents[which]);
                        })
                        .show();
                }
            );
            accentValue[0].setTextColor(activity.accent());
        } else if (tab.equals("Playback")) {
            heading(body, "Playback preferences");
            toggle(body, "Resume playback", "Continue from where you stopped", "resume", true);
            toggle(
                body,
                "Automatic play: films",
                "Choose the best ready-to-play source and movie file",
                AutoPlayback.MOVIES,
                true
            );
            toggle(
                body,
                "Automatic play: series",
                "Choose sources automatically when starting or continuing a series",
                AutoPlayback.SERIES,
                true
            );
            String[] qualityNames = { "720p", "1080p", "4K" };
            int[] qualityValues = { 720, 1080, 2160 };
            TextView[] qualityLabel = new TextView[1];
            int preferred = activity.prefs.getInt(AutoPlayback.QUALITY, 1080);
            qualityLabel[0] = settingCard(
                body,
                "Automatic play quality",
                "Prefer this resolution or lower; try a higher one only if needed",
                preferred >= 2160 ? "4K" : preferred >= 1080 ? "1080p" : "720p",
                false,
                "Change",
                () -> {
                    int current = activity.prefs.getInt(AutoPlayback.QUALITY, 1080);
                    SidePanel.of(activity, "Automatic play quality")
                        .choices(qualityNames, current >= 2160 ? 2 : current >= 1080 ? 1 : 0, which -> {
                            activity.prefs.edit().putInt(AutoPlayback.QUALITY, qualityValues[which]).apply();
                            qualityLabel[0].setText(qualityNames[which]);
                        })
                        .show();
                }
            );
            languagePreference(body, "Subtitles", "subtitleLanguage", "none", true);
            SubtitleSettings.cards(this, activity, body);
            languagePreference(body, "Audio track", "audioLanguage", "eng,en", false);
        } else {
            heading(body, "System");
            toggle(
                body,
                "Automatic updates",
                "Offer new MovieHub versions when they are published",
                "autoUpdates",
                true
            );
            settingCard(
                body,
                "MovieHub",
                "Check now for a newer version of MovieHub",
                versionName(),
                true,
                "Check",
                () -> {
                    activity.updates.lastUpdateCheck = 0;
                    activity.updates.checkForUpdate(true);
                }
            );
            settingCard(
                body,
                "Layout",
                "TV (remote) or phone and tablet (touch)",
                DeviceMode.phone(activity) ? "Phone" : "TV",
                false,
                "Change",
                () -> LayoutChooser.show(activity)
            );
            settingCard(
                body,
                SafetyNotice.TITLE,
                "Messages in a stream telling you to leave the app aren't from us",
                null,
                false,
                "Read",
                () -> SafetyNotice.show(activity)
            );
            settingCard(
                body,
                "Troubleshooting",
                "Speed test, connection checks, playback timings and recent errors",
                null,
                false,
                "Open",
                activity.troubleshooting::show
            );
            settingCard(
                body,
                "Android settings",
                android.os.Build.MANUFACTURER +
                    " " +
                    android.os.Build.MODEL +
                    " · Android " +
                    android.os.Build.VERSION.RELEASE,
                null,
                false,
                "Open",
                () -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception e) {
                        activity.toast("Android Settings is unavailable");
                    }
                }
            );
            settingCard(
                body,
                "Metadata",
                "Uses the TMDB API but is not endorsed or certified by TMDB",
                null,
                false,
                "Details",
                () ->
                    SidePanel.of(activity, "Metadata attribution")
                        .message(
                            "The Movie Database (TMDB)\nhttps://www.themoviedb.org\n\nMetadata is separate from your playback sources."
                        )
                        .action("Close", null)
                        .show()
            );
        }
        activity.settingsStyle(body);
    }
}
