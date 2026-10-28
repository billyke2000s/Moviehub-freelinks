package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Fixture metadata only: never contact a provider or TMDB. */
@RunWith(AndroidJUnit4.class)
public final class SeriesAutoplayTest {

    private JSONObject episode(int number, String date) throws Exception {
        return new JSONObject()
            .put("episode_number", number)
            .put("air_date", date)
            .put("name", "Episode " + number);
    }

    private JSONObject season(JSONObject... episodes) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject episode : episodes) list.put(episode);
        return new JSONObject().put("episodes", list);
    }

    private JSONObject current() throws Exception {
        return new JSONObject()
            .put("id", 123)
            .put("name", "Fixture show")
            .put("imdb_id", "tt123456")
            .put("media_type", "tv")
            .put("season", 1)
            .put("episode", 2)
            .put("episodeKey", "123:1:2");
    }

    @Test
    public void crossesSeasonBoundaryUsingActualCatalogueNumbersAndFreshHistoryKeys() throws Exception {
        Map<String, JSONObject> fixtures = new HashMap<>();
        fixtures.put("tv/123/season/1", season(episode(1, "2020-01-01"), episode(2, "2020-01-02")));
        fixtures.put(
            "tv/123",
            new JSONObject(
                "{\"seasons\":[{\"season_number\":0,\"episode_count\":9},{\"season_number\":3,\"episode_count\":1},{\"season_number\":2,\"episode_count\":2}]}"
            )
        );
        fixtures.put("tv/123/season/2", season(episode(1, "2021-01-01"), episode(2, "2021-01-02")));
        JSONObject before = current();
        SeriesPlayback.Next next = SeriesPlayback.resolve(before, 1, 2, fixtures::get, "2026-10-22");
        assertEquals(2, next.season);
        assertEquals(1, next.episode);
        assertEquals("123:2:1", next.metadata.getString("episodeKey"));
        assertEquals("Episode 1", next.metadata.getString("episodeTitle"));
        assertEquals("123:1:2", before.getString("episodeKey"));
    }

    @Test
    public void missingPackEpisodeIsNotConfusedWithASeasonFinale() throws Exception {
        JSONObject response = season(episode(2, "2020-01-01"), episode(3, "2020-01-02"));
        SeriesPlayback.Next next = SeriesPlayback.resolve(
            current(),
            1,
            2,
            path -> {
                assertEquals("tv/123/season/1", path);
                return response;
            },
            "2026-10-22"
        );
        assertEquals(1, next.season);
        assertEquals(3, next.episode);
        JSONArray pack = new JSONArray(
            "[{\"path\":\"Show.S01E02.mkv\",\"link\":\"https://example.test/2\"},{\"path\":\"Show.S01E04.mkv\",\"link\":\"https://example.test/4\"}]"
        );
        assertEquals(-1, AutoPlayback.pick(pack, next.season, next.episode, "Fixture show"));
    }

    @Test
    public void stopsAtUnairedEpisodeInsteadOfSkippingToAnotherSeason() throws Exception {
        JSONObject response = season(episode(2, "2020-01-01"), episode(3, "2099-01-01"));
        assertNull(
            SeriesPlayback.resolve(
                current(),
                1,
                2,
                path -> {
                    assertEquals("tv/123/season/1", path);
                    return response;
                },
                "2026-10-22"
            )
        );
    }

    @Test
    public void stopsAtEndOfShowAndRefusesToInventEpisodes() throws Exception {
        assertNull(
            SeriesPlayback.resolve(
                current(),
                1,
                2,
                path ->
                    path.endsWith("/season/1")
                        ? season(episode(2, "2020-01-01"))
                        : new JSONObject("{\"seasons\":[]}"),
                "2026-10-22"
            )
        );
        try {
            SeriesPlayback.resolve(current(), 1, 2, path -> season(episode(1, "2020-01-01")), "2026-10-22");
            fail("Must verify the current episode");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void autoSelectionUsesExactEpisodesAndNeverPicksMissingLinksOrSamples() throws Exception {
        JSONArray files = new JSONArray(
            "[{\"path\":\"Film.sample.mp4\",\"size\":9999,\"link\":\"https://example.test/sample\"},{\"path\":\"Film.mkv\",\"size\":9000},{\"path\":\"Film.M2TS\",\"size\":8000,\"link\":\"https://example.test/film\"}]"
        );
        assertEquals(2, AutoPlayback.pick(files, -1, -1, "Film"));
        files = new JSONArray(
            "[{\"path\":\"03.mkv\",\"link\":\"https://example.test/3\"},{\"path\":\"Show.S02E03.mkv\",\"link\":\"https://example.test/exact\"}]"
        );
        assertEquals(1, AutoPlayback.pick(files, 2, 3, "Fixture show"));
        assertEquals(
            -1,
            AutoPlayback.pick(
                new JSONArray("[{\"path\":\"03.mkv\",\"link\":\"https://example.test/3\"}]"),
                2,
                3,
                "Fixture show"
            )
        );
    }

    @Test
    public void automaticModeTriesTheNextCachedSourceWhenPreferredPackLacksTheEpisode() throws Exception {
        SourceRepository.Source high = new SourceRepository.Source("1080p", "first"),
            low = new SourceRepository.Source("720p", "second"),
            notReady = new SourceRepository.Source("4K", "uncached");
        high.cache = low.cache = SourceRepository.Cache.CACHED;
        notReady.cache = SourceRepository.Cache.NOT_CACHED;
        java.util.List<String> attempted = new java.util.ArrayList<>();
        AutoPlayback.Selection result = AutoPlayback.resolve(
            java.util.Arrays.asList(notReady, low, high),
            source -> {
                attempted.add(source.uri);
                return new JSONArray().put(
                    new JSONObject()
                        .put("path", source == high ? "Show.S01E02.mkv" : "Show.S01E03.mkv")
                        .put("link", "https://example.test/video")
                );
            },
            1,
            3,
            "Show",
            1080,
            false,
            new java.util.concurrent.atomic.AtomicBoolean()
        );
        assertSame(low, result.source);
        assertEquals(java.util.Arrays.asList("first", "second"), attempted);
    }

    @Test
    public void automaticModeIsBoundedAndRespectsCancellation() throws Exception {
        java.util.List<SourceRepository.Source> sources = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var source = new SourceRepository.Source("1080p", "source" + i);
            source.cache = SourceRepository.Cache.CACHED;
            sources.add(source);
        }
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AutoPlayback.Direct missing = source -> {
            calls.incrementAndGet();
            return new JSONArray();
        };
        assertNull(
            AutoPlayback.resolve(
                sources,
                missing,
                1,
                3,
                "Show",
                1080,
                false,
                new java.util.concurrent.atomic.AtomicBoolean()
            )
        );
        assertEquals(5, calls.get());
        calls.set(0);
        assertNull(
            AutoPlayback.resolve(
                sources,
                missing,
                1,
                3,
                "Show",
                1080,
                false,
                new java.util.concurrent.atomic.AtomicBoolean(true)
            )
        );
        assertEquals(0, calls.get());
    }

    @Test
    public void restoresSeriesIdentityFromOlderContinueWatchingRecords() throws Exception {
        JSONObject old = new JSONObject(
            "{\"tmdbId\":123,\"id\":\"file-id\",\"season\":1,\"episode\":2,\"episodeKey\":\"123:1:2\"}"
        );
        var next = SeriesPlayback.resolve(
            old,
            1,
            2,
            path ->
                path.contains("append_to_response")
                    ? new JSONObject(
                          "{\"name\":\"Fixture show\",\"external_ids\":{\"imdb_id\":\"tt123456\"}}"
                      )
                    : season(episode(2, "2020-01-01"), episode(3, "2020-01-02")),
            "2026-10-22"
        );
        assertEquals("tt123456", SeriesPlayback.imdb(next.metadata));
        assertEquals("Fixture show", next.metadata.getString("name"));
        assertEquals("123:1:3", next.metadata.getString("episodeKey"));
    }
}
