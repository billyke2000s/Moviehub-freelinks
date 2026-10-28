package local.tv.home;

import java.util.Arrays;
import java.util.List;

/**
 * How subtitles look, chosen in Settings → Playback: their size and an optional dark background,
 * as streaming apps offer. Pure, so it is core-tested; films apply it as LibVLC options
 * ({@link PlayerOptions#film}) and Live TV to Media3's subtitle view ({@link ExoLive}).
 */
final class SubtitleStyle {

    static final String SIZE = "subtitleSize",
        BACKGROUND = "subtitleBackground";
    static final String[] NAMES = { "Small", "Normal", "Large", "Extra large" };
    /** Text size as a percentage of each player's normal size. */
    static final int[] PERCENT = { 75, 100, 130, 160 };
    static final int NORMAL = 100;
    /** How dark the background behind subtitles is, 0 (none) to 255 (solid black). */
    static final int BACKGROUND_OPACITY = 160;

    private SubtitleStyle() {}

    /** A saved size, or Normal when it is not one of the choices (an old or damaged value). */
    static int percent(int saved) {
        for (int value : PERCENT) if (value == saved) return value;
        return NORMAL;
    }

    static int index(int saved) {
        int percent = percent(saved);
        for (int i = 0; i < PERCENT.length; i++) if (PERCENT[i] == percent) return i;
        return 1;
    }

    static String name(int saved) {
        return NAMES[index(saved)];
    }

    /** LibVLC options: VLC 3's subtitle text scale, and its freetype renderer's background. */
    static List<String> vlcOptions(int saved, boolean background) {
        return Arrays.asList(
            "--sub-text-scale=" + percent(saved),
            "--freetype-background-opacity=" + (background ? BACKGROUND_OPACITY : 0)
        );
    }

    /** Media3's subtitle view size: its default fraction of the picture height, scaled. */
    static float exoFraction(float defaultFraction, int saved) {
        return (defaultFraction * percent(saved)) / 100f;
    }

    /** Media3's background colour as ARGB: transparent, or black at {@link #BACKGROUND_OPACITY}. */
    static int exoBackground(boolean background) {
        return background ? BACKGROUND_OPACITY << 24 : 0;
    }
}
