package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TmdbCategoriesTest {

    private TmdbCategories.Entry named(List<TmdbCategories.Entry> entries, String name) {
        for (TmdbCategories.Entry entry : entries) if (entry.name.equals(name)) return entry;
        throw new AssertionError("Missing category: " + name);
    }

    @Test
    public void movieAndTvGenresUseTheirOwnIdsAndContainTheCompleteBaseline() {
        assertEquals(19, TmdbCategories.genres("movie").size());
        assertEquals(16, TmdbCategories.genres("tv").size());
        assertTrue(named(TmdbCategories.genres("movie"), "Science Fiction").path.endsWith("with_genres=878"));
        assertTrue(named(TmdbCategories.genres("movie"), "TV Movie").path.endsWith("with_genres=10770"));
        assertTrue(named(TmdbCategories.genres("tv"), "Sci-Fi & Fantasy").path.endsWith("with_genres=10765"));
        assertTrue(named(TmdbCategories.genres("tv"), "War & Politics").path.endsWith("with_genres=10768"));
        assertTrue(named(TmdbCategories.genres("tv"), "Kids").path.endsWith("with_genres=10762"));
        for (String type : new String[] { "movie", "tv" }) {
            Set<String> routes = new HashSet<>();
            for (TmdbCategories.Entry entry : TmdbCategories.genres(type)) assertTrue(routes.add(entry.path));
        }
    }

    @Test
    public void releaseAndSeriesFiltersCannotBeAppliedToTheWrongMedia() {
        LocalDate day = LocalDate.of(2026, 10, 17);
        assertEquals(0, TmdbCategories.filters("movie", ":types", "GB", day).size());
        assertEquals(0, TmdbCategories.filters("movie", ":status", "GB", day).size());
        assertEquals(0, TmdbCategories.filters("tv", ":releases", "GB", day).size());
        assertTrue(
            named(TmdbCategories.filters("tv", ":types", "GB", day), "Miniseries").path.contains(
                "with_type=2"
            )
        );
        assertTrue(
            named(TmdbCategories.filters("tv", ":status", "GB", day), "Ended").path.contains("with_status=3")
        );
        assertTrue(
            named(TmdbCategories.filters("movie", ":releases", "GB", day), "Digital releases").path.contains(
                "region=GB&with_release_type=4"
            )
        );
    }

    @Test
    public void yearsRatingsRuntimeAndAvailabilityProduceBoundedDiscoverQueries() {
        LocalDate day = LocalDate.of(2026, 10, 17);
        assertTrue(TmdbCategories.year("movie", 2026).path.contains("primary_release_year=2026"));
        assertTrue(TmdbCategories.year("tv", 1999).path.contains("first_air_date_year=1999"));
        assertTrue(
            named(TmdbCategories.filters("movie", ":years", "GB", day), "1990s").path.contains(
                "primary_release_date.gte=1990-01-01&primary_release_date.lte=1999-12-31"
            )
        );
        assertTrue(
            named(TmdbCategories.filters("tv", ":ratings", "GB", day), "8+ rated").path.contains(
                "vote_average.gte=8&vote_count.gte=100"
            )
        );
        assertTrue(
            named(TmdbCategories.filters("movie", ":runtime", "GB", day), "Under 30 minutes").path.contains(
                "with_runtime.lte=29"
            )
        );
        assertTrue(
            named(TmdbCategories.filters("tv", ":availability", "GB", day), "Free").path.contains(
                "watch_region=GB&with_watch_monetization_types=free"
            )
        );
        assertTrue(
            named(TmdbCategories.filters("movie", ":sort", "GB", day), "Newest releases").path.contains(
                "primary_release_date.lte=2026-10-17"
            )
        );
        assertEquals(14, TmdbCategories.filters("movie", ":sort", "GB", day).size());
        assertEquals(12, TmdbCategories.filters("tv", ":sort", "GB", day).size());
        assertTrue(
            named(TmdbCategories.filters("tv", ":sort", "GB", day), "Original: A to Z").path.endsWith(
                "sort_by=original_name.asc"
            )
        );
        try {
            TmdbCategories.year("movie", 0);
            fail("Invalid year");
        } catch (IllegalArgumentException expected) {}
        try {
            TmdbCategories.filters("movie", ":availability", "GB&extra=1", day);
            fail("Region injection");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void combinedFiltersRetainGenreYearRatingAndMediaSpecificSort() {
        String movie = TmdbCategories.refine("movie", 28, 2020, 7, "date.desc");
        assertTrue(movie.contains("with_genres=28"));
        assertTrue(movie.contains("primary_release_date.gte=2020-01-01"));
        assertTrue(movie.contains("primary_release_date.lte=2020-12-31"));
        assertTrue(movie.contains("vote_average.gte=7"));
        assertTrue(movie.contains("sort_by=primary_release_date.desc"));
        String tv = TmdbCategories.refine("tv", 0, 0, 0, "title.asc");
        assertEquals("discover/tv?include_adult=false&sort_by=name.asc", tv);
        try {
            TmdbCategories.refine("tv", 0, 100, 0, "title.asc");
            fail("Invalid year accepted");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void animeRowsAreJapaneseAnimationAndKnowWhetherTheyListFilmsOrSeries() {
        List<TmdbCategories.Entry> rows = TmdbCategories.anime(LocalDate.of(2026, 10, 27));
        Set<String> routes = new HashSet<>();
        for (TmdbCategories.Entry entry : rows) {
            assertTrue(entry.name, routes.add(entry.path));
            assertTrue(entry.name, entry.path.contains("with_original_language=ja"));
            assertTrue(entry.name, entry.path.contains("with_genres=16"));
            assertTrue(entry.name, entry.path.contains("include_adult=false"));
        }
        assertEquals("tv", TmdbCategories.typeOf(named(rows, "Popular anime").path, "movie"));
        assertEquals("movie", TmdbCategories.typeOf(named(rows, "Anime films").path, "tv"));
        assertTrue(
            named(rows, "Airing this week").path.contains("air_date.gte=2026-10-20&air_date.lte=2026-11-03")
        );
        assertTrue(named(rows, "Comedy anime").path.contains("with_genres=16,35"));
        assertEquals("tv", TmdbCategories.typeOf("collection/10", "tv"));
        assertEquals("movie", TmdbCategories.typeOf("trending/movie/day", "tv"));
    }
}
