## 1.0.16 (28 October 2026)

- Rollback: same app as 1.0.12. Removes the 1.0.13 audio badges, 1.0.14 stuck-link switching and 1.0.15 runtime/metadata/Play sources changes at the owner's request, because 1.0.15 misbehaved. Published with a higher version number so installed TVs accept it.

## 1.0.12 (27 October 2026)

- Fix: after a stream dies, the backup source, Try again and the saved resume point now carry on from where you actually were, not from the dead stream's reported time (which could be 0:00 or the very end, making every backup "end" at once).
- Continue watching shows time left and the progress bar after resuming on a different source (the length is now also saved by title).

## 1.0.11 (27 October 2026)

- Fix: resuming an episode on a broken or shorter copy whose video ends before the resume point no longer counts as watching it to the end (no watched mark, no jump to the next episode).

## 1.0.10 (27 October 2026)

- Fix: a broken film or episode source that ended at once, was a short clip or was cut off part-way was treated as watched, and in a series jumped straight to the next episode. Such an end is now a failed source (the player tries another one or offers Try again) and nothing is marked watched. The Up next prompt and leaving the player near the end also ignore media under two minutes (`PlaybackEnd`).

## 1.0.9 (27 October 2026)

- Add Movy Boise and Miami as separate movie and episode sources, including verified 4K with audio. Add Rive Vanguard for episodes, using the existing source system.

- Thumbs up / thumbs down on every title page and in the Hold Select menu. A thumbs up makes For you suggest more like it; a thumbs down keeps that title out of For you. Choosing the same thumb again clears it.
- Continue watching marks a next episode that aired in the last 7 days as "New episode", in the accent colour.
- New Anime section on Home: popular and top rated anime series and films, airing this week, new series and anime by genre, from TMDB (Japanese animation).

## 1.0.8 (27 October 2026)

- Continue watching shows each film once, skips anything watched for under 3 minutes, and shows each series as one row: the episode you are part-way through, or the next episode once you finish one.
- Live TV: pausing shows the pause bar again, then "Behind live". Fast-forward or Options → Back to live catches up with the broadcast.
- Settings → Playback: subtitle size and an optional dark background behind subtitles.

## 1.0.7 (27 October 2026)

- Live TV starts on the first source it finds, then switches to a better-quality one (HD, 1080p…) as soon as it is found. If that source buffers or fails within a minute, it goes back to the one that worked and leaves it alone for 10 minutes or more, so the picture never flips back and forth. Choosing a source yourself turns this off.
- A channel that keeps buffering (8 seconds stuck, or 3 stalls in a minute) moves to its next source; with no other source, a long stall reconnects. A stream that stops without an error is recovered the same way.
- Reconnects are limited to two, refilled after 30 seconds of good picture, and look the link up afresh.
- Provider links are renewed a minute before they expire, instead of cutting out.
- A source chosen by hand no longer carries over to the next channel opened from the guide.
- Clicking the picture with an air mouse, or tapping it on a phone, shows the channel banner again.
- New Rive movie and episode sources.
- The old Live TV player code was removed. Films and series are unchanged.

## 1.0.6 (26 October 2026)

- Movie source quality is read from the file itself where possible: a link labelled 1080p that is really 480p now shows "480p ✓" and ranks as 480p. Websites caught overstating quality twice in their last five checks have their labels ignored ("1080p?").
- If no good source is found in the first 3 seconds, the app waits up to 8 seconds for one before starting.
- While a film plays, the Sources menu shows its real quality, says when the label was wrong, and points out a better confirmed source when one turns up. It never switches by itself.

## 1.0.5 (26 October 2026)

- New movie websites: Vixsrc and 4KHDHub, with each playback host listed separately.
- Better playback and seeking for large movie files from hosts which require fixed byte ranges.
- Quality badges use the release resolution and no longer mistake provider branding for 4K.
- Keep movie backup switches audible after the replacement player was prepared silently.
- Stop querying four domains confirmed parked, for sale or repurposed; keep their modules available for explicit audits.

## 1.0.4 (26 October 2026)

- More movie links from updated website searches and player loading, including Iwaatch, AnyMovies, M4uHD, Levidia, SeriezLoaded/Waffi and Tubi.
- Each actual playback provider has its own source row, including separately named backends behind the same website.
- Source details distinguish website failures, playback-host failures and providers requiring browser verification.
- Preserves the background-poster fix from 1.0.3.

## Unreleased — site-by-site VOD recovery

- Preserve individual playback providers beneath each discovery website, including separate backend diagnostics.
- Adapt confirmed current searches/player flows for canonical TV sites, M4uHD, AnyMovies, Iwaatch, PressPlay, SeriezLoaded, Movies Ni Pipay and nested player selectors.
- Decode the observed Xpass public player response and opt its PNG-wrapped media into the existing relay decoder; authenticate AES-GCM even with empty associated data.
- Recover Tubi movie search with its current anonymous web-client handshake and enumerate Waffi mirrors plus online playback separately.
- Record all 63 website observations and keep unverified restorations disabled.

- Restore verified Levidia. Disable the other 18 provisional restorations pending positive verification, alongside seven retired domains.
- Repair Levidia searches with empty landing cookies, yearless results and optional session markup; resolve Wootly hosts once with the bundled resolver.
- Preserve host failures in diagnostics when another adapter returns no matches.
- Negotiate only compression formats supported by the installed Python runtime.
- Stop metadata lookups reading whole videos, discard stale Host headers on redirects, and validate already-resolved files mislabeled as indirect links.
- Background artwork shows again on Home, title details, search and list pages (hidden behind a solid panel since 1.0). Only Live TV pages now sit inside the Live TV screen; leaving Live TV stops its picture, and Back from a channel no longer restarts the guide's picture when the guide is not showing.

## 1.0.2 (25 October 2026)

- Live TV: when a channel has several equally good copies, the one higher in the guide's order is chosen, rather than whichever finished its lookup first (it could start a slow copy).
- Films and series: a slow website keeps searching until the whole search ends instead of being cut off after 30 seconds, so sites like fzmovies can return links. Links still reach the player as they arrive.
- Films and series: one website's search no longer changes the headers the next website is sent (stagatv, seriesloaded and naijavault leaked their Referer and even Host into later searches).
- Films and series: checking a file's size no longer downloads the whole film (fzmovies failed on every film because of this).
- Films and series: skipping ahead on a slow source no longer makes the player look for another link. After a jump it waits until playback has moved on (up to 30 seconds) before treating buffering as a broken stream; before, it waited only 5 seconds.

## 1.0.1 (25 October 2026)

- Films and series: stop searching 26 free websites that are gone (failed three checks on 25 October 2026), so searches finish sooner. Their scrapers stay bundled.

## 1.0 (25 October 2026)

MovieHub 1.0: everything tested in MovieHub Experimental, up to exp-15, is now part of MovieHub.

- Live TV plays through a new player (ExoPlayer): one screen for the guide, picture and banner, and support for DASH channels.
- Several sources for a channel are tried at once and the best is played; a source with no picture after 15 seconds moves on to the next.
- Programme guides from one daily combined guide, including Sky UK, Freeview, Ireland, US and Samsung TV Plus, with UKTV's new channel names.
- Typed channel numbers update as you type, show Sky-style in the guide, and OK jumps at once. The guide title shows the channel count.
- ZLive channels work again. Plex plays again and has programme listings; DistroTV and Vidaa, which no longer work, are removed.
- Free channels has its own Settings tab; US-only services are labelled and can be hidden.
- Films and series: each website is listed once with its links grouped, and Resume finds a fresh link while keeping your place.
- A failed background load shows a small card instead of squashing the guide. Channels refresh once after an update.

## Unreleased

- Documentation: corrected build requirements (Gradle 9.6.0, AGP 9.4.1, Python 3.11, Node 22), described the MovieHub Experimental app, replaced stale ZLive "404 / no playback" notes, refreshed STATUS (released versions, branch situation, open PRs, files over 700 lines, next steps), test counts and four undocumented classes.
- ZLive: send the browser fetch headers (`sec-fetch-*`, client hints, Accept) on catalogue, ticket and resolve requests. Without them ZLive returns a looping "refresh the site" decoy stream instead of the channel (seen on exp-14).

### Experimental exp13 provider follow-up

- Use ZLive's current public catalogue and ticket/POST playback protocol; preserve source labels, raw IDs, transport checks and refusal cooldowns.
- Group duplicate Kodi adapters under one movie/series website and group its links in the picker.
- Refresh sources when resuming a movie/episode, preserve progress and deduplicate playback history by title.
- Give host resolution a separate bounded budget, preserve final-media cookies safely and use browser request identity consistently.
- Verify official Kodi package provenance and audit movie/episode provider outcomes separately.
- Starting a ZLive channel no longer downloads the channel list first; old saved IDs are matched against the list already in memory. A cancelled or season-less website search now stops cleanly instead of failing.

- MovieHub Experimental: add ExoPlayer's DASH module. Playing a channel whose stream is DASH (.mpd) crashed the app with ClassNotFoundException for DashMediaSource$Factory (found by the 2 GB memory probe on channel 101).
- Free channels is now its own Settings tab (between Accounts and Appearance) instead of a crowded side panel under Accounts plus a separate "Provider engine" page; Accounts holds only logins. Sections: Refresh and "Turn all on" at the top; Public channel lists (country, and whether to keep them with a TV provider); Free services (Pluto TV, FreeLiveSports and every provider-engine service, each on/off with its channel count); Advanced (hide US-only services, Masqueradarr, ZLive address, service updates). The old panel wrongly said the list choice controlled all free channels; it only ever controlled the public lists. No preference keys or meanings changed. Rebuilding the page keeps the remote on the card just used.
- TV guide and Home: a failed background load now shows one small card floating over the bottom of the screen (it hides itself after 12 seconds) instead of a full-width box added below the guide, which pushed the guide up and squashed its rows. Scrolling screens are unchanged.
- Keep explicit XMLTV regional suffixes such as `@Scotland` separate from national listings; quality suffixes such as `@HD` still share a schedule.

- Move all built-in programme fetching to one daily server-generated `Moviehub-epg/epg.xml.gz`. The TV conditionally downloads, validates and streams it into SQLite in the background; failed or invalid refreshes keep usable cached listings.
- Remove separate built-in XMLTV, Pluto/Plex/Samsung guide requests and catalogue programme imports. Keep exact provider identities and country/name fallback; extend the window to yesterday through seven days ahead.
- Expose distinct advertised supported ZLive resolver routes with server labels, stable source identities and approved headers. Decode PNG/WebP/raw/gzip TS carriers without requiring a playlist-token session. Exhausted ZLive attempts stop with manual recovery; existing buffering and refusal cooldowns remain.
- Live ZLive catalogue currently returns 404 and a legacy playback probe returned 403. Fixture checks cannot establish live playback or a complete current server inventory.

- Keep ambiguous radio display names (Freeview’s Gold Radio displayed as “Gold”) from claiming a television channel’s guide.
- Fix built-in channels showing no programme information despite matching XMLTV listings: prefer native schedules, then country-matched name/identity fallback; keep country maps separate.
- Empty, stale or malformed guides no longer block later guides. Populate saved/base channels before slow optional provider catalogues and update the visible guide as listings commit.
- After an app update, channels and programme guides refresh once (on the next home or Live TV visit),
  so new guide sources and fixes apply straight away instead of up to 12 hours later.
- Fix: channels refreshed on almost every home or Live TV visit (after a 10-minute gap) whenever any
  service failed, e.g. a US-only service from the UK. A refresh that finishes now counts for the 12-hour
  schedule even when some services failed.
- Sky UK programme guide (London HD region, Sky's own schedules for ~380 channels, seven days) as the
  second guide after Freeview: Sky Atlantic, Sky Witness, Comedy Central, UKTV and other pay channels.
- Guide matching: UKTV's renamed channels ("U&Dave", "U&Gold"…) answer to the old names playlists use,
  and a trailing "UK" word in a channel name ("Gold UK", "ITV 1 UK") is ignored.
- Typing a channel number: the guide draws the number itself, Sky style (yellow "12–" in a blue box
  beside Options), updating as you type, instead of queueing an Android toast per digit (which lagged
  behind the typing and looked different on every box). OK jumps to the number at once. The wait after
  the last digit stays 1.5 seconds.
- Samsung TV Plus (GB) channels show listings, from i.mjh.nz/SamsungTVPlus/gb.xml.gz, matched by
  Samsung's own channel ids. They showed none before: the adapter drops the catalogue's listings.
- More programme guides, best first: Freeview EPG (UK free-to-air, broadcasters' data, seven days,
  London feeds also answer to the national names), then epgshare01 UK, Ireland and US. A channel taken
  by an earlier guide is not looked up in later ones; one guide failing keeps the others. Irish and US
  channels use their own country's guide; channels of other countries still get none.
- Experimental Live TV source race: when a channel has several copies, up to three are looked up at
  once. The first choice waits up to three seconds only while a better-quality copy (by its name:
  HD, 1080p…) is still being looked up; otherwise the best found plays at once. Copies found but not
  playing wait as standbys, so a copy that errors or shows no picture within eight seconds hands
  over without a new lookup. Unused links are closed. Choosing a source yourself plays that one only.
- Experimental Live TV: a source that shows no picture within 15 seconds moves on to the channel's
  next source, as the standard player does, and a source that errors moves on at once instead of
  being reopened twice first. With no other source, it keeps waiting and retrying as before.
- Live TV guide: the list's channel count sits on the title line, e.g. "ALL CHANNELS 11,000 channels"
  or "SPORTS 240 channels" (copies of one channel count once, as in the rows).
- Settings → Provider engine → "Hide US-only providers" (off by default): hides LG Channels, Roku
  Channel and Local Now, the services known to work only in the US. Nothing is ever switched off
  because it failed.
- Plex programme listings: Plex channels now show what is on, from the daily public XMLTV files at
  i.mjh.nz/Plex (UK file first, US file when the UK one covers under half the channels). Channels
  match by Plex's own channel id, not by name.
- Live TV: the source name in the banner's title strip is now plain text ("Source: Auto ·
  provider · 1 of 2"), not a second Sources button; the green key is the one Source button.
  BACK highlights the green key; OK highlights Audio & subtitles as before.
- Live TV information panel: a "Picture" line with size, quality name, frame rate, codec and
  bitrate (for example "1920×1080 (1080p) · 50 fps · H.264 · 4.8 Mb/s"), plus the source,
  refreshed every two seconds while open. Both the VLC and the experimental ExoPlayer builds.
- Plex channels play again: the play request used the stable channel id, which Plex answers with
  404; it now uses the compound id from the catalogue row (live probe: 0 of 3 sampled channels
  opened before, 2 of 3 played after).
- Removed DistroTV and Vidaa: both services' API hosts no longer exist (DistroTV has shut down;
  Vidaa moved to a new signed backend). Their saved channels are cleared on the next refresh. The
  remaining US-only services are labelled, e.g. LG Channels (US).
- The guide's "Unavailable now" line groups failed providers by their actual failure, for example
  "HTTP 403: LG Channels (US only) · timed out: Plex" (`ProviderOutage`). It never guesses that a
  service is geo-locked; known US-only services say so in their name.
- The live provider probe can be run for chosen providers only.
- Experimental DaddyLive: reuse one isolated lookup process/compiled code, briefly cache public
  mirror pages, enforce lookup deadlines and advance failed players without retrying the same one.
- Experimental: cancel stale channel retries/resolution and release the Media3 player/view on stop.
- Experimental: retain the latest OK/banner changes and add a dedicated ExoPlayer emulator suite
  for guide/fullscreen video, failed sources, screen lifetime and audio/subtitle tracks.
- Audit all 68 free VOD adapters, expose per-provider outcomes, and allow every scheduled provider time to run without extending the three-second initial selection.
- Reject known free videos shorter than two minutes, recover across free services after manual selection, and retain late backups.
- Keep failed VOD native shutdown off the UI thread with retained window/engine lifetime; show selected source, Mb/s and received bytes.

- Configure player panels before window attachment to stabilize the first input-focus handoff.

- Restore free-stream incoming speed from actual upstream relay bytes; count only the active source.
- Rank ready VOD by quality aliases and HLS variant resolution, avoid camera recordings, and compare sampled media throughput/response time within equal quality. Keep the three-second window and buffering thresholds.

## Provider compatibility correction — unreleased

- Preserve bodyless POST searches used by Levidia, default request Referer/language,
  session cookies and redirect methods.
- Prepare movie/episode lookup titles with the bundled add-on rules and supply original
  and alternative TMDB titles. Allow 30 seconds per provider within a 300-second
  background search, preserving three-second initial selection and buffering rules.
- Add real-provider-path HTTP and title-preparation regressions.

## Unreleased — progressive free VOD candidate

- Bundle Scrubs/Free99 provider modules and headless ResolveURL extraction inside the APK.
- Search free providers without requiring Premiumize; retain connected cloud results.
- Select the best reported result at three seconds, wait if none is ready, and append late backups.
- Preserve VOD buffering thresholds and prepared replacement playback.
- Preserve host headers and record third-party provenance and outstanding release blockers.

# Changelog

## Unreleased

- Live TV: OK brings the banner up ready to use, as on the satellite box, with Audio & subtitles highlighted; ◀ ▶ move along and OK presses. ▲ ▼ still show it only briefly while zapping. Removed the temporary button diagnostics (Last button lines, Button not used and recognised-but-ignored messages).
- MovieHub Experimental: the Live TV picture is Media3's PlayerView (correct picture shape, subtitles in Audio & subtitles); Options → Player event log records the decoder, video formats, quality switches, dropped frames, errors and any picture stall (records only, never repairs). The custom stall timer is removed; a failing channel keeps being retried.
- MovieHub Experimental: Live TV is one screen. The TV Guide menu, channel guide and banner are drawn over one video picture that is only resized (corner box or full screen), never rebuilt or moved to another screen. A "Last button" readout shows every key MovieHub receives, on every screen.
- MovieHub Experimental only: Live TV plays on Media3 ExoPlayer (ExoLiveActivity, ExoLive), shared by the guide picture and the full screen by pointing the same decoder at each surface. Normal builds keep LibVLC and do not contain ExoPlayer.
- Live TV: the banner's Sources label names the copy actually playing when full screen opens, instead of assuming the first copy before the player reports its link.
- Live TV: the banner always draws its buttons boxed, as after BACK, whether it opened from OK, a channel change or BACK. ◀ ▶ move between the buttons and OK presses the highlighted one; with none highlighted, OK shows the banner. The separate browsing layout is gone.
- Build switch for a separate MovieHub Experimental app (own package, data and update feed) and a manual workflow to publish it. Normal builds are unchanged.
- Live TV: OK (and an air-mouse click on the picture) always shows the same full channel banner. OK while browsing the current channel, and OK with the banner buttons revealed but none focused, show that banner instead of closing it or opening Sources. ◀ no longer opens the reduced browsing layout.
- Live TV briefly names any remote button MovieHub does not use (key name and scan code), so a remote's colour buttons can be identified.
- "Set up coloured buttons" can learn buttons Android has no key name for (by scan code), says when a button sends nothing, and lists what each button sent. The guide's colour keys now recognise the same standard and learned scan codes as the player.

## 0.51 (23 October 2026)

- Use one visible Live TV overlay for entry, OK, picture clicks and Back; repeated OK refreshes the same banner instead of opening Options. Keep Options on the blue/Menu button and selectable control.
- Focus the visible live picture instead of a hidden film control, and route colour presses through one handler shared with player menus before native-player access.
- Accept the first delivered press, standalone release and standard Android TV colour scan identities while suppressing repeats across menu windows.

## 0.50 (23 October 2026)

- Keep the full Live TV channel banner ahead of optional information/pause panels when space is limited, with a reserved header height that grows for larger text.
- Handle standard coloured remote buttons on the first press, including inside player menus; prevent saved aliases from swapping standard colour actions.

## 0.49 (23 October 2026)

- Keep the complete Live TV channel header visible and coloured remote buttons working from open player menus.
- Automatically recover stalled Pluto/direct channels with limited reconnects and a fresh Pluto connection.
- Repair missing live pictures and keep Back and player menus responsive during reconnection.
- Keep advancing live video playing through brief HLS buffering notifications instead of restarting unnecessarily.
- Add Repair picture and Reconnect channel to Live TV Options, with clearer recovery diagnostics.

## 0.48 (23 October 2026)

- Always use the Live TV banner and colour-key actions on live launches, including restored activities without a static guide session. Save each player’s guide context and match the initial listings to its actual channel.
- Apply learned colour buttons before number/channel shortcuts. Keep source text and all four colour controls inside the banner width.

## 0.47 (23 October 2026)

- Live TV Automatic now prepares one muted backup after the existing 8-second stall or three buffering starts per minute. Working playback stays open until replacement video and available audio advance; promotion keeps the prepared decoder and relay.
- Bound each candidate to a 45-second resolution deadline and a 15-second playback deadline, visit saved copies once, and retain working playback if none succeeds. Preparation runs for fullscreen and guide playback, with cancellation on pause, manual selection, channel change, view handoff and exit. Low-memory signals skip standby allocation.

## 0.46 (22 October 2026)

- Restore the selected audio track after forced native-stop recovery.

- Reject released shared live players in delayed controls, audio-focus and playback-event callbacks.

- Wait for native video shutdown even before VLC reports an output; on shutdown timeout, stop native playback before releasing its surface, then restart at the destination.

- Clear owned dialogs during Activity destruction, including an immediate show-and-close before window attachment.

- Keep the current live stream and relay running during scheduled provider-link renewal. A failed refresh keeps working playback; pause and exit discard late renewal results. The cut still restarts the existing decoder once a fresh link resolves.
- Allow one surface repair until visible decoding progress resumes; a persistent freeze then reaches bounded provider/source recovery rather than repeatedly rebuilding the surface.
- Extend clock/video freeze monitoring and early surface repair to ordinary IPTV, excluding paused, hidden and audio-only playback.
- Use bounded transport buffering profiles: 1.5 s direct live, 2.5 s HLS, 4 s relayed live and 2.5 s VOD. HLS detection uses the URL path, including uppercase extensions, rather than tokens in its query. More VOD headroom can increase startup and seek delay.

## 0.45 (22 October 2026)

- Clear panels when their Activity window detaches; do not continue panel actions on a closing Activity.

- Close the old live video output immediately during handoff while the optional still-image capture runs independently; require a valid destination surface for picture evidence.
- Keep the live video surface alive until VLC has stopped its decoder and video-output threads when leaving playback.

- Connect cached VOD alternatives to manual/automatic source selection, with 4-second/two-in-60-second buffering recovery, in-player Sources, pause/seek cancellation, bounded attempts and a prepared replacement before the current stream stops.
- Adapt 2.5-second still-picture surface recovery and separate guide/full-screen VLC counters to the current SurfaceView implementation. Preserve later video reattachment and cancellation fixes.
- Best-effort bounded still cover during live picture handoff; queue paging during guide loads and remove panel-closing animation/input races.
- Use one core-test script for build and publishing, and update WorkManager to 2.12.0. Retain the existing source registry and provider consent instead of importing an empty conflicting scaffold.

## Unreleased — continuous series, automatic play and TV remote controls

- Continue to the next aired episode across season boundaries using TMDB episode metadata, prefetched while watching. Stop at the last available episode instead of inventing an episode number.
- If the next episode is absent from the current pack, offer **Search for the next episode** in manual mode, or search automatically in automatic mode.
- Settings → Playback adds separate automatic-play switches for films and series, plus a preferred maximum resolution (1080p by default). Try up to five ready-to-play sources and fall back to manual selection if no clear file match is found; never silently queue transfers.
- Route guide colour keys regardless of focus. Back reveals outlined, arrow/OK-accessible colour controls; the next Back uses the existing navigation action. Fullscreen colour controls also gain visible outlines. Standard colour keys, F1–F4 and learned mappings remain supported.

## 0.42

- Movie torrent file lists now put **Recommended movie** first, with video labels and file sizes. Other files remain visible but cannot launch playback. Exact episode selection is unchanged.
- Expanded video filename recognition and local title/size ranking exclude samples, trailers and extras. No file downloads or extra provider calls are needed for ranking.

## 0.41

- Live TV plays through Android's standard video surface: aimed at smoother full-screen video and at the picture stopping while the sound carried on. Moving between the guide's mini picture and full screen keeps the stream playing (a brief blink is normal).
- Sports streams from FreeShot, DaddyLive and ZLive buffer further ahead, and a slow provider gets one retry, so fewer short pauses.
- On first start, Pluto TV and the free channel lists load before the optional providers, so most channels appear much sooner.
- The live banner's Audio & subtitles, Source, Information and Options are buttons: tap them, or press BACK and use ◀ ▶ and SELECT on remotes without coloured buttons. Auto mode names the provider playing.

## Unreleased — free channels before providers

- Import native Pluto and the free lists before FreeLiveSports and the optional providers. With every provider switched on, the free lists (most channels) previously waited until all sixteen providers had finished, each allowed up to four minutes. Numbering and preferred copies are unchanged because they follow `ChannelDatabase.ORDER`, not import order. Not measured on the TV box.

## Unreleased — banner buttons without colour keys, provider shown in Auto

- The banner's Audio & subtitles, Source, Information and Options keys are now buttons: tap them, or press BACK and move with ◀ ▶ and SELECT, so remotes without coloured buttons can use them. The banner hint says so.
- Auto mode names the provider playing now ("Sources · Auto · FreeShot · 1 of 2"), and the Sources list marks the playing copy with ▶ and shows it on the Automatic row.

## Unreleased — smoother relayed sports streams

- Relayed provider streams (FreeShot, DaddyLive, ZLive) buffer 4 s ahead instead of the shared 1.5 s Live TV buffer; other channels keep the faster start.
- The provider relay keeps upstream connections open for reuse after a complete response, runs four workers instead of two, and retries a provider that does not answer in time once before the player sees an error.
- Prompted by the owner's phone diagnostics (six short buffering pauses and relay timeouts in one session). Not yet measured on a device after the change.

## Unreleased — live TV on VLC's standard SurfaceView

- Live TV now draws through VLC's `VLCVideoLayout` (SurfaceView), as the film player already did, instead of a shared SurfaceTexture/TextureView. Pictures go from the hardware decoder to the screen without waiting for the app's main thread, which the owner's full-screen lag and still-picture-with-sound reports pointed at (21 October 2026).
- Moving between the guide's mini picture and the full screen still keeps one player, stream, relay and sound (no reconnect or re-resolve); the picture may blink while the new surface takes over.
- Freeze detection counts VLC's displayed pictures. Tests rewritten accordingly; the EGL/SurfaceTexture test was removed with that design. Not yet checked on a phone or the TV box.

## Unreleased — early live video freeze investigation

- Count rendered texture-buffer updates even when presentation timestamps repeat or reset; clear handoff covers from buffer callbacks.
- Detect a stopped visible video consumer independently of advancing audio; try one consumer refresh without reopening media or audio before bounded source recovery.
- Add constant-timestamp EGL, actual consumer-stall recovery and wrapped-HLS audio/video reload checks, plus a sustained numerical live diagnostic.

## Unreleased — retained live picture

- Remove system Activity fades for Live TV entry/return, including Android 14+ transition overrides.

- Keep the decoder Surface and SurfaceTexture through guide/full-screen handoffs instead of detaching native video output. Cover the transfer with the last rendered frame until a fresh frame arrives.
- Check repeated round trips using rendered frames, nonblank pixels and output identity, alongside existing stream and resolver counters. Physical-TV timing still needs verification.

## Unreleased — sustained provider playback

- Detect stopped native/provider video progress, with clock fallback when video counters are unavailable even without buffering/error events, using the existing eight-second stall threshold and bounded provider retry/backup handling. Ignore paused and hidden playback.
- Speed up PNG segment decoding with filter-0 and RGB bulk-copy paths and larger inflate buffers, preserving validation limits and releasing the inflater on failure. Physical-TV cause and sustained playback remain unverified.

## Unreleased — provider playback startup

- Give bounded link resolution its own phase, then start the video-opening timer when the URL/relay is ready. Previously a working provider taking over 15 seconds could be discarded before LibVLC received a stream.
- Show “Finding a working stream…” during resolution; preserve source backup, cancellation and view handoff behaviour.

## Unreleased — readable provider refresh failures

- Expand Settings cards to fit descriptions, put provider failures first, and retain safe network/HTTP/
  runtime diagnostics. The TV's DaddyLive import failure still needs a fresh diagnostic result.
- Fit guide labels to row height so constrained displays do not cut text in half.

## Unreleased — DaddyLive playlist sessions and PNG media

- Add the current anonymous playlist-token bootstrap, memory-only renewal and a single refresh after a
  rejected token. Keep tokens scoped to the discovered CDN/channel and preserve the existing player.
- Unpack supported PNG-contained MPEG-TS segments with strict format, size and decompression bounds.
- Add provider, renewal/scoping and segment regression checks. Desktop samples decoded for five channels;
  physical TV and sustained playback are not yet verified.

## Unreleased — experimental standalone provider engine

- Live probe on real services (20 October 2026) found MovieHub itself blocking providers: Samsung TV Plus now redirects through github.com (not allowed), and Tubi's guide (3.7 MB) and Plex's catalogue (3.1 MB) were over the 3 MB response limit. Allowed that redirect and raised the limits to 6 MB per response and 15 MB per operation, with a 16 MB script input cap (Tubi totals 7.5 MB, Plex 8.7 MB). A catalogue step may now take 10 s in the interpreter (3 s before) for slower TV boxes. Samsung redirects to Amagi's CDN are allowed. DaddyLive's stream servers answer 403 (referrer/signature gate) and ZLive's catalogue 404: provider-side, not changed.
- Failed providers are named in one line in the guide ("Unavailable now: …"), and the guide status is at most two lines; one line per provider had squeezed the channel rows to nothing. Settings → Provider engine shows the type of each provider's last refresh error, and the import count rises as providers load.
- On start, MovieHub asks once whether to turn on the free TV providers ("Turn them all on" or "Not now"), then shows the safety notice. Existing installs are asked too; Back leaves the question for next time. Providers were previously all off with no prompt.
- Provider channels now back up the same channel from the free lists and accounts: "Sky Sports F1 (720p)", "Sky Sports F1 UK", "… HD" and "Sky Sports Formula 1" are one channel with several sources (channel database version 9 re-keys saved channels; favourites and locks kept). Other countries, +1, regional and UHD channels stay separate.
- When a source fails, Live TV tries each other copy once ("Trying another source") before saying the channel is not working; SELECT then tries all copies again from the first. A slow error and a timeout from the same source no longer skip a backup.
- Provider channels are filed in the guide's existing sections by whole words (the earlier mapping put "TF1" in Sports). The Source list names each copy's provider.
- A provider turned on in Settings → Provider engine now imports whatever the free-list choice is; before, an account or "only without a TV provider" silently hid it.
- Add isolated local JavaScript execution of the historical FreeLiveSports TypeScript leaf, stable SQLite channels, inline EPG and resolve-on-play.
- Add opt-in provider settings, signed package validation, atomic rollback and gated upstream update CI.
- Document private upstream access, missing signing setup and provider/runtime limitations; no production release.

Versions are published to TVs through the in-app updater (`android-tv-latest` release). Each
version below was built from the listed `main` commit. Earlier history is in the git log.

Note: the release shown as 0.10 below was published with version code 11 (name "0.11" in the
update feed), so the next release after it used code 12, named 0.12.

## 0.39 (19 October 2026)

- Discover regional Pluto TV channels directly without a separately hosted server; resolve streams when played and keep favourites stable across session refreshes.
- Add native Pluto controls and catalogue counts to Free channels, while keeping Xtream, M3U and optional hosted Masqueradarr separate. FreeLinks remains disabled.
- Cancel stale native channel requests, preserve guide/fullscreen playback handoff, and try same-channel backups after source failures or live stream endings.
- Refresh provider guide data independently from XMLTV; retain cached channels and listings when a service fails.
- Add provider fixtures and native playback cancellation, fallback and handoff regression tests. Actual desktop decoding succeeded for one UK Pluto channel; real TV verification remains required.

## Unreleased

- Fixed Live TV's upgrade from the 0.35 channel database: create the preferred-channel column
  before rebuilding channel numbers, preserving saved channels, encrypted URLs and favourites.

- Troubleshooting uses the Settings sidebar and cards, with matching typography, spacing and
  buttons. Back returns to the System tab instead of Accounts.

## 0.37 (19 Oct 2026)

- Films and episodes carry on where you stopped whichever source you pick: progress is saved by
  the TMDB film or episode, not only by the link you pressed. Files opened from the cloud (no
  TMDB details) still carry on by file.

## 0.36 (19 Oct 2026)

- Skipping in films is quicker. Pressing −10 s or +10 s several times in a row now makes one
  jump, 0.75 s after the last press (as Kodi does), instead of reconnecting and buffering at every
  press. The controls show where the jump will land straight away.
- Moving along the film's progress bar with the remote jumps once, when you stop, instead of at
  every step. Letting go of the bar on a touch screen jumps at once.
- The player's download speed is right: it read about 1000 times too low (often 0.0 Mb/s), and so
  did the last speed in Diagnostics. It shows how fast the player reads the stream, which follows
  the stream's bitrate once the buffer is full, not the connection's top speed.
- Back on a film first hides the controls, then shows "Press Back again to leave playback", and
  only leaves on another press within 3 seconds. Live TV keeps its own Back.
- Films and Live TV now have separate player settings, so changing one can never change the other
  (the values are the same as before).
- Settings → System → Diagnostics is now Troubleshooting: an internet speed test, checks that each
  service you use answers (internet, TMDB, Premiumize, stream sources, your TV provider, the
  programme guide), playback timings, and the recent errors the app recovered from (place and
  type only, never addresses or account details).
- A closing player could crash the app if Live TV's shared player was released at that moment
  (seen in the device tests as "can't get VLCObject instance"); it now lets go of it instead.
- Live TV: changing channel and browsing other channels in the banner fetch just the one channel
  needed instead of a page of 151, and the guide fetches the eleven rows it uses.
- Live TV: the favourites list no longer rewrites its table on every page or channel change, only
  when favourites change.
- Live TV: finding a channel by number counts the list once instead of up to three times.
- Programme guide download: programmes on channels you don't have are skipped before their times
  are read, and times are read without building a date parser each time.
- Live TV: all channels, sections and countries are read through an index of each channel's
  preferred copy instead of sorting out duplicates across the whole list every time (channel
  database version 8; the upgrade fills it in once).
- Removed three unused channel queries; the channel database's tables and upgrades moved into
  `ChannelSchema`.
- Series: the episode you chose is found in far more season packs (S01 E03, S01Ep03, "Season 1
  Episode 3", E03 in a Season 1 folder, a plain file in an episode folder, and more). A file
  that names exactly that episode still plays straight away; one that very probably is it
  (a bare "03 - Title", a double episode) is marked "Suggested episode" for you to confirm and
  has the focus. Names that could mean something else (720p, x264, years, another season) are
  never marked. A show called "Extras" no longer loses its episodes.
- Apps: an app with separate TV and phone launcher entries is listed once (apps from different
  packages with the same name are still both shown). The Apps screen shows its first rows at once
  and the rest straight after.
- Two marked, empty lists for new sources, ready to fill in: `DefaultSourceProviders.EXTRA` for
  film and series providers and `FreeChannels.EXTRA` for TV channel lists (AGENTS.md, Adding
  sources). Empty, so nothing changes yet.

## 0.35 (18 Oct 2026)

- A Live TV channel that won't play no longer opens a "Can't play this" panel that blocks the
  remote. The picture says the channel isn't working, and ▲ ▼ go straight on to the next one;
  SELECT tries it again and BACK goes to the guide. (Backups are still tried first.) Films and
  series keep their panel.
- Down past the bottom row of the guide turns the page again (round to the first page after the
  last), instead of moving onto the colour keys. The colour keys are a guide to the remote again;
  without coloured buttons, BACK opens the guide menu, which has Reminders, Search and Options
  along the top and Favourites on its second page.
- In the guide menu, Down from the last row goes round to the top, as on the satellite box.
- Going back from the full screen to the guide, the corner picture could stay black on some
  boxes even though the channel carried on. The picture's decoder now restarts on the corner's
  view (no reconnecting), so the picture shows.

## 0.34 (18 Oct 2026)

Details from the old Sky+ box, checked against David Wheatley's Sky+ web EPG simulator
(sky-epg.davwheat.dev), on the dark-blue guide:

- The TV Guide menu is in the box's order: 1 All Channels, 2 Entertainment, 3 Movies, 4 Sports,
  5 News, 6 Documentaries, 7 Kids, 8 Music, 9 Radio, 0 More…. Favourites is on the blue key and
  the second page, with Religion, Shopping, International, your lists' groups and Options. BACK
  on the second page goes back to the first.
- Paging wraps round: Page down on the last page goes to the first, Page up on the first goes to
  the last.
- The box's exact blues and yellows for the rows, number boxes, highlight and colour keys.
  Programme slots with no listings are drawn in the faded blue the box used.

## 0.33 (18 Oct 2026)

- The channel guide is back to the dark-blue look from 0.26: the time and list name at the top
  left, Options and the live picture at the top right, the original sizes, colours and typeface,
  and square colour keys. The light-blue redesign from 0.28 (programme preview and tabs) is gone;
  sections are on the blue key and the guide menu. Scrolling right through the day, reminders and
  the rest are kept.
- The TV Guide menu is in the same dark blue as the guide.
- No coloured buttons on your remote? The colour keys at the bottom of the guide and the menu can
  now be chosen: press Down past the bottom row, move along them and press SELECT. Page down stays
  selected, so SELECT again turns another page. While watching, SELECT twice opens Options, which
  now also has Programme information, so it covers every coloured button.
- Live TV now opens on the TV Guide menu first, like the old satellite box's TV Guide button.
  Pick a list (or press its number) to see the channels; BACK from the channels comes back here.
- Every row of the menu now fits on the screen, so 9 Music and 0 More… are no longer cut off.
- REMINDERS, SEARCH and OPTIONS along the top of the menu now work: press Up from the first row,
  then Left and Right.

## 0.32 (18 Oct 2026)

- The "Staying safe" notice is now short and plain: MovieHub never asks you to leave the app
  while watching, so ignore any message in a stream that does and watch something else.

## 0.31 (18 Oct 2026)

- The first time MovieHub opens it asks whether it is on a TV with a remote or a phone or tablet
  by touch (with its best guess suggested, as some TV boxes report themselves as phones). The
  answer is kept; Settings → System → Layout changes it.
- Phone layout, starting with Live TV: a portrait channel list with numbers, now and next, section
  tabs and search; tap to watch, hold for favourites. The player's banner gets touch buttons
  (channel up and down, info, source, audio, pause, guide), and tapping the picture shows or hides
  it. Films, series and settings still use the TV layout for now.

## 0.30 (18 Oct 2026)

- Phones and tablets get a MovieHub icon in the app drawer, so it opens as an ordinary app without
  being made the home screen. TVs are unchanged. It is still laid out for a TV and a remote.

## 0.29 (18 Oct 2026)

- The guide's coloured buttons are back to how they were, as on the satellite guide: red page up,
  green page down, yellow search, blue sections (the guide menu). CH+ and CH− still turn pages too.
  Tomorrow and Back to today are in Options, and ◀ ▶ still move through the day.

## 0.28 (18 Oct 2026)

The Live TV guide is redone from photos of the real satellite-box guide:

- A light-blue guide: "TV guide" and the clock across the top, then the selected programme's
  time, title, tags and description with the live picture beside it.
- Tabs across the guide: All Channels, Favourites, Entertainment, Movies, Sports, News, Docs and
  Kids (▲ from the top row; ◀ ▶ change the list), and More… for the rest.
- The selected programme and its channel name are both yellow; the other cells are dark blue.
- Round colour keys: red Reminders, green Search A–Z, yellow +24 Hours, blue −24 Hours. CH+ and
  CH− turn the pages.
- BACK from the guide opens the guide menu: a numbered list (1 All Channels … 9 Music, 0 More…)
  with the chosen row in yellow; number keys pick a row. Its colour keys: red Options, green
  Reminders, yellow Search A–Z, blue Favourites.

## 0.27 (18 Oct 2026)

- Staying safe: when MovieHub first opens it explains that it will never ask you, during or about
  a stream, to go to a website, ring a number, install anything, let anyone control your devices
  or pay, and that any channel saying so is a scam. It stays in Settings → System.
- Live TV uses a cleaner TV typeface (Hind, free to use) in the guide, banner and panels.
- The guide has a day bar (today and tomorrow), and the yellow key jumps to the same time
  tomorrow and back. Search is still in Options.
- Programme details where the free guide has them: episode ("S1 Ep3"), age rating, subtitles,
  HD and New, shown in the banner and the programme information.
- The banner slides up when it appears.

## 0.26 (18 Oct 2026)

- Live TV keeps one player running: going from the guide's corner picture to the full screen and
  back carries the same stream on, with no reconnecting or buffering. Films and series are not
  affected.
- The full-screen Live TV picture looks like the classic UK satellite box: the film controls are
  gone, and the banner has a light-blue title strip with the yellow channel number, name and
  clock over a deep-blue panel with Now and Next, a progress bar and the colour keys.
- Satellite remote: SELECT shows the banner (SELECT again opens Options), i or yellow shows the
  programme information panel, red is audio and subtitles, green the source, blue (or MENU)
  Options, ◀ ▶ browse what is on, ▲ ▼ change channel.
- Pausing live TV shows a pause bar; playing on behind live says how to catch up.
- BACK in Live TV closes whatever is open, then goes straight back to the guide (one press: the
  picture carries on in the corner). Films still need BACK twice.
- The guide moves like the satellite guide: ▶ goes along a channel's programmes and, past the
  last slot, moves the times on (the channel names stay on the left); ◀ comes back towards now
  and then to the channel name; ▲ ▼ keep to the same time on the next channel. SELECT on what is
  on now watches it, and on a later programme sets (or cancels) a reminder. i (or holding SELECT)
  shows the programme's details.

## 0.25 (18 Oct 2026)

Live TV is organised like a UK satellite guide instead of one long list:

- Every UK channel has a number in the right place, not just the ones the satellite guide lists:
  other entertainment channels follow 182, films the 300s, music the 350s, sport the 400s, news
  the 500s, religion the 580s, kids the 600s and shopping the 660s. Regional BBC One and BBC Two
  and the BBC red-button streams are in the 800s.
- Radio stations have their own Radio section (UK radio is 901 onwards) instead of sitting among
  TV channels.
- Channels from other countries are in a new International section, numbered from 1001 country
  by country: Ireland, the United States, Canada, Australia and New Zealand first, then A to Z.
  Choosing International on the blue key lists the countries.
- Entertainment, Movies, Sports, News and the other sections now hold UK channels only.

## 0.24 (18 Oct 2026)

Live TV now has the rest of what the old satellite boxes did:

- UK satellite channel numbers: 101 BBC One, 102 BBC Two, 103 ITV1, 104 Channel 4, 105 Channel 5
  and so on, wherever the channel is in your lists. Other channels follow from 1001. The guide is
  in number order, and typing a number goes to that channel even from Favourites or a section.
- Guide sections on the blue key: Entertainment, Movies, Music, Sports, News, Documentaries, Kids,
  Religion and Shopping (your lists' own groups are still there too).
- The channel you last watched keeps playing in the top corner of the guide. Options → Picture in
  the guide turns it off.
- Programme information: press ▶ (or Info) on a channel in the guide for what is on now with a
  description and the rest of the day. Choose a programme to read about it and set a reminder.
- Reminders: when a programme you chose is about to start, a panel offers to switch to it
  (while MovieHub is open). Options → Reminders lists them.
- The banner shows a progress bar and a short description, and ◀ ▶ (with the controls hidden)
  browse what is on other channels without leaving this one: ▲ ▼ move between channels, ◀ ▶
  between programmes, SELECT watches the channel shown and BACK closes it.
- Parental lock: choose a four-digit PIN (Options → Parental lock), then lock channels (from
  their programme information) or whole sections. Locked channels ask for the PIN before playing.
- Pause live TV, then carry on from where you paused, or choose More → Back to live. Rewind works
  on channels whose stream allows it; others say so.

## 0.23 (18 Oct 2026)

- More free channels: the Free-TV list (free-to-air and free ad-supported channels) is added to
  iptv-org, with its UK channels first, so BBC One is channel 101.
- Backups: a channel found in more than one list appears once in the guide; the other copies are
  backups. If a stream fails, stops for 8 seconds or keeps buffering, the player switches to the
  next copy by itself, and More → Source lets you pick one.
- Watching Live TV works like a satellite box: Up/Down (when the controls are hidden), CH+/CH- and
  PG+/PG- change channel, number keys tune straight to a channel, the Last Channel key goes back,
  and a now-and-next banner shows the channel number, name and programmes on every change.
- The guide looks more like the classic satellite-box guide: dark bands with a lighter blue sweep,
  arrows on programmes that run past the slots shown, and SELECT on a yellow key.

## 0.22 (18 Oct 2026)

- Live TV guide shows what is on: a free UK programme guide (epgshare01) is downloaded with the
  channels, about once a day, and programmes fill the half-hour slots. Channels are matched by
  name, so many UK channels have listings; others still say "No programme information".
- Coloured buttons: remotes that send other codes for them now work too, and Options → Set up
  coloured buttons teaches the guide your remote's buttons.
- Number keys jump straight to a channel: type 151 to go to channel 151.

## 0.21 (18 Oct 2026)

- Live TV works without a TV provider: the full public iptv-org channel list is loaded the first
  time you open Live TV. Settings → Free channels can switch to one country, keep the
  full list even with a provider, or turn it off. Some free streams may not work, and some need
  Allow HTTP.
- Live TV redesigned as a classic satellite TV guide: a blue full-screen grid of ten channels per
  page numbered from 101, half-hour time slots across the top, the selected channel in yellow, and
  colour keys (red/green page up and down, yellow search, blue groups; CH+/CH- also page). Options
  covers the same for remotes without colour keys. Programme titles need a programme guide from a
  provider; the free list has none, so rows say "No programme information".

## 0.20 (18 Oct 2026)

- New Diagnostics page (Settings → System → Diagnostics): how long TMDB requests, finding sources,
  the Premiumize cache check and file link, artwork, "press play to picture" and buffering pauses
  take on this box, plus the last download speed. Only names and times are shown.
- Code tidy-up with no visible change: the player is split into three smaller files.

## 0.19 (18 Oct 2026)

- Faster menus: your saved logins are unlocked once instead of on every request (every poster row,
  search and source check used to go through Android's secure storage).
- Pressing play no longer waits for the watch history to be saved; it saves in the background.
- Home no longer reloads every installed app's name and icon each time; it reloads only after apps
  are installed, updated or removed.
- Player: the download speed shows under the title and next to the buffering percentage, so you can
  tell a slow connection from a slow app.

## 0.18 (18 Oct 2026)

- Player controls redone in the streaming-app style: flat icons with no button shapes (rewind 10,
  play/pause, forward 10, Audio & Subtitles, Video, More), the selected one bright and slightly
  larger; the time left shows at the end of the timeline.
- No pill-shaped buttons anywhere: every button uses the app's standard style.
- Faster start: when a source holds one obvious file (the film, or your episode in a season pack)
  it plays straight away instead of asking you to choose a file.
- Resuming opens the video at your place instead of starting at 0:00 and then jumping, which made
  it buffer twice. Buffering shows its progress in percent.

## 0.17 (18 Oct 2026)

- Player: Back no longer stops the video straight away. The first press shows the controls and
  "Press Back again to stop watching"; a second press within 3 seconds leaves. Holding Back does
  not count as several presses.

## 0.16 (18 Oct 2026)

- Player: the title now sits at the top; at the bottom a full-width slim timeline with the time on
  the right, play controls on the left and Video, Audio, Subtitles and More on the right.
- Built with Android Gradle plugin 9.4.1 and Gradle 9.6.0 (no visible change).

## 0.15 (18 Oct 2026)

- For you on Home blends recommendations from what you played, saved and opened, instead of
  changing with every title you look at.
- New Apps screen: search, Android shortcuts (Wi-Fi, Bluetooth, display, sound, storage, ...),
  recently used apps, TV banners, and hiding apps you never use (hold Select on an app).
- MovieHub has its own icon and banner, and is named "MovieHub" instead of "Home".
- Every pop-up is now a panel on the right in MovieHub's style instead of Android's grey dialogs.
- Redesigned Sources (quality, HDR, codec and size at a glance), Series (season pills with the
  episodes on the same page), Continue watching, Live TV, cloud and the player controls.
- Search: one large box with results appearing as you type, in Movies, Series and People rows.
- Movies/Series: Back jumps to the top bar from anywhere; Back again leaves.
- Clearer messages when something fails to load, with Try again or Open Settings.
- Updates show what's new before installing.
- Faster artwork: images you have seen are kept on the box and load instantly.
- Home: the featured title's button is back to the "View details" style, sized to its label.

## 0.14 (18 Oct 2026)

Published from `main` after PRs #32 and #33:

- Redesigned title details page: full-screen artwork with the type and genres, a large title,
  facts, synopsis (shown once, not twice) and pill buttons on the left, the main action in white;
  then a "More like this" poster row, the collection and the cast as round photos.
- The Movies/Series title area keeps the same height for every title, so the poster rows no
  longer move when a synopsis is shorter or longer; the title sits higher to make room.
- Home: the featured title's action became a slim "More info" pill (changed back in 0.15).

## 0.13 (18 Oct 2026)

- Settings: Appearance, Playback and System now use the same cards as Accounts (title, short
  description, current value on the right, one action button) instead of plain switches and
  full-width buttons. The redundant "Return home" button is gone; Back does the same.
- Movies and Series open on a new start page in the style of the big streaming apps: a large
  backdrop with the focused title, year, rating and synopsis, above rows of posters by category
  (Trending, Popular, Top rated, release lists and every genre). Posters have no captions, the row
  being browsed always sits fully in view, rows load as you reach them, and your place is kept when
  you open a title and come back. Browse, Filters, Search and Library work as before; a category
  chosen there opens as a full grid, and Back returns to the rows.
- Home: the featured title's artwork now fills the whole screen behind the page, pinned top-right
  with soft fades on the left and bottom, instead of sitting in a zoomed-in box. Artwork
  cross-fades as you move between posters, and no placeholder block flashes while it loads.
- Home: a MemoryClean shortcut beside Settings, shown as the app's own icon with no box (only
  when an app named exactly "MemoryClean" is installed). The clock moves left to make room.
- Focused list rows (for example stream sources) no longer grow into the rows around them or past
  the screen edge; small items still grow slightly, and focused items always draw on top.

## 0.12 (18 Oct 2026)

Published from `main` after PRs #25 and #26:

- Full portrait posters and redesigned movie/series browsing with filter overlays (PR #20).
- The catalogue no longer jumps back to the top when the first page arrives; long category lists
  moved into a Browse overlay (PR #21, plus direct follow-up commits on initial poster focus).
- Rearrange the Home hotbar with the remote; app actions and Android system controls in All Apps (PR #23).
- Redesigned streaming menus: catalogue previews, cinematic details, trailers, cast, collections,
  search history, combined filters, library sorting, viewing progress, watched episodes, channel
  groups and logos, source quality filters, cloud breadcrumbs and player options (PR #24).
- Fixed: quick D-pad presses along a poster row or grid could lose the selection.
- Failures the app recovers from are logged to logcat (tag `MovieHub`) without account details.
- Device tests are reliable again (8/8 parallel runs) and call app code directly; CI actions use Node 24.
- Code restructure: Java sources reformatted for readability, `MainActivity` split into feature
  classes, project documentation added, and failed device tests listed as CI annotations. No
  intended behaviour change (PR #25).

## 0.10 (17 Oct 2026)

Built from PR #19: keeps scrolling artwork loaded and simplifies catalogue navigation (grouped
catalogues open on daily trending).

## 0.9 (17 Oct 2026)

Built from PR #18: keeps TV focus stable while movie and series feeds load continuously; end-of-list
message and scrolling captions.

## 0.8 (17 Oct 2026)

Built from PR #17: complete TMDB category browsing in Movies and Series (all movie and TV genres,
trending, popular, top rated and release/airing lists).

## 0.7 (17 Oct 2026)

Built from PR #16: TV home fits the viewport; catalogue and account navigation made visible.

## 0.6 (16 Oct 2026)

Built from PR #15: MovieHub TV browsing, scalable IPTV and fullscreen playback, with signing
continuity checks.

### Experimental provider runtime 2 (unreleased)

- Execute fifteen additional historical Masqueradarr adapters locally, including HTTP/POST, async replay,
  cookies within operations, Tubi crypto, compressed catalogues and fresh stream resolution.
- Add individual settings, stable SQLite locators, inline guide isolation and optional local HLS header transport.
- Preserve direct playback and existing providers; document partial live evidence and remaining browser/source blockers.

## Unreleased — live controls and source choice

- First Back shows live controls; next Back returns to the guide’s retained mini-player.
- Add a selectable Sources action, Automatic by default, with manual source pinning.
- Keep backup discovery local and resolve only a selected source or the next bounded failover.

## Unreleased — additional sports source and segment requests

- Add opt-in FreeShot UK sports with lazy channel resolution, native playback headers and scoped anonymous token renewal.
- Accept whole-entity byte-zero requests for wrapped DaddyLive segments and return decoded video bytes instead of rejecting playback.

- Retain a manual or automatic backup and its finite retry history through mini-player/full-screen handoffs, instead of reverting to the primary source on reopening the same channel.

Provider-request read timeouts now honor explicit upstream values within the remaining
search budget, with an eight-second connection cap. A regression checks a 20-second read
request and clipping to a five-second remaining budget; cancellation remains bounded.

- Keep detached VOD SurfaceTextures alive until asynchronous native shutdown completes,
  including Activity destruction while switching to Live TV. Retain non-blocking controls
  and add a real-VLC blocked-stop/window-destruction regression.
