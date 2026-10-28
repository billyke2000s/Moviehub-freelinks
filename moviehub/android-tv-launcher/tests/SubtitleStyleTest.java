package local.tv.home;

import java.util.Arrays;

/** Subtitle size and background choices (SubtitleStyle). */
public final class SubtitleStyleTest {

    private static int checks;

    public static void main(String[] args) {
        same("normal default", 100, SubtitleStyle.percent(0));
        same("damaged value", 100, SubtitleStyle.percent(999));
        same("large kept", 130, SubtitleStyle.percent(130));
        same("index of extra large", 3, SubtitleStyle.index(160));
        same("index of unknown", 1, SubtitleStyle.index(-5));
        same("name", "Small", SubtitleStyle.name(75));
        same(
            "VLC options with background",
            Arrays.asList("--sub-text-scale=130", "--freetype-background-opacity=160"),
            SubtitleStyle.vlcOptions(130, true)
        );
        same(
            "VLC options without background",
            Arrays.asList("--sub-text-scale=100", "--freetype-background-opacity=0"),
            SubtitleStyle.vlcOptions(42, false)
        );
        same("Media3 size", 0.08f, SubtitleStyle.exoFraction(0.0533f, 160) > 0.079f ? 0.08f : -1f);
        same("Media3 normal size unchanged", 0.0533f, SubtitleStyle.exoFraction(0.0533f, 100));
        same("Media3 background", 0xA0000000, SubtitleStyle.exoBackground(true));
        same("Media3 no background", 0, SubtitleStyle.exoBackground(false));
        System.out.println(checks + " subtitle style checks passed");
    }

    private static void same(String name, Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(
            "Subtitle style " + checks + " (" + name + "): " + actual
        );
    }
}
