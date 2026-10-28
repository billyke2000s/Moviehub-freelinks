package local.tv.home;

import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Satellite-box behaviour while watching Live TV: channel up and down, typing a channel number,
 * the last-channel key, the now-and-next banner with a progress bar and description, browsing
 * other channels in the banner without leaving this one, backup copies of a channel (the same
 * channel from another list) with automatic switching when a stream fails or keeps buffering,
 * and the parental lock.
 */
final class LiveZapper {

    /** What the guide was showing when a channel was chosen, so up and down follow the same list. */
    static final class Session {

        final String filter, search;
        final Set<String> favourites;
        int position;

        Session(String filter, String search, Set<String> favourites, int position) {
            this.filter = filter;
            this.search = search;
            this.favourites = new HashSet<>(favourites);
            this.position = position;
        }
    }

    /** Set by the guide when a channel is chosen; read by the player. */
    static volatile Session session;

    /**
     * The source mode and copies tried for the channel playing, kept across the guide and the full
     * screen (each builds its own zapper), keyed by copy. Main thread only. (Kept by LivePlayer
     * until its LibVLC live player was removed.)
     */
    static boolean activeAutomatic = true;

    static final Set<String> attemptedSources = new HashSet<>();

    /** Buffering this long, or this many pauses in a minute, moves to the next backup. */
    static final long STALL_MS = LiveBuffering.STALL_MS;
    static final int STALLS_PER_MINUTE = LiveBuffering.STARTS_PER_MINUTE;
    /** The banner stays this long; browsing in it a little longer. */
    static final long BANNER_MS = 5000,
        BROWSE_MS = 8000;

    private final LiveScreen player;
    /**
     * This player's own copy of the guide's list, so a later guide visit or test clearing the
     * shared one is safe. Replaced by all channels when a typed number is not in the list.
     */
    private Session own;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ChannelDatabase db;
    private final ColourPress colourPress = new ColourPress();
    private List<ChannelDatabase.Row> copies = new ArrayList<>();
    private int copy,
        previousPosition = -1,
        request;
    /**
     * A move to the next backup is on its way. Errors reported meanwhile (a stream can report an
     * error and then time out) belong to the copy that already failed, so they must not skip the
     * next copy before it has been tried.
     */
    private boolean switching;
    private boolean automaticSources = true,
        backControls;
    private final Set<Integer> attemptedCopies = new HashSet<>();
    /** The banner, programme information and pause bar (package-private for tests). */
    SkyOverlay overlay;
    private final StringBuilder typed = new StringBuilder();
    private final Runnable jump = this::jumpToTyped;
    private final Runnable hideBanner = () -> {
        browsing = false;
        if (overlay != null) overlay.hideBanner();
    };

    /** Browsing other channels in the banner: which channel and which of its programmes. */
    private boolean browsing;
    /** The channel shown while browsing (package-private for tests). */
    int browsePosition;
    private int browseProgramme;
    private ChannelDatabase.Row browseRow;

    /** Phone layout: the banner has touch buttons and a tap on the picture shows or hides it. */
    final boolean touch;

    LiveZapper(LiveScreen player, Session session) {
        this.player = player;
        touch = DeviceMode.phone(player.activity());
        own = session;
        db = ChannelDatabase.get(player.activity());
    }

    /** Builds the banner and loads the current channel's backups and listings. */
    void attach(FrameLayout root) {
        overlay = new SkyOverlay(player, root);
        if (!touch) overlay.boxControls();
        if (touch) overlay.touchControls(
            new String[] { "▲ Ch+", "▼ Ch−", "Info", "Source", "Audio", "❚❚ ▶", "Guide" },
            new Runnable[] {
                () -> channel(1),
                () -> channel(-1),
                () -> {
                    if (overlay.infoShowing()) hideInfo();
                    else showInfo();
                },
                player.liveMenus()::chooseSource,
                player.liveMenus()::audioAndSubtitles,
                player::toggle,
                player::leave,
            }
        );
        showBanner();
        load(own.position, false, true);
    }

    Session guideSession() {
        return own;
    }

    int position() {
        return own.position;
    }

    String sourceLabel() {
        return copies.size() <= 1 ? "1 of 1" : copy + 1 + " of " + copies.size();
    }

    /** The provider of the copy playing now (for example "FreeShot"), or "" before one is known. */
    String sourceProvider() {
        if (copy < 0 || copy >= copies.size()) return "";
        return AdapterSources.sourceName(copies.get(copy));
    }

    /** The banner's source text: the mode, the provider playing now, and which copy it is. */
    String sourceText() {
        String provider = sourceProvider();
        return (
            "Source: " +
            (automaticSources ? "Auto · " : "Manual · ") +
            (provider.isEmpty() ? "" : provider + " · ") +
            sourceLabel()
        );
    }

    boolean automaticSources() {
        return automaticSources;
    }

    int selectedCopy() {
        return copy;
    }

    void automaticSources(boolean enabled) {
        if (enabled && !automaticSources) {
            attemptedSources.clear();
            attemptedCopies.clear();
            attemptedCopies.add(copy);
        }
        automaticSources = enabled;
        rememberSources();
        if (enabled && player.liveFailed() && !copies.isEmpty()) useCopy(0, true);
        else showBanner();
    }

    List<ChannelDatabase.Row> copies() {
        return copies;
    }

    private void rememberSources() {
        activeAutomatic = automaticSources;
        if (!automaticSources) attemptedSources.clear();
        for (int index : attemptedCopies)
            if (index >= 0 && index < copies.size()) attemptedSources.add(copies.get(index).key);
    }

    // ---- Changing channel -----------------------------------------------------------------

    void channel(int delta) {
        endBrowse();
        tune(Math.max(0, own.position + delta));
    }

    void lastChannel() {
        endBrowse();
        if (previousPosition >= 0) tune(previousPosition);
    }

    void typeDigit(int digit) {
        endBrowse();
        if (typed.length() >= 6) typed.setLength(0);
        typed.append(digit);
        overlay.typing(typed.toString());
        player.overlayHandler().removeCallbacks(hideBanner);
        player.overlayHandler().postDelayed(hideBanner, BANNER_MS);
        player.overlayHandler().removeCallbacks(jump);
        player.overlayHandler().postDelayed(jump, LiveTvScreen.TYPE_MS);
    }

    /**
     * Tunes to the typed channel number: in this list, else in all channels (a number no channel
     * has goes to the nearest one above it).
     */
    private void jumpToTyped() {
        if (typed.length() == 0) return;
        int number = Integer.parseInt(typed.toString());
        typed.setLength(0);
        Session s = own;
        worker.execute(() -> {
            int here = db.position(s.filter, s.search, s.favourites, number, false);
            Session target = s;
            if (here < 0) {
                here = Math.max(0, db.position("all", "", s.favourites, number, true));
                target = new Session("all", "", s.favourites, s.position);
            }
            int found = here;
            Session list = target;
            player.activity().runOnUiThread(() -> {
                if (player.activity().isFinishing()) return;
                if (list != own) {
                    previousPosition = -1; // positions in the old list mean nothing in the new one
                    own = list;
                }
                tune(found);
            });
        });
    }

    /** Tunes to this channel (a reminder's "Watch now"), switching to all channels. */
    void tuneKey(String key) {
        endBrowse();
        Session s = own;
        worker.execute(() -> {
            ChannelDatabase.Row row = db.byKey(key);
            if (row == null) return;
            int position = Math.max(0, db.position("all", "", s.favourites, row.number, true));
            player.activity().runOnUiThread(() -> {
                if (player.activity().isFinishing()) return;
                own = new Session("all", "", s.favourites, own.position);
                previousPosition = -1;
                load(position, true, false);
            });
        });
    }

    private void tune(int position) {
        if (position == own.position) {
            showBanner();
            return;
        }
        load(position, true, false);
    }

    /**
     * Finds the channel at {@code position} in the guide's list, its backups and what is on, then
     * plays it ({@code play}) and shows the banner. Positions past the end stay on the last channel.
     * A locked channel asks for the PIN first and stays on this one if it is not given.
     */
    private void load(int position, boolean play, boolean current) {
        int ticket = ++request;
        Session s = own;
        String launchKey = current ? ChannelQuery.key(player.playbackUrl()) : null;
        // The copy playing is the one the engine reports or, before it reports one, the one this
        // screen was opened with. Never assume the first copy (that mislabelled the source).
        String activeUrl = current ? playingUrl() : null;
        String activeKey = activeUrl == null ? null : ChannelQuery.key(activeUrl);
        boolean wasAutomatic = activeAutomatic;
        Set<String> activeAttempts = new HashSet<>(attemptedSources);
        worker.execute(() -> {
            try {
                Session target = s;
                int targetPosition = position;
                if (current) {
                    // The launch URL is authoritative, including when a stale static session survived.
                    ChannelDatabase.Row playing = db.byKey(launchKey);
                    if (playing == null) return;
                    targetPosition = db.position(s.filter, s.search, s.favourites, playing.number, false);
                    if (targetPosition < 0) {
                        target = new Session("all", "", s.favourites, 0);
                        targetPosition = db.position("all", "", s.favourites, playing.number, true);
                    }
                }
                final Session list = target;
                final int at = Math.max(0, targetPosition);
                List<ChannelDatabase.Row> rows = db.browse(list.filter, list.search, list.favourites, at, 1);
                if (rows.isEmpty()) return;
                ChannelDatabase.Row row = rows.get(0);
                List<ChannelDatabase.Row> found = db.copies(row);
                int selected = 0;
                boolean carried = false;
                if (activeUrl != null) for (int i = 0; i < found.size(); i++) {
                    ChannelDatabase.Row candidate = found.get(i);
                    if (candidate.key.equals(activeKey)) {
                        selected = i;
                        carried = true;
                        break;
                    }
                }
                final int playingCopy = selected;
                final boolean automatic = !carried || wasAutomatic;
                String url = play ? db.url(found.get(0).id, found.get(0).key) : null;
                List<ChannelDatabase.Programme> on = listing(row);
                player.activity().runOnUiThread(() -> {
                    if (ticket != request || player.activity().isFinishing()) return;
                    Runnable apply = () -> {
                        if (!current) previousPosition = s.position;
                        own = list;
                        own.position = at;
                        session = own;
                        copies = found;
                        copy = playingCopy;
                        automaticSources = automatic;
                        attemptedCopies.clear();
                        attemptedCopies.add(copy);
                        if (activeUrl != null) for (int i = 0; i < found.size(); i++) {
                            if (activeAttempts.contains(found.get(i).key)) attemptedCopies.add(i);
                        }
                        switching = false;
                        if (play) {
                            automaticSources = true;
                            backControls = false;
                            player.switchTo(row.name, url, found.get(0).key);
                            remember(row);
                        }
                        if (current) sourceReplaced();
                        rememberSources();
                        showBanner(row, on);
                        // A native resolver can fail before this initial database read finishes.
                        if (current && player.liveFailed()) failover("This source is unavailable");
                    };
                    if (play && ParentalLock.locked(player.activity(), row)) {
                        player.dialogOpen(true);
                        ParentalLock.check(
                            player.activity(),
                            row.number + " " + row.name,
                            () -> {
                                player.panelClosed();
                                apply.run();
                            },
                            player::panelClosed
                        );
                    } else apply.run();
                });
            } catch (Exception e) {
                Diagnostics.recovered("Changing channel", e);
            }
        });
    }

    /** The guide's picture shows the last channel watched. */
    private void remember(ChannelDatabase.Row row) {
        player
            .activity()
            .getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(LiveTvScreen.LAST_CHANNEL, row.key)
            .apply();
    }

    private List<ChannelDatabase.Programme> listing(ChannelDatabase.Row row) {
        long now = System.currentTimeMillis();
        return db.programmes(row, EpgImporter.COUNTRY, now, now + 6 * 3600000L, 8);
    }

    // ---- Browsing in the banner ---------------------------------------------------------------

    boolean browsing() {
        return browsing;
    }

    /**
     * Browses without changing channel, as the satellite banner did: {@code channelDelta} moves to
     * another channel's now and next, {@code programmeDelta} to later programmes on it.
     */
    void browse(int channelDelta, int programmeDelta) {
        if (!browsing) {
            browsing = true;
            browsePosition = own.position;
            browseProgramme = 0;
            browseRow = null;
        }
        int position = Math.max(0, browsePosition + channelDelta);
        if (channelDelta != 0) browseProgramme = 0;
        browseProgramme = Math.max(0, browseProgramme + programmeDelta);
        int ticket = ++request;
        Session s = own;
        int programme = browseProgramme;
        worker.execute(() -> {
            try {
                List<ChannelDatabase.Row> rows = db.browse(s.filter, s.search, s.favourites, position, 1);
                if (rows.isEmpty()) return;
                ChannelDatabase.Row row = rows.get(0);
                List<ChannelDatabase.Programme> on = listing(row);
                player.activity().runOnUiThread(() -> {
                    if (ticket != request || player.activity().isFinishing() || !browsing) return;
                    browsePosition = position;
                    browseRow = row;
                    browseProgramme = Math.min(programme, Math.max(0, on.size() - 1));
                    showProgramme(row, on, browseProgramme, true);
                });
            } catch (Exception e) {
                Diagnostics.recovered("Browsing channels", e);
            }
        });
    }

    /** SELECT while browsing: watch the channel shown, or show this channel's full overlay. */
    void browseSelect() {
        int position = browsePosition;
        boolean other = browseRow != null && position != own.position;
        endBrowse();
        if (other) tune(position);
        else ok();
    }

    void endBrowse() {
        if (!browsing) return;
        browsing = false;
        hideBanner.run();
    }

    // ---- Backups ----------------------------------------------------------------------------

    /**
     * Plays copy {@code index} (0 is the main one). {@code automatic} is true when a failed copy
     * moved on by itself; false when the viewer chose it in the Source list.
     */
    void useCopy(int index, boolean automatic) {
        if (index < 0 || index >= copies.size()) return;
        if (!automatic) {
            automaticSources = false;
            attemptedCopies.clear();
        }
        attemptedCopies.add(index);
        rememberSources();
        ChannelDatabase.Row row = copies.get(index);
        List<ChannelDatabase.Row> list = copies;
        int ticket = ++request;
        worker.execute(() -> {
            try {
                String url = db.url(row.id, row.key);
                player.activity().runOnUiThread(() -> {
                    if (ticket != request || player.activity().isFinishing() || list != copies) return;
                    copy = index;
                    switching = false;
                    player.switchTo(row.name, url, row.key);
                    rememberSources();
                    if (automatic) Toast.makeText(
                        player.activity(),
                        "Trying another source (" + (index + 1) + " of " + copies.size() + ")",
                        Toast.LENGTH_SHORT
                    ).show();
                    showBanner();
                });
            } catch (Exception e) {
                Diagnostics.recovered("Switching to a backup", e);
                // This copy cannot be opened (its row changed under a refresh): count it as
                // failed and go on to the next, or say the channel is not working.
                player.activity().runOnUiThread(() -> {
                    if (ticket != request || player.activity().isFinishing() || list != copies) return;
                    copy = index;
                    switching = false;
                    player.liveSourceFailed();
                });
            }
        });
    }

    /**
     * Moves to the next copy of this channel after the current one failed. Each copy is tried at
     * most once per channel change, so a dead source is never retried in a loop. Returns false when
     * none is left, and the player then says the channel is not working.
     */
    boolean failover(String reason) {
        if (!automaticSources) return false;
        if (switching) return true; // the next copy is already on its way
        for (int i = 0; i < copies.size(); i++) if (
            attemptedSources.contains(copies.get(i).key)
        ) attemptedCopies.add(i);
        for (int offset = 1; offset < copies.size(); offset++) {
            int next = (copy + offset) % copies.size();
            if (attemptedCopies.contains(next)) continue;
            switching = true;
            Timings.record("Live TV backup used", Timings.start());
            useCopy(next, true);
            return true;
        }
        return false;
    }

    /**
     * The copies a source race may try: the one asked for, then every copy not yet tried this
     * time. Empty when the viewer chose a copy themselves or the channel has one copy only.
     */
    int[] raceCandidates() {
        if (!automaticSources || copies.size() <= 1 || copy < 0 || copy >= copies.size()) return new int[0];
        List<Integer> out = new ArrayList<>();
        out.add(copy);
        for (int i = 0; i < copies.size(); i++) if (
            i != copy && !attemptedCopies.contains(i) && !attemptedSources.contains(copies.get(i).key)
        ) out.add(i);
        if (out.size() <= 1) return new int[0];
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    /** The source race is playing copy {@code index}: the banner and source list follow it. */
    void raceSelected(int index) {
        if (index < 0 || index >= copies.size()) return;
        attemptedCopies.add(index);
        if (index != copy) {
            copy = index;
            showBanner();
        }
        rememberSources();
    }

    /** Copies the source race tried without success: failover will not try them again. */
    void raceFailed(int[] indices) {
        for (int index : indices) attemptedCopies.add(index);
    }

    /** Whether copy {@code index} has not been tried since this channel was chosen (no side effects). */
    boolean untriedCopy(int index) {
        return (
            index >= 0 &&
            index < copies.size() &&
            !attemptedCopies.contains(index) &&
            !attemptedSources.contains(copies.get(index).key)
        );
    }

    /** Whether {@link #failover} has a copy left to move to (no side effects). */
    boolean hasUntriedCopy() {
        if (!automaticSources || copies.size() <= 1) return false;
        for (int offset = 1; offset < copies.size(); offset++) {
            int next = (copy + offset) % copies.size();
            if (
                !attemptedCopies.contains(next) && !attemptedSources.contains(copies.get(next).key)
            ) return true;
        }
        return false;
    }

    /**
     * Try again after every copy failed (SELECT): starts from the first copy again, so all of them
     * get another chance in order. Returns false when the channel has one copy only.
     */
    boolean retryFromFirst() {
        if (!automaticSources || copies.size() <= 1) return false;
        switching = true;
        attemptedCopies.clear();
        useCopy(0, true);
        return true;
    }

    /** The link playing now: the live engine's, else the one this screen was opened with. */
    private String playingUrl() {
        String engine = ExoLive.currentUrl();
        return engine != null ? engine : player.playbackUrl();
    }

    /** Promotion changed the shared player, without calling switchTo or resolving again. */
    void sourceReplaced() {
        String current = playingUrl();
        if (current == null) return;
        String key = ChannelQuery.key(current);
        for (int i = 0; i < copies.size(); i++) {
            if (copies.get(i).key.equals(key)) copy = i;
            if (attemptedSources.contains(copies.get(i).key)) attemptedCopies.add(i);
        }
        switching = false;
        automaticSources = activeAutomatic;
        showBanner();
    }

    void close() {
        player.overlayHandler().removeCallbacks(jump);
        player.overlayHandler().removeCallbacks(hideBanner);
        worker.shutdownNow();
    }

    // ---- Banner -----------------------------------------------------------------------------

    private ChannelDatabase.Row bannerRow;
    private List<ChannelDatabase.Programme> bannerListing = new ArrayList<>();
    /** The channel and programme the banner shows, for the information panel. */
    private ChannelDatabase.Row shownRow;
    private ChannelDatabase.Programme shownProgramme;

    /** Shows the banner for the current channel again (SELECT, the Info key). */
    void showBanner() {
        endBrowse();
        if (bannerRow != null) showBanner(bannerRow, bannerListing);
        else {
            overlay.banner(
                "",
                player.title(),
                "No programme information",
                "",
                "",
                "",
                "",
                -1,
                sourceText(),
                false
            );
            player.overlayHandler().removeCallbacks(hideBanner);
            if (!backControls) player.overlayHandler().postDelayed(hideBanner, BANNER_MS);
        }
    }

    private void showBanner(ChannelDatabase.Row row, List<ChannelDatabase.Programme> listing) {
        bannerRow = row;
        bannerListing = listing;
        showProgramme(row, listing, 0, false);
    }

    /**
     * The banner for {@code row}: programme {@code index} of its listing (0 is the one on now),
     * the one after it, the description, and how far through the programme on now is.
     */
    private void showProgramme(
        ChannelDatabase.Row row,
        List<ChannelDatabase.Programme> listing,
        int index,
        boolean browse
    ) {
        long now = System.currentTimeMillis();
        ChannelDatabase.Programme shown = index < listing.size() ? listing.get(index) : null,
            next = index + 1 < listing.size() ? listing.get(index + 1) : null;
        shownRow = row;
        shownProgramme = shown;
        boolean onNow = shown != null && shown.start <= now && shown.stop > now;
        int progress = onNow
            ? (int) (((now - shown.start) * 1000) / Math.max(1, shown.stop - shown.start))
            : -1;
        overlay.banner(
            String.valueOf(row.number),
            row.name,
            shown == null
                ? "No programme information"
                : shown.title + (shown.tags().isEmpty() ? "" : "    " + shown.tags()),
            shown == null ? "" : (onNow ? "Now " : "") + LiveTvScreen.clockTime(new Date(shown.start)),
            next == null ? "" : next.title,
            next == null ? "" : "Next " + LiveTvScreen.clockTime(new Date(next.start)),
            shown == null ? "" : shown.description,
            progress,
            !browse ? sourceText() : "",
            browse
        );
        if (overlay.infoShowing()) showInfo();
        player.overlayHandler().removeCallbacks(hideBanner);
        if (!backControls) player.overlayHandler().postDelayed(hideBanner, browse ? BROWSE_MS : BANNER_MS);
    }

    /** The "i" panel: the programme the banner shows, in full. Shown until closed. */
    void showInfo() {
        player.overlayHandler().removeCallbacks(hideBanner);
        refreshPicture();
        if (shownRow == null) {
            overlay.info("", player.title(), "", "No programme information");
            return;
        }
        ChannelDatabase.Programme item = shownProgramme;
        String when =
            item == null
                ? ""
                : LiveTvScreen.clockTime(new Date(item.start)) +
                  " – " +
                  LiveTvScreen.clockTime(new Date(item.stop)) +
                  "   (" +
                  Math.max(1, (item.stop - item.start) / 60000) +
                  " mins)" +
                  (item.category.isEmpty() ? "" : "   ·   " + item.category) +
                  (item.tags().isEmpty() ? "" : "   ·   " + item.tags());
        overlay.info(
            shownRow.number + "  " + shownRow.name,
            item == null ? "No programme information" : item.title,
            when,
            item == null ? "" : item.description
        );
    }

    /**
     * Keeps the panel's picture line current while it is open: the first frame, a quality change
     * or a new source can arrive after it opened.
     */
    private final Runnable pictureTick = () -> {
        if (overlay.infoShowing()) refreshPicture();
    };

    private void refreshPicture() {
        player.overlayHandler().removeCallbacks(pictureTick);
        overlay.infoPicture(player.videoDetails(), sourceText());
        player.overlayHandler().postDelayed(pictureTick, PICTURE_REFRESH_MS);
    }

    static final int PICTURE_REFRESH_MS = 2000;

    void hideInfo() {
        player.overlayHandler().removeCallbacks(pictureTick);
        overlay.hideInfo();
        player.overlayHandler().removeCallbacks(hideBanner);
        player.overlayHandler().postDelayed(hideBanner, BANNER_MS);
    }

    // ---- Remote keys ----------------------------------------------------------------------------

    /**
     * The satellite remote while watching Live TV. Returns true when the key was used.
     *
     * <ul>
     *   <li>▲ ▼, CH+ CH-: change channel (in the banner while browsing: other channels).
     *   <li>◀ ▶: browse what is on, without changing channel; SELECT watches the channel shown.
     *   <li>SELECT: the same full banner, every time (a revealed and focused banner button is
     *       pressed instead). Numbers, last channel, GUIDE.
     *   <li>i (or yellow): programme information. Red: audio and subtitles. Green: source.
     *       Blue (or MENU): options.
     *   <li>BACK: closes what is open; then back to the guide, whose picture carries on.
     * </ul>
     */
    boolean key(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN,
            first = down && event.getRepeatCount() == 0;
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back();
            return true;
        }
        if (colourKey(event, this::colour)) return true;
        boolean select = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (browsing) {
            int channel = key == KeyEvent.KEYCODE_DPAD_UP ? 1 : key == KeyEvent.KEYCODE_DPAD_DOWN ? -1 : 0,
                programme =
                    key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (channel != 0 || programme != 0 || select) {
                if (down) {
                    if (select) {
                        if (first) browseSelect();
                    } else browse(channel, programme);
                }
                return true;
            }
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // ◀ ▶ move between the banner's buttons, opening the banner first when it is hidden.
            if (!overlay.bannerShowing()) {
                if (first) showBanner();
            } else if (down) {
                overlay.moveControl(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                player.overlayHandler().removeCallbacks(hideBanner);
                if (!backControls) player.overlayHandler().postDelayed(hideBanner, BANNER_MS);
            }
            return true;
        }
        boolean up =
                key == KeyEvent.KEYCODE_CHANNEL_UP ||
                key == KeyEvent.KEYCODE_PAGE_UP ||
                key == KeyEvent.KEYCODE_DPAD_UP,
            downKey =
                key == KeyEvent.KEYCODE_CHANNEL_DOWN ||
                key == KeyEvent.KEYCODE_PAGE_DOWN ||
                key == KeyEvent.KEYCODE_DPAD_DOWN;
        if (up || downKey) {
            if (down) channel(up ? 1 : -1);
            return true;
        }
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            if (first) typeDigit(key - KeyEvent.KEYCODE_0);
            return true;
        }
        boolean handled = true;
        if (select) {
            // OK while typing a number goes there at once (as on Sky); otherwise it presses the
            // highlighted banner button, or with none highlighted shows the banner.
            if (first && typed.length() > 0) {
                player.overlayHandler().removeCallbacks(jump);
                jumpToTyped();
            } else if (first && (!overlay.bannerShowing() || !overlay.pressControl())) ok();
        } else if (key == KeyEvent.KEYCODE_INFO || key == KeyEvent.KEYCODE_MENU) {
            if (first) colour(key == KeyEvent.KEYCODE_INFO ? 'y' : 'b');
        } else if (key == KeyEvent.KEYCODE_LAST_CHANNEL) {
            if (first) lastChannel();
        } else if (key == KeyEvent.KEYCODE_GUIDE) {
            if (first) player.leave();
        } else handled = false;
        return handled;
    }

    /** One input path for Activity and Dialog windows, before any native-player access. */
    boolean colourKey(KeyEvent event, java.util.function.Consumer<Character> action) {
        char colour = LiveTvScreen.colour(player.activity().getSharedPreferences("ui", 0), event);
        if (colour == 0) return false;
        if (
            colourPress.accept(
                event.getDeviceId(),
                colour,
                event.getAction(),
                event.getDownTime(),
                event.isCanceled()
            )
        ) action.accept(colour);
        return true;
    }

    void colour(char colour) {
        if (colour == 'r') player.liveMenus().audioAndSubtitles();
        else if (colour == 'g') player.liveMenus().chooseSource();
        else if (colour == 'b') player.liveMenus().liveOptions();
        else if (overlay.infoShowing()) hideInfo();
        else information();
    }

    /** Programme information from Options (the yellow or i key's job, for remotes without them). */
    void information() {
        if (!overlay.bannerShowing()) showBanner();
        showInfo();
    }

    /**
     * OK, or a click on the picture: always the same full channel banner (number, name, clock,
     * sources, now and next when the channel has listings, and the colour keys).
     */
    void ok() {
        showBanner();
        if (touch || !overlay.bannerShowing()) return;
        // As on the satellite box: OK brings the banner up ready to use, with Audio & subtitles
        // highlighted; ◀ ▶ move along and OK presses. ▲ ▼ only show it briefly while zapping.
        overlay.focusControl(0);
        player.overlayHandler().removeCallbacks(hideBanner);
        if (!backControls) player.overlayHandler().postDelayed(hideBanner, BROWSE_MS);
    }

    /** A tap on the picture (phone layout): shows the banner and its buttons, or hides them. */
    void tapped() {
        if (overlay.bannerShowing()) {
            overlay.hideInfo();
            hideBanner.run();
        } else showBanner();
    }

    /** BACK reveals the controls first; the next press returns to the guide's same live picture. */
    private void back() {
        if (overlay.infoShowing()) hideInfo();
        else if (browsing) endBrowse();
        else if (backControls) player.leave();
        else {
            backControls = true;
            showBanner();
            overlay.focusSources();
        }
    }
}
