# Status

## Rolled back to 1.0.12 code — 1.0.16, 28 October 2026

The owner reported 1.0.15 as broken and asked to return to 1.0.12. The source tree is restored exactly to the 1.0.12 publish commit `c36ff6c` (only this note and the changelog differ) and published as 1.0.16 (code 116), because the updater rejects lower version codes. The 1.0.13–1.0.15 work remains in Git history if any of it is wanted back later. Sections below describing 1.0.12 apply to this build.

## Broken sources no longer count as watched — 1.0.10, 27 October 2026

The owner saw an episode marked watched and the next one start at once, most likely from a faulty
source. `VlcPlaybackActivity` treated any LibVLC end as a finish; the short-video guard only covered
free sources with a known length, so a source that ended with nothing played slipped through. Ends
are now checked by `PlaybackEnd`, using only positions actually played (a closed stream may report its
full length as the time). Published as 1.0.10 at the owner's request as an emergency fix; the owner reports a failing
video now moves to another source. 1.0.11 also rejects a resume point beyond the
end of a shorter or broken copy. 1.0.12: backups, Try again and saved positions use
`playedPosition` instead of the dead stream's time. Not yet tried on a device or TV, and which source
caused the owner's case is not known.

## Additional FMHY routes — merged, 27 October 2026

Movy Boise/Miami and Rive Vanguard (episodes only) use the existing free-source system and retain separate playback-route labels. PR #172 is included in the same integration branch. Local core/provider checks and 68 Python regressions pass; sampled desktop video/audio/runtime evidence exists for the four routes. The first packaged Movy audit passed all 16 movie/episode links including measured 4K with audio. Vanguard passed its episode; its failed movie route is not newly enabled. Atlantic still failed Android after an adapter-only MIME setting was tried. Its draft adapter and registration have been removed from this batch. Final integration head `3cd19c6` passed Android build/lint, signing/updater compatibility, both device suites and all 17 retained source playback checks. [PR #173](https://github.com/billyke2000s/moviehub/pull/173) merged the sources and all of #172. These changes are published in 1.0.9; no physical-TV result is claimed.

## Confirmed source quality — 1.0.6

Movie links' quality is read from the file or stream where possible instead of trusting the
website's label, websites that overstate quality lose trust, the first choice waits up to 8 s when
nothing good has been found, and the player corrects labels and points out confirmed better
sources without switching by itself. Known limits: MPEG-TS and DASH files are not size-checked
before playback (only once playing); a streaming playlist's label is what it offers, and the
player may still choose a lower step on a slow connection; how often real websites mislabel links
has not been measured. Not yet tried on a physical TV.

## Current release — 27 October 2026

1.0.9 (code 109) is published from `release/moviehub-1.0.9` at
`13c867e5a74c0224e913adcab8cb50056c571201`. The public APK download matched the update feed's
SHA-256 (`94a07683f121fc00638be0d56c33fd440212db262fa6f376898609470c5bc9b5`).
The [publish run](https://github.com/billyke2000s/moviehub/actions/runs/40506979965) passed core/device
checks, release build/lint and signing-certificate continuity before updating the existing feed.
The [numbered release](https://github.com/billyke2000s/moviehub/releases/tag/moviehub-v1.0.9) contains
the same APK and manifest. This version includes Movy Boise/Miami, Vanguard episodes, ratings,
recent-episode labels and Anime. No physical-TV playback result is claimed.

The initial 1.0.4 live audit covered all 33 then-enabled movie websites and verified 18 of 21
returned links across seven sites. The final focused pass verified Levidia (four of six links),
one 4KHDHub HD link, Vixsrc and a known Rarefilmm film, with decoding, plausible duration,
midpoint playback and decoded/output audio. Other links failed or remained unverified. A
3840×1600 file decoded and sought in an earlier pass, but reliable 4K audio playback was not established in that initial audit; no universal provider or 4K guarantee is claimed. See the dated audit reports.

The inventory is 38 enabled websites and 41 adapters, plus excluded auditable sites, with 68
original Kodi modules and four native modules. Rive is the first addition in the new
FMHY-only batch; desktop movie/episode decoding passed, with Android validation tracked in
[the addition report](docs/FMHY_ADDITIONS_2026_10_26.md). Four additional exclusions have fresh sale/parking
or unrelated gambling evidence. Historical notes below describe earlier snapshots.

## Continuous series and automatic playback

New settings keep manual source selection as the default. Next-episode metadata is prefetched while watching; season transitions use catalogue episodes and air dates. Missing-pack episodes follow the selected manual/automatic mode. Guide colour shortcuts are handled independently of focused widgets, with Back revealing accessible controls. Physical TV, remote and real-account validation remain outstanding.

Last updated: 25 October 2026 (1.0 promotes the experimental line into MovieHub).

## Where things stand

- **Released versions.** Normal MovieHub: **0.57** (`android-tv-latest`, version code 57, published
  24 October 2026). MovieHub Experimental: **exp-15** (`android-tv-experimental`, version code 19,
  25 October 2026). The public `update.json` files are authoritative; merging code publishes nothing.
- **1.0 (in progress, 25 October 2026).** The experimental line becomes normal MovieHub: ExoPlayer Live
  TV and the fast provider process are now in every build, and `-Pexperimental` only changes the test
  app's identity. Until 1.0 is published, the lines below describe the state before it.
- **Two lines of work.** 0.57 was published from a one-off `release/moviehub-*` branch; `main` (last merge
  #120, 24 October) is three commits past that source and unreleased. All later work lives on
  `release/experimental-epg-exp11` (the name is historical; it now carries exp-15), about 70 commits
  ahead of `main` and never merged back. Experimental builds are published from that branch.
- **ZLive works on the TV (exp-15, 25 October 2026).** See "Correction" at the end of this file. Only
  the owner's spot check is behind this, not every channel or long sessions.
- **Code structure:** feature classes per area, see [ARCHITECTURE.md](ARCHITECTURE.md). Three files are
  over the 700-line limit in AGENTS.md and should each be split in a behaviour-free commit:
  `LiveZapper`, `LiveTvScreen` and `ChannelDatabase`; `SettingsScreens` and `MainActivity` are close.
  `LivePlayer` was removed with the LibVLC live player (27 October 2026), which also brought
  `VlcPlaybackActivity` under the limit.
- **CI.** The experimental branch's last merged PRs (#143, #144, #145) passed every check, including
  device tests. One playback device test (`vodRepeatedBufferingPreparesBackupsWithoutStoppingCurrentPlayback`)
  failed once and passed on rerun of the same commit; watch for it recurring.

## Known issues

1. **Not verified on real accounts or the target TV.** Automated tests use fixtures. Real TMDB,
   Premiumize, Xtream and M3U accounts, real app banners, and installing and updating on the TV box
   need a manual check.
2. **Recovered errors are logged, screen loads explained.** Failed screen loads show a plain
   explanation (`ErrorMessages`); other recovered failures only log the location and exception
   type to logcat (tag `MovieHub`).
3. **CI runner change.** The `ubuntu-latest` runner label moves to Ubuntu 26 from 19 October 2026;
   watch the first runs after that date.
4. **Android Gradle plugin 9.4.1** (replacing Dependabot #12) builds with Gradle 9.6.0 and Build
   Tools 36. First published in 0.16.

5. **Live TV satellite features are only tested on fixtures.** Channel numbers come from a fixed
   table of UK satellite numbers (June 2026) matched by name, so some channels may get none or a
   wrong one. The guide layout was checked by running the real Free-TV and iptv-org lists
   (18 October 2026, about 12,500 channels) through `GuideOrder` on a computer, not on the box.
   A channel's country comes from its guide id, a provider prefix or a country-named group; free
   channels with none of these are filed as international. Pausing live TV relies on LibVLC: whether a real stream resumes where it paused or
   jumps to live depends on the stream, and rewinding works only on streams that allow seeking.
   Reminders show only while MovieHub is open. Automatic backup switching has not been seen on a
   real failing stream. Moving the live picture between the full screen and the guide (one shared
   LibVLC player, views detached and attached) is tested on the emulator with a local stream,
   not on the X905X5M box.

6. **Provider channels still arrive slowly.** Pluto and the free lists now import before the
   optional providers, but the providers still run one after another (their script steps share one
   lock with playback, so running them in parallel would delay starting a channel), and the
   programme guide is fetched only after all of them. Not measured on the X905X5M box.

7. **Relayed sports streams used a 4 s buffer** in the removed LibVLC live player; ExoPlayer keeps its
   default buffering (see item 10). The relay
   retries a timed-out provider once. Whether this removes the short pauses seen on the owner's
   phone (21 October 2026) is not yet confirmed on a phone or the TV box.

8. **Live TV now draws through a SurfaceView** (21 October 2026), replacing the shared
   TextureView after the owner saw full-screen lag and a still picture over playing sound. Moves
   between the mini picture and full screen keep the stream but may blink. Emulator tests only;
   not yet checked on a phone or the X905X5M box. Earlier notes below about the retained
   SurfaceTexture describe the replaced design.

9. **Phone layout covers Live TV only.** Films, series, My List and settings still open in the TV
   layout. MainActivity now handles rotation itself (configChanges), so screens are not rebuilt
   when a phone turns.

10. **Live TV stream rules ported from the LibVLC player (27 October 2026).** Buffering, dead-stream,
    reconnect-budget and provider-link-renewal rules now apply to the ExoPlayer live player
    (`LiveStallRules`). Checked by core tests and emulator fixtures only; not yet seen on a real
    stalling provider stream or the TV box. Link renewal has no device test. The LibVLC 4 s relayed
    buffer was not ported; watch for short pauses on FreeShot/DaddyLive/ZLive.

11. **Live TV quality upgrades (27 October 2026).** Quickest source first, then a better-named source
    takes over as soon as it is found, with a 60 s trial, fallback and cooldown (`LiveUpgrade`).
    Emulator fixtures only. Candidates are judged by their names only. The copy playing counts at the
    better of its name and its measured picture, so a copy whose name overstates its quality is not
    upgraded away from. Plain-address checks open one extra
    request per candidate; not checked against providers that limit connections. Only the full screen
    upgrades; the guide's corner picture does not.

12. **LibVLC live player removed (27 October 2026).** Nothing could reach it (Live TV has played on
    `LiveStage`/ExoPlayer since 1.0), so its classes, its Live TV mode in `VlcPlaybackActivity` and its
    tests were deleted; films and series still play on LibVLC. The picture click (air-mouse OK, phone
    tap) it had was ported to `LiveStage`. `LiveControlsTest` now runs on `LiveStage`. Emulator only.

13. **Continue watching shows the next episode (27 October 2026).** One row per film or series,
    nothing under three minutes, and a finished episode's series offers the next aired episode
    (`ContinueWatching`, `LibraryScreens`). The next-episode lookup uses TMDB each time the screen
    opens; it has only been checked with fixtures, not real series. History saved before this change
    still has its old records; they are grouped the same way.

14. **Thumbs up/down, New episode, Anime section (27 October 2026).** Ratings are stored on the box
    and steer For you (`TitleRatings`, `ForYou`); Continue watching marks next episodes aired in the
    last week; Home has an Anime section built from TMDB discovery (`TmdbCategories.anime`). Checked by
    core tests and emulator CI only; awaiting the owner's check on the box. [Unverified] whether the
    👍/👎 symbols draw on every box's font.

## Decisions

- Version 7 → 8 channel upgrades must add `main` before renumbering the preferred copies.
  The repair preserves the existing database and encryption key; clearing app data is unnecessary.
  A device regression fixture now omits the column, unlike the older version-number-only test.

- My List, watch history and watched episodes stay in SharedPreferences (history encrypted).
  They are read only on detail pages and menus and the history is capped at 50, so a database
  would add migration risk without a visible gain (reviewed 18 October 2026).
- Film skips are combined into one jump 750 ms after the last press, as Kodi's default seek delay
  does. LibVLC's `--input-fast-seek` (keyframe seeking, which Kodi also uses) was considered and
  left out: VLC 3 seeks to a keyframe at or before the target, so in files with far-apart keyframes
  a +10 s skip could land behind where you were (the device-test video has keyframes only at 0 s
  and 16.7 s). Kodi avoids that by seeking forward to the next keyframe. Revisit only with a test
  on real files (18 October 2026).
- The film buffer stays at 1.5 s (`--network-caching=1500`, shared with Live TV). A larger buffer
  delays the start and every jump, which were the slow parts reported (18 October 2026).

- Big guide lists (all channels, sections, countries) read each channel's preferred copy
  (`main=1`, database version 8) through an index instead of grouping every copy. Favourites,
  provider groups and searches still group, because they can match a backup copy only (each copy
  has its own key, group and name). Checked equal to the grouped queries in SQLite (19 October
  2026).

## Open pull requests and branches

- **#121–#140 are already inside the experimental branch** (checked 25 October 2026: none has a commit
  the branch lacks). They are a stacked chain, most based on one another, and none reached `main`.
  They can be closed as superseded, or the experimental branch merged to `main` as one reviewed PR when
  it is ready to become a normal release.
- Dependabot #3, #12 and #13 mentioned in earlier notes are closed; LibVLC stays on 3.7.0.
- Branches that are merged or no longer needed can be deleted on GitHub: `ci/device-test-control`,
  `ci/flaky-measure`, `refactor/readable-structure`, `fix/flaky-device-tests`, `feature/launcher-shim`,
  and every `feature/*` or `fix/*` branch whose pull request is merged. `diag/zlive-boomerang` holds the
  25 October ZLive diagnostics (decoy matrix, headless-browser recording); it only runs when started by hand.
- `release/moviehub-*` branches are one-off publish jobs and are intentionally not merged.

## Suggested next steps

1. Decide how the experimental line returns to normal MovieHub: one PR from
   `release/experimental-epg-exp11` to `main`, then close #121–#140.
2. Split the files over 700 lines (above) before adding more to them.
3. Check more ZLive channels and a long session on the TV; add ZLive playback (not only the catalogue)
   to the live probe, using the decoy check from the diagnostics.
4. Optional: a TMDB account sign-in so My List syncs to a TMDB watchlist.

## Native free-TV work (19 October 2026)

PR #81 adds native regional Pluto discovery, resolve-on-play, source cancellation, guide data and Settings → Free channels controls on top of #80’s optional hosted connection. A real GB catalogue returned 187 channels; one sample decoded successfully on desktop. Other upstream providers are not advertised as native. Validation and release status are linked in the PR and GitHub release; physical TV checks remain outstanding. See [the full evidence record](docs/NATIVE_FREE_SOURCES.md).

## Standalone engine development

Experimental local JS engine and FreeLiveSports wiring are implemented on a development branch.
This is incomplete, opt-in and not a production release. The current Masqueradarr server submodule
returns 404; current adapter parity and automatic inheritance are blocked by source access.
Signing-key setup and physical ARM64 playback checks remain outstanding. Full implementation,
provider matrix and evidence: [standalone provider engine](docs/STANDALONE_PROVIDER_ENGINE.md).

## Extended Android provider port

Draft PR #83 now adapts fifteen additional historical upstream providers through Runtime 2, including
DaddyLive's multi-player resolver and Tubi's signing/bootstrap. Discovery has succeeded for several FAST
platforms; DaddyLive discovery alone does not establish playback. Remaining unported dulo login
and HDHomeRun transport are documented. Nothing is merged or released by this work.

The standalone-engine code at `4ca05fdf89eebc659e7ba8f3e0c66cdd04b72abf` passed build/lint, signing
compatibility, provider verification and the complete TV emulator suite. A debug APK is available in
[CI artifacts](https://github.com/billyke2000s/moviehub/actions/runs/39341576650). Ten added providers
have one decoded desktop media sample each; this does not prove physical TV playback or all channels.
The PR remains draft, and automatic remote provider updates remain unconfigured.

## Provider channels as backups (20 October 2026)

Provider-engine channels now join the existing Live TV guide and backup switching instead of
standing alone: copies of one channel across free lists, accounts and providers share a duplicate
key (`ChannelIdentity`, database version 9), provider channels go into the shared sections
(`ChannelCategory`), and a failed source moves to the next copy once each. Enabled providers no
longer depend on the free-list setting. Checked with core checks, the real Free-TV/iptv-org lists
on a computer, and fixture device tests in CI. **Not yet seen on the TV box or on a real failing
provider stream**; whether a given provider's Sky Sports copy actually plays depends on that provider.

Flaky device tests seen 20 October 2026: run 39357280476 (attempt 1) failed
`EpgImporterTest.matchesChannelsAcrossNamingStylesAndKeepsOnlyCurrentProgrammes` (a parsed XMLTV time
130 s off) and `LiveGuideTest.pagesWrapRoundLikeTheSatelliteGuide` (timed out on "first page again").
The commit changed neither area, both passed on the two previous commits, and attempt 2 passed
unchanged. Cause not found; watch for a repeat.

## Live provider probe (20 October 2026, GitHub runner, United States)

`provider-engine/tools/live-probe.mjs` (workflow "Live provider probe") runs each provider with the
app's own interpreter, allow-lists and limits, then decodes samples with ffmpeg. Run 39361949935,
after raising the limits:

| Provider                                                                  | Catalogue                         | Sample playback                                          | Fault                  |
| ------------------------------------------------------------------------- | --------------------------------- | -------------------------------------------------------- | ---------------------- |
| STIRR, TCL, Xumo, LG, Whale, Vizio, Roku, Tubi, FreeLiveSports, Local Now | works                             | plays                                                    | —                      |
| Samsung TV Plus (GB)                                                      | 252 channels                      | some play; some GB streams fail from a US runner         | probably region        |
| Plex                                                                      | 692 channels                      | stream 404                                               | provider               |
| DaddyLive                                                                 | 899 channels (45 Sky Sports)      | original probe: 403; missing token step identified below | incomplete integration |
| DistroTV, Vidaa                                                           | host does not resolve (ENOTFOUND) | —                                                        | provider               |
| ZLive                                                                     | catalogue 404                     | —                                                        | provider               |

Before the fixes, Samsung (GitHub redirect not allowed), Tubi and Plex (over the 3/4 MB limits)
failed inside MovieHub. Not checked on the TV box: catalogue steps there are slower (Plex's slowest
step took 2.9 s on the runner; the box limit is now 10 s), and UK region results may differ.

## DaddyLive investigation correction (20 October 2026)

The original "outside players refused" conclusion was too broad. The actual CDN response was `token
required`: the current player bootstraps an anonymous 120-second playlist token, which the historical
resolver omitted. Adding that ordinary request returned HTTP 200 HLS. Media then needed PNG container
unpacking; direct FFmpeg initially failed. After unpacking, Main Event, Football, Action, F1 and ABC USA
samples each decoded successfully (FFmpeg exit 0). The original walk also exhausted its 20-second budget
before later players, so it was not evidence about every player.

The development fix adds the current token protocol, scoped in-memory renewal and bounded PNG transport
to the existing native player/relay. Local core checks, new PNG fixtures and relay/session fixtures have
passed; live checks and Android CI are recorded with the change. Physical TV, long sessions and all-channel
availability remain unverified. No release or in-app update has been published by this fix.

## Box report: hidden provider failure (20 October 2026)

The user's TV reports no successful DaddyLive refresh, before the playback resolver is reached. Its
failure description is clipped because Settings cards had a fixed 78dp height; the guide also clips
large text in short rows. This follow-up fixes card sizing, places failures before catalogue metadata,
and retains safe HTTP/network/runtime failure categories rather than only the outer exception class.
Guide labels now fit row height. This is a readability and diagnosis fix: the actual cause of the
reported box's DaddyLive catalogue failure is still unknown until another refresh records its category.

## Main Event playback report (20 October 2026)

The latest TV screenshot shows DaddyLive discovery succeeding (899 channels), so the previous catalogue-failure report is no longer current. Playback is a separate path: selecting its stored channel identity runs the player-chain resolver and hands a transient session to the native relay. A fresh Main Event (38) walk returned valid HLS/session metadata through Player 1 in about 28 seconds in this workspace, using the bundled module in Node VM and curl transport. The actual JVM step hit its processing deadline in this slower environment; this Node observation is not Android interpreter evidence.

A confirmed app mismatch started the 15-second video-startup timeout before provider resolution, even though the broker allows a longer bounded lookup. The fix starts that timer when LivePlayer has the URL/relay, retaining immediate resolution-error failover and cancellation. Physical TV playback remains to be tested; a successful directory refresh alone never proves playback.

The same fresh Main Event result returned HTTP 200 media HLS and a 6,803,517-byte PNG segment. Production ProviderPngSegments unpacked it on Java 17; FFmpeg decoded four seconds of video with exit 0. This verifies a desktop sample and the native unpacker, not physical TV playback or a complete native-network relay session.

## Sustained playback report (20 October 2026)

The user confirmed Main Event starts on the physical TV, then reported repeated freezes and a frame stuck for several minutes without a buffering spinner. This verifies startup only, not sustained playback or token renewal. The exact TV-side cause remains unknown. Confirmed code gaps: only VLC buffering/error events could initiate recovery, and PNG processing unnecessarily walked every byte of filter-0 rows. The latest sample used filter 0 on 3,815 of 4,477 rows.

The fix adds native/provider progress monitoring (clock plus displayed-picture counter) with the existing eight-second threshold, one provider retry then normal backup/error handling; paused/hidden playback is excluded. PNG bulk copying and larger inflate buffers improve desktop decoding with identical output. This does not prove that provider-side frozen content, timestamp discontinuities, hardware decoding or network delivery is fixed on the box. Token renewal already has bounded fixture coverage; this report alone does not identify a renewal failure.

## Live guide/full-screen handoff repair

The previous shared-player handoff still detached LibVLC's native video output. LiveVideoOutput
now retains its SurfaceTexture/Surface across both presentations, with a temporary last-frame
cover until fresh video arrives. Stronger emulator checks verify rendered output and repeated
round trips, rather than only the playback clock. Revision `89f90e285b7944b1be93f40b9cde40dfef7df873` passed
formatting, all 293 core checks, APK/debug/release builds and lint, signing compatibility,
provider checks and the complete API 28 TV emulator suite. Eight measured handoffs produced
fresh destination frames in 47–104 ms, preserving the output and avoiding stream restarts.
Physical-TV frame pacing and seamlessness are not yet verified. Local execution was unavailable;
checks ran in CI.

- [Android validation](https://github.com/billyke2000s/moviehub/actions/runs/39492778810)
- [Provider validation](https://github.com/billyke2000s/moviehub/actions/runs/39492778815)
- [Test APK ZIP](https://github.com/billyke2000s/moviehub/actions/runs/39492778810/artifacts/12296945928)

The earlier repeated-trip test reused an ActivityMonitor after waitForMonitorWithTimeout
removed it. Both first trips passed with fresh frames; the second trips could not detect their
Activity. The test now registers a new monitor per trip, and the final full suite passed.

## Live playback, controls and FreeShot (20 October 2026)

The user confirmed instant mini-player/full-screen movement on the physical TV, then reported
video freezing within about five seconds while audio continued, audio missing after a reload,
and playback recovering on a move back to the mini-player. Physical-TV sustained playback and
the precise cause of its audio symptom remain unverified.

The candidate counts actual TextureView updates even when presentation timestamps repeat and
tries one consumer-only refresh for stopped rendered video while the clock advances. It keeps
the decoder, network session and audio output. Persistent stalls use bounded source recovery.
The relay also accepts `Range: bytes=0-` for wrapped segments by fetching/decoding the complete
entity instead of applying decoded offsets to its PNG container.

First Back reveals the live controls and Sources; the next returns to the retained mini-player.
Sources defaults to Automatic. A manual choice pins that source; restoring Automatic tries
unvisited copies once, including earlier copies, then stops. The menu reads saved database rows;
only a selected source or needed failover is resolved.

FreeShot (UK sports) is available as an opt-in source in Settings → Free channels. It imports
the public related-channel directory in one request, follows only the chosen channel's literal
embed/player links, normalizes relative/whitespace-containing iframe addresses, and uses the
native player. Fetched page scripts and adverts never execute. Anonymous tokens renew at the
fixed WideIPTV endpoint within one discovered BlueTier origin and channel prefix. Other website
countries/categories are not imported.

Verified live host evidence: FreeShot Main Event decoded video (149.92 seconds, 3,748 unique
frames) and audio (150 seconds, 7,032 unique frames) through the actual resolver and production
relay, including forced token renewal, with no timestamp gaps. DaddyLive Football decoded both
tracks for 150 seconds without timestamp gaps after the wrapped-range fix. DaddyLive Main Event
showed a roughly 10-second timestamp gap in the host check; this is not proof of the TV's cause.
The desktop diagnostic explicitly preserves Origin to match Android; otherwise Java silently
omits that header.

Local validation: 540 core checks, compiler tests, 21 engine checks, 45 actual-Rhino multi-provider
checks, 17 PNG checks and four real-TCP wrapped-range checks passed. The FreeShot relative-URL and
channel-identity fixtures also passed after normalization. Android run 39513033996 passed all 122
TV emulator tests (zero failures/skips), debug/release builds and lint, instrumentation compilation,
and permanent-signing compatibility. This includes a stopped real texture consumer with continuing
audio, three wrapped-HLS reloads with both tracks advancing, constant EGL timestamps, Back/Sources,
and two guide/full-screen round trips after manually selecting a backup. Player/output identity,
current copy/mode, source-open counts and fresh nonblank video remained correct. Twelve fresh-frame
handoffs measured 44–137 ms on this emulator; this is not a hardware latency guarantee.

[Signed test build](https://github.com/billyke2000s/moviehub/actions/runs/39513033996/artifacts/12307505129)
(source `4ae9dce6e6adeb8d95b319ff46f0eb90897a3b22`). Extract and install `moviehub-test.apk`.
No production update has been merged or published.

## Reviewed pending work (22 October 2026)

The integration branch adapts #82 (VOD recovery), #88 (early still-picture repair), #89 (current SurfaceView diagnostics), and the remaining #90 still-picture cover while preserving subsequent surface fixes. #84, #85 and #87 already belong to main; #92's publishing checks are superseded by a shared core-test script. #91 is a duplicate combined test branch.

#77's empty video registry and conflicting free-channel gate are not imported: DefaultSourceProviders.EXTRA already registers shared movie/series manifests, and ProviderSettings already owns provider opt-in and per-provider settings. Its third-party warning is added to the existing SafetyNotice without silently disabling existing channel preferences. No new provider adapters are claimed. WorkManager is updated to 2.12.0 for current CI validation.

Local core validation: 664 checks passed (Eclipse JDT/Java 17). Android build, lint, emulator and signing validation must be read from this integration PR before merging. Physical TV playback and installation remain unverified.

## Live shutdown follow-up (22 October 2026)

The integration PR passed its full emulator suite, but post-merge run 39821398629 crashed in
native EGL initialization during the channel-key playback test. Live shutdown now cancels
surface callbacks without destroying the native window, stops VLC, then detaches the view.
Release validation is pending on this follow-up; no real-device result is claimed.

## Handoff capture follow-up (22 October 2026)

Post-merge run 39824605890 repeated the native EGL crash during a guide/full-screen handoff.
The attempted 0.45 publish ran all 136 tests with one failure: the manual-backup handoff
reported fresh pictures before its surface had a size. Nothing was uploaded. PixelCopy no
longer delays closing the old video output; destination evidence excludes old decoded frames
and waits for a valid, sized surface. Follow-up device validation is pending.

## Panel lifecycle follow-up (22 October 2026)

The second 0.45 release validation passed playback handoffs but failed two key-event tests
during dialog focus transitions and a teardown that retained a detached dialog. The release
upload was skipped. Panels now clear their reference on window detach and suppress follow-up
actions for a finishing/destroyed Activity. Input tests wait for actual focus; a new device
regression closes an Activity with a panel open. CI validation is pending.

Post-merge run 39827167743 passed 135/136 tests with one screenshot captured during handoff
before destination composition. Handoff evidence now observes actual pixels within the same
three-second total budget, including decoding and composition; validation is pending.

## Published 0.45 and next playback candidate

0.45 was published after PR #102 passed full emulator, build/lint, provider and signing checks;
the independent publish run 39828740034 and post-merge run 39829699262 also passed. The public
numbered APK and updater APK have matching SHA-256 digests. Earlier pending-validation notes
above describe intermediate attempts.

The next candidate improves scheduled link renewal without a second decoder, covers ordinary
IPTV with the existing freeze watchdog and separates bounded buffering profiles. Local core
validation passes 703 checks. Android validation is pending. Cross-provider failover still
restarts playback; preparing a second live decoder is deferred until physical-TV memory and
decoder concurrency can be measured. VOD already has bounded prepared replacements. Cache
profiles are fixed by transport, not dynamically adapted from measured network performance.

Surface recovery is also bounded to one repair until observed video progress resumes. A
repair that hides its surface can no longer reset the watchdog into repeated early repairs.

Release validation 39834499208 passed 142/143 tests, with a panel still retained when its
Activity was destroyed before window attachment. Explicit owner cleanup now covers that path.
PR validation also stopped during the native picture-repair fixture; that fixture now
establishes real frame progress before a second synthetic freeze, and full crash-buffer
diagnostics are preserved for subsequent validation. Main's newer README is retained.

Native window shutdown follow-up: post-merge retry 39868257885 crashed in EGL window
creation during native-source handoff. An unreported Vout count no longer permits immediate
detachment. Every move requests track shutdown; a missing acknowledgement after 600 ms
stops the native reader before moving and restarts playback on the destination. This rare
fallback reconnects the stream; normal acknowledged moves retain it. Device fixtures cover
unreported output counts and suppressed shutdown acknowledgement. Validation is pending.

Release run 39870438797 exposed a delayed overlay callback querying an externally released
shared live player. Overlay, skip, audio-focus, event, delayed-resume and error callbacks now
apply the existing ownership check before native access; a real-player regression covers it.

Independent release validation 39871238896 passed 146/146 tests. PR validation passed
145/146, observing audio track -1 during picture recovery. Forced native-stop recovery now
restores the selected audio track after rediscovery. Repair evidence waits for selected
audio and playing state, then checks new audio-output progress rather than old-session stats.

## Prepared live backups (23 October 2026)

0.46 was published with matching numbered/updater assets after 703 core checks and 146 Android
tests passed; its post-merge build/device run also passed. Earlier pending notes are history.

The next change adds bounded prepared Live TV failover to the shared session. It preserves
the live 8-second/3-buffer thresholds and the current stream until a single muted candidate
decodes video and available audio. Failed candidates are released and tried once each, with
working original playback retained after exhaustion. Fullscreen and guide ownership, manual
pinning, cancellation, native surface lifetime and provider relay renewal are covered by new
fixtures. A low-memory signal skips additional allocation. Different provider delays mean
programme-time alignment is not promised. Local core checks pass (733); Android CI is pending.
No physical-TV or real-provider concurrent-decoder test is claimed.

## Live TV control consistency

The owner reports colour keys still failing and inconsistent live-player controls. Confirmed code paths allowed live launches without the static guide session to use film controls, learned number/channel codes to be consumed before colour handling, and a fixed-width banner row to overflow. The candidate always builds live controls, persists per-player guide context, matches initial metadata to the launch channel, prioritizes learned colours and bounds banner content. Core checks pass (733); Android CI is pending. Physical remote behaviour and the precise appearance seen on the owner’s TV remain unverified.

## Live header and menu-owned remote keys

The supplied photographs both show SkyOverlay; losing the channel strip is distinct from falling back to film controls. The strip now reserves at least 64 dp while allowing larger text to expand it. Player side panels now forward colour shortcuts after dismissal, since their Dialog window otherwise consumes keys before the Activity sees them. Colour actions complete on release to avoid passing a trailing release into a replacement window. Added fixture checks cover all four colours across open panels and the entire header after number entry, programme updates and Back focus. All 733 core checks pass locally; Android CI and physical remote confirmation are pending. The exact cause of the photographed strip collapse has not been reproduced.

## Live playback recovery

The 0.49 recovery source passed all 166 Android TV emulator tests (no skips), 753 core assertions, Android build/lint, provider-engine checks and updater/signing compatibility (CI run 39985039245). It adds bounded native Pluto/direct reconnects, refreshed anonymous Pluto sessions, off-main native shutdown with retained window lifetime, surface validity checks and optional buffer diagnostics, and explicit Repair picture/Reconnect channel actions. Live 8-second/3-start thresholds and VOD rules remain unchanged. HLS transitions produced 320 decoded video frames and 547 played audio buffers without reconnecting. A native driver that never returns cannot safely be killed in-process; its resources remain quarantined. Physical TV/live Pluto causes remain unconfirmed; comparative Media3 testing has not been done.

Three short buffering notifications can still prepare an alternate source. A same-source reconnect requires prolonged buffering (eight seconds) or stalled video progress; ordinary HLS period notifications do not consume the reconnect budget.

## Consistent live banner and standard colours

The owner supplied new photographs after 0.49: a full banner on some channels and a collapsed strip on others, with colour shortcuts still failing. The next candidate restores first-down colour handling, prevents saved aliases from overriding standard colour identities, reserves explicit scaled header height and measures the banner before optional panels. The constrained-layout case and input edge cases have new regressions. 753 core checks pass locally; Android CI is pending. The exact physical-TV failure remains unconfirmed; no claim that the owner must inspect remote codes or configure standard buttons.

0.50 validation: all 169 Android tests passed without skips in CI run 40006802520, along with builds/lint/signing and provider checks. Inspected normal and constrained banner screenshots; header and all four controls are visible. Core validation: 753 checks. Physical-TV behaviour still requires confirmation.

## One live overlay and shared colour input

The owner confirms the problem persists on the current release. A concrete UI mismatch remained: OK opened Options when the banner was already visible, and TV picture clicks only revealed pause state. The candidate routes entry/OK/click/Back through the same banner and removes the attached film-controls tree from live sessions. Visible video receives initial focus. Colour dispatch is centralized before native access and shared with player Dialogs; first-received repeats, standalone releases and standard scan identities are covered without requiring remote setup or code capture. Core validation: 775 passing checks. Android validation is recorded on the pull request/release. The owner’s exact hardware-input failure is not reproduced and must not be declared conclusively resolved.

## Free VOD integration candidate (23 October 2026)

Bundled all 59 Scrubs V2 and nine Free99 non-archived provider modules and ResolveURL host
extractors. Added free discovery without a Premiumize gate, the three-second best-available
selection window and late-source appending to existing VOD recovery. Website responses are
probed before selection; reported quality is not a measured decoder resolution. Local Java
core checks and Python offline fixtures pass. Android build/emulator validation is pending
on this branch. No production release or real-provider/physical-TV success is claimed.
The Free99 metadata licence field is blank; its Scrubs ancestry and inherited GPL notices
are recorded. Kodi-library and API-key sources are explicitly not represented as working
anonymous website providers. The follow-up removes website-fed eval/exec, bounds direct urllib
requests, adds an external CI availability probe and verifies packaged Python imports on Android.

## VOD incoming-speed correction

The candidate counts incoming upstream relay bytes for free HLS/media-file sources, shows an explicit waiting state instead of blank statistics, and strengthens ready-link ranking. It preserves the three-second selection window and existing failover thresholds. Local fixtures and CI validate code paths; physical-TV validation is outstanding. A startup sample does not guarantee future host performance.

## Free VOD source audit and failure recovery candidate (24 October 2026)

The owner reports mostly empty providers, short error clips, absent speed and crashes on switching services. All 68 module constructors and original package hashes were checked: 59 Scrubs and nine Free99, no missing active files. Confirmed code gaps: discarded provider outcomes, a global deadline shorter than the worst-case worker schedule, manual free-provider pinning, no short-duration rejection and main-thread native shutdown. The candidate addresses these gaps and shows selected source, Mb/s and cumulative incoming bytes. The three-second first choice and existing buffering thresholds remain. See docs/FREE_VOD_AUDIT.md for the per-module inventory. Local live DNS is unavailable; provider availability must be recorded from the independent GitHub audit. Android/emulator validation is pending. No physical-TV crash diagnosis or real-title availability is claimed.

Additional confirmed compatibility defect: the bounded HTTP adapter returned an HTTP status integer in place of request headers in `output="extended"`. The actual WatchSeries provider then passed that integer as headers and swallowed the resulting exception. The adapter now returns the original five fields (body, request headers, response headers, cookies, final URL), without an extra cookie request. Default scrapePage Referer/protocol-relative handling and redirect cookie accumulation also match the upstream contract. A fixture drives the real WatchSeries provider through search, page and host lookup.

## Provider request/title compatibility correction (24 October 2026)

Reproduced and fixed bodyless POST becoming GET in the requests adapter, affecting the
actual Scrubs Levidia search. Added legacy default Referer/language headers, upstream
movie/episode title preparation and original/alternative TMDB aliases. Providers now have
30 seconds each and 300 seconds of background search headroom; the three-second initial
choice and buffering thresholds remain. Seventeen Python tests and 814 core assertions pass
locally. Android CI and real-provider outcomes remain to be checked on the final commit.
No Guardians playback or physical-TV success is claimed.

Provider-request read timeouts now honor explicit upstream values within the remaining
search budget, with an eight-second connection cap. A regression checks a 20-second read
request and clipping to a five-second remaining budget; cancellation remains bounded.

## EGL crash during film-to-Live-TV transition

Provider revision 202ecee passed Android build/lint/signing, core/Python/provider checks,
but emulator run 40100218911 crashed after 121 passes while the native-channel test closed
its initial film Activity. The prior asynchronous VOD join retained the Java view/engine
but could not prevent Android from destroying its SurfaceView buffer queue. Films now
use retainable TextureViews; VodSurface owns detached textures until native stop and
detachViews finish. A blocked-stop/Activity-destruction regression verifies this lifetime.
Full new Android validation is pending; no physical-device fix is claimed.

## One OK overlay and learnable unnamed colour buttons (24 October 2026)

The owner wants OK to always show the most detailed overlay, and reports that the coloured buttons of a universal/air-mouse remote do nothing (colour set-up never used). Confirmed code paths that showed different overlays: OK after BACK opened Sources when no button was focused; OK while browsing the current channel closed the banner; ◀ opened the reduced browsing layout; an air-mouse click showed the banner without information. These now show the same full banner (owner's photo confirmed banner only, no separate information panel). The live player also names unused buttons in a toast, to identify what the owner's MX3-style air-mouse remote sends; the owner reports its colour buttons work in other apps, and the colour mapping itself is unchanged since 22 October. Confirmed colour gap: buttons arriving as KEYCODE_UNKNOWN (other than the standard 398–401 scan codes) could not be learned, because set-up stored only the key code (0). Set-up now stores scan codes and shows what was received. Whether this remote sends its colour buttons to Android at all is unknown; running "Set up coloured buttons" (Live TV guide → Options) will show it. Core checks pass locally; Android build/device tests run in CI only (no SDK in the workspace). Not tested on the owner's TV or remote.

## One banner look (24 October 2026, after 0.56)

Owner photographs on 0.56 showed the banner with unboxed buttons (OK/channel change), with boxed buttons (after BACK, the look the owner wants) and once with the channel header strip missing. The banner now always boxes its buttons and ◀ ▶ move between them; the browsing layout is removed. The missing header strip is still unexplained: the strip has a fixed reserved height, and no app code hides it or draws over it. Its trigger on the owner's TV is unknown.

## Experimental ExoPlayer Live TV (24 October 2026)

At the owner's request, Live TV on Media3 ExoPlayer is being built as a separate MovieHub
Experimental app, keeping the normal app on LibVLC. First version: same guide and banner, ExoPlayer
shared between the guide picture and the full screen. Audio track choice only (no subtitle
rendering yet); no prepared standby backup. Built in CI only; not yet run on the owner's TV.

exp-2 candidate: one-screen Live TV (`LiveStage`) replaces the separate ExoPlayer full-screen
Activity. Same guide and banner code. Not yet compiled at the time of writing; not run on a TV.

## Experimental release follow-up (25 October 2026)

The published exp-3 predates the latest OK/banner changes. This candidate cancels stale engine
and screen retries, cancels abandoned resolution, and releases the static Media3 player/view
on stop. A dedicated experimental emulator suite now covers actual Media3 playback, picture
continuity, 404 retry cancellation, lifecycle and HLS tracks. Results must be checked on the
final CI commit before publishing exp-4. No physical-TV or real-provider success is claimed.
Frozen-picture detection remains diagnostic, preserving the experimental comparison behaviour.

## Experimental DaddyLive startup candidate (25 October 2026)

Desktop probes of the previous implementation took 18–20 seconds before playback. The timed
probe spent about five seconds in interpreter execution and eleven seconds in four network
requests; these are not TV timings. This candidate reuses one isolated process and compiled
code per lookup, caches public mirror pages briefly, counts failed network time, bounds fast
lookups and advances failed selected players. Local fixtures and CI must validate the final
commit before publishing the next experimental version. Physical-TV latency remains unverified.

## Provider corrections (25 October 2026)

- **Plex** (listed above as "stream 404 · provider") was a MovieHub fault: the play request used the
  stable channel id instead of the compound id Plex requires. Fixed; the live probe then played 2 of
  3 sampled channels from GitHub's US runner (the third opened but the probe's own output step
  failed). Not yet confirmed on a UK connection or the owner's TV.
- **DistroTV and Vidaa removed.** Their hosts still do not resolve; FastChannels retired DistroTV on
  10 September 2026 ("upstream shut down"), and Vidaa's replacement backend needs an app signing
  secret and OAuth taken from Hisense's APK, which was judged too fragile to adopt.
- **UK region copies were tried and dropped.** LG's API answered HTTP 500 for country GB; FastChannels
  also supports LG, Roku, Tubi and TCL for the US (and Canada for TCL) only.
- From the UK, LG Channels (US), Local Now (US) and Roku Channel are expected to fail (they work from
  the US runner). They are labelled "US only" from research; the guide shows each failure as it is
  (e.g. HTTP 403) and never guesses geo-blocking. An earlier exp-6 build did guess ("likely
  geo-locked") and offered to switch providers off after two such failures; the owner pointed out
  that a service down for a day would be mislabelled, so that was replaced by an opt-in "Hide
  US-only providers" setting that applies only to the known US-only list.

## Refresh schedule correction (25 October 2026)

`ChannelSync.refreshIfStale` measured the 12-hour interval from `lastSuccess`, which is written only when
no service failed. With any provider failing (e.g. US-only services from the UK) it was never written, so
every home or Live TV visit more than 10 minutes after the last attempt started a full refresh. The
interval now also counts `lastFinished` (any completed refresh), and an app update refreshes once.
Found by reading the code after the owner said refreshes "take a while"; not measured on the TV.

## Experimental EPG matching correction (25 October 2026)

Confirmed exp-9 imported XMLTV by plain channel name but queried built-in channels only by provider id, leaving valid schedules unused. Country-scoped XMLTV fallback now follows exact native lookups, and stale/empty/malformed priority guides no longer claim keys. Saved/base channels receive XMLTV before optional provider catalogues; new channel identities trigger a follow-up, without a second download for unchanged identities. The TV guide observes committed guide revisions during the import. Core regression checks pass locally; Android CI is pending. Freeview/Sky/Plex/Samsung feeds were reachable from the workspace; epgshare01 UK returned 403 here, which does not establish the owner’s TV response. No physical-TV verification claimed.

## Experimental EPG and ZLive integration

The public `Moviehub-epg` repository now generates and atomically publishes one compressed guide at 04:37 UTC daily. Its generated coverage report is the current authority for measured coverage and failures. The app integration removes separate built-in feed fetching and inline EPG writes, retaining cached SQLite data during failed refreshes. This work is on an experimental feature branch; see its PR checks and rolling experimental release manifest for build status.

ZLive's current public player supports additional media carriers; the app relay now handles these without a token-session requirement. Supported advertised source routes receive individual labels and stable ids. Live investigation found `cast.zlive.st/channels.json` returning 404 and legacy media returning 403. Complete current server discovery and successful external playback remain unverified; no access-control bypass was implemented. Physical TV validation remains outstanding.

## Experimental exp13 provider repair candidate (25 October 2026)

Based on published exp13 source `4fb8552`, despite its branch retaining the exp11 name. Current official packages remain Scrubs 5.1.42 and Free99 2.0.1; original active-provider hashes/inventories match. Anonymous search counts 63 distinct websites rather than 68 modules, keeps fallback scrapers and groups links by website. Resume refreshes movie/episode metadata and provider results while retaining progress. Separate discovery/resolution budgets and media-cookie/identity corrections address local integration limits. Dead sites, unsupported hosts, refusals and title gaps are not declared fixed.

ZLive's public catalogue moved to `iptv.zlive.st/channels.json`; the current player uses `/session/ticket` and encrypted POST `/resolve`. A host probe using this flow obtained a valid HLS playlist and decoded a real MPEG-TS segment containing 1280x720 H.264 video and AAC audio. This is one channel/segment, not a physical-TV or long-session result. The observed catalogue listed one source per channel; labelled advertised source routes remain supported without inventing unavailable alternatives. Full final-commit Android CI and per-website audit outcomes are recorded on the repair PR.

**Correction (25 October 2026):** exp-14 played ZLive's decoy, a looping "refresh the site" clip, on the owner's TV. The ticket/AES-GCM protocol was correct (it decrypts the real web player's requests); ZLive chooses the decoy when API requests lack browser fetch metadata. A request matrix from GitHub runners got the decoy (`route.transcode.cfd`, ~85 KB chunks, huge discontinuity sequence) with the old headers or with client hints alone, and the real stream (`hello.transcode.cfd`, ~3 MB Full HD chunks) once `sec-fetch-*` and Accept headers were added. The earlier "decoded 720p segment" probe result above was very likely the decoy. Fixed in #145 and published as exp-15; the owner confirmed ZLive channels play on the TV the same day.

## VOD exclusion review and compatibility recovery (25 October 2026)

Only Levidia remains restored after positive verification. The other 18 provisional restorations from #155 are disabled pending positive catalogue/search evidence; seven retired domains remain disabled. Ordinary discovery searches 38 websites and 41 adapters. Levidia homepage and search were directly reachable at the exact www address used by its scrapers. Current source contains the merged 1.0.2 header-isolation, file-size and search-budget fixes, plus maintained Levidia parsing and general metadata/redirect repairs. Automated live outcomes and fixture results must be read from this change’s final CI; no physical-TV result is claimed.

## Site-by-site VOD recovery (26 October 2026)

Site-by-site recovery is in progress on `fix/site-by-site-vod`. All 63 public website roots were inspected; reachable sites were traced into current searches, detail pages and player protocols. The website/host list is separated, and concrete parser/transport repairs are documented in [the audit](docs/FREE_VOD_SITE_AUDIT_2026_10_26.md). Inconclusive responses are not treated as closure; no excluded site is restored in this work. Offline/core and exact-head runner validation are required before completion. No new APK published.

Initial runner audit [40251539758](https://github.com/billyke2000s/moviehub/actions/runs/40251539758) at `4522a1c` completed 57 movie-site and 48 episode-site checks, covering all 63 public sites. The focused Inception probe returned eight validated media links from FZMovies, AnyMovies, M4uHD and Iwaatch. Tubi’s current anonymous movie protocol and Waffi’s separate mirrors/online player were then inspected and adapted; their exact-head runner results are still pending. Browser-challenge and unsupported downstream-host cases remain explicitly unresolved.

The follow-up runner validated 12 Inception links across six websites and four clear-HLS Tubi links for a listed film. PR 158 preserves the independently merged PR 157 backdrop fix and tracks combined Android/device validation. The exact reported Spider-Man title is included in the final public-source audit. Release target: MovieHub 1.0.4, update code 104, preserving the existing package, certificate and updater feed.

## Native movie source expansion (26 October 2026)

1.0.4 is published (code 104, unchanged signing identity). Its existing-site recovery was followed by a 33-movie-website Android audit: 21 returned sources, 18 decoded and passed runtime plus midpoint-seek checks across seven websites; two Levidia links did not decode and one other film lacked an independently verified expected runtime. New native 4KHDHub/Vixsrc adapters are staged in this branch; live Android results are pending and no new 4K release is claimed yet.

The Android backup-switch audio assertion recurred on unchanged production playback code. The selected replacement now restores a transient native zero to normal gain; Android still controls the user volume. Fresh homepage checks confirm anitaku.pe is parked, goojara.club and okmov.to lead to GoDaddy sale listings, and series9mx.pro leads to an unrelated gambling service. These four remain bundled for auditing but leave default discovery. Upmovies and WatchSeriesHD remain inconclusive on the fresh request and are not newly retired.

The first native Android audit passed Vixsrc but all seven resolved 4KHDHub files failed to decode. Follow-up requests proved finite ranges return 206 while no/open-ended ranges return 403. The new opt-in finite-range transport and progressive-file 403 fallback are tested locally (19 new checks); the final live Android rerun is pending.
