package local.tv.home;

import java.util.Locale;

/**
 * One line describing the picture playing, for the "i" information panel, such as
 * "1920×1080 (1080p) · 50 fps · H.264 · 4.8 Mb/s". Both Live TV players use it, so the panel reads
 * the same whichever player is in the build. Unknown parts are left out rather than guessed.
 */
final class VideoDetails {

    private VideoDetails() {}

    /**
     * {@code fps} and {@code bitsPerSecond} are ignored when zero or less; {@code codec} may be a
     * MIME type ("video/avc"), a VLC four-character code ("h264") or null.
     */
    static String describe(int width, int height, float fps, String codec, int bitsPerSecond) {
        if (width <= 0 || height <= 0) return "";
        StringBuilder text = new StringBuilder();
        text.append(width).append('×').append(height);
        text.append(" (").append(quality(width, height)).append(')');
        if (fps > 0) text.append(" · ").append(frameRate(fps));
        String name = codecName(codec);
        if (!name.isEmpty()) text.append(" · ").append(name);
        if (bitsPerSecond > 0) text.append(" · ").append(bitrate(bitsPerSecond));
        return text.toString();
    }

    /**
     * The usual name for the picture size. A wide film frame (1920×800) still counts as 1080p, so
     * the width decides when it implies a taller 16:9 picture than the height does.
     */
    static String quality(int width, int height) {
        int tier = tier(width, height);
        return tier >= 2160 ? "4K" : tier + "p";
    }

    /**
     * The same rule as a number for comparisons: 2160, 1440, 1080, 720, 576, 480, or the height
     * itself below that. 854×480 is 480; 1920×800 is 1080. 0 when the size is unknown.
     */
    static int tier(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        int lines = Math.max(height, Math.round((width * 9f) / 16f));
        if (lines >= 2100) return 2160;
        if (lines >= 1400) return 1440;
        if (lines >= 1050) return 1080;
        if (lines >= 700) return 720;
        if (lines >= 560) return 576;
        if (lines >= 470) return 480;
        return height;
    }

    /** "50 fps", or "29.97 fps" when the rate is not a whole number. */
    static String frameRate(float fps) {
        long whole = Math.round(fps);
        if (Math.abs(fps - whole) < 0.01f) return whole + " fps";
        return String.format(Locale.ROOT, "%.2f fps", fps);
    }

    static String codecName(String codec) {
        if (codec == null) return "";
        String c = codec.toLowerCase(Locale.ROOT).trim();
        if (c.isEmpty()) return "";
        if (c.contains("avc") || c.contains("h264")) return "H.264";
        if (c.contains("hevc") || c.contains("h265") || c.contains("hvc1")) return "H.265";
        if (c.contains("av01") || c.contains("av1")) return "AV1";
        if (c.contains("vp9") || c.contains("vp90")) return "VP9";
        if (c.contains("vp8") || c.contains("vp80")) return "VP8";
        if (c.contains("mpeg2") || c.equals("mpgv") || c.equals("mp2v")) return "MPEG-2";
        if (c.contains("mp4v") || c.contains("mpeg4")) return "MPEG-4";
        return "";
    }

    /** "4.8 Mb/s", or "800 kb/s" below one megabit. */
    static String bitrate(int bitsPerSecond) {
        if (bitsPerSecond >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1f Mb/s", bitsPerSecond / 1_000_000f);
        }
        return Math.max(1, bitsPerSecond / 1000) + " kb/s";
    }
}
