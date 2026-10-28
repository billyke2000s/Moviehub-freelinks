package local.tv.home;

public final class VideoDetailsTest {

    private static int checks;

    private static void same(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "Video details " + checks + ": expected [" + expected + "] got [" + actual + "]"
            );
        }
        checks++;
    }

    public static void main(String[] args) {
        same(
            "1920×1080 (1080p) · 50 fps · H.264 · 4.8 Mb/s",
            VideoDetails.describe(1920, 1080, 50f, "video/avc", 4_800_000)
        );
        same(
            "1280×720 (720p) · 29.97 fps · H.265 · 800 kb/s",
            VideoDetails.describe(1280, 720, 29.97f, "video/hevc", 800_000)
        );
        same("1920×1080 (1080p) · 59.94 fps", VideoDetails.describe(1920, 1080, 59.94f, "", 0));
        // A wide film frame is still called 1080p; VLC four-character codes are named too.
        same("1920×800 (1080p) · H.264", VideoDetails.describe(1920, 800, 0, "h264", 0));
        same(
            "3840×2160 (4K) · 60 fps · VP9",
            VideoDetails.describe(3840, 2160, 60f, "video/x-vnd.on2.vp9", -1)
        );
        same("720×576 (576p) · 25 fps · MPEG-2", VideoDetails.describe(720, 576, 25f, "mpgv", 0));
        same("640×360 (360p)", VideoDetails.describe(640, 360, 0, null, 0));
        // No picture yet: nothing is guessed.
        same("", VideoDetails.describe(0, 0, 25f, "h264", 1_000_000));
        same("", VideoDetails.codecName("video/unknown"));
        same("1440p", VideoDetails.quality(2560, 1440));
        same("480p", VideoDetails.quality(854, 480));
        // The numeric tier used to compare a source's label with its real picture.
        same("480", String.valueOf(VideoDetails.tier(854, 480)));
        same("1080", String.valueOf(VideoDetails.tier(1920, 800)));
        same("2160", String.valueOf(VideoDetails.tier(3840, 1600)));
        same("240", String.valueOf(VideoDetails.tier(320, 240)));
        same("0", String.valueOf(VideoDetails.tier(0, 0)));
        System.out.println(checks + " picture-detail checks passed");
    }
}
