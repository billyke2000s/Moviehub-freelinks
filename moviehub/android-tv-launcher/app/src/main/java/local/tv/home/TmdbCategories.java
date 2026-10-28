package local.tv.home;

import java.time.LocalDate;
import java.util.*;

/** TMDB discovery routes shared by Movies and Series. No account or playback state. */
final class TmdbCategories {

    static final class Entry {

        final String name, path;

        Entry(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    static String type(String type) {
        if (!type.equals("movie") && !type.equals("tv")) throw new IllegalArgumentException(
            "Unknown catalogue"
        );
        return type;
    }

    static String discover(String type, String query) {
        return "discover/" + type(type) + "?include_adult=false&" + query;
    }

    static String refine(String type, int genre, int year, int rating, String sort) {
        type(type);
        if (
            genre < 0 ||
            (year != 0 && (year < 1874 || year > 9999)) ||
            rating < 0 ||
            rating > 10 ||
            !Arrays.asList("popularity.desc", "vote_average.desc", "date.desc", "title.asc").contains(sort)
        ) throw new IllegalArgumentException("Invalid catalogue filters");
        String date = type.equals("movie") ? "primary_release_date" : "first_air_date";
        String order = sort.equals("date.desc")
            ? date + ".desc"
            : sort.equals("title.asc")
              ? (type.equals("movie") ? "title" : "name") + ".asc"
              : sort;
        StringBuilder query = new StringBuilder("sort_by=" + order);
        if (genre > 0) query.append("&with_genres=").append(genre);
        if (year > 0) query
            .append("&")
            .append(date)
            .append(".gte=")
            .append(year)
            .append("-01-01&")
            .append(date)
            .append(".lte=")
            .append(year)
            .append("-12-31");
        if (rating > 0) query.append("&vote_average.gte=").append(rating);
        if (rating > 0 || sort.equals("vote_average.desc")) query.append("&vote_count.gte=100");
        return discover(type, query.toString());
    }

    /** Japanese animation: TMDB's Animation genre and Japanese as the original language. */
    static final String ANIME = "with_genres=16&with_original_language=ja";

    /**
     * The Anime start page's rows: series first (most anime is series), then films, then series by
     * genre. Each row's path says whether it lists films or series ({@link #typeOf}).
     */
    static List<Entry> anime(LocalDate today) {
        String now = today.toString(),
            weekAgo = today.minusDays(7).toString(),
            weekAhead = today.plusDays(7).toString();
        List<Entry> rows = new ArrayList<>();
        rows.add(new Entry("Popular anime", discover("tv", ANIME + "&sort_by=popularity.desc")));
        rows.add(
            new Entry(
                "Airing this week",
                discover(
                    "tv",
                    ANIME +
                        "&air_date.gte=" +
                        weekAgo +
                        "&air_date.lte=" +
                        weekAhead +
                        "&sort_by=popularity.desc"
                )
            )
        );
        rows.add(
            new Entry(
                "New anime series",
                discover(
                    "tv",
                    ANIME + "&first_air_date.lte=" + now + "&sort_by=first_air_date.desc&vote_count.gte=20"
                )
            )
        );
        rows.add(
            new Entry(
                "Top rated anime",
                discover("tv", ANIME + "&sort_by=vote_average.desc&vote_count.gte=200")
            )
        );
        rows.add(new Entry("Anime films", discover("movie", ANIME + "&sort_by=popularity.desc")));
        rows.add(
            new Entry(
                "Top rated anime films",
                discover("movie", ANIME + "&sort_by=vote_average.desc&vote_count.gte=200")
            )
        );
        String[][] genres = {
            { "10759", "Action & adventure anime" },
            { "35", "Comedy anime" },
            { "10765", "Fantasy & sci-fi anime" },
            { "18", "Drama anime" },
            { "9648", "Mystery anime" },
            { "10751", "Family anime" },
        };
        // A comma in with_genres means every listed genre (Animation and this one).
        for (String[] genre : genres)
            rows.add(
                new Entry(
                    genre[1],
                    discover(
                        "tv",
                        "with_genres=16," + genre[0] + "&with_original_language=ja&sort_by=popularity.desc"
                    )
                )
            );
        return rows;
    }

    /** Whether a catalogue path lists films or series; {@code fallback} when the path does not say. */
    static String typeOf(String path, String fallback) {
        if (
            path.startsWith("discover/movie") ||
            path.startsWith("movie/") ||
            path.startsWith("trending/movie")
        ) {
            return "movie";
        }
        if (
            path.startsWith("discover/tv") || path.startsWith("tv/") || path.startsWith("trending/tv")
        ) return "tv";
        return fallback;
    }

    static Entry genre(String type, int id, String name) {
        if (id <= 0 || name.trim().isEmpty()) throw new IllegalArgumentException("Invalid genre");
        return new Entry(name, discover(type, "with_genres=" + id));
    }

    static List<Entry> genres(String type) {
        type(type);
        List<Entry> rows = new ArrayList<>();
        String[][] definitions = type.equals("movie")
            ? new String[][] {
                  { "28", "Action" },
                  { "12", "Adventure" },
                  { "16", "Animation" },
                  { "35", "Comedy" },
                  { "80", "Crime" },
                  { "99", "Documentary" },
                  { "18", "Drama" },
                  { "10751", "Family" },
                  { "14", "Fantasy" },
                  { "36", "History" },
                  { "27", "Horror" },
                  { "10402", "Music" },
                  { "9648", "Mystery" },
                  { "10749", "Romance" },
                  { "878", "Science Fiction" },
                  { "10770", "TV Movie" },
                  { "53", "Thriller" },
                  { "10752", "War" },
                  { "37", "Western" },
              }
            : new String[][] {
                  { "10759", "Action & Adventure" },
                  { "16", "Animation" },
                  { "35", "Comedy" },
                  { "80", "Crime" },
                  { "99", "Documentary" },
                  { "18", "Drama" },
                  { "10751", "Family" },
                  { "10762", "Kids" },
                  { "9648", "Mystery" },
                  { "10763", "News" },
                  { "10764", "Reality" },
                  { "10765", "Sci-Fi & Fantasy" },
                  { "10766", "Soap" },
                  { "10767", "Talk" },
                  { "10768", "War & Politics" },
                  { "37", "Western" },
              };
        for (String[] definition : definitions)
            rows.add(genre(type, Integer.parseInt(definition[0]), definition[1]));
        return rows;
    }

    static List<Entry> lists(String type) {
        type(type);
        return Arrays.asList(
            new Entry("Trending today", "trending/" + type + "/day"),
            new Entry("Trending this week", "trending/" + type + "/week"),
            new Entry("Popular", type + "/popular"),
            new Entry("Top rated", type + "/top_rated"),
            new Entry(
                type.equals("movie") ? "Now playing" : "Airing today",
                type + (type.equals("movie") ? "/now_playing" : "/airing_today")
            ),
            new Entry(
                type.equals("movie") ? "Upcoming" : "On the air",
                type + (type.equals("movie") ? "/upcoming" : "/on_the_air")
            )
        );
    }

    static List<Entry> groups(String type) {
        type(type);
        List<Entry> rows = new ArrayList<>();
        for (String[] group : new String[][] {
            { "Years & decades", ":years" },
            { "Languages", ":languages" },
            { "Countries", ":countries" },
            { "Streaming services", ":services" },
            { "Ratings", ":ratings" },
            { "Runtime", ":runtime" },
            { "Sort order", ":sort" },
            { "Keywords & themes", ":keywords" },
            { "Studios", ":companies" },
            { "People", ":people" },
        })
            rows.add(new Entry(group[0], group[1]));
        if (type.equals("movie")) {
            rows.add(new Entry("Collections", ":collections"));
            rows.add(new Entry("Release types", ":releases"));
        } else {
            rows.add(new Entry("Series types", ":types"));
            rows.add(new Entry("Series status", ":status"));
        }
        return rows;
    }

    static List<Entry> filters(String type, String section, String country, LocalDate today) {
        type(type);
        List<Entry> rows = new ArrayList<>();
        String date = type.equals("movie") ? "primary_release_date" : "first_air_date";
        switch (section) {
            case ":years":
                rows.add(year(type, today.getYear()));
                for (int y = today.getYear() - 1; y >= today.getYear() - 24; y--) rows.add(year(type, y));
                for (int decade = (today.getYear() / 10) * 10; decade >= 1900; decade -= 10) rows.add(
                    new Entry(
                        decade + "s",
                        discover(
                            type,
                            date + ".gte=" + decade + "-01-01&" + date + ".lte=" + (decade + 9) + "-12-31"
                        )
                    )
                );
                break;
            case ":ratings":
                for (int rating = 9; rating >= 5; rating--) rows.add(
                    new Entry(
                        rating + "+ rated",
                        discover(
                            type,
                            "vote_average.gte=" + rating + "&vote_count.gte=100&sort_by=vote_average.desc"
                        )
                    )
                );
                rows.add(new Entry("Most voted", discover(type, "sort_by=vote_count.desc")));
                break;
            case ":runtime":
                for (String[] range : new String[][] {
                    { "Under 30 minutes", "with_runtime.gte=1&with_runtime.lte=29" },
                    { "30–60 minutes", "with_runtime.gte=30&with_runtime.lte=60" },
                    { "60–90 minutes", "with_runtime.gte=60&with_runtime.lte=90" },
                    { "90–120 minutes", "with_runtime.gte=90&with_runtime.lte=120" },
                    { "Over 2 hours", "with_runtime.gte=121" },
                })
                    rows.add(new Entry(range[0], discover(type, range[1])));
                break;
            case ":sort":
                String title = type.equals("movie") ? "title" : "name",
                    original = type.equals("movie") ? "original_title" : "original_name";
                for (String[] sort : new String[][] {
                    { "Most popular", "popularity.desc" },
                    { "Least popular", "popularity.asc" },
                    { "Highest rated", "vote_average.desc" },
                    { "Lowest rated", "vote_average.asc" },
                    { "Most voted", "vote_count.desc" },
                    { "Least voted", "vote_count.asc" },
                    { "Newest releases", date + ".desc" },
                    { "Oldest releases", date + ".asc" },
                    { "Title: A to Z", title + ".asc" },
                    { "Title: Z to A", title + ".desc" },
                    { "Original: A to Z", original + ".asc" },
                    { "Original: Z to A", original + ".desc" },
                })
                    rows.add(
                        new Entry(
                            sort[0],
                            discover(
                                type,
                                "vote_count.gte=10&" +
                                    (sort[1].startsWith(date) ? date + ".lte=" + today + "&" : "") +
                                    "sort_by=" +
                                    sort[1]
                            )
                        )
                    );
                if (type.equals("movie")) {
                    rows.add(new Entry("Highest grossing", discover(type, "sort_by=revenue.desc")));
                    rows.add(
                        new Entry(
                            "Revenue: low to high",
                            discover(type, "vote_count.gte=10&sort_by=revenue.asc")
                        )
                    );
                }
                break;
            case ":types":
                if (type.equals("tv")) {
                    String[] names = {
                        "Documentary series",
                        "News programmes",
                        "Miniseries",
                        "Reality shows",
                        "Scripted series",
                        "Shows",
                        "Video series",
                    };
                    for (int i = 0; i < names.length; i++) rows.add(
                        new Entry(names[i], discover(type, "with_type=" + i))
                    );
                }
                break;
            case ":status":
                if (type.equals("tv")) {
                    String[] names = {
                        "Returning series",
                        "Planned",
                        "In production",
                        "Ended",
                        "Cancelled",
                        "Pilot",
                    };
                    for (int i = 0; i < names.length; i++) rows.add(
                        new Entry(names[i], discover(type, "with_status=" + i))
                    );
                }
                break;
            case ":releases":
                if (type.equals("movie")) {
                    String[] names = {
                        "Premieres",
                        "Limited theatrical",
                        "Theatrical releases",
                        "Digital releases",
                        "Physical releases",
                        "TV releases",
                    };
                    for (int i = 0; i < names.length; i++) rows.add(
                        new Entry(
                            names[i],
                            discover(type, "region=" + region(country) + "&with_release_type=" + (i + 1))
                        )
                    );
                }
                break;
            case ":availability":
                String[] names = { "Subscription", "Free", "With adverts", "Rent", "Buy" },
                    values = { "flatrate", "free", "ads", "rent", "buy" };
                for (int i = 0; i < names.length; i++) rows.add(
                    new Entry(
                        names[i],
                        discover(
                            type,
                            "watch_region=" + region(country) + "&with_watch_monetization_types=" + values[i]
                        )
                    )
                );
                break;
            default:
                throw new IllegalArgumentException("Unknown category group");
        }
        return rows;
    }

    static Entry year(String type, int year) {
        if (year < 1874 || year > 9999) throw new IllegalArgumentException("Enter a year from 1874 to 9999");
        return new Entry(
            String.valueOf(year),
            discover(
                type,
                (type.equals("movie") ? "primary_release_year" : "first_air_date_year") + "=" + year
            )
        );
    }

    static String region(String region) {
        if (region == null || !region.matches("[A-Z]{2}")) throw new IllegalArgumentException(
            "Invalid region"
        );
        return region;
    }
}
