package local.tv.home;

public final class StreamRateTest {

    public static void main(String[] args) {
        StreamRate rate = new StreamRate();
        check(rate.sample(1000, 0) == 0);
        check(rate.sample(2000, 1000000) == 8);
        check(rate.sample(2100, 2000000) == 8);
        check(rate.sample(3000, 2000000) == 8);
        check(rate.sample(4000, 2000000) == 0);
        check(rate.sample(5000, 0) == 0);
        check(rate.sample(6000, 500000) == 4);
        check(SourceRanking.resolution("FHD") == 1080);
        check(SourceRanking.resolution("HD") == 720);
        check(SourceRanking.resolution("UHD") == 2160);
        check(SourceRanking.resolution("4Kings") == 480);
        check(SourceRanking.resolution("4KHDHub 1080p") == 1080);
        check(SourceRanking.resolution("1080p UHD BluRay") == 1080);
        check(SourceRanking.resolution("720p from 4khdhub.one") == 720);
        check(SourceRanking.rank("1080p HDCAM", 1080) < SourceRanking.rank("SD WEB-DL", 1080));
        check(SourceRanking.readyRank("1080p", 1080, 1, 500) > SourceRanking.readyRank("720p", 1080, 99, 1));
        check(
            SourceRanking.readyRank("1080p", 1080, 20, 500) > SourceRanking.readyRank("1080p", 1080, 2, 500)
        );
        check(SourceRanking.readyRank("1080p", 1080, 0, 50) > SourceRanking.readyRank("1080p", 1080, 0, 500));
        check(SourceRanking.readyRank("1080p", 1080, 0, 500) > SourceRanking.readyRank("4K", 1080, 99, 1));
        // A quality read from the file beats the same quality claimed by a website, at any speed,
        // but never beats a better quality.
        check(
            SourceRanking.readyRank("1080p", 1080, 0, 999, true) >
                SourceRanking.readyRank("1080p", 1080, 99, 1, false)
        );
        check(
            SourceRanking.readyRank("1080p", 1080, 0, 999, false) >
                SourceRanking.readyRank("720p", 1080, 99, 1, true)
        );
        check(
            SourceRanking.readyRank("720p", 1080, 5, 300) ==
                SourceRanking.readyRank("720p", 1080, 5, 300, false)
        );
        // Confirmed small pictures rank below plain SD; ordinary SD and unknown labels stay 480.
        check(SourceRanking.resolution("360p") == 360);
        check(SourceRanking.resolution("240p · Film.mp4") == 240);
        check(SourceRanking.resolution("480p") == 480);
        check(SourceRanking.resolution("SD") == 480);
        check(SourceRanking.resolution("Film.2019.x264") == 480);
        check(SourceRanking.resolution("1080p 360p") == 1080);
        System.out.println("28 incoming-rate and ready-source ranking checks passed");
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
}
