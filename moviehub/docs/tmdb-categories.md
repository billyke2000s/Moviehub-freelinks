# Movie and series categories

Movies and Series open directly on **All categories**. The Home screen and its artwork shelf are unchanged.

All official genre baselines are immediately available; successful TMDB genre responses are cached and merged by genre ID so additional genres are included without an arbitrary count limit. An unavailable metadata server does not remove the category controls.

| Movies (19 genres) | Series (16 genres) |
| --- | --- |
| Action, Adventure, Animation, Comedy, Crime, Documentary, Drama, Family, Fantasy, History, Horror, Music, Mystery, Romance, Science Fiction, TV Movie, Thriller, War, Western | Action & Adventure, Animation, Comedy, Crime, Documentary, Drama, Family, Kids, Mystery, News, Reality, Sci-Fi & Fantasy, Soap, Talk, War & Politics, Western |

Both sections also expose daily and weekly trending, popular and top rated lists. Movies includes Now playing and Upcoming; Series includes Airing today and On the air.

Browse-by groups cover years and decades, all original languages, countries of origin, regional streaming services, rating thresholds, runtime ranges, sorting, keyword/theme search, studio search and people credits. Movies additionally has collection search and release types; Series has series types and production status. Provider availability is attributed to JustWatch and describes TMDB availability listings, not MovieHub playback availability.

All title-result pages retain poster browsing, details and pagination. Selecting a category uses the appropriate movie or TV query; movie-only genres and release filters are not reused as TV filters. Existing account credentials, personal sources, favourites and installed-app pins are untouched.

The sort browser includes all 14 documented movie sort orders and all 12 TV sort orders. Two hundred generated routes were checked against the official TMDB OpenAPI paths, query parameters and sort enums.

Verification includes tests for every baseline genre appearing on opening the section, actual D-pad selection of a final genre, media-specific filter groups, discover query boundaries and malformed region/year inputs. Authenticated live TMDB account checks require external credentials and are not inferred from fixtures or compilation.

References:

- https://developer.themoviedb.org/reference/genre-movie-list
- https://developer.themoviedb.org/reference/genre-tv-list
- https://developer.themoviedb.org/reference/discover-movie
- https://developer.themoviedb.org/reference/discover-tv
- https://developer.themoviedb.org/reference/search-collection
- https://developer.themoviedb.org/reference/search-keyword
- https://developer.themoviedb.org/reference/tv-series-watch-providers
