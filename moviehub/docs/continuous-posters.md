# Continuous poster navigation

Movies and Series use a four-column RecyclerView; Home uses the same append-only feed horizontally. Stable media IDs and range insertions preserve existing holders, focus and scroll offset. Artwork views clear previous bindings before reuse.

The feed requests another page with roughly two grid rows or six shelf cards remaining. Only one request runs at a time. A failed request keeps its page number and existing posters, and offers Retry. Leaving a screen or changing a Home tab closes its feed, so late responses cannot update the replacement screen. Duplicate IDs are suppressed independently for movies and TV. Adult and non-title search results are excluded. The TMDB 500-page API boundary is respected and explicitly labelled when it limits a larger result set.

Right from a catalogue sidebar category selects the visible poster nearest its vertical centre, breaking ties towards the leftmost column. If the first page is pending, the request is retained until cards are ready. Page appends never request focus.

Poster captions reserve two top-aligned lines without the previous text padding. A focused title which exceeds two lines uses a repeating, frame-timed marquee; moving focus away restores the two-line caption. The Home dock heading has its own 28dp row and elevation above clipped app tiles.

Instrumentation uses generated, paginated TMDB-shaped responses through the production loader interface, with real D-pad events. This verifies navigation and delayed-page behaviour without requiring or modifying saved account credentials. It does not verify an authenticated live TMDB account or target-device installation.
