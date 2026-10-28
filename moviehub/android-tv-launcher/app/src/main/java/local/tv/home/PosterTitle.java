package local.tv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.*;
import android.view.Gravity;
import android.widget.TextView;

/** Two lines at rest; an overflowing focused title scrolls without depending on platform marquee state. */
final class PosterTitle extends TextView {

    private boolean cardFocused, scrolling;
    private long started;
    private String fullTitle = "";

    PosterTitle(Context context) {
        super(context);
        setTextSize(15);
        setTextColor(0xfff2f4f8);
        setIncludeFontPadding(false);
        setGravity(Gravity.TOP);
        setPadding(0, 0, 0, 0);
        rest();
    }

    private void rest() {
        scrolling = false;
        setSelected(false);
        setSingleLine(false);
        setMaxLines(2);
        setEllipsize(TextUtils.TruncateAt.END);
        setHorizontallyScrolling(false);
        invalidate();
    }

    void cardFocus(boolean focused) {
        cardFocused = focused;
        rest();
        if (focused) post(this::scrollIfNeeded);
    }

    private void scrollIfNeeded() {
        if (!cardFocused || getWidth() <= 0) return;
        StaticLayout layout = StaticLayout.Builder.obtain(getText(), 0, length(), getPaint(), getWidth())
            .setIncludePad(false)
            .build();
        if (layout.getLineCount() > 2) {
            fullTitle = getText().toString().replace('\n', ' ');
            setSingleLine(true);
            setEllipsize(null);
            setSelected(true);
            scrolling = true;
            started = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!scrolling) {
            super.onDraw(canvas);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float width = getPaint().measureText(fullTitle),
            gap = 48 * density,
            speed = 28 * density;
        long travel = (long) (((width + gap) / speed) * 1000),
            cycle = 1200 + travel;
        long elapsed = (SystemClock.uptimeMillis() - started) % Math.max(1, cycle);
        float x = elapsed <= 1200 ? 0 : (-(elapsed - 1200) * speed) / 1000;
        getPaint().setColor(getCurrentTextColor());
        float baseline = -getPaint().getFontMetrics().ascent;
        int save = canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        canvas.drawText(fullTitle, x, baseline, getPaint());
        canvas.drawText(fullTitle, x + width + gap, baseline, getPaint());
        canvas.restoreToCount(save);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        cardFocused = false;
        rest();
        super.onDetachedFromWindow();
    }
}
