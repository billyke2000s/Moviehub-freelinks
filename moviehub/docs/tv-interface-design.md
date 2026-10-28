# MovieHub TV interface

The TV home is a fixed viewport. Main navigation, the featured title, category controls, a poster shelf and the installed-app dock must fit together without vertical scrolling. Horizontal shelves can contain additional titles or pinned apps. The catalogue is a separate destination with a stationary category menu and a scrolling results pane.

## Reference decisions

| Reference | Useful pattern | MovieHub application |
| --- | --- | --- |
| Netflix | Visible primary navigation and contextual title information | Movies, Series, Live TV, My List and Continue are visible on Home; focusing a poster changes the featured title and artwork. |
| Prime Video | Clear content destinations and availability | Catalogue, personal library, cloud and transfers have distinct destinations. Metadata browsing never implies a playable source is available. |
| Disney+ | Content hubs | Trending, top rated, release/airing lists and dynamically loaded TMDB genres are visible in the catalogue navigation. |
| Apple TV / HBO Max | Returning to saved and unfinished titles | My List stores TMDB metadata; Continue uses actual saved playback positions and encrypted recent source addresses. |
| Plex / Jellyfin | Control over a personal library | Preserve installed-app pins, favourites and personal sources; retain account and player preferences. |

Use one neutral dark surface palette, restrained corners, readable text and a high-contrast focus state across destinations. Keep the selection visible when focus moves elsewhere. Do not combine platform logos, proprietary branding or unrelated visual themes.

## Setup and settings

Settings has a stationary section menu and a connection overview with a status for each service. Connecting or changing one provider opens only that provider's form. Verify new credentials before replacing the saved connection. Keep password/key visibility optional, show validation failures inside the form and confirm disconnection. TMDB uses the 32-character v3 API key, not the read-access token.

## Verification

Instrumentation checks the home dock and main actions against actual visible bounds, verifies that catalogue categories are visible, checks separate connection forms, and checks details actions and cast text for clipping. Artwork screenshots use explicitly supplied test fixtures; production titles always come from TMDB. Provider/database/player regression tests remain enabled. Live authenticated provider access and installation on the user's TV require their respective external environments and are not inferred from these tests.

## Sources

- https://www.netflix.com/tudum/articles/netflix-new-tv-layout
- https://www.aboutamazon.com/news/entertainment/prime-video-updated-steaming-experience
- https://help.disneyplus.com/article/disneyplus-navigate-app
- https://support.apple.com/guide/tvapp/welcome/web
- https://help.hbomax.com/gr-en/Answer/Detail/000002505
- https://support.plex.tv/articles/customizing-the-apps/
- https://jellyfin.org/
- https://developer.themoviedb.org/docs/finding-data
