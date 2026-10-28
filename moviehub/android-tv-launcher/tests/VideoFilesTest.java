package local.tv.home;

public class VideoFilesTest {

    private static int checks;

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    /** E: exact (may play straight away), L: likely (suggested only), N: not suggested. */
    private static void episode(String path, int season, int episode, char expected) {
        int score = VideoFiles.score(path, "", season, episode);
        char got = score == VideoFiles.EXACT ? 'E' : score == VideoFiles.LIKELY ? 'L' : 'N';
        check(
            got == expected,
            path + " for S" + season + "E" + episode + ": expected " + expected + ", got " + got
        );
        check(
            VideoFiles.suggested(path, season, episode) == (expected != 'N'),
            path + ": suggested() agrees with score()"
        );
    }

    public static void main(String[] args) {
        // The original rules.
        check(VideoFiles.score("Show.S01E02.mkv", "", 1, 2) == 2, "S01E02");
        check(VideoFiles.score("Show.S01E20.mkv", "", 1, 2) == -1, "S01E20 is not episode 2");
        check(VideoFiles.score("sample.mkv", "", -1, -1) == -1, "sample");
        check(VideoFiles.score("film.mp4", "film.mp4", -1, -1) == 3, "hint");
        check(VideoFiles.score("Show.1x02.mkv", "", 1, 2) == 2, "1x02");
        check(VideoFiles.score("subtitle.srt", "", 1, 2) == -1, "not a video");
        java.util.List<String> paths = java.util.Arrays.asList(
            "Film/Sample/sample.mkv",
            "Film/film.mkv",
            "Film/extra.mkv"
        );
        java.util.List<Long> sizes = java.util.Arrays.asList(50L, 9000L, 800L);
        check(VideoFiles.autoPick(paths, sizes, -1, -1) == 1, "film beside extras");
        java.util.List<String> parts = java.util.Arrays.asList("Film/cut.one.mkv", "Film/cut.two.mkv");
        check(
            VideoFiles.autoPick(parts, java.util.Arrays.asList(900L, 800L), -1, -1) == -1,
            "two similar files: ask"
        );
        java.util.List<String> season = java.util.Arrays.asList("S01E01.mkv", "S01E02.mkv", "S01E03.mkv");
        java.util.List<Long> same = java.util.Arrays.asList(1L, 1L, 1L);
        check(VideoFiles.autoPick(season, same, 1, 2) == 1, "episode in a pack");
        check(VideoFiles.autoPick(season, same, 1, 5) == -1, "missing episode: ask");

        // Exact: the name says this season and episode.
        episode("Show.Name.S01E03.1080p.WEB-DL.x264.mkv", 1, 3, 'E');
        episode("show.name.s1e3.720p.mkv", 1, 3, 'E');
        episode("Show Name - S01 E03 - Title.mkv", 1, 3, 'E');
        episode("Show.Name.S01.E03.mkv", 1, 3, 'E');
        episode("Show Name S01Ep03.mkv", 1, 3, 'E');
        episode("Show Name S01 Episode 03.mkv", 1, 3, 'E');
        episode("Show Name 1x03 Title.mkv", 1, 3, 'E');
        episode("Show Name Season 1 Episode 3.mp4", 1, 3, 'E');
        episode("Show Name - Series 2 - Ep 3.mkv", 2, 3, 'E');
        episode("Pack/Season 1/E03 - Title.mkv", 1, 3, 'E');
        episode("Show.S02.1080p/Show - Episode 03.mkv", 2, 3, 'E');
        episode("Show.S01E03.1080p.WEB/video.mkv", 1, 3, 'E');
        episode("Show.S00E01.Special.mkv", 0, 1, 'E');
        episode("Show.S10E100.mkv", 10, 100, 'E');
        // Shows with numbers in their names.
        episode("The.100.S02E01.mkv", 2, 1, 'E');
        episode("9-1-1.S01E02.mkv", 1, 2, 'E');
        episode("24.S03E02.mkv", 3, 2, 'E');
        episode("1923.S01E04.2160p.mkv", 1, 4, 'E');

        // Likely: suggested for you to confirm, never played straight away.
        episode("Show.S01E01E02.mkv", 1, 2, 'L');
        episode("Show.S01E01-E02.mkv", 1, 1, 'L');
        episode("Show.S01E01-03.mkv", 1, 2, 'L');
        episode("Pack/Season 1/03 - Title.mkv", 1, 3, 'L');
        episode("Show Name - 03.mkv", 1, 3, 'L');
        episode("Show Name 103.mkv", 1, 3, 'L');
        episode("Show (2019) - 03 [1080p].mkv", 1, 3, 'L');
        episode("Pack/E03.mkv", 1, 3, 'L');
        episode("Pack/Season 1/E03 - Part 2 of 2.mkv", 1, 3, 'L');
        episode("Extras.S01E02.mkv", 1, 2, 'L');
        episode("Show.S01/Featurettes/Show.S01E02.Behind.the.Scenes.mkv", 1, 2, 'L');

        // Not suggested: another episode or season, or a name that could mean something else.
        episode("Show.S01E30.mkv", 1, 3, 'N');
        episode("Show.S11E03.mkv", 1, 3, 'N');
        episode("Show.S02E03.mkv", 1, 3, 'N');
        episode("Show.S01E03.mkv", 1, 30, 'N');
        episode("Show.S01E01-720p.mkv", 1, 720, 'N');
        episode("Show.S01E01.1080p.mkv", 1, 1080, 'N');
        episode("Show.1920x1080.mkv", 19, 20, 'N');
        episode("Show.Name.x264.mkv", 1, 264, 'N');
        episode("Pack/Season 2/E03.mkv", 1, 3, 'N');
        episode("Pack/Season 2/03 - Title.mkv", 1, 3, 'N');
        episode("Show Name - 04.mkv", 1, 3, 'N');
        episode("05 - The Finale Pt 2.mkv", 1, 2, 'N');
        episode("Show 2 - 03.mkv", 1, 3, 'N');
        episode("Show.Name.2019.1080p.mkv", 1, 19, 'N');
        episode("Show.5.1.Audio.mkv", 1, 5, 'N');
        episode("Show.S01E03.Sample.mkv", 1, 3, 'N');
        episode("Show.S01E03.srt", 1, 3, 'N');
        episode("Pack/Season 1/Trailer.mkv", 1, 3, 'N');
        episode("video.mkv", 1, 3, 'N');

        // A season pack: plays the one exact file; suggests but never plays a likely one.
        java.util.List<String> pack = java.util.Arrays.asList(
            "Show.S01/Show.S01E01.mkv",
            "Show.S01/Show.S01E02.mkv",
            "Show.S01/Show.S01E02.Sample.mkv",
            "Show.S01/Show.S01E03.mkv"
        );
        java.util.List<Long> four = java.util.Arrays.asList(9L, 9L, 1L, 9L);
        check(VideoFiles.autoPick(pack, four, 1, 2) == 1, "the one exact file plays");
        java.util.List<String> bare = java.util.Arrays.asList("Pack/01.mkv", "Pack/02.mkv", "Pack/03.mkv");
        check(VideoFiles.autoPick(bare, same, 1, 2) == -1, "a likely file is only suggested");
        check(VideoFiles.suggested("Pack/02.mkv", 1, 2), "and it is marked");
        check(!VideoFiles.suggested("Show.S01E02.mkv", -1, -1), "films are never marked");
        // Movie manifest recognition and recommendation never fetch media bytes.
        String formats =
            "mkv mk3d mp4 m4v mov qt avi divx xvid webm wmv asf mpg mpeg mpe m1v m2v mpv m2p vob vro mod tod ts mts m2ts m2t mxf flv f4v f4p ogv ogm rm rmvb 3gp 3g2 3gpp 3gpp2 amv nsv nut wtv dvr-ms bik bink dv mjpeg mjpg h264 264 h265 265 hevc av1";
        for (String format : formats.split(" "))
            check(VideoFiles.isVideo("Folder/Film." + format.toUpperCase()), "video format " + format);
        for (String other : new String[] {
            "srt",
            "ass",
            "nfo",
            "jpg",
            "txt",
            "exe",
            "zip",
            "rar",
            "iso",
            "mp3",
            "flac",
            "ogg",
            "mkv.exe",
        })
            check(!VideoFiles.isVideo("Film." + other), "not a video: " + other);
        check(!VideoFiles.isVideo(null), "null name");
        check(
            VideoFiles.recommendMovie(
                java.util.Arrays.asList("Film.srt", "Film_sample.mp4", "Film.M2TS", "Bonus/interview.mov"),
                java.util.Arrays.asList(1L, 100L, 900L, 1000L),
                "Film"
            ) == 2,
            "movie beside subtitles and larger extras"
        );
        check(
            VideoFiles.recommendMovie(
                java.util.Arrays.asList("Other.Movie.mkv", "The.Film.2024.avi"),
                java.util.Arrays.asList(9000L, 800L),
                "The Film"
            ) == 1,
            "requested title outranks unrelated larger video"
        );
        check(
            VideoFiles.recommendMovie(
                java.util.Arrays.asList("a.mkv", "b.mp4"),
                java.util.Arrays.asList(800L, 900L),
                "Film"
            ) == 1,
            "largest video when names give no title"
        );
        check(
            VideoFiles.recommendMovie(
                java.util.Arrays.asList("Film_sample.mkv", "Extras\\bonus.mp4", "readme.txt"),
                java.util.Collections.emptyList(),
                "Film"
            ) == -1,
            "no recommendation for only extras or documents"
        );
        check(
            VideoFiles.recommendMovie(
                java.util.Arrays.asList("a.mkv", "b.mp4"),
                java.util.Arrays.asList(800L, 800L),
                ""
            ) == 0,
            "stable choice for equal files"
        );
        check(
            VideoFiles.recommendMovie(
                java.util.Arrays.asList("Folder\\Le.Film.MOV", "other.mp4"),
                java.util.Arrays.asList(null, -1L),
                "Le Film"
            ) == 0,
            "Windows paths and missing sizes"
        );
        check(
            VideoFiles.recommendMovie(
                java.util.Arrays.asList("Film.mkv", "big.mp4"),
                java.util.Collections.emptyList(),
                "Film"
            ) == 0,
            "title works without sizes"
        );
        check(
            VideoFiles.recommendMovie(
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                "Film"
            ) == -1,
            "empty manifest"
        );
        check(
            EpisodeOrder.next(
                java.util.Arrays.asList(
                    new EpisodeOrder.Episode(3, "2020-01-03"),
                    new EpisodeOrder.Episode(2, "2020-01-02")
                ),
                1,
                "2026-10-22"
            ) == 2,
            "episode order is numeric"
        );
        check(
            EpisodeOrder.next(
                java.util.Arrays.asList(
                    new EpisodeOrder.Episode(2, "2099-01-01"),
                    new EpisodeOrder.Episode(3, "2020-01-01")
                ),
                1,
                "2026-10-22"
            ) == -1,
            "never skip an unaired episode"
        );
        check(
            EpisodeOrder.next(java.util.Arrays.asList(new EpisodeOrder.Episode(2, null)), 1, "2026-10-22") ==
                -1,
            "unknown air date needs confirmation"
        );
        check(
            EpisodeOrder.laterSeasons(java.util.Arrays.asList(0, 3, 2, 2, 1), 1).equals(
                java.util.Arrays.asList(2, 3)
            ),
            "season order excludes specials and duplicates"
        );
        check(SourceRanking.rank("1080p", 1080) > SourceRanking.rank("720p", 1080), "prefer desired quality");
        check(SourceRanking.rank("720p", 1080) > SourceRanking.rank("4K", 1080), "respect preferred ceiling");
        check(SourceRanking.rank("4K", 2160) > SourceRanking.rank("1080p", 2160), "4K preference works");
        check(SourceRanking.resolution("WEB-DL 2160P") == 2160, "resolution case insensitive");
        System.out.println(checks + " episode/file checks passed");
    }
}
