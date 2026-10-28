# Architecture

## Series continuation and automatic play

`SeriesPlayback` prefetches next-episode metadata on the existing I/O pool and verifies real episode numbers and air dates before crossing a season boundary. It updates both the season and the history key. `EpisodeOrder` holds plain-Java ordering rules. `AutoPlayback` bounds selection to five cached sources, ranks them through `SourceRanking`, and uses `VideoFiles` to select movies or exact episodes; screen-generation and cancellation guards prevent late automatic playback. `KeyBar` registers with the active `MainActivity` screen, so hardware colours are routed before focused widgets. Screen changes clear that registration.

MovieHub is a single native Android app (Java, no WebView, no Kotlin) in `android-tv-launcher/`.
All source lives in one package, `local.tv.home`, under
`android-tv-launcher/app/src/main/java/local/tv/home/`.

## The big picture

```
                        ┌────────────────────────────┐
  Android ─ HOME key ─▶ │ MainActivity (shell)       │── builds every screen through ──┐
                        │ lifecycle, navigation,     │                                 │
                        │ shared state, async()      │                                 ▼
                        └─────────────┬──────────────┘          ┌──────────────────────────────┐
                                      │ extends                 │ Feature classes (one per area)│
                        ┌─────────────▼──────────────┐          │ HomeScreen   AppDock          │
                        │ TvActivity (base class)    │◀─ uses ──│ CatalogueScreens  ...         │
                        │ buttons, text, rows, focus │          └──────────────┬───────────────┘
                        └────────────────────────────┘                         │ call
                                                                               ▼
          ┌──────────────────────────── data and services ──────────────────────────────┐
          │ MediaRepository (TMDB, Premiumize)   SourceRepository (Torrentio → MediaFusion │
          │ → Comet)   ChannelDatabase/Importer/Sync (IPTV)   Secrets (Keystore)  Network  │
          └──────────────────────────────────────────────────────────────────────────────┘
                                      │ play
                        ┌─────────────▼──────────────┐
                        │ VlcPlaybackActivity        │  fullscreen LibVLC player
                        └────────────────────────────┘
```

There are two activities. `MainActivity` is the launcher (it registers for the `HOME` and
`LEANBACK_LAUNCHER` intents) and draws every browsing screen. `VlcPlaybackActivity` plays one stream.

## How a screen is built

Screens are built in code, not XML layouts. Every screen follows the same pattern:

1. A feature method (for example `LibraryScreens.watchlist()`) calls `activity.screen(title, back)`
   for a scrolling page, or `activity.fixedScreen(title, back)` for a fixed viewport.
   That resets the page, closes poster feeds and sets what the Back key does.
2. It adds widgets to `activity.page` using the helpers in `TvActivity`
   (`text`, `button`, `chip`, `row`, `section`, `emptyState`, ...).
3. Network or disk work goes through `activity.async(job, done)`. `job` runs on a background pool.
   `done` runs on the UI thread **only if the user is still on the same screen**. Each screen change
   increments `activity.generation`, and late results from an old screen are dropped.

Feature classes hold a reference to the activity (`activity`) and to nothing else. They are created
once, when the activity is constructed, and keep almost no state of their own.

## Code map

### Shell and shared UI

| File                           | Responsibility                                                                                                                                                                                                                                                                                     |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MainActivity`                 | Activity lifecycle, Back handling, remote key routing, screen reset (`screen`, `fixedScreen`), background work (`async`), shared state (`page`, `prefs`, `secrets`, `media`, `generation`), starting playback (`play`). Holds one field per feature class.                                         |
| `TvActivity`                   | Base class of `MainActivity`. Colours, sizes (`dp`), focus styling and the standard widgets every screen uses, including form dialogs (`field`, `form`), list rows and `fullBleedBackdrop()`. Every button uses the one standard style (`button`, `styleFocus`); there are no pill-shaped buttons. |
| `PosterCards`                  | Poster card views, binding TMDB items to cards, creating `PosterFeed`s, the long-press poster menu.                                                                                                                                                                                                |
| `PosterFeed`                   | Recycled, append-only poster grid/shelf (RecyclerView) with paging, retry and focus preservation. See [docs/continuous-posters.md](docs/continuous-posters.md).                                                                                                                                    |
| `PosterTitle`                  | Two-line poster caption that scrolls when focused and too long.                                                                                                                                                                                                                                    |
| `BackdropView`                 | Full-bleed background artwork: covers the view pinned top-right, cross-fades between images, keeps the old image while the next loads. Placed behind a screen with `TvActivity.fullBleedBackdrop()`; used on Home and details.                                                                     |
| `Artwork`                      | Image download and caching for posters and backdrops: up to four downloads at once, decoded images in memory (an eighth of the app's memory, 12–64 MB).                                                                                                                                            |
| `ArtworkDiskCache`             | Artwork bytes on disk (`cache/artwork`, 80 MB, least recently used removed first), so images seen before load without downloading again.                                                                                                                                                           |
| `DpadRepeats`                  | Rate-limits held D-pad keys so a held direction cannot race through lists.                                                                                                                                                                                                                         |
| `SidePanel`                    | The app's pop-up: a rounded panel on the right with a title, message, options or ticked choices and standard buttons. Used everywhere instead of AlertDialog/PopupMenu, also by the player.                                                                                                        |
| `ErrorMessages`                | Plain-language explanation and fix (Try again / Open Settings) for a failed load, from the exception type only.                                                                                                                                                                                    |
| `Timings`                      | In-memory timings (TMDB, sources, Premiumize, artwork, press play to picture, buffering pauses) shown on Settings → System → Troubleshooting. Names and durations only.                                                                                                                            |
| `Diagnostics`, `ErrorLog`      | Logs failures the app recovers from (`adb logcat -s MovieHub`) and keeps the last 50 in memory for Troubleshooting: place, type and time only, without messages or account details.                                                                                                                |
| `Troubleshooting`, `SpeedTest` | Shares SettingsScreens’ sidebar layout and setting cards. Settings → System → Troubleshooting: internet speed test (Cloudflare, on request), connection checks for each connected service, playback timings, recent errors.                                                                        |

### Screens (feature classes)

| File                                | Screens                                                                                                                                                                                                                                                                                                                       |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `HomeScreen`                        | Home: top navigation, featured artwork, poster shelf (`HomeShelf`), clock, Update/Settings buttons, and a MemoryClean icon when that app is installed.                                                                                                                                                                        |
| `AppDock`                           | Installed apps (one entry per app: a package's TV and phone launcher entries with the same name are merged), the dock on Home (long-press Select to lift, move, remove), launching (remembers recently used), hiding, and the app menu.                                                                                       |
| `AllApps`                           | The Apps screen: search, Android settings shortcuts, recently used, and every app as a TV banner tile (three rows first, the rest straight after the first draw).                                                                                                                                                             |
| `CatalogueRows`                     | The Movies/Series start page: a hero area for the focused title above Netflix-style rows (Trending, Popular, Top rated, release lists, then genres). The focused row is pinned at the top of the rows area, rows load as they come near, and position is restored after opening a title.                                      |
| `CatalogueScreens`                  | The Movies/Series screen (`CatalogueScreen`): header, hero and either the rows page or a single-category poster grid ("see all", Browse and filter results), plus the Browse and filter overlay. Back from a grid opened from the rows returns to the rows. See [docs/tmdb-categories.md](docs/tmdb-categories.md).           |
| `CatalogueOptions`                  | The Browse and Filters sheets' contents: category and genre lists, region, years, languages, countries, streaming services and the "Refine results" form.                                                                                                                                                                     |
| `CatalogueSearch`                   | Live search: a large search box whose results appear as you type, as Movies, Series and People rows; recent searches as buttons; person/company/keyword search inside the Browse sheets. Also builds the round person cards used for cast.                                                                                    |
| `TitleDetails`                      | The details page for one movie or series: full-bleed artwork, title block and action buttons (resume, sources/episodes, My List, trailer), then "More like this" posters, collection and round cast photos.                                                                                                                   |
| `SeriesScreens`                     | Seasons and episodes, watched-episode marks.                                                                                                                                                                                                                                                                                  |
| `SourcesScreen`                     | Finds stream sources (Torrentio → MediaFusion → Comet fallback, Premiumize cache) and lists them.                                                                                                                                                                                                                             |
| `LibraryScreens`                    | My List (watchlist), Continue Watching, saved Premiumize library.                                                                                                                                                                                                                                                             |
| `ContinueWatching`                  | Plain-Java rules for the Continue watching rows: only titles watched for 3 minutes or more, one row per film or series whichever source played it, and a series as its newest episode in progress or, once that episode is finished, the next one (looked up with `SeriesPlayback.resolve`; the row goes when there is none). |
| `CloudScreens`                      | Premiumize cloud folders and transfers.                                                                                                                                                                                                                                                                                       |
| `LiveTvScreen`                      | Live TV as a classic TV guide grid: ten channels per page in satellite number order, time slots, colour-key paging, number entry, favourites, the live picture in the corner.                                                                                                                                                 |
| `DeviceMode`, `LayoutChooser`       | TV or phone layout: the first-start question (best guess suggested), kept in preferences, changeable in Settings.                                                                                                                                                                                                             |
| `PhoneLive`                         | Live TV in the phone layout: portrait touch channel list with tabs, search, now and next; the player gets touch buttons on its banner.                                                                                                                                                                                        |
| `GuideFrame`                        | The guide's fixed parts in the dark-blue classic look (as in 0.26): clock, list name and status, Options and the live picture, time slots, colour keys.                                                                                                                                                                       |
| `KeyBar`                            | The colour-key guide at the bottom of the guide and its menu; each key is a D-pad button too, for remotes without coloured buttons.                                                                                                                                                                                           |
| `GuideMenu`                         | The numbered guide menu (1 All Channels … 0 More…) that Live TV opens first and BACK from the guide returns to; its rows share the height so all fit, and its top parts (Reminders, Search, Options) can be chosen.                                                                                                           |
| `GuideRows`                         | The guide's rows: channel names and programme cells, moving along programmes and through time, SELECT to watch or set a reminder.                                                                                                                                                                                             |
| `LiveTvPanels`                      | The guide's side panels: options, sections and groups, search, programme information with reminders, the parental lock, colour-key set-up.                                                                                                                                                                                    |
| `GuidePicture`                      | The live picture in the guide's corner (the last channel watched); stops when the guide closes unless the full screen has taken the player over.                                                                                                                                                                              |
| `LiveStage`, `ExoLive`              | Live TV's one screen and its one ExoPlayer, shared by the full screen and the guide picture, so going between them moves the picture without reconnecting (see "ExoPlayer Live TV").                                                                                                                                          |
| `PlayerOptions`                     | LibVLC engine options for films and series (Live TV plays on ExoPlayer).                                                                                                                                                                                                                                                      |
| `TitleRatings`, `RatingActions`     | Thumbs up / thumbs down per TMDB title: plain-Java storage (`ui` preference `ratings`, `movie:603=1;tv:1399=-1`, newest first, at most 300), and the buttons on title pages and options in the Hold Select menu.                                                                                                              |
| `SubtitleStyle`, `SubtitleSettings` | Subtitle size and background from Settings → Playback: plain-Java choices, applied to films as LibVLC options (`--sub-text-scale`, `--freetype-background-opacity`) and to Live TV through Media3's `SubtitleView` (`ExoLive.attach`).                                                                                        |
| `SettingsScreens`                   | Settings tabs, account setup (TMDB, Premiumize, Xtream, M3U), languages, toggles.                                                                                                                                                                                                                                             |
| `FreeChannelsPage`                  | The Settings → Free channels tab: public lists, every free service on/off with channel counts, and Advanced (US-only, Masqueradarr, ZLive address, service updates). `ProviderSettings` keeps only the first-start question.                                                                                                  |
| `ForYou`                            | The Home "For you" shelf: blends TMDB recommendations for up to six titles you liked, played, saved or opened, ranked and cached on the box; titles given a thumbs down are never seeds or suggestions.                                                                                                                       |
| `UpdatePrompt`                      | Checks the public release feed and offers a verified update. See [android-tv-launcher/UPDATES.md](android-tv-launcher/UPDATES.md).                                                                                                                                                                                            |

### Data and services

| File                                                                   | Responsibility                                                                                                                                                                                                                                                                                                                                                          |
| ---------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MediaRepository`                                                      | TMDB and Premiumize HTTP API calls; Xtream account checks.                                                                                                                                                                                                                                                                                                              |
| `SourceRepository`, `DefaultSourceProviders`                           | Stream source providers and their endpoints; new film and series sources go in `DefaultSourceProviders.EXTRA` (see AGENTS.md, Adding sources).                                                                                                                                                                                                                          |
| `TmdbCategories`                                                       | TMDB discovery routes, genres and filters (no account state), including the Anime section's rows (`with_genres=16&with_original_language=ja`, series and films mixed; `typeOf` tells a row's type from its route).                                                                                                                                                      |
| `Network`                                                              | Bounded HTTP(S) streams with manual redirect handling; bearer tokens only sent over HTTPS to the same host.                                                                                                                                                                                                                                                             |
| `Playlist`                                                             | M3U/Xtream playlist parsing and URL validation (refuses `http://` unless the user allows it).                                                                                                                                                                                                                                                                           |
| `ChannelDatabase`, `ChannelStore`, `ChannelQuery`, `ChannelGuideStore` | On-device SQLite channel database, paging and search. All channels, sections and countries are read from each channel's preferred copy (`main=1`, indexed); favourites, groups and searches group the copies.                                                                                                                                                           |
| `ChannelSchema`                                                        | The channel database's tables, indexes and version upgrades (version 8 adds `main`; version 9 recomputes duplicate keys).                                                                                                                                                                                                                                               |
| `ChannelIdentity`, `ChannelCategory`                                   | Which copies are the same channel across lists and providers (country + plain name, quality and country tags dropped, +1/regions/UHD kept apart), and the guide section for a provider channel (plain Java).                                                                                                                                                            |
| `EpgImporter`, `EpgMatch`, `EpgLookup`                                 | The combined public XMLTV programme guide: conditionally cached and streamed by `CombinedEpg`, matched to exact native and country-scoped channel identities, with yesterday through seven days ahead stored locally. `EpgMatch` also reads XMLTV times (plain Java).                                                                                                   |
| `SkyNumbers`, `GuideOrder`, `ChannelNumbers`                           | UK satellite channel numbers and sections (`SkyNumbers`, a fixed table matched by name); the whole guide's layout: UK blocks by section, regions in the 800s, radio in the 900s, other countries from 1001 (`GuideOrder`, plain Java); applied to the saved channels after every import (`ChannelNumbers`).                                                             |
| `Reminders`, `ParentalLock`                                            | Programme reminders (kept in preferences, shown while the app is open) and the Live TV PIN lock (a salted hash of the PIN, locked channels and sections).                                                                                                                                                                                                               |
| `FreeChannels`                                                         | Which free lists Live TV imports (Free-TV, then iptv-org by country, all, or off); "automatic" (the full list) only without a provider, imported when Live TV is first opened. New TV channel lists go in `FreeChannels.EXTRA` (see AGENTS.md, Adding sources).                                                                                                         |
| `ChannelImporter`, `ChannelSync`                                       | Background channel import (WorkManager), refreshed every 12 hours. Order: accounts, native Pluto, free lists, then FreeLiveSports and the optional providers (which can take minutes each), with XMLTV populated before optional providers and fetched again only if channel identities changed. Each source commits on its own, so its channels appear as it finishes. |
| `Secrets`                                                              | Account details encrypted with an Android Keystore key; the key and read values are cached in memory.                                                                                                                                                                                                                                                                   |
| `VideoFiles`                                                           | Chooses the right file in a multi-file release: the film beside extras, or the episode in a season pack (exact matches can play straight away; likely ones are only marked "Suggested episode"). Plain Java.                                                                                                                                                            |
| `SkipQueue`                                                            | Combines quick film skips and seek-bar moves into one jump, 750 ms after the last (plain Java).                                                                                                                                                                                                                                                                         |
| `AppUpdates`, `UpdateApkProvider`                                      | Downloads and verifies update APKs; shares the verified APK with the system installer.                                                                                                                                                                                                                                                                                  |
| `VlcPlaybackActivity`                                                  | Fullscreen film and series playback (LibVLC; Live TV never opens it): tracks, subtitles, speed, picture ratio, sleep timer, resume positions by title, so another source carries on (`ResumePoint`; opened directly at the resume point), skips combined into one jump (`SkipQueue`), an end that is only a real finish when enough played (`PlaybackEnd`; otherwise a failed source, never watched or Up next), Back twice to leave. Controls are flat icons (`res/drawable/ic_player_*`).        |
| `PlayerOverlay`                                                        | Builds the player's on-screen controls (title and download speed, timeline, flat icon controls, Back hint).                                                                                                                                                                                                                                                             |
| `LiveZapper`                                                           | Live TV while watching: the satellite remote's keys, channel up/down, number entry, last channel, browsing other channels in the banner, the PIN lock, and backup copies with automatic switching.                                                                                                                                                                      |
| `SkyFont`                                                              | The Live TV typeface (Hind, OFL, in `assets/fonts`), with the system font as a fallback.                                                                                                                                                                                                                                                                                |
| `SafetyNotice`                                                         | The first-start "Staying safe" notice (also in Settings → System) about scam messages in streams.                                                                                                                                                                                                                                                                       |
| `SkyOverlay`                                                           | The full-screen Live TV overlay in the classic satellite-box style: banner, programme information panel (with a picture/source line) and pause bar.                                                                                                                                                                                                                     |
| `VideoDetails`                                                         | Formats the picture playing (size, 1080p/720p name, frame rate, codec, bitrate) for the information panel; `ExoLive.videoDetails` feeds it through `LiveScreen.videoDetails`; the film player uses it directly.                                                                                                                                                         |
| `QualityClaims`                                                        | Plain-Java rules for movie source quality: judging a website's claim against the real picture, the last-five history that makes a site distrusted, the label once a source has played, and which confirmed better source to mention. `FreeVodProviders.judge`/`history` store each website's history in the `ui` preferences.                                           |
| `VodQuality`                                                           | While a film plays, reads the real picture size from VLC after 15 s, corrects the Sources label, judges the website, and mentions a confirmed better source (never switches by itself). Owned by `VodPlayback`.                                                                                                                                                         |
| `PlayerMenus`                                                          | The player's side panels: audio, subtitles, video track, speed, picture size, subtitle timing, sleep timer.                                                                                                                                                                                                                                                             |

## Where data is stored

| Store                                        | Contents                                                                                                                                                                                                                                                                                                                                                                                                          |
| -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SharedPreferences `ui`                       | UI settings and lists: accent colour, clock, `http` toggle, auto-updates, dock order, pinned apps, hidden and recently used apps, My List, watched episodes, search history, For you interest (`interest`), thumbs up/down (`ratings`), catalogue region/genre cache, Live TV reminders, the last channel watched (a hashed stream key), and the parental lock (a hash of the PIN, locked channels and sections). |
| SharedPreferences `accounts` (via `Secrets`) | Encrypted: TMDB key, Premiumize key, Xtream and M3U details, saved library, recent playback.                                                                                                                                                                                                                                                                                                                      |
| SharedPreferences `positions`                | Playback resume positions, by title (`title:movie:<TMDB id>`, `title:tv:<TMDB id>:<season>:<episode>`) and by file id (`ResumePoint`).                                                                                                                                                                                                                                                                            |
| SQLite `channels-v2.db`                      | Imported IPTV channels (encrypted stream URLs), groups, channel numbers, sections and countries, which copy of each channel is preferred (`main`), favourites, and the programme guide.                                                                                                                                                                                                                           |
| Cache `artwork/`                             | Downloaded public artwork (TMDB images, channel logos), at most 80 MB. No account data.                                                                                                                                                                                                                                                                                                                           |
| Cache `updates/`                             | The downloaded update APK, before installation.                                                                                                                                                                                                                                                                                                                                                                   |

## Design decisions

- **One package, many files.** Splitting into sub-packages would force most members to become
  `public`. Keeping one package lets feature classes share package-private members while each file
  stays small. The file names above are the map.
- **Feature classes instead of Fragments.** The original app was one activity that swapped views.
  Moving each area into a plain class kept that behaviour exactly (no lifecycle changes) while
  making the code navigable. Fragments would be a behavioural rewrite.
- **Base class for widgets.** `TvActivity` holds the view helpers so `MainActivity` code can call
  `text(...)` and feature classes can call `activity.text(...)` with no extra indirection.
- **Recovered failures are logged, not shown.** Where the app deliberately carries on after an
  error (for example a failed artwork download), it calls `Diagnostics.recovered(...)`, which writes
  the location and exception type to logcat under the tag `MovieHub`. Exception messages are not
  logged because they can contain provider URLs with account details.

## Native free TV

`PlutoSource` implements regional anonymous discovery, in-memory session/catalogue caching and resolve-on-play. `NativeHttp` bounds and cancels HTTPS requests. `NativeSources` connects catalogue and programme refresh to the existing SQLite store and ChannelImporter. ExoLive resolves stable native locators asynchronously; the same picture is shared by the guide and the full screen. Built-in programme refresh uses only the combined guide. Native Pluto is preferred to public-list copies, after connected accounts. See [provider scope and evidence](docs/NATIVE_FREE_SOURCES.md).

## Experimental standalone JavaScript providers

`ProviderScript` executes compiled upstream leaf functions in the isolated `ProviderService`;
`ProviderBridge` uses bounded file-descriptor IPC. `EngineSources` connects FreeLiveSports discovery,
on-play resolution and channel metadata to SQLite and the shared live player (`ExoLive`). `ProviderPackages`,
`ProviderSignature` and `ProviderUpdates` implement signed package checks, atomic slots and rollback.
Remote updates fail closed without a pinned key. See [scope and limitations](docs/STANDALONE_PROVIDER_ENGINE.md).

## Runtime 2 provider compatibility

`ProviderBroker` services bounded async HTTP continuations from isolated scripts. `ProviderCodec` supplies
pure crypto/compression primitives; `AdapterSources` integrates the historical FAST, DaddyLive, Tubi and Local Now
modules with SQLite and settings. `ProviderRelay` adds session-scoped loopback HLS header transport and
`ProviderSegments` ports upstream TS-prefix detection. See the updated [provider architecture and evidence](docs/STANDALONE_PROVIDER_ENGINE.md).

`ProviderSessions` retains encrypted ZLive HTTP/DNS event history and replays it through the original
resolver without repeating those requests, preserving token reuse, cooldowns and decoy latches across
disposable processes. Native IP/subnet primitives supply the required Node `net` subset. Signed token
expiry can renew an actively playing live stream; stopping, switching or pausing does not auto-start video.

DaddyLive's current anonymous playlist protocol is adapted by `provider-engine/tools/dlhd-session.js`
without modifying the historical upstream files. It learns literal CDN/channel declarations from player
HTML and returns session metadata after validating a tokenized manifest. `PlaylistSession` renews tokens
inside the existing `ProviderRelay`, scoped to one HTTPS authority and channel directory. The relay uses
`ProviderPngSegments` for bounded PNG-to-MPEG-TS unpacking only for this session type. Direct sources and
other providers retain their existing paths; fullscreen/mini-player share the same relay session.

`ProviderFailure` classifies fixed diagnostic summaries and HTTP status codes without copying exception
messages. `ProviderBroker` retains safe transport failure categories across replay and wraps failed script
steps with the observed HTTP/network cause. `ChannelImporter` stores the summary; `FreeChannelsPage`
puts it first in the service's description. Settings cards use minimum height plus wrap-content. Guide label sizing
fits row height while retaining horizontal ellipsis.

`ChannelSync.refreshIfStale` also refreshes once when the app version that last finished an import (`refreshedVersion`, recorded whatever the outcome, since `lastSuccess` needs no failures at all) differs from the running one. `ProviderOutage` turns those fixed summaries into the guide's one "Unavailable now" line, grouped by
actual failure (timed out, HTTP status, DNS…). It never infers geo-blocking: a service down for a
day looks the same as one refusing a country. Known US-only services (`ProviderOutage.US_ONLY`: LG,
Roku, Local Now) say "US only" in their labels, and the opt-in `HIDE_US_ONLY` setting makes
`AdapterSources.enabled` skip exactly those. `LiveRace` (rules in `LiveRacePlan`, core-tested; the first choice waits up to `TIE_MS`, 250 ms, for an equal copy ahead in priority order, so plain addresses that resolve within milliseconds are not chosen by thread timing) runs when an experimental channel with several
copies starts with automatic sources: at most three lookups and unused standbys together, quality
from `SourceRanking.resolution(name)` with the guide's copy order as tiebreak (a copy cooling down
after a failed upgrade ranks last), and the quickest copy found plays at once (the three-second wait
for a better copy was removed in October 2026), then `ExoLive.playResolved` with the found link.
Failures go to `LiveRace.next()` (the best standby, else the next lookup) before the zapper's failover.

`LiveUpgrade` (rules in `LiveUpgradePlan`, core-tested) then moves the full-screen channel to a better
copy as soon as one is found. Once a second, with automatic sources, it picks the untried copy whose
name claims the most lines above the copy playing, judged by the better of that copy's name and the
best video track ExoPlayer reports (`ExoLiveTracks.bestVideoLines`), so a mislabelled SD copy that
is really 1080 is not "upgraded". It waits while the race is still choosing or looking that copy up,
takes the race's standby link when there is one, else looks it up itself (`LiveRace.find`) and, for
a plain address, checks it answers (a 2xx status and first bytes within 5 s; an `.m3u8` must start
with `#EXTM3U`). An unreachable copy is marked tried. The found copy plays through
`LiveStage.upgradeTo` with the race's 8 s startup budget and is on trial for 60 s. During the trial,
`hasAlternative` is true, so ExoLive's ported buffering rules (8 s stuck or 3 stalls a minute), an
error or a missed start report it failed at once, and `LiveStage.showError` hands it to
`LiveUpgrade.failed`: the copy before plays again (`upgradeFellBack`, a fresh `ExoLive.play`) and the
failed copy cools down for 10 minutes, doubling on each repeat up to an hour, kept by its channel key
across channel changes for the app session. A copy that holds its trial resets its cooldown length.
Quality only rises and a copy that fell back cools down, so switching cannot loop. A new channel, a
copy chosen by hand (which turns automatic sources off) or by failover, reconnecting, and leaving full
screen reset any upgrade in progress. Upgrades also wait while the viewer has paused or is behind live.

Pausing Live TV (`ExoLive.pause`) marks the picture behind the live broadcast until Back to live
(`ExoLive.backToLive`: the live edge of an HLS/DASH live window, else `LiveStage` reopens the
channel) or a new opening. `LiveStage.showPauseBar` shows SkyOverlay's pause bar for both states;
the fast-forward key and Options → Back to live catch up.
Experimental Live TV moves to the
next source after `LiveStage.START_MS` (15 s) without a picture, or at once on an error, whenever
`LiveZapper.hasUntriedCopy()`; with no other copy it keeps the old reopen-and-wait behaviour. Plex plays by
the compound channel id from its catalogue row (`row.id`), so it does not skip the catalogue on a cold
resolve. DistroTV and Vidaa were removed in October 2026: both services' API hosts no longer resolve. `CombinedEpg` fetches the single server-generated compressed guide, using conditional requests and private file caching, validates before replacing anything, then streams only saved channel identities through `EpgImporter.parse` and SQLite in a background task. `ChannelDatabase.combinedGuideKeys` translates canonical native ids and country-scoped aliases. Complete transactions retain still-usable old listings absent from a healthy snapshot, without extending their timestamps. The window is yesterday through the next seven days. `ProviderGuide` remains only for offline parser regression fixtures; production native/adapter refreshes do not fetch or import programmes. The upstream source inventory, priorities, merging, native public catalogue extraction and coverage reports live in `Moviehub-epg`.

`ZliveSources` expands advertised supported resolver routes, deduplicates them and stores bounded encrypted resolver hints. `source_label` is additive database schema version 10; original primary channel identities keep favourites intact. Older signed provider packages without the ZLive source-schema capability use the compatible bundled ZLive module; other module/update/signature rules remain. `ProviderContainerSegments` supports PNG, WebP EXIF, raw TS and gzip TS carriers when a ZLive stream declares that capability, without changing HTTP refusals. Exhausted ZLive source attempts stop and offer manual recovery; a lone startup is bounded at 60 seconds. Existing race/backup timing and buffering stay unchanged.

`LiveProgress` is the pure progress rule for Live TV (picture and clock movement); `ExoLive` applies it through `LiveStallRules` (see "Live stream rules ported from the LibVLC player").

PNG transport skips byte-wise unfiltering for filter-0 rows, copies RGB payload in bulk, and uses larger bounded inflate/gzip input buffers. Explicit Inflater instances are ended in finally; container validation and allocation limits are unchanged.

Three small helpers sit alongside these. `LiveMenus` is the interface for the Live TV side panels
(audio/subtitles, sources, options) that the banner and colour keys open; `ExoMenus` implements it for
`LiveStage`. `LiveSourceDeadline`
(core-tested) gives `LiveStage` its source budgets: 15 s before trying an alternative, 8 s when sources are
raced, and 60 s for a final ZLive attempt with no alternative. `ProviderUrls.playback` validates
provider-supplied playback URLs (length, line breaks, literal spaces from browser User-Agent macros) for
`EngineSources`, `NativeHttp` and the ZLive address setting.

LiveZapper owns the per-channel Automatic/manual source policy and two-step Back interaction. PlayerMenus lists Automatic followed by saved copies; The source name in SkyOverlay's title strip is text only; the green Source key opens the list. Listing copies is a local database operation; only the selected copy is looked up (`ExoLive`, or the source race). Automatic failover visits the remaining saved copies once and stops.

`provider-engine/tools/freeshot.js` parses the FreeShot related-channel directory and follows only the selected UK sports channel’s two literal iframe links. ProviderBroker permits only FreeShot directory/embed and WideIPTV player paths. PlaylistSession supports the fixed WideIPTV anonymous JSON renewal endpoint, scoped to one discovered BlueTier origin/channel path; ProviderRelay posts renewal bodies and rewrites tokens without exposing them to VLC. Zero-to-end range requests fetch and decode the complete wrapped segment rather than applying decoded offsets to its PNG container.

`LiveZapper.activeAutomatic` and `LiveZapper.attemptedSources` keep the current source mode and attempted source keys across the guide and the full screen (each builds its own zapper). LiveTvScreen matches the active stable URL’s saved SHA-256 key against the selected channel’s copies before opening full screen. LiveZapper restores the matching copy/mode/attempt history, and resets them on a new channel. These comparisons read local metadata only.

Restoring a retained source compares saved hashes rather than decrypting every backup URL; it performs no additional stream lookup.

## Reviewed playback integration (22 October 2026)

`VodSources` retains at most two short-lived sessions of up to five cached candidates, identified by random intent tokens. Manual Sources and AutoPlayback pass the selected file and remaining cached candidates. Alternative direct links resolve on demand through the existing repository; the session holds no Activity. A process restart loses alternatives and safely retains ordinary single-source history playback.

`VodSourceFailover` owns plain-Java thresholds and source exhaustion. `VodPlayback` owns asynchronous resolution, cancellation generations, a 45-second deadline per candidate, and a muted second VLC player using the film engine. The original player continues until video decodes within two seconds of its position. Replacement uses a TextureView so staging can be transparent on API 28; initial VOD and shared Live TV retain their existing SurfaceView path. Insufficient decoder resources may prevent a replacement: failure keeps the original player. Live thresholds and the shared live instance are independent.

## Live buffering thresholds

`LiveBuffering` owns monotonic, edge-counted live thresholds: 8 seconds or 3 starts per 60
seconds, excluding startup and pause. Movies/series retain their independent 4-second/2-start
rules (`VodSourceFailover`) and the LibVLC network buffer `PlaybackBuffer.VOD_MS`. `ExoLive`
applies the live thresholds through `LiveStallRules`; provider link renewal is in `ExoLive` too.

## Live TV control consistency

Full screen copies `LiveZapper.Session` through `LiveGuideState` (`LiveStage.fullScreen`), so the guide's later changes cannot move it. The initial database lookup matches the launch URL and falls back to all channels if its saved list excludes that channel. Learned colour actions precede generic number/channel shortcuts.

Player-owned `SidePanel` dialogs route recognized colour keys through `LiveZapper` after closing, because dialog input does not reach Activity dispatch. Live colours complete on uncancelled release. `SkyOverlay` reserves the channel strip independently of programme content, with room to expand for larger fonts.

## Live playback recovery

`LiveStream` finds a channel's playable link (`resolve`, with an optional device-test fixture, `ExoLive.testResolver`); playback, selection and recovery remain in `ExoLive` and `LiveStage`. `LiveRestartBudget` allows two reconnects and replenishes only after 30 seconds of observed progress. Native Pluto invalidates its anonymous session on retry. `LiveNative` now serves only the film player (through `VodNative`): it serializes LibVLC stop/video-disable operations per player on two bounded workers, and disposal follows completion, never a timeout.

Three short buffering notifications move to an alternate source only when one waits. A same-source reconnect requires prolonged buffering (eight seconds) or stopped picture and clock; ordinary HLS period notifications do not consume the reconnect budget.

## Consistent live banner and standard colours

The live overlay measures the channel banner before optional pause/information panels, so those panels cannot consume its layout height. The channel strip has an explicit height scaled for text size. Standard PROG_RED/GREEN/YELLOW/BLUE identities precede learned aliases; function-key aliases remain configurable. Player and dialog colour handlers act on the initial key-down and consume repeat/up events without reopening menus.

## One live overlay and shared colour input

Live TV never uses the film controls (PlayerOverlay); SkyOverlay is the sole visible live controls view. Entry calls LiveZapper.showBanner; OK and a click on the picture call LiveZapper.ok (a tap on a phone calls LiveZapper.tapped), which shows that same banner. On a TV its buttons are always boxed (`SkyOverlay.boxControls`); ◀ ▶ move between them and OK presses the highlighted one. Back focuses the green Source key (`SkyOverlay.SOURCE_KEY`). Colour set-up stores key codes, or scan codes for buttons Android reports as KEYCODE_UNKNOWN (`LiveTvScreen.COLOUR_SCAN_PREFS`); KeyBar and the player share `LiveTvScreen.colour(prefs, event)`. Options remains an explicit blue/Menu/selected-button action. Activity and SidePanel route colours through one session-owned ColourPress before native-player access, recognizing the first delivered press, standalone release or complete multiple event while suppressing duplicates across changing windows. Unknown translated keys can use Android TV Generic.kl’s standard colour scan identities (398–401); known non-colour keys are never overridden.

## Progressive free VOD discovery

SourcesScreen delegates discovery to FreeVodScreen and VodSearch. SourceWindow holds the
three-second initial ranking window. Eight workers query the bundled Scrubs/Free99 modules
through FreeVodProviders and Chaquopy; an optional Premiumize search runs alongside them.
The overall search budget is 120 seconds, with per-provider HTTP/processing deadlines.
Free sources retain already-resolved file links and headers in SourceRepository.Source.
VodSources.progressive appends late results without reordering existing session indices.
VodSourceFailover.appendSources preserves failed sources, pinning and buffer history.
VodPlayback uses session-owned ProviderRelay instances when host headers are required.
A new title replaces the previous search; screen-generation checks and player-session cleanup
prevent abandoned screens from starting playback. App screens remain native Java.

## VOD incoming traffic

Free HLS and media-file sources use the existing session-scoped `ProviderRelay`, including header-free URLs. Header-free DASH retains native base-URL handling and VLC statistics. It counts upstream payload reads and `StreamRate` converts active-relay deltas to Mb/s; backup prefetches have separate counters. Direct cloud playback continues using VLC statistics. `VodSearch` ranks picture quality first, then bounded transfer sample and response time; HLS manifests provide variant resolution, not a fictitious media throughput.

## Free VOD diagnosis and safe replacement

`VodSearch` records every module's sanitized outcome and permits 180 seconds for eight 18-second workers, while retaining the three-second first-selection window. `FreeVodProviders.search` returns the bridge outcome as well as progressive sources. `moviehub_compat.media` reads declared MP4/HLS/DASH duration from bounded probe bytes. Free videos with known duration below 120 seconds are rejected during probing and again by the active/prepared decoder. Unknown duration remains unknown; live/event playlist windows are not movie durations. `VodNative` reuses the existing off-main native-stop coordinator, retaining the player window and engine until shutdown completes. Free manual source choices allow cross-provider recovery; cloud pinning and buffering thresholds remain unchanged.

## Provider compatibility follow-up

The requests adapter preserves explicit GET/POST independently of the body, including
redirect method/cookie rules. The legacy client supplies default Referer/language headers.
FreeVodScreen forwards TMDB original/alternative titles; moviehub_vod applies each family's
scene title and episode mapping. Work is bounded to 30 seconds per provider and 300 seconds
for the eight-worker search; selection still begins after three seconds.

Provider-request read timeouts now honor explicit upstream values within the remaining
search budget, with an eight-second connection cap. A regression checks a 20-second read
request and clipping to a five-second remaining budget; cancellation remains bounded.

## VOD window lifetime during asynchronous shutdown

Films use TextureView output, as prepared VOD replacements already do. VodNative installs
a VodSurface listener before starting the native join. If Android detaches the Activity's
window first, the listener retains its SurfaceTexture and defers VLC's destruction callback.
After native stop returns, detachViews closes VLC's output and the retained textures are
released. An unsuccessful native join keeps the window/engine quarantined. Live TV's
retained guide/fullscreen output is unchanged.

## ExoPlayer Live TV

Live TV plays through `ExoLive`, on Media3 ExoPlayer 1.5.1 (HLS and DASH). Until 1.0 this was only in
the separate experimental build, with a stub in normal builds; it is now the one Live TV engine.
`MainActivity` always builds `LiveStage` and sends every Live TV channel there. Films and series still
play through LibVLC (`VlcPlaybackActivity`), which has no Live TV mode.

The LibVLC live player was removed on 27 October 2026, after an audit showed nothing could reach it
(`VlcPlaybackActivity` is not exported, and `MainActivity` only opened it for Live TV when `LiveStage`
was missing, which never happened): `LivePlayer`, `LiveBackup`, `LiveVideoOutput`,
`LiveSurfaceProbe`, `LivePresentation`, `LivePictureRates`, `LiveRepairBudget`, `LiveHandoffCover`,
`LiveHost`, `PlayerOptions.live`, the live profiles of `PlaybackBuffer`, the Live TV mode of
`VlcPlaybackActivity`, `PlayerMenus` and `PlayerOverlay`, and the VLC branches of `GuidePicture`,
`LiveTvScreen` and `LiveZapper`. Its general stream rules had been ported to `ExoLive` first; its
surface and decoder workarounds were not needed. Git history keeps the removed code.

Live TV is one screen, `LiveStage`, owned by `MainActivity`: its root holds
the page host (only Live TV pages: the TV Guide menu, channel guide and phone Live TV, which call
`fixedScreen(…, true)`; every other page is shown on its own and `LiveStage.release()` stops the
picture and takes the stage off screen), then one
`SurfaceView` that is only ever placed over the guide's corner box or stretched to full screen,
then an overlay (the guide picture's label, status text and the satellite banner). `MainActivity.play` for Live TV calls `LiveStage.fullScreen` instead of opening a player
Activity, so the guide stays underneath and BACK returns to it unchanged. A channel started from a
page outside Live TV (a reminder's Watch now) first moves that page into the stage (`adopt`), so BACK
returns to it. `MainActivity.dispatchKeyEvent`
sends keys to `LiveStage.key` while full screen. The banner and
channel changing are the same `SkyOverlay` and `LiveZapper`, through the `LiveScreen` interface
(implemented by `LiveStage`). `ExoMenus` holds the
audio, Sources and Options panels. Link lookup still goes through `LiveStream.resolve`
unchanged. Recovery: behind-live-window rejoins; other errors and 15 s stalls reopen the channel,
then `LiveZapper.failover` moves to a backup copy. `ExoLiveTracks` holds audio/subtitle selection and
`ExoLiveLog` the Player event log (both split out of `ExoLive`, unchanged).

### Live stream rules ported from the LibVLC player

`LiveStallRules` (pure, core-tested) gives `ExoLive` the general rules the LibVLC live player had,
reusing its rule classes unchanged. Buffering (`LiveBuffering`): 8 s stuck or 3 stalls a minute,
counted only after the first picture of an opening and never while paused. With another source
waiting (`Listener.hasAlternative`), that source is reported failed at once, so `LiveStage` moves to
the race's next copy or `LiveZapper.failover`; with none, only a long stall with no picture movement
in the last 2 s reconnects. Dead stream (`LiveProgress`): playing with neither picture nor clock
moving for 8 s and no error recovers the same way; a still picture while the clock (sound) moves is
only logged. Reconnects (`LiveRestartBudget`): two per selection, refilled after 30 s of moving
picture, replacing the old reset on every first frame, which let a stream that played briefly and
died reopen forever. A reopen clears the provider cache once per selection (`EngineSources.invalidate`)
and Pluto's session, so the link is looked up afresh. Provider links with an expiry
(`EngineSources.Stream.expiresAt`) are renewed a minute before they expire (at least 30 s, at most
three hours, after opening); the old link plays during the lookup and a failed renewal leaves it
playing. A new channel, a source chosen by the race or by hand, or an explicit restart is a new
selection with a fresh budget; an internal reopen is not.

Not ported, as LibVLC-specific workarounds (since removed with that player): view refreshes for a
still picture and their repair budget, surface probes, software-video fallback, native stop
serialisation and the muted prepared backup (the source race's standbys serve that purpose). The 4 s
relayed-stream buffer was a LibVLC option and was not ported either; ExoPlayer keeps its default
buffering. The LibVLC screen's picture click (an air mouse's OK, a phone tap) was ported to
`LiveStage`.

### Experimental retry and screen lifetime

ExoLive cancels its delayed reopen and its cancellable NativeHttp/Future resolution on every
new open and stop. Generation checks discard late results and close unused relays. Stopping
detaches PlayerView, releases ExoPlayer and drops static view/owner/listener references and
frame polling. LiveStage cancels screen timers in the background and on each new selection;
a full-screen return creates a fresh player. Destroying MainActivity disposes the stage.
Exhausted-channel retries wait for the new attempt to fail before starting another cycle.
A still picture over moving sound remains diagnostic, with no automatic repair; see the ported
stream rules above for stalls and dead streams.

### Experimental DaddyLive startup

ProviderBroker routes experimental DaddyLive resolution through ProviderSessionBridge and a
separate non-exported isolated FastProviderService. One binding lasts for the selected lookup,
not one continuation; unbinding still kills the entire heap at completion/cancellation. Every
step has a fresh safe Rhino scope. A two-entry compiled-code cache exists only in that isolated
process. Legacy providers keep the per-step disposable service. Fast lookup has a 25-second
network deadline, clipped socket timeouts and failed-request clock accounting. Socket timeout
is distinguishable from cancellation, allowing alternate-player lookup within the budget.

DlhdPageCache keeps at most eight public mirror pages and four MiB for 30 seconds, keyed by
module fingerprint and exact request. It never caches embeds, tokens, playlists, media or
authenticated requests. A failed selected player clears these page hints and is retired through
AdapterSources.failed before ExoLive reopens it. Ordinary HLS parsing/behind-live-window handling
is unchanged.

An exhausted DaddyLive resolver reports to source failover immediately, rather than repeating
the entire unsuccessful walk through ExoLive's two reconnects. Available sources still use
the existing automatic/manual policy; established-stream retry behaviour remains separate.

Public mirror responses are compacted to the exact iframe, premium-player URL and data-url
patterns consumed by the upstream parser, avoiding repeated parsing of unrelated page markup.
Pages declaring the token protocol are retained verbatim. The bundled parser is exercised
through a 500 KB fixture page and locator-preservation checks.

Markup compaction is enabled only for the exact bundled transform. Independently downloaded
provider revisions receive whole pages, with separate cache entries, so parser updates cannot
lose inputs that a new parser needs.

## Country-scoped programme lookup

`EpgLookup` uses the same channel identity/country rules as channel grouping. Provider-native listings win when usable; otherwise a channel with known country may use that country’s XMLTV keys. Unknown native countries cannot borrow the default UK schedule. XMLTV maps are country-scoped, so equally named US/UK channels never collide. A guide claims keys only after fully parsing usable listings, keeping empty/stale/failed guides from hiding fallback data. `GuideWriter` advances an in-memory revision after commit; the TV guide reloads its current page when that revision changes during a channel import.

`ChannelGuideStore` holds programme identity mapping, guide queries and snapshot transactions; `ChannelDatabase` delegates its existing entry points without changing their behavior.

## Experimental exp13 provider follow-up

`VodResume` re-fetches TMDB title and exact episode metadata before entering the normal source-search path; only direct/cloud records without a TMDB identity reopen their saved address. History deduplicates by title/episode identity and progress remains in `ResumePoint`. `moviehub_vod.search_providers` groups adapters by their verified website domain, excludes Kodi-library/API-key modules from anonymous discovery, filters media capabilities and retains alternate adapters under one site budget. The source picker groups usable links by website plus actual playback provider; `VodSearch.sourceKey` preserves distinct named backends even when they return the same CDN URL. Each website may search until `moviehub_vod.SITE_BUDGET_S` (400 s); the whole search (`VodSearch`, 420 s) cancels any site still running, and every request keeps its own timeout. Until 1.0.2 each site had 30 s for discovery and 15 s for host resolution, which cut off slow sites such as fzmovies before they found a link.

`provider-engine/tools/zlive.js` implements the observed public ticket -> encrypted JSON POST -> location flow. `ProviderCodec` supplies bounded AES-256-GCM encryption as a pure-data operation. The broker allows this POST only at the selected ZLive domain's exact `/resolve` route, including redirect checks. Raw catalogue IDs are retained. Session history reuses locations for ten minutes, bounds fresh requests, retains refusal cooldowns and migrates successful old-protocol history without replaying obsolete requests.

A cold ZLive start (no saved source hint) never downloads the catalogue: `AdapterSources.resolverRow` matches old IDs without `auto-`/`evt-` only against an in-memory catalogue, otherwise passes the raw ID to the resolver; only source variants need their catalogue row. `moviehub_vod.query_site` returns `timeout` before reading title fields when the search is already cancelled.

## VOD provider recovery

Discovery offers 36 websites (39 adapters) and 70 modules (68 Kodi and two native). Eleven domains are retired or repurposed and 18 provisional restorations are in `UNVERIFIED_SITES`, disabled pending positive verification. Only Levidia is restored from the original 26 exclusions. `include_retired=True` exposes both groups for explicit audits; transient failures do not prove permanent closure. Both Levidia adapters have maintained parsing fixes. Header-only client operations retain redirects and cookies without reading media bodies. Cross-authority redirects remove an explicit Host alongside credentials. Already-resolved file paths still pass media validation even when an upstream scraper labels them indirect.

## Site-by-site VOD recovery (26 October 2026)

`moviehub_compat/current_hosts.py` implements inspected Xpass/Wplay, Waffi mirror/online-player and MoviesAPI player flows without executing remote JavaScript. Website adapters retain current search/player protocols. `moviehub_vod.py` preserves website/source-provider identity and redacted per-host/request diagnostics; `FreeVodProviders` carries the existing segment-container flag into the relay. `VodSearch` counts website jobs separately from host results.

`moviehub_compat/tubi.py` follows Tubi’s current anonymous guest-session handshake and public movie search. It retains only matching clear HLS resources and does not supply the guest API token to media hosts.

## Native movie source expansion (26 October 2026)

`moviehub_native` contains independent 4KHDHub and Vixsrc website adapters and a separate inventory. `moviehub_providers.json` remains the 68-module Kodi provenance inventory. `moviehub_vod` combines the inventories for discovery and reuses the existing bounded transport, resolver, source labels and media checks. Native adapters do not execute downloaded scripts.

`ProviderFileRanges` supports the observed hosts that reject whole-file and open-ended HTTP requests. It joins finite 4 MiB upstream ranges into one continuous, seekable response, checks byte offsets/length/entity identity and retains normal public-URL and redirect checks. Sources explicitly marked `boundedRanges` use it immediately. A validated progressive file that returns 403 for a whole-file/open-ended read can also retry through this path; HLS keeps its existing transport.

## Confirmed source quality (26 October 2026)

Website labels such as "1080p" are claims. `moviehub_vod.playable` already reads the first 64 KiB of every free link; `moviehub_compat.media.video_size` now reads the picture size that MP4 (track headers), Matroska and WebM (PixelWidth/PixelHeight) declare there, and names it with the same rule as `VideoDetails.tier` (1920×800 is 1080p). An MP4 that stores its index (moov) last gets one more bounded 64 KiB read at that offset, which also supplies its duration to the existing short-clip check. A failed read leaves the size unknown and never rejects a source. HLS master playlists keep their variant resolution as before. Results carry `qualityVerified`, `playlist` and the website's `claimedQuality`.

`FreeVodProviders` judges the website when a file's own size differs from its claim (`QualityClaims.verdict`; playlists are not judged because their label is capped at the preferred quality). Each website keeps its last five verdicts; two overstated claims make it distrusted, and `QualityClaims.effective` then ranks its unconfirmed claims as SD. `SourceRanking.readyRank(..., confirmed)` puts a confirmed quality ahead of the same quality only claimed, regardless of transfer sample, without lifting it into a higher quality. `SourcesScreen.badge` shows "480p ✓" for confirmed and "1080p?" for a distrusted claim.

`SourceWindow.pick(now, finished, target, quality)` keeps the three-second first choice when the best source reaches `min(preferred, 1080p)`; otherwise it waits up to `LONGER_WAIT_MS` (8 s) or until the search finishes. Camera copies never count as good enough.

`VodQuality` (owned by `VodPlayback`, main thread) reads `getCurrentVideoTrack` once a source has played for 15 seconds, so adaptive playlists can settle. It corrects the Sources label ("480p ✓ (said 1080p)", or "playing 480p of up to 1080p" for a playlist), judges an unconfirmed file source's website, and shows a short notice. When a later source has a confirmed HD quality better than what is playing (and within the preferred quality), it says so once per step and points to More → Sources; choosing it there uses the existing background-prepared switch at the current position. It never switches by itself.

## FMHY Rive adapter

`moviehub_native/rive.py` implements the existing movie/tvshow/episode/sources/resolve contract, using TMDB identities and the public anonymous Primevids/Citadel endpoint. `moviehub_native.PROVIDERS` registers the website. Existing website grouping, source keys, ranking, audio handling, credentials and persistence are unchanged. Every returned distinct URL retains its own source row. Citadel opts into the existing carrier path: whole-entity zero-range TS reads are sniffed before selecting the MIME type, including when the origin incorrectly says image/jpeg. Unflagged range handling is unchanged.

## Additional FMHY delivery routes (27 October 2026)

`moviehub_native/movy.py` translates the public seed-bound response decoder and uses Boise and Miami only. Movie requests omit episode parameters; series retain the series title, TMDB identity and exact season/episode. Rive adds Vanguard for episodes only while retaining its existing routes; its movie audio/seek check failed. All adapters return the established `direct` contract, with distinct origin labels and ordinary header/media validation. The existing Java engine, relay, ranking and source keys are unchanged. Discovery now has 38 enabled websites and 41 adapters (72 bundled constructors, including excluded sites).
