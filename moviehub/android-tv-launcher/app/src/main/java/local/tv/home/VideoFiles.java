package local.tv.home;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses the file to play from a release with several files: the film beside samples and
 * extras, or one episode in a season pack. Suggestions never auto-play a different episode.
 *
 * <p>Episode scores: {@link #EXACT} when the name says exactly this season and episode (the only
 * kind that can play straight away), {@link #LIKELY} when it very probably is this episode but
 * the name is less certain (a bare "03 - Title", a double episode), {@link #UNKNOWN} when the name
 * says nothing usable, and -1 when it is another episode or not a video. A file is never
 * suggested when its name could mean something else.
 */
final class VideoFiles {

    static final int EXACT = 2,
        LIKELY = 1,
        UNKNOWN = 0;

    static final Pattern VIDEO = Pattern.compile(
        "(?i).*\\.(mkv|mk3d|mp4|m4v|mov|qt|avi|divx|xvid|webm|wmv|asf|mpg|mpeg|mpe|m1v|m2v|mpv|m2p|vob|vro|mod|tod|ts|mts|m2ts|m2t|mxf|flv|f4v|f4p|ogv|ogm|rm|rmvb|3gp|3g2|3gpp|3gpp2|amv|nsv|nut|wtv|dvr-ms|bik|bink|dv|mjpeg|mjpg|h264|264|h265|265|hevc|av1)$"
    );
    static final Pattern JUNK = Pattern.compile("(?i)\\bsample\\b|\\btrailer\\b|\\bextras?\\b|featurette");
    private static final Pattern SAMPLE = Pattern.compile("(?i)\\bsample\\b");

    private static final Pattern MOVIE_EXTRA = Pattern.compile(
        "(?:^| )(?:samples?|trailers?|extras?|deleted scenes|behind the scenes|making of|bonus|interviews?|featurettes?)(?: |$)"
    );

    /** Digits that are a resolution ("720p", "1080i") rather than an episode. */
    private static final String NOT_RESOLUTION = "(?!\\d)(?![pi](?![a-z]))";

    /**
     * "S01E02", "S1E2", "S01.E02", "S01 E02", "S01Ep02", "S01 Episode 02"; more episodes may follow:
     * "S01E01E02", "S01E01-E02", "S01E01-02".
     */
    private static final Pattern SEASON_EPISODE = Pattern.compile(
        "(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]*e(?:p(?:isode)?)?[ ._-]*(\\d{1,3})" +
            NOT_RESOLUTION +
            "((?:[ ._]*(?:-[ ._]*e?|e)\\d{1,3}" +
            NOT_RESOLUTION +
            ")*)"
    );
    private static final Pattern MORE_EPISODES = Pattern.compile("(?i)(-?)[ ._]*e?(\\d{1,3})");
    /** "1x02", "01x02" (not "1920x1080" or "x264"). */
    private static final Pattern CROSS = Pattern.compile("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{1,3})(?!\\d)");
    /** "Season 1", "Series 2", "Season.01". */
    private static final Pattern SEASON_WORD = Pattern.compile(
        "(?i)(?<![a-z0-9])(?:season|series)[ ._-]*(\\d{1,2})(?!\\d)"
    );
    /** A season on its own: "Show.S01.1080p", "S02" (not "S01E02"). */
    private static final Pattern SEASON_TAG = Pattern.compile(
        "(?i)(?<![a-z0-9])s(\\d{1,2})(?!\\d)(?![ ._-]*e(?:p(?:isode)?)?[ ._-]*\\d)"
    );
    /** An episode on its own: "E02", "Ep 2", "Episode.02" (not "Part 2", often a title). */
    private static final Pattern EPISODE_WORD = Pattern.compile(
        "(?i)(?<![a-z0-9])(?:episode|ep|e)[ ._-]*(\\d{1,3})" + NOT_RESOLUTION
    );
    /** Bracketed notes, years, resolutions, codecs, bit depths and audio channels: numbers that are not episodes. */
    private static final Pattern NOISE = Pattern.compile(
        "(?i)\\[[^]]*]|\\([^)]*\\)|(?<!\\d)(?:19|20)\\d\\d(?!\\d)|\\d{3,4}[pi](?![a-z])|[xh][ .]?26[45]|\\d+[ ._-]?bits?|(?<![a-z])(?:ddp?|aac|dts|ac3|eac3|truehd|atmos|flac|opus|mp3)[ ._-]?\\d(?:[ .]\\d)?|\\d\\.\\d(?!\\d)"
    );
    private static final Pattern NUMBER = Pattern.compile(
        "(?<![a-z0-9])\\d{1,4}(?![a-z0-9])",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * How well {@code path} fits. With an episode wanted ({@code season} at least 0 and {@code
     * episode} above 0) see the class notes; for a film: 1 for any video, -1 for samples and
     * extras. A file named {@code hint} scores 3.
     */
    static int score(String path, String hint, int season, int episode) {
        if (!isVideo(path)) return -1;
        boolean wantEpisode = season >= 0 && episode > 0;
        boolean junk = JUNK.matcher(path).find();
        if (!wantEpisode && junk) return -1;
        if (SAMPLE.matcher(path).find()) return -1;
        String normal = path.replace('\\', '/');
        String file = normal.substring(normal.lastIndexOf('/') + 1);
        if (
            hint != null &&
            !hint.isEmpty() &&
            file.equalsIgnoreCase(hint.substring(hint.lastIndexOf('/') + 1))
        ) return 3;
        if (!wantEpisode) return 1;
        String name = file.substring(0, file.lastIndexOf('.'));
        String[] parts = normal.split("/");
        // 1. The file name gives season and episode: it decides. Under a trailer, extras or
        // featurette name it is only suggested (a show can be called "Extras"), never played.
        int[] named = seasonAndEpisodes(name);
        if (named != null) {
            int judged = judge(named, season, episode);
            return junk ? Math.min(judged, LIKELY) : judged;
        }
        // Otherwise trailers and extras are left out.
        if (junk) return -1;
        // 2. A plain file ("video.mkv") in a folder named for one episode ("Show.S01E02.1080p").
        if (parts.length >= 2 && !EPISODE_WORD.matcher(name).find() && numbers(name).isEmpty()) {
            int[] folder = seasonAndEpisodes(parts[parts.length - 2]);
            if (folder != null) return judge(folder, season, episode);
        }
        // 3. The season, from the name or the nearest folder that gives one.
        int knownSeason = seasonOnly(name);
        for (int i = parts.length - 2; i >= 0 && knownSeason < 0; i--) knownSeason = seasonOnly(parts[i]);
        if (knownSeason >= 0 && knownSeason != season) return -1;
        // 4. "E03", "Episode 3", "Part 3".
        Matcher word = EPISODE_WORD.matcher(name);
        if (word.find()) {
            int found = Integer.parseInt(word.group(1));
            if (found != episode) return -1;
            // Exact only with the season known and no other number that could be the episode.
            boolean alone = numbers(
                name.substring(0, word.start()) + " " + name.substring(word.end())
            ).isEmpty();
            return knownSeason == season && alone ? EXACT : LIKELY;
        }
        // 5. A bare number: "03 - Title" or "Show - 03" (episode), "Show 103" (season 1, episode 3).
        List<Integer> numbers = numbers(name);
        if (numbers.size() == 1) {
            int found = numbers.get(0);
            if (found == episode) return LIKELY;
            if (season > 0 && episode < 100 && found == season * 100 + episode) return LIKELY;
        }
        return UNKNOWN;
    }

    /** Common video containers and elementary streams; recognition does not guarantee decoding. */
    static boolean isVideo(String path) {
        return path != null && VIDEO.matcher(path).matches();
    }

    /** Rank locally from the returned manifest, without probing or downloading any file. */
    static int recommendMovie(List<String> paths, List<Long> sizes, String title) {
        int best = -1;
        boolean bestTitle = false;
        long bestSize = -1;
        String wanted = words(title == null ? "" : title);
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (!isVideo(path) || score(path, "", -1, -1) < 0 || movieExtra(path)) continue;
            String file = path.replace('\\', '/');
            file = file.substring(file.lastIndexOf('/') + 1);
            String name = words(file.substring(0, file.lastIndexOf('.')));
            boolean titleMatch = !wanted.isEmpty() && (" " + name + " ").contains(" " + wanted + " ");
            long size = i < sizes.size() && sizes.get(i) != null ? Math.max(0, sizes.get(i)) : 0;
            if (best < 0 || (titleMatch && !bestTitle) || (titleMatch == bestTitle && size > bestSize)) {
                best = i;
                bestTitle = titleMatch;
                bestSize = size;
            }
        }
        return best;
    }

    private static String words(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .trim();
    }

    private static boolean movieExtra(String path) {
        String normal = words(path);
        return MOVIE_EXTRA.matcher(normal).find();
    }

    /** Whether to mark this file as the suggested episode in the list. */
    static boolean suggested(String path, int season, int episode) {
        if (season < 0 || episode <= 0) return false;
        int score = score(path, "", season, episode);
        return score == EXACT || score == LIKELY;
    }

    /** EXACT for this one episode, LIKELY for a file holding it with others, else -1. */
    private static int judge(int[] found, int season, int episode) {
        if (found[0] != season) return -1;
        for (int i = 1; i < found.length; i++) if (found[i] == episode) return found.length == 2
            ? EXACT
            : LIKELY;
        return -1;
    }

    /** {season, episode, more episodes…} when the text names both, else null. */
    static int[] seasonAndEpisodes(String text) {
        Matcher full = SEASON_EPISODE.matcher(text);
        if (full.find()) {
            List<Integer> episodes = new ArrayList<>();
            int first = Integer.parseInt(full.group(2));
            episodes.add(first);
            Matcher more = MORE_EPISODES.matcher(full.group(3));
            int last = first;
            while (more.find()) {
                int next = Integer.parseInt(more.group(2));
                // "-" between two episodes is a range ("S01E01-03"); only sensible ones count.
                if (next <= last || next > first + 20) break;
                if (!more.group(1).isEmpty()) for (int e = last + 1; e < next; e++) episodes.add(e);
                episodes.add(next);
                last = next;
            }
            return pack(Integer.parseInt(full.group(1)), episodes);
        }
        Matcher cross = CROSS.matcher(text);
        while (cross.find()) {
            String after = cross.group(2);
            if (after.startsWith("26") && after.length() == 3) continue; // x264, x265
            return pack(Integer.parseInt(cross.group(1)), Collections.singletonList(Integer.parseInt(after)));
        }
        Matcher season = SEASON_WORD.matcher(text);
        if (season.find()) {
            Matcher episode = EPISODE_WORD.matcher(text.substring(season.end()));
            if (episode.find()) return pack(
                Integer.parseInt(season.group(1)),
                Collections.singletonList(Integer.parseInt(episode.group(1)))
            );
        }
        return null;
    }

    private static int[] pack(int season, List<Integer> episodes) {
        int[] out = new int[episodes.size() + 1];
        out[0] = season;
        for (int i = 0; i < episodes.size(); i++) out[i + 1] = episodes.get(i);
        return out;
    }

    /** The season this text names on its own ("Season 2", "S02"), or -1. */
    static int seasonOnly(String text) {
        Matcher word = SEASON_WORD.matcher(text);
        if (word.find()) return Integer.parseInt(word.group(1));
        Matcher tag = SEASON_TAG.matcher(text);
        return tag.find() ? Integer.parseInt(tag.group(1)) : -1;
    }

    /** The plain numbers in a name once seasons, years, resolutions, codecs and the like are removed. */
    private static List<Integer> numbers(String name) {
        String clean = NOISE.matcher(name).replaceAll(" ");
        clean = SEASON_WORD.matcher(clean).replaceAll(" ");
        clean = SEASON_TAG.matcher(clean).replaceAll(" ");
        List<Integer> out = new ArrayList<>();
        Matcher number = NUMBER.matcher(clean);
        while (number.find()) out.add(Integer.parseInt(number.group()));
        return out;
    }

    /**
     * The file to play straight away, or -1 to let the user choose. For an episode: the only
     * file that names exactly that episode. For a movie: the only video file, or one at least
     * three times larger than any other (the film beside samples or extras).
     */
    static int autoPick(List<String> paths, List<Long> sizes, int season, int episode) {
        boolean wantEpisode = season >= 0 && episode > 0;
        int best = -1,
            matches = 0;
        long bestSize = -1,
            secondSize = -1;
        for (int i = 0; i < paths.size(); i++) {
            int score = score(paths.get(i), "", season, episode);
            if (score < (wantEpisode ? EXACT : 1)) continue;
            matches++;
            long size = i < sizes.size() && sizes.get(i) != null ? sizes.get(i) : 0;
            if (best < 0 || size > bestSize) {
                secondSize = bestSize;
                best = i;
                bestSize = size;
            } else if (size > secondSize) secondSize = size;
        }
        if (matches == 1) return best;
        if (wantEpisode || matches == 0) return -1;
        return bestSize > 0 && bestSize >= secondSize * 3 ? best : -1;
    }
}
