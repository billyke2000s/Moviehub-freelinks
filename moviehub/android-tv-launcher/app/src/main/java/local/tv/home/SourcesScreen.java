package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Stream source lookup (Torrentio, MediaFusion, Comet) and the source picker. */
final class SourcesScreen {

    private final MainActivity activity;

    static final class Binge {

        static String imdb, title, kind;
        static int season, episode;
        static JSONObject metadata;
        static JSONArray files;

        static void remember(
            String imdbId,
            int s,
            int e,
            String label,
            String type,
            JSONObject info,
            JSONArray list
        ) {
            imdb = imdbId;
            season = s;
            episode = e;
            title = label;
            kind = type;
            metadata = info;
            files = list;
        }

        static void next(MainActivity activity) {
            new SeriesPlayback(activity).next();
        }
    }

    SourcesScreen(MainActivity activity) {
        this.activity = activity;
    }

    /** "4K", "1080p", "720p" or "SD", from a source's description. */
    static String quality(String raw) {
        // Provider names such as 4KHDHub are not a video's resolution. Numeric
        // release resolution also takes precedence over a generic UHD source tag.
        int resolution = SourceRanking.resolution(release(raw));
        return resolution >= 2160 ? "4K" : resolution >= 720 ? resolution + "p" : "SD";
    }

    /**
     * A free source's quality for lists and the player's Sources menu: "480p ✓" when read from the
     * file or stream itself, the website's claim otherwise ("1080p"), with "?" when that website has
     * recently overstated quality and its claim is ignored for ranking.
     */
    static String badge(SourceRepository.Source source) {
        String claimed = quality(source.title);
        if (source.confirmed && source.ranking != null) {
            int real = SourceRanking.resolution(source.ranking);
            return (real >= 2160 ? "4K" : real + "p") + " ✓";
        }
        boolean ignored =
            source.ranking != null && quality(source.ranking).equals("SD") && !claimed.equals("SD");
        return ignored ? claimed + "?" : claimed;
    }

    /** Badges for a source: HDR or Dolby Vision, codec and file size, when the description says. */
    static List<String> details(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (
            lower.contains("dolby vision") ||
            java.util.regex.Pattern.compile("\\b(?:dv|dovi)\\b").matcher(lower).find()
        ) out.add("Dolby Vision");
        else if (lower.contains("hdr")) out.add("HDR");
        if (lower.contains("h265") || lower.contains("hevc") || lower.contains("x265")) out.add("HEVC");
        else if (lower.contains("h264") || lower.contains("x264")) out.add("H.264");
        java.util.regex.Matcher size = java.util.regex.Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s?(TB|GB|MB)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(raw);
        if (size.find()) out.add(
            size.group(1).replace(',', '.') + " " + size.group(2).toUpperCase(Locale.ROOT)
        );
        return out;
    }

    /** The provider line (the first line of the description). */
    static String provider(String raw) {
        int split = raw.indexOf('\n');
        String name = (split >= 0 ? raw.substring(0, split) : "Source").trim();
        return name.isEmpty() ? "Source" : name;
    }

    /** The release name: the description without its provider line, on one line. */
    static String release(String raw) {
        int split = raw.indexOf('\n');
        return (split >= 0 ? raw.substring(split + 1) : raw).replace('\n', ' ').trim();
    }

    void torrentSources(String imdb, int season, int episode, String title, String kind) {
        final JSONObject metadata = activity.playbackTitle;
        activity.screen("", () -> {
            if (metadata != null && metadata.has("episode")) activity.seriesScreens.seasons(
                metadata.optInt("id"),
                metadata.optString("name", title),
                metadata.optInt("season", 1)
            );
            else if (metadata != null) activity.posters.tmdbTitle(metadata);
            else activity.catalogues.catalogueMenu(kind);
        });
        activity.posters.listBackdrop(metadata);
        activity.pageTitle(
            activity.page,
            AutoPlayback.enabled(activity, season) ? "Finding a source" : "Choose a source",
            title,
            "Free website streams and connected cloud sources appear together."
        );
        if (activity.secrets.has("premiumize")) {
            LinearLayout tools = activity.horizontal(activity.page);
            activity.chip(tools, "Premiumize cloud", () -> activity.cloudScreens.cloud("", kind));
            activity.chip(tools, "Transfers", () -> activity.cloudScreens.transfers(kind));
            activity.chip(tools, "All cloud sources", () ->
                cloudSources(imdb, season, episode, title, kind, metadata)
            );
        }
        new FreeVodScreen(activity).start(imdb, season, episode, title, kind, metadata);
    }

    /** Preserve cached/uncached cloud browsing and explicit transfer actions beside free autoplay. */
    private void cloudSources(
        String imdb,
        int season,
        int episode,
        String title,
        String kind,
        JSONObject metadata
    ) {
        activity.screen("Cloud sources", () -> {
            activity.playbackTitle = metadata;
            torrentSources(imdb, season, episode, title, kind);
        });
        activity.posters.listBackdrop(metadata);
        TextView loading = activity.muted("Checking cloud source availability…", 16);
        activity.page.addView(loading);
        SourceRepository repo = new SourceRepository(activity.secrets);
        activity.async(
            () -> repo.torrentio(imdb, season, episode),
            sources -> {
                activity.page.removeView(loading);
                LinearLayout filters = activity.horizontal(activity.page),
                    results = activity.vertical();
                activity.page.addView(results);
                for (String grade : new String[] { "All", "4K", "1080p", "720p" }) {
                    activity.chip(filters, grade, () ->
                        renderSources(
                            results,
                            sources,
                            grade,
                            repo,
                            metadata,
                            imdb,
                            season,
                            episode,
                            title,
                            kind
                        )
                    );
                }
                renderSources(results, sources, "All", repo, metadata, imdb, season, episode, title, kind);
            }
        );
    }

    void renderSources(
        LinearLayout target,
        List<SourceRepository.Source> sources,
        String quality,
        SourceRepository repo,
        JSONObject metadata,
        String imdb,
        int season,
        int episode,
        String title,
        String kind
    ) {
        target.removeAllViews();
        int shown = 0;
        for (SourceRepository.Cache status : SourceRepository.Cache.values()) {
            List<SourceRepository.Source> group = new ArrayList<>();
            for (SourceRepository.Source source : sources)
                if (
                    source.cache == status && (quality.equals("All") || quality(source.title).equals(quality))
                ) group.add(source);
            if (group.isEmpty()) continue;
            shown += group.size();
            activity.section(
                target,
                (status == SourceRepository.Cache.CACHED
                    ? "Ready to play"
                    : status == SourceRepository.Cache.NOT_CACHED
                      ? "Available to transfer"
                      : "Cache check unavailable") +
                    " · " +
                    group.size()
            );
            for (SourceRepository.Source source : group) {
                LinearLayout card = activity.listRow();
                TextView grade = activity.rowText(quality(source.title), 20, false, 1);
                grade.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                grade.setGravity(Gravity.CENTER);
                card.addView(grade, new LinearLayout.LayoutParams(activity.dp(84), -2));
                LinearLayout copy = activity.vertical();
                LinearLayout top = activity.row();
                TextView label = activity.rowText(provider(source.title), 17, false, 1);
                label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                top.addView(label, new LinearLayout.LayoutParams(-2, -2));
                for (String detail : details(source.title)) {
                    LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(-2, -2);
                    badgeLayout.leftMargin = activity.dp(8);
                    top.addView(activity.badge(detail), badgeLayout);
                }
                copy.addView(top);
                copy.addView(activity.rowText(release(source.title), 13, true, 1));
                card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                TextView next = activity.rowText(
                    source.cache == SourceRepository.Cache.CACHED
                        ? "▶  Play"
                        : source.cache == SourceRepository.Cache.NOT_CACHED
                          ? "Send to cloud"
                          : "Check again",
                    15,
                    false,
                    1
                );
                LinearLayout.LayoutParams nextLayout = new LinearLayout.LayoutParams(-2, -2);
                nextLayout.leftMargin = activity.dp(16);
                card.addView(next, nextLayout);
                activity.styleListRow(card, false);
                card.setOnClickListener(v -> {
                    if (source.cache == SourceRepository.Cache.UNKNOWN) {
                        activity.playbackTitle = metadata;
                        torrentSources(imdb, season, episode, title, kind);
                        return;
                    }
                    if (source.cache == SourceRepository.Cache.NOT_CACHED) {
                        SidePanel.of(activity, "Not ready to play yet")
                            .message(
                                "This source is not in your Premiumize cache yet. Send it to your cloud and it will appear in Transfers; it can be played once it finishes."
                            )
                            .action("Send to cloud", () ->
                                activity.async(
                                    () -> repo.queue(source),
                                    id -> {
                                        activity.toast("Transfer queued");
                                        activity.cloudScreens.transfers(kind);
                                    }
                                )
                            )
                            .action("Cancel", null)
                            .show();
                        return;
                    }
                    card.setEnabled(false);
                    label.setText("Opening source…");
                    activity.async(
                        () -> {
                            try {
                                return repo.direct(source);
                            } finally {
                                activity.runOnUiThread(() -> {
                                    card.setEnabled(true);
                                    label.setText(provider(source.title));
                                });
                            }
                        },
                        files -> {
                            // Movies show their recommendation in the file list; exact episodes can play directly.
                            List<String> paths = new ArrayList<>();
                            List<Long> sizes = new ArrayList<>();
                            for (int i = 0; i < files.length(); i++) {
                                JSONObject file = files.getJSONObject(i);
                                paths.add(file.optString("path", file.optString("name", "")));
                                sizes.add(file.optLong("size", 0));
                            }
                            Binge.remember(imdb, season, episode, title, kind, metadata, files);
                            boolean movie = season < 0 || episode <= 0;
                            List<String> playablePaths = new ArrayList<>(paths);
                            for (int i = 0; i < files.length(); i++) {
                                if (files.getJSONObject(i).optString("link").isEmpty()) playablePaths.set(
                                    i,
                                    ""
                                );
                            }
                            int recommended = movie
                                ? VideoFiles.recommendMovie(playablePaths, sizes, title)
                                : -1;
                            int pick = movie ? -1 : VideoFiles.autoPick(paths, sizes, season, episode);
                            String pickLink = pick < 0 ? "" : files.getJSONObject(pick).optString("link");
                            if (!pickLink.isEmpty()) {
                                activity.play(
                                    title,
                                    pickLink,
                                    activity.id(
                                        source.uri + ":" + files.getJSONObject(pick).optString("path", "File")
                                    ),
                                    false,
                                    metadata,
                                    VodSources.prepare(
                                        source,
                                        pickLink,
                                        files,
                                        sources,
                                        repo,
                                        season,
                                        episode,
                                        title,
                                        activity.prefs.getInt(AutoPlayback.QUALITY, 1080),
                                        true
                                    )
                                );
                                return;
                            }
                            activity.screen("", () -> {
                                activity.playbackTitle = metadata;
                                torrentSources(imdb, season, episode, title, kind);
                            });
                            activity.posters.listBackdrop(metadata);
                            activity.pageTitle(
                                activity.page,
                                "Choose a file",
                                title,
                                files.length() +
                                    (files.length() == 1 ? " file" : " files") +
                                    " in this source"
                            );
                            if (files.length() == 0) activity.emptyState(
                                activity.page,
                                "No playable files",
                                "Try a different source.",
                                "Back to sources",
                                () -> {
                                    activity.playbackTitle = metadata;
                                    torrentSources(imdb, season, episode, title, kind);
                                }
                            );
                            // Recommended movie first, then videos, then the remaining torrent files.
                            List<Integer> order = new ArrayList<>();
                            for (int i = 0; i < files.length(); i++) order.add(i);
                            order.sort(
                                Comparator.comparingInt(i ->
                                    i == recommended ? 0 : VideoFiles.isVideo(paths.get(i)) ? 1 : 2
                                )
                            );
                            // Focus starts on the recommendation or the best matching episode.
                            View first = null;
                            int firstRank = -1;
                            for (int i : order) {
                                JSONObject file = files.getJSONObject(i);
                                String link = file.optString("link"),
                                    name = paths.get(i);
                                boolean video = VideoFiles.isVideo(name);
                                boolean suggest = VideoFiles.suggested(name, season, episode);
                                int rank =
                                    i == recommended
                                        ? 3
                                        : !suggest
                                          ? 0
                                          : VideoFiles.score(name, "", season, episode) == VideoFiles.EXACT
                                            ? 2
                                            : 1;
                                LinearLayout row = activity.listRow();
                                LinearLayout fileCopy = activity.vertical();
                                String normalName = name.replace('\\', '/');
                                String shortName = normalName.substring(normalName.lastIndexOf('/') + 1);
                                fileCopy.addView(activity.rowText(shortName, 17, false, 2));
                                if (!shortName.equals(name)) fileCopy.addView(
                                    activity.rowText(name, 12, true, 1)
                                );
                                row.addView(fileCopy, new LinearLayout.LayoutParams(0, -2, 1));
                                if (suggest || i == recommended) {
                                    LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(
                                        -2,
                                        -2
                                    );
                                    badgeLayout.leftMargin = activity.dp(12);
                                    row.addView(
                                        activity.badge(
                                            i == recommended ? "Recommended movie" : "Suggested episode"
                                        ),
                                        badgeLayout
                                    );
                                }
                                fileCopy.addView(
                                    activity.rowText(
                                        (video ? "Video file" : "Other file") +
                                            (file.optLong("size") > 0
                                                ? " · " +
                                                  android.text.format.Formatter.formatFileSize(
                                                      activity,
                                                      file.optLong("size")
                                                  )
                                                : ""),
                                        12,
                                        true,
                                        1
                                    )
                                );
                                activity.styleListRow(row, false);
                                row.setEnabled(video && !link.isEmpty());
                                row.setOnClickListener(clicked ->
                                    activity.play(
                                        title,
                                        link,
                                        activity.id(source.uri + ":" + name),
                                        false,
                                        metadata,
                                        VodSources.prepare(
                                            source,
                                            link,
                                            files,
                                            sources,
                                            repo,
                                            season,
                                            episode,
                                            title,
                                            activity.prefs.getInt(AutoPlayback.QUALITY, 1080),
                                            true
                                        )
                                    )
                                );
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                                lp.topMargin = activity.dp(8);
                                activity.page.addView(row, lp);
                                if (video && !link.isEmpty() && rank > firstRank) {
                                    first = row;
                                    firstRank = rank;
                                }
                            }
                            if (first != null) first.requestFocus();
                        }
                    );
                });
                card.setOnLongClickListener(v -> {
                    SidePanel.of(activity, "Source details")
                        .message(source.title)
                        .action("Close", null)
                        .show();
                    return true;
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = activity.dp(8);
                target.addView(card, lp);
            }
        }
        if (shown == 0) activity.emptyState(
            target,
            "No " + quality + " sources",
            "Choose another quality to see available sources.",
            null,
            null
        );
    }
}
