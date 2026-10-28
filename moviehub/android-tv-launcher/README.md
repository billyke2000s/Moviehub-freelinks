# Native Android TV launcher

This is the repository's Android application. See the [root README](../README.md) for features and documentation, [ARCHITECTURE.md](../ARCHITECTURE.md) for the code map, [TESTING.md](../TESTING.md) for tests, and [UPDATES.md](UPDATES.md) for signed release publication.

The app is native Android, not a WebView. Playback uses LibVLC. It has no custom product wordmark or logo. The HTML visual reference is not bundled as firmware or used as the application runtime.

Streaming screens share fixed headers, remote focus styling and scrollable content. Catalogue grids preserve complete poster aspect ratios and restore loaded titles, scroll position and focus when returning from details. Browse and advanced filters remain in side sheets.

Title pages include TMDB metadata, cast filmographies, collection browsing, recommendations, My List and official trailers when available. Search stores up to 12 distinct recent queries locally. My List supports media filters and alphabetical/recent ordering; Continue Watching adds artwork, saved duration, resume/start-over and removal controls. Episodes can be marked watched, and completed playback marks an episode automatically.

Live TV keeps database pagination without a channel-count limit and adds exact channel-group filtering. Source quality filters operate on the fetched results without repeating provider calls. Playback options include speed, picture ratio, subtitle timing and a session sleep timer. Trailers open the installed YouTube/browser handler; no trailer or stream quality is invented when metadata is absent.
