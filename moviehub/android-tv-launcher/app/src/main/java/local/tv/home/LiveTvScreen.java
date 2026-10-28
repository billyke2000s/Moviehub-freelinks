package local.tv.home;

import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live TV as a classic satellite-box TV guide: a blue full-screen grid of ten channels per page,
 * channel numbers and names on the left, the half-hour time slots across the top, the selected
 * channel in yellow, and a colour-key footer. Backed by the on-device channel database.
 *
 * <p>Channels carry UK satellite numbers where known (see {@link SkyNumbers}). The last channel
 * watched plays in the top corner. Programme titles come from the free UK programme guide (see
 * {@link EpgImporter}); channels it does not cover say so.
 */
final class LiveTvScreen {

    /** Channels per guide page, as on the classic guides. */
    static final int ROWS = 10;
    /** The guide shows three half-hour slots. */
    static final long WINDOW_MS = 90L * 60 * 1000;
    /** Guide colours. */
    /**
     * Guide colours. The cell blues, yellows and the faded "no listings" blue are the satellite
     * box's own, as recreated by the Sky+ web EPG simulator (davwheat); the background stays the
     * owner's dark blue.
     */
    static final int NAVY = 0xff10175a,
        BLUE = 0xff002db6,
        BLUE_DARK = 0xff00137d,
        NUMBER_TEXT = 0xff758ae5,
        FADED = 0xff2144c4,
        FADED_TEXT = 0xff99aaee,
        YELLOW = 0xfffcda15,
        YELLOW_DARK = 0xfffec200,
        INK = 0xff0000b3;

    private final MainActivity activity;
    /**
     * What the guide shows now: "all", "favourites", "sports", "section:<name>", "group:<name>",
     * plus a search.
     */
    String filter = "all",
        search = "";
    int pageStart;
    /** The guide's channel cells on the current page (for tests and focus). */
    final List<View> cells = new ArrayList<>();

    /** The guide's side panels: options, sections, programme information, lock and reminders. */
    final LiveTvPanels panels;
    /** The live picture in the corner of the guide. */
    final GuidePicture picture = new GuidePicture();
    /** Preference: the last channel watched (its stream key), shown in the guide's picture. */
    static final String LAST_CHANNEL = "lastLiveKey";
    /** Preference: whether the guide plays the last channel in its corner. */
    static final String PICTURE = "guidePicture";

    /** The guide frame (header, picture, footer) stays while paging; only the grid reloads. */
    private int frameGeneration = -1;
    /** The guide's fixed parts (header, picture, slots, keys) and its menu. */
    final GuideFrame frame;
    final GuideMenu menu;
    /** The guide rows: channel names and programme cells, and moving along them. */
    final GuideRows rows;
    /** A programme cell has the focus (not a channel name), at this point in time. */
    boolean programmeFocus;
    long cursorTime;
    /** How far the time slots have moved on from now (multiples of half an hour). */
    long windowOffset;
    /** The slots go up to a day ahead, as far as the programme guide reaches. */
    static final long MAX_OFFSET = 7L * 24 * 60 * 60 * 1000,
        HALF_HOUR = 30L * 60 * 1000;
    private int loadTicket;
    private long displayedGuideRevision;

    LiveTvScreen(MainActivity activity) {
        this.activity = activity;
        panels = new LiveTvPanels(activity, this);
        rows = new GuideRows(activity, this);
        frame = new GuideFrame(activity, this);
        menu = new GuideMenu(activity, this);
    }

    ChannelDatabase channelsDb() {
        return ChannelDatabase.get(activity);
    }

    Set<String> favouriteKeys() {
        return new HashSet<>(activity.prefs.getStringSet("favourites", Collections.emptySet()));
    }

    /** Live TV from Home: the guide menu first, as the satellite box's TV Guide button did. */
    void start() {
        refreshChannels();
        menu.show();
    }

    /** Straight to All Channels in the grid. */
    void live() {
        refreshChannels();
        filter = "all";
        search = "";
        windowOffset = 0;
        programmeFocus = false;
        guide(0, 0);
    }

    private void refreshChannels() {
        try {
            ChannelSync.refreshIfStale(activity, true);
        } catch (Exception e) {
            Diagnostics.recovered("Starting channel refresh", e);
        }
    }

    /** Shows the page starting at {@code start}, focusing row {@code focusRow} (-1 for the last). */
    void guide(int start, int focusRow) {
        pageStart = Math.max(0, start);
        if (frameGeneration != activity.generation || frame.grid == null) buildFrame();
        frame.update(windowStart());
        loadPage(focusRow);
    }

    /** The guide's fixed parts; BACK goes to the guide menu. */
    private void buildFrame() {
        activity.fixedScreen(null, menu::show, true);
        frame.build();
        frameGeneration = activity.generation;
        activity.leaving = () -> {
            picture.stop();
            frameGeneration = -1;
        };
        startPicture();
    }

    /** Whether there is a page after this one. */
    boolean hasMore;
    /** Ignore paging while a page is being replaced, before hasMore reflects its rows. */
    private boolean pageLoading;
    private int pendingPageDirection, pendingPageRow;

    /** Up from the colour keys: the bottom row, on its name or the programme at the cursor. */
    void focusLastRow() {
        if (cells.isEmpty()) {
            frame.focusTop();
            return;
        }
        int last = cells.size() - 1;
        if (programmeFocus) rows.focusAt(last, cursorTime);
        else cells.get(last).requestFocus();
    }

    /** Paging is a screen action even when row replacement has moved focus to the header. */
    boolean handlePageKey(KeyEvent event) {
        if (
            frameGeneration != activity.generation || frame.grid == null || !frame.grid.isShown()
        ) return false;
        int key = event.getKeyCode();
        int direction =
            key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_CHANNEL_UP
                ? -1
                : key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_CHANNEL_DOWN
                  ? 1
                  : 0;
        if (direction == 0) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) turnPage(
            direction,
            focusedRow()
        );
        return true;
    }

    /** Page up or Page down tapped in the key guide (touch screens). */
    void pageFromBar(int direction) {
        turnPage(direction, 0);
    }

    /**
     * The next or previous page, wrapping round as the satellite guide did: down from the last page
     * goes to the first, up from the first to the last.
     */
    void turnPage(int direction, int row) {
        if (pageLoading) {
            pendingPageDirection = direction;
            pendingPageRow = row;
            return;
        }
        if (direction > 0) {
            if (hasMore) guide(pageStart + ROWS, row);
            else if (pageStart > 0) guide(0, row);
            return;
        }
        if (pageStart > 0) {
            guide(pageStart - ROWS, row);
            return;
        }
        String listFilter = filter,
            listSearch = search;
        Set<String> keys = favouriteKeys();
        int request = activity.generation;
        activity.async(
            () -> channelsDb().position(listFilter, listSearch, keys, Integer.MAX_VALUE - 1, true),
            last -> {
                if (request != activity.generation || !listFilter.equals(filter)) return;
                if (last >= ROWS) guide(last - (last % ROWS), row);
            }
        );
    }

    /** Down from the Options button: the first row of the grid. */
    void focusFirstRow() {
        if (!cells.isEmpty()) {
            if (programmeFocus) rows.focusAt(0, cursorTime);
            else cells.get(0).requestFocus();
        }
    }

    /** Up from the top row of the first page: the Options button. */
    void focusTabs() {
        frame.focusTop();
    }

    /** The first time slot shown: now's half hour, moved on by ◀ ▶. */
    long windowStart() {
        return nowSlot() + windowOffset;
    }

    /** The start of the half hour now. */
    private static long nowSlot() {
        Calendar slot = Calendar.getInstance();
        slot.set(Calendar.MINUTE, slot.get(Calendar.MINUTE) < 30 ? 0 : 30);
        slot.set(Calendar.SECOND, 0);
        slot.set(Calendar.MILLISECOND, 0);
        return slot.getTimeInMillis();
    }

    /** Plays the last channel watched in the guide's corner (unless turned off or locked). */
    void startPicture() {
        if (frame.pictureBox != null && frame.pictureBox.isAttachedToWindow()) picture.show(
            activity,
            frame.pictureBox,
            frame.pictureLabel
        );
    }

    /** Loads the current page of channels and their programmes into the grid. */
    private void loadPage(int focusRow) {
        int ticket = ++loadTicket;
        pageLoading = true;
        LinearLayout grid = frame.grid;
        grid.removeAllViews();
        cells.clear();
        rows.clear();
        grid.addView(text("Loading channels…", 18, false));
        String listFilter = filter,
            listSearch = search;
        int start = pageStart;
        long windowStart = windowStart();
        activity.async(
            () -> {
                long revision = channelsDb().guideRevision();
                // The rows shown, and one more to tell whether another page follows.
                List<ChannelDatabase.Row> rows = channelsDb().browse(
                    listFilter,
                    listSearch,
                    favouriteKeys(),
                    start,
                    ROWS + 1
                );
                // Programmes for the ten rows shown, across the three half-hour slots.
                List<List<ChannelDatabase.Programme>> listings = new ArrayList<>();
                for (int i = 0; i < Math.min(ROWS, rows.size()); i++) listings.add(
                    channelsDb().programmes(
                        rows.get(i),
                        EpgImporter.COUNTRY,
                        windowStart,
                        windowStart + WINDOW_MS
                    )
                );
                // How many channels the whole list has, for the count beside its name.
                int total = channelsDb().listCount(listFilter, listSearch, favouriteKeys());
                return new Object[] { rows, listings, total, revision };
            },
            result -> {
                if (ticket != loadTicket) return; // a later page was asked for meanwhile
                displayedGuideRevision = (Long) result[3];
                pageLoading = false;
                if (pendingPageDirection != 0) {
                    int direction = pendingPageDirection,
                        row = pendingPageRow;
                    pendingPageDirection = 0;
                    activity.handler.post(() -> {
                        if (ticket == loadTicket && frameGeneration == activity.generation) turnPage(
                            direction,
                            row
                        );
                    });
                }
                @SuppressWarnings("unchecked")
                List<ChannelDatabase.Row> items = (List<ChannelDatabase.Row>) result[0];
                @SuppressWarnings("unchecked")
                List<List<ChannelDatabase.Programme>> listings = (List<
                    List<ChannelDatabase.Programme>
                >) result[1];
                grid.removeAllViews();
                frame.listCount((Integer) result[2]);
                showStatus(frame.status);
                cells.clear();
                rows.clear();
                int count = Math.min(items.size(), ROWS);
                boolean more = items.size() > ROWS;
                hasMore = more;
                Set<String> favourites = favouriteKeys(),
                    reminders = new HashSet<>();
                for (Reminders.Reminder item : Reminders.all(activity)) reminders.add(item.id());
                for (int i = 0; i < count; i++) rows.add(
                    grid,
                    items.get(i),
                    listings.get(i),
                    windowStart,
                    pageStart + i,
                    i,
                    count,
                    more,
                    favourites,
                    reminders
                );
                for (int i = count; i < ROWS; i++) grid.addView(
                    new View(activity),
                    new LinearLayout.LayoutParams(-1, 0, 1)
                );
                if (count == 0) {
                    grid.removeAllViews();
                    activity.emptyState(
                        grid,
                        filter.equals("favourites") ? "No favourites yet" : "No channels here",
                        filter.equals("favourites")
                            ? "Hold SELECT on a channel to add it to your favourites."
                            : "Channels are still loading, or none match. Free channels and TV providers are in Settings → Accounts.",
                        "Options",
                        panels::options
                    );
                    frame.focusTop(); // nothing to focus in the grid: the Options button
                    return;
                }
                int focus = focusRow < 0 ? count - 1 : Math.min(focusRow, count - 1);
                if (programmeFocus) rows.focusAt(focus, cursorTime);
                else cells.get(focus).requestFocus();
            }
        );
    }

    String listName() {
        if (!search.isEmpty()) return "Search: " + search;
        if (filter.equals("favourites")) return "Favourites";
        if (filter.equals("sports")) return "Sports";
        if (filter.startsWith("section:")) return filter.substring(8);
        if (filter.startsWith("country:")) return GuideOrder.name(filter.substring(8));
        if (filter.startsWith("group:")) return filter.substring(6).isEmpty()
            ? "Ungrouped"
            : filter.substring(6);
        return "All channels";
    }

    /** The guide label for a channel: its number, a lock, a star for favourites, its name. */
    String label(ChannelDatabase.Row channel, boolean favourite) {
        return (
            channel.number +
            "  " +
            (ParentalLock.locked(activity, channel) ? "🔒 " : "") +
            (favourite ? "★ " : "") +
            channel.name
        );
    }

    /**
     * Opens a channel full screen, asking for the PIN first when it is locked. Up and down in the
     * player follow the list the guide shows; coming back shows the page of the last channel.
     */
    void open(ChannelDatabase.Row channel, int position) {
        if (ParentalLock.locked(activity, channel)) {
            ParentalLock.check(activity, channel.name, () -> play(channel, position), null);
            return;
        }
        play(channel, position);
    }

    private void play(ChannelDatabase.Row channel, int position) {
        String listFilter = filter,
            listSearch = search;
        String activeUrl = ExoLive.currentUrl();
        String activeKey = activeUrl == null ? null : ChannelQuery.key(activeUrl);
        activity.async(
            () -> {
                ChannelDatabase db = channelsDb();
                if (activeUrl != null) for (ChannelDatabase.Row candidate : db.copies(channel)) {
                    if (candidate.key.equals(activeKey)) return activeUrl;
                }
                return db.url(channel.id, channel.key);
            },
            url -> {
                LiveZapper.session = new LiveZapper.Session(
                    listFilter,
                    listSearch,
                    favouriteKeys(),
                    position
                );
                activity.prefs.edit().putString(LAST_CHANNEL, channel.key).apply();
                // The guide's picture keeps playing: the full screen takes it over, so the same
                // channel carries on without reconnecting.
                activity.resumeRefresh = DeviceMode.phone(activity)
                    ? activity.phoneLive::refresh
                    : () -> {
                          int last = LiveZapper.session == null ? position : LiveZapper.session.position;
                          guide(last - (last % ROWS), last % ROWS);
                      };
                activity.play(channel.name, url, channel.key, true);
            }
        );
    }

    /** "Watch now" on a reminder: finds the channel in all channels and opens it. */
    void watch(Reminders.Reminder reminder) {
        activity.async(
            () -> {
                ChannelDatabase.Row row = channelsDb().byKey(reminder.key);
                int position =
                    row == null ? -1 : channelsDb().position("all", "", favouriteKeys(), row.number, true);
                return new Object[] { row, position };
            },
            result -> {
                ChannelDatabase.Row row = (ChannelDatabase.Row) result[0];
                if (row == null) {
                    activity.toast(reminder.channel + " is no longer in your channel lists");
                    return;
                }
                filter = "all";
                search = "";
                open(row, Math.max(0, (Integer) result[1]));
            }
        );
    }

    /** Red, CH+ or Page Up: the page before, as on the satellite guide. */
    private boolean isPageUp(int key) {
        return colour(key) == 'r' || key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP;
    }

    /** Green, CH- or Page Down: the next page. */
    private boolean isPageDown(int key) {
        return (
            colour(key) == 'g' || key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN
        );
    }

    /** The row that has the focus (its name or a programme), so a refresh keeps the place. */
    int focusedRow() {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).hasFocus()) return i;
            if (i < rows.programmes.size()) for (View item : rows.programmes.get(i))
                if (item.hasFocus()) return i;
        }
        return 0;
    }

    /**
     * ▶ past the last slot: moves the times on until {@code target} is shown, keeping to
     * {@code row}.
     */
    void later(long target, int row) {
        if (windowOffset >= MAX_OFFSET) return;
        while (windowStart() + WINDOW_MS <= target && windowOffset < MAX_OFFSET) windowOffset += HALF_HOUR;
        cursorTime = target;
        programmeFocus = true;
        guide(pageStart, row);
    }

    /** ◀ before the first slot: moves the times back towards now. False when already at now. */
    boolean earlier(long target, int row) {
        if (windowOffset <= 0) return false;
        while (windowStart() > target && windowOffset > 0) windowOffset -= HALF_HOUR;
        cursorTime = Math.max(target, windowStart());
        programmeFocus = true;
        guide(pageStart, row);
        return true;
    }

    /** Yellow (+24 hours): the same time tomorrow, as far as the programme guide reaches. */
    void nextDay(int row) {
        long step = Math.min(MAX_OFFSET, MAX_OFFSET - windowOffset);
        if (step <= 0) return;
        windowOffset += step;
        cursorTime += step;
        guide(pageStart, row);
    }

    /** Blue (−24 hours): the same time a day earlier, not before now. */
    void previousDay(int row) {
        if (windowOffset == 0) return;
        long step = Math.min(MAX_OFFSET, windowOffset);
        windowOffset -= step;
        cursorTime = Math.max(nowSlot(), cursorTime - step);
        guide(pageStart, row);
    }

    /** Keys that work the same on a channel name and a programme: numbers, paging, colour keys. */
    boolean sharedKey(int key, int row, boolean more) {
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            typeDigit(key - KeyEvent.KEYCODE_0);
            return true;
        }
        // OK while typing a number goes there at once, instead of opening the highlighted row.
        if ((key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) && typed.length() > 0) {
            selectUpPending = true;
            jumpToTyped();
            return true;
        }
        if (isPageUp(key)) {
            turnPage(-1, row);
            return true;
        }
        if (isPageDown(key)) {
            turnPage(1, row);
            return true;
        }
        // Yellow searches and blue opens the sections (the guide menu), as on the satellite guide.
        char colour = colour(key);
        if (colour == 'y') {
            panels.searchPanel();
            return true;
        }
        if (colour == 'b') {
            menu.show();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            panels.options();
            return true;
        }
        return false;
    }

    boolean isGuideKey(int key) {
        return (
            isPageUp(key) ||
            isPageDown(key) ||
            colour(key) != 0 ||
            key == KeyEvent.KEYCODE_MENU ||
            key == KeyEvent.KEYCODE_INFO ||
            (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9)
        );
    }

    /** Remote colour-key preferences, set by "Set up coloured buttons". */
    static final String[] COLOUR_PREFS = { "remoteRed", "remoteGreen", "remoteYellow", "remoteBlue" };
    /**
     * Learned scan codes, for remotes whose colour buttons Android has no key name for (they
     * arrive as KEYCODE_UNKNOWN, so only the scan code tells them apart).
     */
    static final String[] COLOUR_SCAN_PREFS = {
        "remoteRedScan",
        "remoteGreenScan",
        "remoteYellowScan",
        "remoteBlueScan",
    };
    static final char[] COLOURS = { 'r', 'g', 'y', 'b' };

    /**
     * Which colour key this is ('r', 'g', 'y', 'b') or 0. Remotes send different codes for the
     * colour keys: the standard ones, F1–F4 on many air-mouse remotes, or whatever was taught in
     * "Set up coloured buttons".
     */
    char colour(int key) {
        return colour(activity.prefs, key);
    }

    static char colour(android.content.SharedPreferences prefs, KeyEvent event) {
        int scan = event.getScanCode();
        // Android TV Generic.kl maps Linux KEY_RED/GREEN/YELLOW/BLUE (398–401) to PROG_*.
        // Retain those standard identities when firmware leaves the translated key unknown.
        if (event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
            if (scan >= 398 && scan <= 401) return COLOURS[scan - 398];
            if (scan > 0) for (int i = 0; i < 4; i++) if (
                prefs.getInt(COLOUR_SCAN_PREFS[i], -1) == scan
            ) return COLOURS[i];
            return 0;
        }
        return colour(prefs, event.getKeyCode());
    }

    static char colour(android.content.SharedPreferences prefs, int key) {
        // Standard colour buttons always perform the colour printed on the remote. Old learned
        // aliases must not turn (for example) a real green button into the red action.
        if (key == KeyEvent.KEYCODE_PROG_RED) return 'r';
        if (key == KeyEvent.KEYCODE_PROG_GREEN) return 'g';
        if (key == KeyEvent.KEYCODE_PROG_YELLOW) return 'y';
        if (key == KeyEvent.KEYCODE_PROG_BLUE) return 'b';
        if (key != KeyEvent.KEYCODE_UNKNOWN) for (int i = 0; i < 4; i++) if (
            prefs.getInt(COLOUR_PREFS[i], -1) == key
        ) return COLOURS[i];
        // Keep existing custom aliases for remotes which use ordinary function keys.
        if (key == KeyEvent.KEYCODE_F1) return 'r';
        if (key == KeyEvent.KEYCODE_F2) return 'g';
        if (key == KeyEvent.KEYCODE_F3) return 'y';
        if (key == KeyEvent.KEYCODE_F4) return 'b';
        return 0;
    }

    private final StringBuilder typed = new StringBuilder();
    private final Runnable jump = this::jumpToTyped;
    /**
     * How long after the last digit the typed number is used; OK uses it at once. 1.5 s: the owner
     * found a longer wait too slow once the number on screen updated as typed.
     */
    static final long TYPE_MS = 1500;
    private boolean selectUpPending;

    /** Number keys go straight to a channel, as on a satellite box: "151" opens channel 151. */
    private void typeDigit(int digit) {
        if (typed.length() >= 6) typed.setLength(0);
        typed.append(digit);
        // Drawn by the guide (GuideFrame.showNumber), so it looks the same on every box. Android
        // toasts differed between boxes, and a new one per digit queued behind the typing.
        frame.showNumber(typed.toString());
        activity.handler.removeCallbacks(jump);
        activity.handler.postDelayed(jump, TYPE_MS);
    }

    /** The number being typed, or "" (package-private for tests). */
    String typedNumber() {
        return typed.toString();
    }

    /** The release of an OK that jumped to a typed number: consumed, so it does not also open a row. */
    boolean consumeSelectUp(int key) {
        boolean select = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (!selectUpPending || !select) return false;
        selectUpPending = false;
        return true;
    }

    /**
     * Goes to the typed channel number in this list; a number this list does not have opens it in
     * all channels, and a number no channel has goes to the nearest one above it.
     */
    private void jumpToTyped() {
        activity.handler.removeCallbacks(jump);
        frame.showNumber("");
        if (typed.length() == 0) return;
        int number = Integer.parseInt(typed.toString());
        typed.setLength(0);
        String listFilter = filter,
            listSearch = search;
        activity.async(
            () -> {
                Set<String> keys = favouriteKeys();
                int here = channelsDb().position(listFilter, listSearch, keys, number, false);
                if (here >= 0) return new int[] { here, 0 };
                return new int[] { Math.max(0, channelsDb().position("all", "", keys, number, true)), 1 };
            },
            found -> {
                if (found[1] == 1) {
                    filter = "all";
                    search = "";
                }
                programmeFocus = false;
                guide(found[0] - (found[0] % ROWS), found[0] % ROWS);
            }
        );
    }

    void show(String newFilter, String newSearch) {
        filter = newFilter;
        search = newSearch;
        windowOffset = 0;
        programmeFocus = false;
        guide(0, 0);
    }

    private void showStatus(TextView status) {
        ChannelImporter.Status sync = ChannelImporter.status(activity);
        status.setText(
            sync.running
                ? String.format(Locale.getDefault(), "Updating channels · %,d imported", sync.progress)
                : sync.queued
                  ? "Waiting for the network to update channels"
                  : sync.message
        );
        if (!sync.running && !sync.queued) return;
        int request = activity.generation;
        activity.handler.postDelayed(
            new Runnable() {
                public void run() {
                    if (
                        activity.isFinishing() || activity.isDestroyed() || activity.generation != request
                    ) return;
                    ChannelImporter.Status latest = ChannelImporter.status(activity);
                    if (latest.running || latest.queued) {
                        if (channelsDb().guideRevision() != displayedGuideRevision) {
                            // Listings may finish well before the slow optional catalogues.
                            guide(pageStart, focusedRow());
                            return;
                        }
                        status.setText(
                            latest.running
                                ? String.format(
                                      Locale.getDefault(),
                                      "Updating channels · %,d imported",
                                      latest.progress
                                  )
                                : "Waiting for the network to update channels"
                        );
                        activity.handler.postDelayed(this, 2000);
                    } else guide(pageStart, focusedRow()); // the import finished: show the new channels
                }
            },
            2000
        );
    }

    private TextView text(String value, int size, boolean bold) {
        TextView label = activity.compactText(value, size);
        label.setTextColor(0xffffffff);
        SkyFont.apply(label, bold);
        return label;
    }

    private GradientDrawable box(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(3));
        shape.setStroke(activity.dp(1), 0x55ffffff);
        return shape;
    }

    /** "8.00pm Mon 18 Jul", as the classic guides wrote it. */
    static String clock(Date when) {
        return clockTime(when) + " " + new SimpleDateFormat("EEE d MMM", Locale.UK).format(when);
    }

    static String clockTime(Date when) {
        return new SimpleDateFormat("h.mma", Locale.UK).format(when).toLowerCase(Locale.ROOT);
    }

    private static final class FoundChannel extends Exception {}

    /** Reads only until the first playable channel, so connecting a very large playlist stays quick. */
    boolean hasChannel(String url) throws Exception {
        try (java.io.InputStream in = Network.open(url, null, Network.CATALOGUE_MAX, activity.http())) {
            Playlist.parse(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                url,
                activity.http(),
                c -> {
                    throw new FoundChannel();
                }
            );
        } catch (FoundChannel found) {
            return true;
        }
        return false;
    }
}
