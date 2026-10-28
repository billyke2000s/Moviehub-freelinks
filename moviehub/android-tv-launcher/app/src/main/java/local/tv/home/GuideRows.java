package local.tv.home;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The rows of the Live TV guide, as on the satellite guide: the channel name on the left and its
 * programmes across the time slots. ◀ ▶ move along the programmes and, past the last slot, move
 * the times on (the channel names stay put); ▲ ▼ keep to the same time on the next channel.
 * SELECT on what is on now watches it; on a later programme it sets or cancels a reminder.
 */
final class GuideRows {

    /** A programme cell's place in time (clipped to the slots shown) and its programme, if any. */
    static final class Slot {

        final ChannelDatabase.Programme programme;
        final long from, to;

        Slot(ChannelDatabase.Programme programme, long from, long to) {
            this.programme = programme;
            this.from = from;
            this.to = to;
        }
    }

    private final MainActivity activity;
    private final LiveTvScreen guide;
    /** The programme cells of each row on the page (for tests and moving between rows). */
    final List<List<TextView>> programmes = new ArrayList<>();

    GuideRows(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    void clear() {
        programmes.clear();
    }

    /** Adds one channel's row to {@code grid}. */
    void add(
        LinearLayout grid,
        ChannelDatabase.Row channel,
        List<ChannelDatabase.Programme> listing,
        long windowStart,
        int position,
        int row,
        int count,
        boolean more,
        Set<String> favourites,
        Set<String> reminders
    ) {
        LinearLayout line = activity.row();
        line.setClipChildren(false);
        TextView cell = text(guide.label(channel, favourites.contains(channel.key)), 19);
        cell.setPadding(activity.dp(10), 0, activity.dp(8), 0);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setContentDescription(channel.name);
        List<TextView> cells = programmeCells(channel, listing, windowStart, reminders);
        LinearLayout across = activity.row();
        for (TextView item : cells) {
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                0,
                -1,
                ((LinearLayout.LayoutParams) item.getLayoutParams()).weight
            );
            layout.rightMargin = activity.dp(3);
            across.addView(item, layout);
        }
        // The focused cell is yellow. On the channel name the whole row lights up; on a
        // programme, the channel name stays lit so you can see which row you are on.
        Runnable restyle = () -> {
            boolean nameFocused = cell.hasFocus();
            boolean rowFocused = nameFocused;
            for (TextView item : cells) rowFocused |= item.hasFocus();
            cell.setBackground(
                box(nameFocused ? LiveTvScreen.YELLOW : rowFocused ? 0xff4d7ce0 : LiveTvScreen.BLUE)
            );
            cell.setTextColor(nameFocused ? LiveTvScreen.INK : 0xffffffff);
            for (TextView item : cells) {
                boolean on = item.hasFocus();
                // No listings: the faded blue, as the satellite guide drew it.
                boolean none = ((Slot) item.getTag()).programme == null;
                item.setBackground(
                    box(
                        on
                            ? LiveTvScreen.YELLOW
                            : nameFocused
                              ? 0xfffff1a6
                              : none
                                ? LiveTvScreen.FADED
                                : LiveTvScreen.BLUE
                    )
                );
                item.setTextColor(
                    on || nameFocused ? LiveTvScreen.INK : none ? LiveTvScreen.FADED_TEXT : 0xffffffff
                );
            }
        };
        cell.setOnFocusChangeListener((v, focused) -> restyle.run());
        restyle.run();
        cell.setOnClickListener(v -> guide.open(channel, position));
        cell.setOnLongClickListener(v -> {
            Set<String> keys = guide.favouriteKeys();
            boolean added = keys.add(channel.key);
            if (!added) keys.remove(channel.key);
            activity.prefs.edit().putStringSet("favourites", keys).apply();
            cell.setText(guide.label(channel, added));
            activity.toast(added ? "Added to favourites" : "Removed from favourites");
            return true;
        });
        cell.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return (
                guide.consumeSelectUp(key) || guide.isGuideKey(key)
            );
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!cells.isEmpty()) focus(cells, 0);
                return true;
            }
            if (key == KeyEvent.KEYCODE_INFO) {
                guide.panels.programmeInfo(channel, position);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && row == count - 1) {
                // Below the bottom row: the next page (round to the first after the last).
                guide.programmeFocus = false;
                guide.turnPage(1, 0);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP && row == 0) {
                guide.programmeFocus = false;
                if (guide.pageStart > 0) guide.guide(guide.pageStart - LiveTvScreen.ROWS, -1);
                else guide.focusTabs();
                return true;
            }
            return guide.sharedKey(key, row, more);
        });
        for (int i = 0; i < cells.size(); i++) {
            TextView item = cells.get(i);
            int index = i;
            Slot slot = (Slot) item.getTag();
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    guide.programmeFocus = true;
                    // The time cursor stays put moving up and down; it moves to this programme
                    // when it lies outside it.
                    if (guide.cursorTime < slot.from || guide.cursorTime >= slot.to) guide.cursorTime =
                        slot.from;
                }
                restyle.run();
            });
            item.setOnClickListener(v -> select(channel, position, row, slot));
            item.setOnLongClickListener(v -> {
                info(channel, position, slot);
                return true;
            });
            item.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return (
                    guide.consumeSelectUp(key) || guide.isGuideKey(key)
                );
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (index + 1 < cells.size()) {
                        guide.cursorTime = ((Slot) cells.get(index + 1).getTag()).from;
                        focus(cells, index + 1);
                    } else guide.later(
                        slot.programme != null ? Math.max(slot.to, slot.programme.stop) : slot.to,
                        row
                    );
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (index > 0) {
                        guide.cursorTime = ((Slot) cells.get(index - 1).getTag()).from;
                        focus(cells, index - 1);
                    } else if (!guide.earlier(slot.from - 1, row)) {
                        guide.programmeFocus = false;
                        cell.requestFocus();
                    }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int target = row + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    if (target >= 0 && target < programmes.size()) focusAt(target, guide.cursorTime);
                    else if (target < 0 && guide.pageStart > 0) guide.guide(
                        guide.pageStart - LiveTvScreen.ROWS,
                        -1
                    );
                    else if (target < 0) guide.focusTabs();
                    else if (target >= count) guide.turnPage(1, 0);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_INFO) {
                    info(channel, position, slot);
                    return true;
                }
                return guide.sharedKey(key, row, more);
            });
        }
        programmes.add(cells);
        guide.cells.add(cell);
        LinearLayout.LayoutParams cellLayout = new LinearLayout.LayoutParams(0, -1, 34);
        cellLayout.rightMargin = activity.dp(8);
        line.addView(cell, cellLayout);
        line.addView(across, new LinearLayout.LayoutParams(0, -1, 66));
        LinearLayout.LayoutParams lineLayout = new LinearLayout.LayoutParams(-1, 0, 1);
        lineLayout.topMargin = activity.dp(4);
        grid.addView(line, lineLayout);
    }

    private void focus(List<TextView> cells, int index) {
        cells.get(index).requestFocus();
    }

    /** Focuses the programme on {@code row} that is on at {@code time} (or the nearest one). */
    void focusAt(int row, long time) {
        if (row < 0 || row >= programmes.size()) return;
        List<TextView> cells = programmes.get(row);
        if (cells.isEmpty()) return;
        int best = cells.size() - 1;
        for (int i = 0; i < cells.size(); i++) {
            Slot slot = (Slot) cells.get(i).getTag();
            if (time < slot.to) {
                best = i;
                break;
            }
        }
        long keep = guide.cursorTime;
        cells.get(best).requestFocus();
        guide.cursorTime = keep;
    }

    /** SELECT on a programme: watch what is on now; set or cancel a reminder for a later one. */
    private void select(ChannelDatabase.Row channel, int position, int row, Slot slot) {
        long now = System.currentTimeMillis();
        ChannelDatabase.Programme item = slot.programme;
        if (item == null || item.start <= now) {
            guide.open(channel, position);
            return;
        }
        boolean set = !Reminders.has(activity, channel, item);
        Reminders.set(activity, channel, item, set);
        activity.toast(set ? "Reminder set for " + item.title : "Reminder cancelled");
        guide.guide(guide.pageStart, row);
    }

    /** The i key (or holding SELECT) on a programme: about it, with a reminder to set. */
    private void info(ChannelDatabase.Row channel, int position, Slot slot) {
        if (slot.programme == null) guide.panels.programmeInfo(channel, position);
        else guide.panels.programme(channel, position, slot.programme);
    }

    /**
     * The programme cells across the 90-minute window, sized by how much of it each programme
     * fills, with an empty cell in any gap; one cell when there is no guide for the channel. A
     * programme with a reminder set is marked with a clock.
     */
    private List<TextView> programmeCells(
        ChannelDatabase.Row channel,
        List<ChannelDatabase.Programme> listing,
        long windowStart,
        Set<String> reminders
    ) {
        List<TextView> out = new ArrayList<>();
        long windowEnd = windowStart + LiveTvScreen.WINDOW_MS;
        if (listing.isEmpty()) {
            out.add(
                cell(
                    (channel.group.isEmpty() ? "" : channel.group + "  ·  ") + "No programme information",
                    new Slot(null, windowStart, windowEnd)
                )
            );
            return out;
        }
        long at = windowStart;
        for (ChannelDatabase.Programme item : listing) {
            long from = Math.max(item.start, windowStart),
                to = Math.min(item.stop, windowEnd);
            if (to <= at) continue;
            if (from > at + 60000) out.add(cell("", new Slot(null, at, from)));
            from = Math.max(from, at);
            // Arrows show a programme starting before or running past the slots shown.
            String title =
                (reminders.contains(channel.key + "@" + item.start) ? "⏰ " : "") +
                (item.start < windowStart ? "‹ " : "") +
                item.title +
                (item.stop > windowEnd ? "  ›" : "");
            out.add(cell(title, new Slot(item, from, to)));
            at = to;
            if (at >= windowEnd) break;
        }
        if (windowEnd - at > 60000) out.add(cell("", new Slot(null, at, windowEnd)));
        return out;
    }

    private TextView cell(String title, Slot slot) {
        TextView item = text(title, 17);
        item.setPadding(activity.dp(8), 0, activity.dp(4), 0);
        item.setTag(slot);
        item.setContentDescription(
            slot.programme == null ? "No programme information" : slot.programme.title
        );
        item.setLayoutParams(
            new LinearLayout.LayoutParams(0, -1, Math.max(1f, (slot.to - slot.from) / 60000f))
        );
        return item;
    }

    private TextView text(String value, int size) {
        TextView label = activity.compactText(value, size);
        label.setTextColor(0xffffffff);
        label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setIncludeFontPadding(false);
        // Fit the available row height without shrinking long titles to fit their width.
        // Width still ellipsizes, so channels in one column keep the same readable size.
        label.addOnLayoutChangeListener(
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                float available = bottom - top - label.getPaddingTop() - label.getPaddingBottom();
                android.graphics.Paint.FontMetrics metrics = label.getPaint().getFontMetrics();
                float height = metrics.descent - metrics.ascent;
                if (available <= 0 || height <= 0) return;
                float maximum = size * label.getResources().getDisplayMetrics().scaledDensity;
                float desired = Math.min(maximum, (label.getTextSize() * available) / height);
                if (Math.abs(desired - label.getTextSize()) > 0.5f) label.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    desired
                );
            }
        );
        return label;
    }

    private GradientDrawable box(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(3));
        shape.setStroke(activity.dp(1), 0x55ffffff);
        return shape;
    }
}
