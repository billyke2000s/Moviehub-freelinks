package local.tv.home;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.widget.ImageView;

/**
 * Full-bleed background artwork, as streaming apps show behind the featured title.
 *
 * <ul>
 *   <li>Fills the view and is anchored to the top-right, so the subject is not cropped by the text
 *       side, instead of being centre-cropped.
 *   <li>Cross-fades from the previous image to the next instead of snapping.
 *   <li>Keeps the previous image while the next one loads, and ignores the loader's placeholder
 *       colour, so a solid block never flashes behind the page. Use {@link #clear()} to remove it.
 * </ul>
 */
final class BackdropView extends ImageView {

    private static final int FADE_MILLIS = 450;

    BackdropView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
    }

    /** Removes the artwork. */
    void clear() {
        super.setImageDrawable(null);
    }

    @Override
    public void setBackgroundColor(int colour) {
        // The artwork loader sets a placeholder colour; a full-screen backdrop stays transparent.
    }

    /**
     * ImageView.setImageBitmap reuses one internal drawable and clears the current image before
     * setting it, which would hide the previous artwork from the cross-fade. Each bitmap therefore
     * gets its own drawable here.
     */
    @Override
    public void setImageBitmap(android.graphics.Bitmap bitmap) {
        setImageDrawable(
            bitmap == null ? null : new android.graphics.drawable.BitmapDrawable(getResources(), bitmap)
        );
    }

    @Override
    public void setImageDrawable(Drawable next) {
        if (next == null) return; // keep showing the current artwork until the next one arrives
        Drawable current = getDrawable();
        if (current instanceof TransitionDrawable) current = ((TransitionDrawable) current).getDrawable(1);
        if (current == null || current == next) {
            super.setImageDrawable(next);
            if (current == null) {
                setAlpha(0f);
                animate().alpha(1f).setDuration(FADE_MILLIS).start();
            }
        } else {
            TransitionDrawable fade = new TransitionDrawable(new Drawable[] { current, next });
            fade.setCrossFadeEnabled(true);
            super.setImageDrawable(fade);
            fade.startTransition(FADE_MILLIS);
        }
        alignTopRight();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        alignTopRight();
    }

    /** Scales the image to cover the view and pins it to the top-right corner. */
    private void alignTopRight() {
        Drawable image = getDrawable();
        int width = getWidth(),
            height = getHeight();
        if (image == null || width == 0 || height == 0) return;
        int imageWidth = image.getIntrinsicWidth(),
            imageHeight = image.getIntrinsicHeight();
        if (imageWidth <= 0 || imageHeight <= 0) return;
        float scale = Math.max(width / (float) imageWidth, height / (float) imageHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(width - imageWidth * scale, 0);
        setImageMatrix(matrix);
    }
}
