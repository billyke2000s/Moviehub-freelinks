package local.tv.home;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

/**
 * The typeface for the Live TV guide and the full-screen Live TV overlay: Hind (SIL Open Font
 * License, in assets/fonts), a clean humanist sans in the spirit of the satellite box's own
 * lettering, which cannot be used. Falls back to the system font if the files cannot be read.
 */
final class SkyFont {

    private static Typeface regular, bold;

    private SkyFont() {}

    private static synchronized void load(Context context) {
        if (regular != null) return;
        try {
            regular = Typeface.createFromAsset(context.getAssets(), "fonts/Hind-Regular.ttf");
            bold = Typeface.createFromAsset(context.getAssets(), "fonts/Hind-SemiBold.ttf");
        } catch (RuntimeException e) {
            Diagnostics.recovered("Loading the guide font", e);
            regular = Typeface.DEFAULT;
            bold = Typeface.DEFAULT_BOLD;
        }
    }

    /** Sets the guide typeface on {@code text}. */
    static void apply(TextView text, boolean strong) {
        load(text.getContext());
        text.setTypeface(strong ? bold : regular);
        text.setIncludeFontPadding(false);
    }
}
