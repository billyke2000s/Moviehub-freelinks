package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FreeVodProvidersTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void resumeRefreshesMovieAndExactEpisodeMetadataWithoutSavedStreamUrl() throws Exception {
        JSONObject old = new JSONObject()
            .put("tmdbId", 12)
            .put("id", "old-file")
            .put("title", "Old display title")
            .put("url", "https://expired.example/movie.mp4");
        java.util.List<String> paths = new java.util.ArrayList<>();
        VodResume.Metadata fixture = path -> {
            paths.add(path);
            if (path.contains("/episode/")) return new JSONObject()
                .put("name", "Episode title")
                .put("air_date", "2020-01-02");
            return new JSONObject()
                .put("id", 12)
                .put("title", "Fresh film")
                .put("name", "Fresh series")
                .put("first_air_date", "2020-01-01")
                .put("external_ids", new JSONObject().put("imdb_id", "tt1234567"));
        };
        JSONObject film = VodResume.refresh(old, fixture);
        assertEquals("movie/12?append_to_response=external_ids,alternative_titles", paths.get(0));
        assertEquals("Fresh film", film.getString("title"));
        assertFalse(film.has("url"));
        paths.clear();
        old.put("season", 2).put("episode", 3).put("episodeKey", "12:2:3");
        JSONObject episode = VodResume.refresh(old, fixture);
        assertEquals("tv/12?append_to_response=external_ids,alternative_titles", paths.get(0));
        assertEquals("tv/12/season/2/episode/3", paths.get(1));
        assertEquals("12:2:3", episode.getString("episodeKey"));
        assertEquals("Episode title", episode.getString("episodeTitle"));
        assertEquals("2020-01-02", episode.getString("air_date"));
        assertFalse(episode.has("url"));
    }

    @Test
    public void movieAndSeriesAliasesRetainOriginalAndAlternativeTitles() throws Exception {
        JSONObject movie = new JSONObject()
            .put("original_title", "Original Film")
            .put(
                "alternative_titles",
                new JSONObject().put(
                    "titles",
                    new JSONArray()
                        .put(new JSONObject().put("title", "Regional Film"))
                        .put(new JSONObject().put("title", "Original Film"))
                        .put(new JSONObject().put("title", "  "))
                )
            );
        JSONArray titles = FreeVodScreen.aliases(movie, true);
        assertEquals(2, titles.length());
        assertEquals("Original Film", titles.getString(0));
        assertEquals("Regional Film", titles.getString(1));
        JSONObject series = new JSONObject()
            .put("original_name", "Original Series")
            .put(
                "alternative_titles",
                new JSONObject().put(
                    "results",
                    new JSONArray().put(new JSONObject().put("title", "Regional Series"))
                )
            );
        assertEquals(2, FreeVodScreen.aliases(series, false).length());
        assertEquals(0, FreeVodScreen.aliases(new JSONObject(), true).length());
    }

    @Test
    public void packagedPythonImportsEveryProviderAndHostResolverWithoutKodi() throws Exception {
        // Eleven retired/repurposed and 18 unverified sites stay disabled; Levidia stays searchable.
        assertEquals(38, FreeVodProviders.ids(context).size());
        assertEquals(72, FreeVodProviders.validate(context));
    }

    @Test
    public void realExtractorEmitsBothServersThroughTheAndroidCallback() throws Exception {
        FreeVodProviders.ids(context);
        Context tests = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext();
        java.io.File script = java.io.File.createTempFile("free-vod-fixture", ".py", context.getCacheDir());
        try (
            java.io.InputStream input = tests.getAssets().open("free_vod_fixture.py");
            java.io.OutputStream output = new java.io.FileOutputStream(script)
        ) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        com.chaquo.python.PyObject fixture = com.chaquo.python.Python.getInstance()
            .getModule("runpy")
            .callAttr("run_path", script.getAbsolutePath());
        fixture.callAttr("get", "install").call();
        try {
            java.util.List<SourceRepository.Source> sources = new java.util.ArrayList<>();
            FreeVodProviders.search(
                context,
                "free99:my4hydra_top",
                new JSONObject()
                    .put("imdb", "tt1234567")
                    .put("tmdb", 12)
                    .put("title", "Fixture Film")
                    .put("year", "2020")
                    .put("season", -1)
                    .put("episode", -1)
                    .put("http", false),
                new AtomicBoolean(false),
                sources::add
            );
            assertEquals(2, sources.size());
            assertEquals(SourceRepository.Cache.CACHED, sources.get(0).cache);
            assertNotNull("Header-free sources also use counted incoming transport", sources.get(0).stream);
            assertTrue(sources.get(0).responseMs >= 0);
            assertEquals(
                "https://8.8.8.8/video.mp4",
                sources.get(0).files.getJSONObject(0).getString("link")
            );
            assertEquals(
                "https://1.1.1.1/video.mp4",
                sources.get(1).files.getJSONObject(0).getString("link")
            );
            assertEquals(0, AutoPlayback.pick(sources.get(0).files, -1, -1, "Fixture Film"));
        } finally {
            fixture.callAttr("get", "restore").call();
            script.delete();
        }
    }

    @Test
    public void namedBackendsSharingOneMediaUrlStaySeparateInTheSelectionWindow() {
        String url = "https://cdn.example/film.m3u8";
        SourceRepository.Source one = new SourceRepository.Source("site.example · Host One\n1080p", url);
        SourceRepository.Source two = new SourceRepository.Source("site.example · Host Two\n1080p", url);
        SourceWindow<SourceRepository.Source> window = new SourceWindow<>(0, source -> 1);
        assertTrue(window.add(VodSearch.sourceKey(one), one));
        assertTrue(window.add(VodSearch.sourceKey(two), two));
        assertFalse(window.add(VodSearch.sourceKey(one), one));
        assertEquals(2, window.snapshot().size());
        assertNotEquals(SourcesScreen.provider(one.title), SourcesScreen.provider(two.title));
    }

    @Test
    public void sourceBadgesUseReleaseResolutionInsteadOfProviderBranding() {
        assertEquals(
            "1080p",
            SourcesScreen.quality("4khdhub.one · Host\n1080p · Film.1080p.UHD.4KHDHub.mkv")
        );
        assertEquals("720p", SourcesScreen.quality("4khdhub.one · Host\n720p · Film.4KHDHub.mkv"));
        assertEquals("4K", SourcesScreen.quality("4khdhub.one · Host\n2160p · Film.UHD.mkv"));
        assertEquals("SD", SourcesScreen.quality("4khdhub.one · Host\nSD · Film.4KHDHub.mkv"));
    }

    @Test
    public void javaCancellationReachesPackagedPythonBeforeAnyProviderRequest() throws Exception {
        AtomicInteger emitted = new AtomicInteger();
        FreeVodProviders.search(
            context,
            "free99:my4hydra_top",
            new JSONObject().put("http", false),
            new AtomicBoolean(true),
            source -> emitted.incrementAndGet()
        );
        assertEquals(0, emitted.get());
    }
}
