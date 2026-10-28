# Testing

## Continuous series and automatic playback

`VideoFilesTest` includes episode ordering, future-date handling and source-resolution ranking. `SeriesAutoplayTest` uses catalogue and source fixtures to check season transitions, updated history keys, missing pack episodes, show endings, uncertain filenames, bounded source fallback and cancellation. `LiveGuideTest` covers colour keys from header focus and Back/arrow/OK controls; `LiveControlsTest` covers all four physical colours during playback, learned scan-code colours, and OK/click/◀ always showing the same banner. These do not verify a particular physical remote or real-provider playback.

There are three layers of checks. CI runs all of them on every pull request that touches
`android-tv-launcher/` (workflow: `.github/workflows/android-launcher.yml`).

| Layer                          | Where                                      | Needs                                | Runs in CI step                                              |
| ------------------------------ | ------------------------------------------ | ------------------------------------ | ------------------------------------------------------------ |
| Core checks (932)              | `android-tv-launcher/tests/`               | Only a JDK                           | "Verify playlist, network, channel search and episode rules" |
| Build and lint                 | Gradle                                     | Android SDK 35                       | "Build APK and run Android lint"                             |
| Device tests (instrumentation) | `android-tv-launcher/app/src/androidTest/` | Android emulator                     | `device-tests` job                                           |
| Signing compatibility          | release build vs published APK             | signing secrets (same-repo PRs only) | `signed-compatibility` job                                   |

## Core checks (fast, run these locally)

Pure-Java rules for playlists, URLs, channel search, episode matching and combining film skips. They do not need Android.

```sh
mkdir -p build/core-tests
javac -d build/core-tests \
  android-tv-launcher/app/src/main/java/local/tv/home/{Playlist,Network,VideoFiles,EpisodeOrder,SourceRanking,ChannelQuery,EpgMatch,SkyNumbers,GuideOrder,SkipQueue,ErrorLog,SpeedTest,ChannelIdentity,ChannelCategory,ProviderFailure,LiveProgress}.java \
  android-tv-launcher/tests/{PlaylistTest,NetworkTest,VideoFilesTest,ChannelQueryTest,EpgMatchTest,SkyNumbersTest,GuideOrderTest,SkipQueueTest,TroubleshootingTest,ChannelIdentityTest,ProviderFailureTest,LiveProgressTest}.java
for t in PlaylistTest NetworkTest VideoFilesTest ChannelQueryTest EpgMatchTest SkyNumbersTest GuideOrderTest SkipQueueTest TroubleshootingTest ChannelIdentityTest ProviderFailureTest LiveProgressTest; do
  java -Xmx64m -cp build/core-tests local.tv.home.$t
done
```

Expected output: 17 playlist, 9 network, 110 episode/file, 13 channel-query, 18 programme-guide
matching, 17 satellite numbering, 15 guide order, 11 film skip, 13 troubleshooting and 36 channel matching/category checks passed.

## Device tests

`ChannelDatabaseTest.versionSevenWithoutMainUpgradesAndKeepsChannelsAndFavourites` reconstructs
an actual version-7 channels table without `main`, then opens it through the app's normal helper.
It checks guide rows, encrypted URL/key preservation, favourites, search and reopening. Merely
lowering `user_version` on a version-8 table cannot catch this regression.

Instrumentation tests drive the real app with D-pad key events on an emulator. They cover the
encrypted channel database, imports and migrations, LibVLC playback, Home and dock navigation,
catalogue paging and focus, the streaming menus and artwork loading, and Live TV: guide paging and
number entry, the Options button and guide menu, moving along programmes and through time, programme information, the PIN lock, reminders, channel changing and browsing in the
banner, the satellite-box overlay (banner, information panel, pause bar), pausing live TV, the
guide's live picture, and moving the live picture between the guide and the full screen without
reopening the stream (all played from a local test stream). Provider responses are local
fixtures, so these tests **do not** prove that a real TMDB, Premiumize or Xtream account works.

```sh
bash android-tv-launcher/tests/run-device-tests.sh   # needs a running emulator or device
```

The tests run through `MovieHubTestRunner`, which marks the first-start safety notice as read and
chooses the TV layout, so neither covers the screens under test; `SafetyNoticeTest` and
`PhoneModeTest` show them on purpose.

The script runs `connectedDebugAndroidTest` and then pulls the test screenshots off the device.
CI runs it on an API 28 `tv_1080p` emulator. Note that the CI step is named "Verify real SQLite,
encryption, migrations and pagination", but it runs **every** device test, so a failure in that
step can come from any test.

Some tests save screenshots (`UiEvidence.capture`) that CI uploads as the `android-tv-device-tests`
artifact.

### Device-test reliability

Until 17 October 2026 the device tests failed at random, even on unchanged code. Running the full
suite 8 times in parallel on CI found three causes:

| Cause                                                                                                        | Kind                                         | Fix                                                                                                                                  |
| ------------------------------------------------------------------------------------------------------------ | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Quick D-pad presses moved focus to a poster just past the edge; a layout then recycled it and focus was lost | **App bug** (users could lose the selection) | Poster rows and grids keep half a screen laid out beyond each edge, and refocus the same title if focus is still lost (`PosterFeed`) |
| A Back press sent while a dialog was closing reached no window and was dropped                               | Test timing                                  | `TvNavigationTest.back()` waits for window focus before the next key                                                                 |
| Controls were looked up once, immediately after a screen change                                              | Test timing                                  | `TvNavigationTest.select()` waits up to 5 s                                                                                          |

Results of 8 parallel runs of the whole suite:

| Code                                    | Passed    |
| --------------------------------------- | --------- |
| Before the fixes                        | 3 / 8     |
| After the focus fix                     | 4 / 8     |
| After keeping off-edge posters laid out | 6 / 8     |
| After the Back fix                      | **8 / 8** |

On 19 October 2026 a further cause was found (named by the improved failure reporter):
`LivePlayer.stop()` in `PlaybackTest.cleanup` released the shared live player while a closing
player screen still used it, crashing the app ("can't get VLCObject instance"). A crash ends the
run before results are written, which is why earlier failures named no test. The player now lets
go of a shared player that was released or replaced (`forgetReleasedLivePlayer`).

Eight passing runs are good evidence, not proof. If a device test fails, read its annotation: CI
lists each failed test with its message on the run (`tests/report-test-failures.py`), and timeouts
in `ArtworkTest` and `TvNavigationTest` describe the screen state when they fail.

### Catalogue rows

`CatalogueRowsTest` drives the Movies rows page with fixture data (`CatalogueRows.testLoader`, never
set outside tests): first poster focused on entry, no captions, the hero follows focus, each focused
row is pinned at the top, only the first rows load on entry, Up reaches the header, and Back from a
grid opened from the rows returns to the same row.

### Other feature tests (October 2026)

| Test                   | Covers                                                                                                                                                                      |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ForYouTest`           | For you ranking (shared recommendations rise, seeds and played movies hidden), trending fallback, caching until signals change, old seed migration. Fixture TMDB responses. |
| `AllAppsTest`          | Apps screen: Android shortcuts and Recently used shown, search box not focused on entry, search filters by name, hide and Show hidden. Uses the emulator's installed apps.  |
| `SidePanelTest`        | Side panel focuses and ticks the current choice and runs it after closing; `ErrorMessages` explanations never include provider text.                                        |
| `DiagnosticsTest`      | Timings and private error details; Troubleshooting retains the Settings sidebar, Back returns to System, and other tabs remain usable.                                      |
| `SecretsTest`          | Encrypted on disk, cached reads, replace and remove.                                                                                                                        |
| `ArtworkDiskCacheTest` | Artwork disk cache: hit, miss, least-recently-used trimming, oversized files skipped.                                                                                       |

## Tests call app code directly

Device tests live in the same package as the app (`local.tv.home`), so they call package-private
members directly, for example `activity.libraryScreens.toggleWatchlist(title)`. The compiler
checks every one of these calls, so renaming or moving a member breaks the test build immediately
instead of failing at run time. Do not use reflection to reach app members in tests; make the
member package-private instead.

### Channel copies and automatic backups (October 2026)

| Test                                                                         | Covers                                                                                                                                                                                                                                                                                                                                                                            |
| ---------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ChannelIdentityTest` (core)                                                 | Same channel across lists and providers ("Sky Sports F1 (720p)", "… UK", "… HD", "Sky Sports Formula 1"); kept apart: other countries, +1, regions, UHD, similar networks; provider categories into the guide sections (whole words, so "TF1" is not sport). Also run over the real Free-TV and iptv-org UK/US/IE lists (20 October 2026): five new merges, all the same channel. |
| `ChannelDatabaseTest.providerCopiesJoinTheSameChannelAndSharedSections`      | A provider copy becomes a backup of the free-list copy, keeps number 407, appears in Sports, no provider group.                                                                                                                                                                                                                                                                   |
| `ChannelDatabaseTest.versionEightKeysAreRecomputedKeepingFavouritesAndLocks` | Version 8 → 9 upgrade re-keys saved channels; favourites and channel locks kept.                                                                                                                                                                                                                                                                                                  |
| `PlaybackTest.skySportsF1FailsOverToAnotherProvidersCopy`                    | **The reported problem**: the free-list copy fails (404), the provider copy plays; "isn't working" is not shown.                                                                                                                                                                                                                                                                  |
| `PlaybackTest.failoverTriesEachCopyInTurnUntilOnePlays`                      | Two failing copies, the third plays.                                                                                                                                                                                                                                                                                                                                              |
| `PlaybackTest.whenEveryCopyFailsTheChannelSaysSoWithoutRetryingForever`      | Every copy tried once, then the message; no further requests.                                                                                                                                                                                                                                                                                                                     |

These use local fixture streams. They do not show that a real provider's stream plays on the TV.

## What no automated test covers

- Real provider accounts (TMDB, Premiumize, Xtream, M3U) and live provider availability.
- Installation and updates on the actual target TV box.
- How real apps' TV banners look on the Apps screen, and the update prompt's notes from a real feed.
- How the screens look on a real TV (screenshots from the emulator are a partial substitute).

Those need a manual check on the device before a release.

## Native source tests

`NativeSourcesTest` verifies provider JSON fixtures, partial-response rejection, regional stable IDs, secure session endpoints, database rollback/favourite preservation and native/XMLTV guide coexistence. PlaybackTest additionally exercises a fixture native resolver through LibVLC, cancelled channel requests, same-channel fallback and fullscreen/guide continuity with exactly one resolution. These run in the existing device job. Real Pluto catalogue, HLS and desktop decoding evidence is separate in [NATIVE_FREE_SOURCES.md](docs/NATIVE_FREE_SOURCES.md); it does not establish TV playback.

The native-source rollout exposed a legacy migration regression: disabled-source cleanup must retain `legacy` rows when every configured provider fails, including subsequent failed refreshes after the old database was deleted. `ChannelImporterTest` verifies that case and independently verifies that disabled FreeLinks rows are still removed during another provider's outage.

Native guide lookup uses the provider's own channel identity, including outside the UK. A regression fixture gives a native US channel the same name as a different UK guide entry and verifies that it receives its own programme.

## Standalone provider runtime

Run `npm ci --ignore-scripts --prefix provider-engine`, `npm run build --prefix provider-engine` and
`npm test --prefix provider-engine`. With Rhino 1.9.1 downloaded, run
`RHINO_JAR=/absolute/path/rhino.jar bash provider-engine/tools/verify.sh` for 21 checks on the actual
JVM interpreter/signature verifier, codecs, URLs and segment handling, plus 40 multi-provider fixture checks. `ProviderEngineDeviceTest` covers isolated Binder calls and
signed package installation, tampering, downgrade protection, rollback and corrupt-slot recovery.
The normal Android workflow runs these device tests. Real provider and physical-TV evidence must
remain separate; see [evidence and limitations](docs/STANDALONE_PROVIDER_ENGINE.md).

## Runtime 2 adapters and header transport

The provider verifier now also runs `runtime-check.mjs`, which drives the compiled upstream modules through
the real JVM interpreter with recorded HTTP fixtures. It checks POST resolution, compressed catalogues,
long async walks, DaddyLive embed resolution, Tubi signatures and Samsung filtering. `ProviderAdaptersTest`
exercises isolated Android calls, API capabilities, stable compound IDs and the loopback relay with fixture
upstream connections. See [the evidence distinction](docs/STANDALONE_PROVIDER_ENGINE.md).

ZLive fixtures run the actual resolver and Node IP/subnet compatibility through token reuse, persisted
403 cooldowns, decoy detection/latching, corrupt history refusal and cancellation recovery. Device tests
exercise encrypted ledger reuse across actual isolated Binder calls, manual redirects and credential
redaction. The relay fixture also fragments upstream reads into two-byte chunks.

## DaddyLive playlist and segment regression checks

The provider JVM fixture verifies player HTML -> anonymous token -> tokenized HLS, and preserves the
historical no-token path. `ProviderPngSegmentsTest` adds 17 pure-Java checks for RGB/RGBA, every PNG row
filter, split IDAT chunks, appended TS, checksums, truncation, bad sync and oversized dimensions. It runs
through `provider-engine/tools/verify.sh`. `PlaylistTransportTest` checks renewal, one 403 refresh, channel/
authority scoping, token-free segment requests and PNG transport with two-byte fragmented reads. Its
host-JVM run uses Android-shell stubs; the Android workflow runs the real instrumentation tests.

Live desktop evidence (20 October 2026): Main Event plus Football (35), Action (37), F1 (60) and ABC USA
(51) returned tokenized HLS and unpacked media with FFmpeg exit 0. No real stream URLs, tokens or captured
media are committed. Four-second samples do not prove every channel, sustained playback or the TV box.

## Readable provider failures and compact displays

`ProviderFailureTest` adds 17 core checks for DNS/TLS/connection errors, isolated-runtime deadlines,
response limits, fixed HTTP summaries and redaction of sensitive exception messages. ProviderAdaptersTest
exercises HTTP 403 and DNS failures through the real isolated service. SettingsStyleTest checks that a
multi-line provider failure is not ellipsized or clipped by its card. LiveGuideTest shrinks the guide grid
and verifies that channel and programme font metrics fit the rows. CI screenshots include
`provider-error-card` and `live-guide-short-rows`. These fixtures do not identify a particular box's failure.

`PlaybackTest.slowLinkResolutionDoesNotSpendTheVideoStartupTimeout` holds an asynchronous native resolver past the real 15-second video-startup deadline while a backup exists, verifies the original source is still resolving, then releases it and checks actual LibVLC playback without switching sources. Existing cancellation, immediate-failure backup and full-screen/guide handoff tests remain in the full suite.

`LiveProgressTest` now has 264 core assertions for a stopped clock, stopped video while audio time advances, four minutes of healthy progress, pause/resume, missing video counters and reset timestamps. PlaybackTest drives the real shared player and its attached failure callback with a silent-video-freeze observation, verifies paused observations do not trigger recovery, and checks the error appears without sending buffering/error events. The full existing suite also checks provider retries, source backup, cancellation and view handoff.

A same-JVM desktop benchmark compared old/new PNG decoding on the same 6.8 MB Main Event container: every run produced identical 6,885,312-byte TS output, and every new run was faster (roughly 2x in later runs; timings vary). All 17 existing PNG format/filter/error checks remain applicable. This is not an ARM64 benchmark or proof of the cause of a particular TV freeze.

## Live picture handoff evidence (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

PlaybackTest repeats guide → full screen → guide twice for both direct and resolved fixture
streams. LiveHandoffEvidence checks that the same `LiveVideoOutput` and player survive, that
VLC's decoded-video count rises again in the destination, that the picture returns within
3 seconds of the move (`HANDOFF_LIMIT_MS`, logged as MovieHubHandoff), and that the SurfaceView's
actual pixels (sampled from a screenshot where the video surface is; PixelCopy cannot read a hardware decoder's surface) are not blank. Stream-open and resolver counters assert no
restart or re-resolution. This verifies fixtures on the API 28 emulator, not the TV.

After a move, VLC's hardware decoder shows nothing until the stream's next keyframe, so the
blink lasts up to one keyframe interval. `androidTest/assets/playback.ts` was re-encoded on
21 October 2026 with a keyframe every second (320x180, 15 fps, 30 s, audio unchanged), like
typical live sports streams; the earlier file had keyframes 16.7 s apart, which no live channel
uses and which made every move wait up to 16.7 s.

Before 21 October 2026 the live picture used a retained SurfaceTexture/TextureView; its handoff
times (47–104 ms on the emulator, 20 October 2026) are not comparable with the SurfaceView move.

## Early video freeze and reload investigation (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

PlaybackTest `aPictureRefreshKeepsTheSameStreamAndSound` calls the watchdog's repair
(`refreshView`, a video-track restart) during playback and checks unchanged player/output, stream-open and resolver
counts, displayed pictures advancing again and audio output advancing. A wrapped-PNG HLS fixture
exercises the production relay and LibVLC through three reloads, requiring displayed pictures and
audio output counters to advance. The EGL/SurfaceTexture test (LiveVideoOutputTest) was removed
with the TextureView output it tested.

The opt-in dlhd-playback workflow resolves through the actual bundled interpreter and decodes
both tracks through production Java relay/session/PNG classes for 150 seconds per selected
channel. It replaces only Android-dependent DTO/logging/DNS seams on the JVM and uses FFmpeg,
so it does not prove Android hardware decoding. Only numerical results are uploaded; no fetched
media, URLs or tokens. A green diagnostic workflow does not imply bothDecoded=true.
Android validation passed in run 39513033996; local core/provider checks are also available.

LiveControlsTest holds the selected native resolver pending while opening controls and Sources, asserts no backup resolution, checks first Back reveals controls and second Back leaves, and checks manual pinning plus restoring bounded Automatic failover. Local execution became available again: core and provider checks are run locally; Android build/lint and real EGL/LibVLC regressions require CI.

FreeShot adds actual-Rhino fixtures for catalogue deduplication/sports filtering, a three-request selected-channel resolver, headers/token lifetime and rejecting invalid identities before HTTP. PlaylistSessionTest verifies fixed-destination renewal, token reuse for media segments and rejecting other CDN/channel scopes. ProviderRelayTransportTest sends a real TCP `Range: bytes=0-` request through the production relay and checks exact decoded TS bytes, 200 status, no container Content-Range and no upstream decoded-offset Range. The sustained diagnostic also forces FreeShot renewal before decoding Main Event for 150 seconds.

Live verification on 20 October 2026: FreeShot Main Event passed the 150-second production-relay
check with a forced initial anonymous token renewal: 3,748 unique video frames over 149.92 seconds
and 7,032 unique audio frames over 150 seconds, with no timestamp gaps or relay errors. DaddyLive
Football also passed both tracks for 150 seconds after accepting whole-entity wrapped ranges.
The Main Event DaddyLive run contained a timestamp gap; these host checks do not establish the
physical TV's decoder/audio behavior. The signed test APK comes from Android run 39513033996.

PlaybackTest.aManualBackupSurvivesGuideAndFullScreenWithoutReopening plays a second copy, pins it manually, makes two full-screen/guide trips and checks the retained player, current copy/mode, fresh output and unchanged stream-open count after the intentional source change.

Final application revision `4ae9dce6e6adeb8d95b319ff46f0eb90897a3b22` passed all 122 API 28 TV
emulator tests with zero failures/skips in Android run 39513033996. The manual-backup round-trip
regression passed, alongside constant EGL timestamps, actual stopped-consumer recovery with
advancing audio and three wrapped-HLS reloads with advancing video/audio. Twelve fresh-frame
handoffs were 137, 82, 88, 63, 116, 53, 112, 63, 119, 44, 117 and 122 ms; no handoff reopened
the stream. Build/lint, signing compatibility and provider run 39513033968 also passed.

[Signed test APK ZIP](https://github.com/billyke2000s/moviehub/actions/runs/39513033996/artifacts/12307505129) ·
[Emulator reports/screenshots](https://github.com/billyke2000s/moviehub/actions/runs/39513033996/artifacts/12306229486).
Only documentation follows this tested application revision.

## Reviewed PR regression coverage (22 October 2026)

Run `bash android-tv-launcher/tests/run-core-tests.sh`; regular CI and publishing call this same script. A local run on 25 October 2026 (OpenJDK 21 `javac`) passed 903 checks in 26 suites; the suite list is in the script. GitHub CI still verifies javac, APK builds, lint, emulator behavior, and signing.

`VodSourceFailoverTest` covers startup exclusion, threshold boundaries, seeking/pause exclusion, provider pinning, duplicates and exhaustion. PlaybackTest adds actual-VLC fixtures for repeated-buffering replacement through a failed candidate, preserving the old stream while resolving, cancellation on pause and late results, and exhausted alternatives without loops. These tests do not establish dual-decoder support on the target TV or live Premiumize availability.

LiveControlsTest waits for a dismissed panel to leave the event queue before injecting the next physical color key; panels have no closing window animation. Guide paging retains the next direction during asynchronous loads rather than dropping it.

The guide wrap regression deliberately focuses the header before each Page Up/Page Down over four round trips. Paging is routed at the screen level; row-local handlers previously lost Page Down when asynchronous row replacement left focus on the header.

Live channel-key playback explicitly leaves immediately after Back to live restarts VLC, before
waiting for decoded frames. This covers shutdown while native video output is still opening.
The post-merge run 39821398629 crashed in EGL window initialization in this path; shutdown now
cancels view callbacks first, stops VLC with its surface retained, then detaches the surface.

Handoff evidence now excludes closing-output frames and requires the destination SurfaceView
to be shown, valid and sized. The optional PixelCopy capture never delays disabling the old
video track. The existing direct, native-resolved and manually pinned backup tests each make
two full-screen/guide round trips without reconnecting.

SidePanelTest covers Activity destruction with an open panel and rejects continuation callbacks.
LiveControlsTest and SafetyNoticeTest wait for actual window focus before injecting keys across
panel transitions; showing/idle alone does not establish input focus.

Handoff screenshots are sampled until the destination is visibly nonblank, within the existing
three-second budget including time to resume decoding. One screenshot immediately after the
first decoded frames can still precede SurfaceFlinger composition; this is not proof of a
persistently black surface. The test still fails if the visible picture misses that budget.

## Playback continuity validation (0.46 candidate) (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

`LiveRenewalTest` serves fixture MPEG-TS through real LibVLC. A latch blocks provider renewal
while the original video advances; tests then cover successful reopening, a failed refresh
without reopening, pause without automatic resumption, exit without a late reopen, a
synthetic video freeze repaired on ordinary IPTV without reopening, and a persistent freeze
that reaches failure rather than starting another surface repair.
`PlaybackBufferTest` checks URL-path classification (query/fragment decoys and uppercase HLS),
relay precedence, malformed paths and bounded VOD headroom. The core script now runs 703 checks.
CI must validate the full Android suite, builds/lint and signing before release. Physical-TV
memory/decoder limits and long real-provider sessions remain unverified.

`LiveRepairBudgetTest` verifies persistent freezes across hidden/paused surfaces cannot loop
through repairs, queued frames do not count as recovery, and genuine progress replenishes
the repair budget.

Activity destruction now clears its owned SidePanel explicitly, including show-and-finish
before the first window attach. The native picture-repair fixture establishes actual frame
progress before injecting a new freeze; hiding a surface is intentionally insufficient to
reset the new repair budget. Failed device runs print the full crash buffer so Java process
failures cannot disappear from a video-only filtered tail.

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

## Prepared live backup validation (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

`LiveBufferingTest` covers exact live thresholds, duplicate buffering notifications, window
expiry, startup/pause exclusion, buffering completion and one trigger per recovery window.
The shared core script runs 733 checks.

`LiveBackupTest` uses actual LibVLC and local MPEG-TS: old frames during preparation, one muted
candidate, promotion of the same already-running decoder/transport, audio volume restoration,
failed candidates followed by a working copy, exhaustion without loops, bounded startup,
pause/manual/channel/exit cancellation with deliberately late resolver completion, and a real
fullscreen/guide transition followed by preparation and promotion in the mini-player. Actual
screenshots must contain nonblank video after promotion and later full-screen reuse. No new
provider sources or account credentials are used. Local core validation passed; Android
build/lint, full emulator and signing validation are pending on the PR. Concurrent hardware
decoder support, actual TV memory limits and live-feed timing require physical-device checks.

## Live TV control consistency

Since 27 October 2026 `LiveControlsTest` runs on `LiveStage` (see "LibVLC live tests removed"); mentions of Activity recreation below describe the removed separate player screen.

`LiveControlsTest` now opens its live fixture without the static guide session. It covers physical colours, real Activity recreation with cleared static context, saved guide snapshot independence, a learned green button using a numeric key, and visible unclipped controls with long source/channel names. Existing Back/arrow/OK tests remain. These fixtures do not verify the owner’s physical remote. Local core checks pass (733); Android CI is pending.

`LiveControlsTest.physicalColoursKeepWorkingWhileAnotherPlayerPanelOwnsFocus` sends red, green, blue and yellow without manually dismissing intermediate dialogs. `completeHeaderSurvivesProgrammeUpdatesAndBackControls` checks full-height channel-strip labels, not only footer buttons, after typing and programme updates; it saves `live-complete-channel-header.png`. These use fixtures, not the physical remote.

## Live playback recovery (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

LiveRecoveryTest serves deterministic 320×180 and 640×360 H.264/AAC MPEG-TS segments behind an HLS playlist with timestamp discontinuities. It checks actual video/audio progression across format changes, bounded same-channel Pluto retries, controls during a deliberately blocked native stop, and recovery after hiding the active SurfaceView. Pure-Java checks cover retry exhaustion/replenishment and presentation timeouts without treating valid static/black buffers as failures. Physical ARM64/TV-box decoding and comparative Media3 testing remain outstanding; no claim that these fixtures reproduce every Pluto broadcast.

The alternating-resolution HLS fixture contains explicit timestamp discontinuities. It requires advancing decoded video, decoded audio and played audio buffers across at least eleven seconds, unchanged source/player identity and actual visible pixels. LibVLC's live demux clock alone is not proof of stalled output. A separate test verifies three brief buffering notifications do not reconnect an advancing selected channel when there is no alternate source; the existing third-buffer test still verifies prepared-backup promotion.

0.49 validation checkpoint: source f2e37666b297cb225a458ef9df9101fe0b01d87f passed CI run 39985039245: all 166 TV emulator tests, zero failures/skips, build/lint and signing/updater compatibility. Provider CI also passed. Local core checks: 753. HLS transition evidence: 320 decoded video frames, 547 played audio buffers, one resolver call. Screenshots confirm the moving fixture output and complete channel header. This is fixture/emulator evidence, not a physical TV or live Pluto test.

## Consistent live banner and standard colours

LiveControlsTest now checks actions on key-down before any release, repeats/releases across replacement dialog windows, conflicting saved colour aliases, and a constrained viewport with information/pause panels visible. Earlier tests only sent complete down/up pairs with normal mappings and ample layout space. New tests must pass Android CI; they do not establish behaviour on the owner’s physical remote.

## One live overlay and shared colour input

LiveControlsTest.entryOkClickAndBackUseTheSameFullBanner checks visible video focus, absence of an attached film overlay, repeated OK without opening Options, picture click and Back, and stable header bounds. standardColourDeliveryVariantsShareOneActionAcrossWindows covers a first received nonzero-repeat event, held/released events crossing dialogs, standalone release, multiple delivery, the standard colour scan fallback and preservation of known non-colour keys. ColourPressTest adds 22 pure-Java gesture checks. Core total: 775. Android build/emulator/signing results are recorded on the change’s pull request and release run; physical-TV confirmation remains outstanding.

## Free VOD provider candidate

SourceWindowTest covers the exact three-second boundary, best available result, no-result
waiting, late results, duplicates, cancellation and appending alternatives without resetting
VOD stall/failed-source state. test_free_vod.py imports the 68 Kodi constructors and two native constructors and runs
the real MP4Hydra provider against offline search/page/playlist/media fixtures with two CDN
servers. It also covers header preservation, HTML error rejection, cancellation and private/
plain-HTTP target rejection. Run `python android-tv-launcher/tests/test_free_vod.py` with
requests and six installed. These checks do not prove live playback. Python 3.11 is required
for Android builds; Chaquopy adds native runtime ABIs to the APK. Full Android CI and device
validation must pass before treating this as a release candidate.

FreeVodProvidersTest imports all 70 constructors from the packaged Android Python runtime
and checks Java-to-Python cancellation. The offline suite also rejects executable decoder
expressions. A separate CI probe reports public provider availability without logging URLs.

## Incoming VOD traffic and ranking

`StreamRateTest` covers timed byte deltas, idle zero, counter resets, quality aliases, camera penalties, the resolution ceiling and equal-quality transfer comparisons. Provider adapter instrumentation counts actual upstream bytes once; the Android Python bridge verifies counted transport for header-free sources. Offline Python tests verify master-playlist resolution and sampled media rates. These small startup samples estimate readiness, not future host stability.

The VOD relay regression includes real file playback/seeking and a TCP check that files retain their byte lengths and range headers without being advertised as HLS. Android extractor fixtures use public literal IP addresses so transport validation never depends on DNS for `.example` test hosts.

## Player panel initial focus

The live-controls physical-key regression must receive actual window focus for Audio, Sources and Options in sequence. Panel layout and system-UI attributes are configured before the window is attached, so initial focus is not followed by a second layout/visibility change. Repeated isolated emulator runs accompany the full suite for this handoff.

Live-controls physical colour injection uses one down-time identity for both events and drains pending UI work before capturing a replacement dialog. Android Instrumentation’s convenience down/up helper constructs independently timed events, which can look like another press after a window-transfer cancellation. Existing window-focus and old-panel-closure assertions remain required.

The decoded-backup test drains the main-thread handoff before checking the Activity reference. It still requires the same standby decoder to be promoted and the old picture to keep advancing until candidate data is released.

Live-controls wait predicates and dialog snapshots execute on the main thread; the instrumentation thread receives their result through atomics. This keeps window focus checks inside Android’s UI-thread contract.

Player panels request input focus after their DecorView attaches, with the initial layout already configured. The repeated colour-key test continues to require the attached view’s actual window-focus callback and successful key-driven panel replacement.

## Free VOD failure and duration regressions

The Python suite individually checks all 70 provider lookup contracts and explicit library/API-key exclusions, rejects 7/30/119.9-second media, accepts the 120-second boundary and unknown metadata, and preserves a matching playback User-Agent. See [inventory audit](docs/FREE_VOD_AUDIT.md). `PlaybackTest` adds real-VLC manual free-source failure through a broken host and short clip to another provider, initial short-clip rejection with a late-discovered backup, and a deliberately blocked native stop while controls and original playback remain responsive. The speed regression requires visible Mb/s and received-byte labels with a positive real relay byte count. The checked-in `vod.mp4` fixture is real 150-second H.264/AAC media, generated from the original fixture with FFmpeg and fully decoded locally. Duration checks stay active; short-video regressions still use the original 30-second fixture. See the test-assets README for the reproduction command. Full Android CI validation is required; these are fixtures, not physical-TV/provider evidence.

## Provider compatibility regressions

The 17 Python tests include the actual Scrubs Levidia lookup from landing page through
bodyless POST, result, host and media probe; redirect methods for 301/302/303/307/308;
explicit/default Referer; and both families' movie title/alias and episode mapping rules.
FreeVodProvidersTest checks movie/series TMDB alias forwarding on Android. Local Python
and 814 core assertions pass (Eclipse JDT/Java 17). Android build/lint/emulator results
must be read from the final PR commit; fixtures do not prove physical-TV availability.

Provider-request read timeouts now honor explicit upstream values within the remaining
search budget, with an eight-second connection cap. A regression checks a 20-second read
request and clipping to a five-second remaining budget; cancellation remains bounded.

## VOD Activity destruction regression

PlaybackTest.finishingFilmRetainsDetachedTextureUntilNativeStopCompletes plays real VLC
video, blocks its native stop, finishes/destroys the Activity and verifies the detached
SurfaceTexture remains unreleased. Unblocking stop must release it. The full suite also
retains the film-to-native-Live-TV cancellation test that exposed the EGL crash.
The previous final run failed after 121 passes with SIGSEGV in EGL swapBuffers; this
regression and its correction require a new full emulator result.

## LibVLC live tests removed (27 October 2026)

The LibVLC live player was removed (see ARCHITECTURE.md, "ExoPlayer Live TV"), and with it the device
tests that only exercised it: `LiveBackupTest`, `LiveRecoveryTest`, `LiveRenewalTest`,
`LiveHandoffEvidence`, the Live TV tests in `PlaybackTest` (including the seven already skipped with
`@Ignore`), and the core `LivePresentationTest`, `LiveRepairBudgetTest` and `PlaybackBufferTest`.
`PlaybackTest` keeps every film and series test. `LiveControlsTest` was ported rather than removed:
the banner, colour keys and source rules it checks are shared with Live TV, so it now opens the
channel full screen in `LiveStage` (with `ExoLive.testResolver` holding the link lookup) instead of
the removed separate player screen. Its `recreatedPlayerKeepsLiveControlsWithoutStaticGuideState`
was dropped: Live TV no longer has a separate player screen to recreate. `entryOkClickAndBackUseTheSameFullBanner`
now also covers the picture click ported to `LiveStage`. Provider link renewal on ExoPlayer still has
no device test.

## ExoPlayer Live TV and the test app

`ExoLiveTest` and `DlhdStartupTest` are ordinary device tests (in `src/androidTest`), so the normal
`device-tests` job runs them. CI's `apk` job also compiles the MovieHub Experimental test app
(`-Pexperimental assembleDebug`) and checks its package name, and `experimental-device-tests` runs the two
suites again inside that build. `ExoLiveTest` exercises Media3 itself on the TV emulator: decoded frame progress and nonblank
screenshots through two guide/fullscreen round trips without new stream requests; 404 retry
cancellation on channel change and stop; pause/resume; foreground restart and Activity
destruction; HLS audio/subtitle selection and remote banner entry. Experimental lint also runs.

Run that suite on a running emulator with:

```sh
gradle -p android-tv-launcher connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=local.tv.home.ExoLiveTest
```

These local HTTP fixtures establish decoder and lifecycle behaviour, not real-provider or
physical-TV compatibility. Freeze detection remains diagnostic; this release does not add an
automatic picture-repair algorithm.

### Experimental DaddyLive startup regression suite

DlhdStartupTest runs real isolated Binder calls with bundled DaddyLive transforms and fixture
HTTP: one process binding per complete lookup, fewer warm-page requests, fresh script globals,
expired/cancelled lookups, socket-timeout continuation, cache expiry/module separation and
exclusion of tokens, credentials and failures. It runs alongside ExoLiveTest in the dedicated
experimental emulator job. ProviderScriptFastTest uses actual Rhino on the JVM to verify one
compile across steps, fresh globals, no Java access, failed-request clock accounting and the
unchanged legacy replay contract. Fixtures do not establish physical-TV startup latency.

`VideoDetailsTest` (core) checks the information panel's picture line: size and quality name
(including wide film frames), whole and fractional frame rates, codec names from Media3 MIME types
and VLC codes, bitrates, and that nothing is shown before the first frame. `LiveControlsTest` checks
that the source name is not a button (four colour keys only), that BACK highlights the green Source
key, and that the information panel shows "Picture:" and the source. Fixtures only: the real values
come from the stream and were not checked on a TV.
`ProviderOutageTest` (core) checks the grouped "Unavailable now" line shows real failures only (HTTP 403
and script errors are never called geo-locked) and that "Hide US-only providers" hides only LG, Roku
and Local Now. `runtime-check.mjs`
checks that LG Channels asks for country US. The Plex play fix was verified only by the live probe
(two of three sampled channels opened and played from GitHub's US runner), not on a UK connection.

`ProviderEngineDeviceTest.plexListingsMatchByStableIdAndFallBackToTheUsGuide` feeds fixture XMLTV to
`ProviderGuide` (Plex spec): channels match by the id after the dash (with a different server prefix), unrelated
channels are ignored, the US file is read only when the UK file covers under half the lineup. The real
gb.xml (368 channels, all `<24 hex>-<24 hex>` ids) was inspected offline; that its ids equal the
catalogue's gridKeys is inferred, not yet seen on a device.

`LiveGuideTest.tenChannelsPerPageNumberedFrom101AndPagedWithTheRemote` checks the title line reads
"ALL CHANNELS 25 channels" for its 25-channel fixture (the whole list, not the page).

`ExoLiveTest.aFailingSourceWithAnotherWaitingMovesOnInsteadOfReopening` checks that an erroring source
is reported once and not reopened when the listener has another source. The 15-second startup
deadline (`LiveStage.START_MS`) has no device test; it uses the same failover path.

`LiveStallRulesTest` (core, 44 checks) covers the stream rules ported from the LibVLC live player:
startup and reopen startup not counted, a long stall moving on when another source waits, three
stalls a minute moving on only with another source, a long stall with none reconnecting once, pausing
never counting, a dead stream recovered while a still picture with moving sound is not, and two
reconnects refilled only by 30 s of moving picture. `ExoLiveTest.aSourceThatKeepsBufferingMovesToTheNextSource`
plays a two-copy fixture channel whose first copy sends a few seconds of HLS and then holds the
connection open: after its pictures, eight seconds of buffering move to the working copy without
reopening the stalled one. `aLongStallWithNoOtherSourceReconnectsThatSource` checks the same stall
reconnects a single-source channel. `anotherChannelStartsInAutomaticSourcesAfterAManualChoice` checks a manual
source choice does not carry over to a different channel opened full screen (it did since 1.0:
only the removed LibVLC player reset the mode when the stream changed). Provider link renewal has no device test (it needs a provider
link with an expiry); real provider streams are not exercised by fixtures.

`LiveRacePlanTest` (core, 24 checks) covers the source race rules: priority by quality then copy order,
at most three lookups and standbys together, the quickest copy playing at once, a copy taken by
`LiveUpgrade` counted as playing, no waiting after a failure, the short tie window, and exhaustion.
`LiveUpgradePlanTest` (core, 36 checks) covers upgrades: only a better claim than both the copy's name
and its measured picture, best claim first, one at a time, an unreachable copy changing nothing, a
held trial allowing a further upgrade, a failed trial falling back, a 10-minute cooldown that survives
a channel change, doubles on repeat, is capped at an hour and resets after a held trial, and picture
lines from width and height. `ExoLiveTest.aBetterCopyFoundAfterTheQuickestOneTakesOver` starts a
channel on its SD copy and checks its HD copy takes over and stays. `aBetterCopyThatKeepsBuffering
FallsBackAndCoolsDownAcrossChannels` gives the HD copy a stalling stream: it falls back to SD, cools
down, is not played again, and after another channel and back the race and upgrades still leave it
alone. `aManualSourceChoiceIsNeverUpgraded` checks a copy chosen by hand stays. `ExoLiveTest.aDeadCopyHandsOverToAWorking
OneWithoutWaitingForTheSlowOne` plays a three-copy fixture channel (404, working, slow) and checks the
working copy plays through the race and the slow one is never opened. Real provider lookups, quality
names and the eight-second deadline are not exercised by fixtures.

`EpgMatchTest` (core) checks Freeview's London-is-national aliases (BBC One/ITV1/Channel 4 London, "5"
as Channel 5, radio and other regions left alone). `EpgImporterTest.guidesInPriorityOrderLondonIsNational
AndCountriesStayApart` imports three fixture guides: Freeview beats the later UK guide, a channel only the
second guide has is filled from it, Irish channels use the Irish guide, and a German channel gets no UK
listings. `oneFailingGuideKeepsTheOthers` checks a failed download does not lose the rest. The real
files' coverage was inspected offline (Freeview: 284 channels, 47,787 programmes); epgshare01's IE and
US files were not downloadable from the workspace and are untested beyond Free-TV listing them.

`ProviderEngineDeviceTest.samsungListingsMatchTheStoredChannelIdsUnchanged` checks the Samsung spec reads
only the gb file and matches ids unchanged. The live gb.xml (273 channels, 2,550 programmes, ids such as
GBBC2300003ZQ) was inspected offline; it was not checked against the adapter's live catalogue.

`LiveGuideTest.okJumpsToATypedNumberAtOnceWithoutOpeningTheHighlightedChannel` types 112 and OK: the guide
lands on 112 before the digit wait runs out and the OK's release does not open the old row; the guide's own
number box shows "112–" while typing and is gone after the jump.

`EpgMatchTest` also covers UKTV's renamed channels (U&Dave, U&Gold, the Freeview id UAndDrama) and the
trailing "UK" word in names. The Sky London HD guide was inspected offline (377 channels, 75,677
programmes; Sky Atlantic, Sky Witness, Comedy Central, U&Dave/U&Gold present; Sky Sports and Sky Cinema
absent). Its 45 MB file is served gzip-compressed at about 5.3 MB.

`ChannelImporterTest.importsBothXtreamFormatsAndM3uAndKeepsFailedRefresh` also checks a finished refresh
records the app version (`refreshedVersion`) and `ChannelSync.updatedSinceRefresh` treats another or
unknown version as updated.

## XMLTV fallback regression (25 October 2026)

`EpgLookupTest` checks provider-name lookup, country prefixes/aliases, fixed provider country, GB/UK equivalence, timeshifts and refusal to borrow UK listings for unknown native countries. `EpgImporterTest` imports a Sky-shaped XMLTV fixture for a built-in provider channel, verifies missing/expired native fallback and native priority, separates equally named US/UK channels, and verifies stale/malformed priority guides cannot claim keys or leak partial programmes. A Freeview-shaped Gold Radio fixture cannot hide Sky’s U&Gold television listings. Existing native/XMLTV coexistence tests remain. Core checks run locally; full Android build/lint and both emulator suites must pass on the final PR commit. Live feed availability and the owner’s physical TV remain separate checks.

## Combined guide and ZLive regression checks

`CombinedEpgTest` uses loopback HTTP, real gzip/XML and SQLite to check conditional 304s, a single guide URL, native/name priority, future-only fallback and retention after malformed, empty, stale or failed updates. `ZliveSourcesTest` checks distinct labelled routes, duplicate rejection, old primary identity, permitted headers, unsupported website options and module-bound hints. Experimental `ExoLiveTest` checks exhausted ZLive failure/recovery with loopback media. Core provider tests validate fragmented PNG/WebP/raw/gzip carriers and the real TCP relay without a playlist-token session. None establishes live provider playback or a physical TV result.

The generator has independent Python/Node merge, cache, expiry, DST, priority, invalid-XML and coverage-drop tests. Its GitHub run and published reports establish server publication; review exact per-run source failures rather than assuming every configured source succeeds.

## Experimental exp13 provider repair checks

The Python suite covers distinct website inventory (63 sites and 66 adapters bundled; seven retired and 18 unverified restorations disabled after the 25 October review, leaving 38 sites and 41 adapters searched; all 68 bundled modules still validate), alternate-adapter fallback, final-host cookie scope, cross-origin credential removal and browser identity/query-list handling. `verify-vod-upstream.py` compares current official Scrubs/Free99 package versions, active provider inventory and original hashes. The on-demand audit runs movie and episode queries separately, records extraction/resolution/media outcomes and never logs source URLs or credentials. Results establish only those titles from the runner's location.

The actual JVM provider runtime encrypts a fixture session POST which Node independently decrypts and verifies, including raw channel IDs, ticket/AAD, timestamp, reuse and refusal suppression. Android fixtures check the current session round trip, encrypted contact history and fresh movie/exact-episode resume metadata. Build/lint and emulator outcomes must be read on the PR's final commit.

The first Android run of this PR failed two device tests (`ProviderAdaptersTest.coldResolversAvoidCatalogueAndTclHintsAreEncryptedAndCompact` and `FreeVodProvidersTest.javaCancellationReachesPackagedPythonBeforeAnyProviderRequest`), failing again on rerun. Both were code faults, fixed in a follow-up commit; `test_cancelled_website_search_stops_before_reading_title_fields` reproduces the second offline.

## VOD retirement and transport recovery

The Python suite checks Levidia remains in ordinary discovery, empty landing cookies, yearless results, cross-site Host/credential removal, and zero body reads for file-size, redirect, header and cookie lookups. Existing duration, cancellation, TLS/public-address, redirect-cookie and all-adapter contracts remain required. The public probe supports `--include-retired` and repeated `--provider` filters; failures are observations for the selected title and network, not proof of closure. Current inventory: 36 searchable websites, 39 adapters and 70 modules (68 Kodi plus two native). A regression requires Levidia to be the only enabled member of the original 26 exclusions.

Additional recovery regressions check Scrubs Levidia delegates Wootly once and an empty fallback does not hide a host timeout. Offline tests forbid live HTTP from upstream constructors. Local suite: 32 Python tests.

## Site-by-site VOD recovery (26 October 2026)

The site recovery suite imports `test_current_vod_sites.py` from `test_free_vod.py`. It covers canonical result links, current search/player flows, distinct providers sharing a CDN URL, authenticated Xpass responses, rejected tampering with empty AES-GCM associated data, and diagnostics without URLs or credentials. Live checks and limitations are recorded in [the 26 October audit](docs/FREE_VOD_SITE_AUDIT_2026_10_26.md).

Current site recovery: 46 offline Python tests, including Tubi’s padded challenge/issued signing key, clear-resource filtering and Waffi mirror isolation. Android instrumentation exercises named-backend deduplication through the actual selection-window key. The HTTP identity audit compares app requests, a cookie-preserving browser-style requests session and curl without executing challenge code.

## Native movie source expansion (26 October 2026)

`test_native_vod_sites.py` covers exact title/year selection, literal redirect decoding without executing scripts, isolated mirror failure, distinct returned hosts, safe filename encoding, current Vixsrc movie/episode identities and expired token rejection. The opt-in native Android audit plays each returned source independently through the actual relay and LibVLC; a completed job is not evidence every source passed. Inspect its redacted JSONL statuses and final shard marker.

The focused native-source audit also records decoded audio buffers and selected audio-language metadata; it does not call a source verified when neither decoded nor played audio buffers were observed. Device volume remains controlled by Android.

`ProviderFileRangesTest` exercises a real finite-range-only HTTP fixture: full streaming across multiple parts, middle/finite/suffix seeks, interrupted-part resumption without duplicated bytes, guarded redirects, invalid/changing metadata, changed ETags, downgrade rejection and cancellation. `PlaybackTest.finiteRangeOnlyHostPlaysAndSeeksThroughTheRealRelay` requires actual LibVLC playback through the same opt-in relay; an open-ended fixture request is rejected with HTTP 403. It covers explicit source opt-in and automatic recovery from that rejected request.

The live audit reports decoded audio and output audio-buffer counters separately, so valid passthrough does not require software audio decoding. It records selected audio-language metadata, received bytes and redacted transport exception types. Final dimensions/counters are taken after the midpoint check. Source badges ignore provider branding and prefer numeric resolution over a generic UHD source tag.

Final validation on `f5b4e63e65788c512389ca31996c6a8d66d43474`: 51 offline Python tests,
932 local core checks, all Android/build/lint/signing CI jobs and standalone engine checks passed.
The focused live audit completed for Levidia, both native sites and a catalogue-listed Rarefilmm
film. Read `docs/NATIVE_VOD_PLAYBACK_AUDIT_2026_10_26.jsonl` for each result; a completed audit
does not assert all links passed. Product code is unchanged by the final documentation commit.

## Confirmed source quality (26 October 2026)

`test_free_vod.py` reads header slices of real FFmpeg-made files in `tests/media-headers` (854×480 and 1920×800 MP4 with the index first, 1280×720 MKV, 640×360 WebM, and an 854×480 MP4 with its index last). It checks the probe replaces a website's "1080p" with the file's 480p, makes exactly one extra bounded read for an index stored last (which also exposes a real 3-second file as a short clip), keeps the size unknown without rejecting the source when that read fails, never requests an index beyond the file's end, and stops on cancellation. The site-level test checks the website's claim is kept separately and marked unconfirmed.

`QualityClaimsTest` covers judging claims (including the reported 1080p-labelled 854×480 case), the five-result history and recovery, ranking distrusted claims as SD, played-source labels and which confirmed better source is worth mentioning. `SourceWindowTest` covers the conditional longer first-choice wait; `StreamRateTest` covers confirmed-over-claimed ranking and small-picture labels; `VideoDetailsTest` covers the numeric tier.

`PlaybackTest.playingFilmCorrectsAnOverstatedLabelAndOnlySuggestsAConfirmedBetterSource` plays the real 160×90 fixture labelled 1080p through LibVLC, with a 4K link that is only claimed and a 720p link confirmed from its file. It requires the corrected label, the website's recorded verdict, a suggestion of the confirmed 720p link only, and no automatic switch. These are fixtures: they do not show how often real websites mislabel links, or how a real TV's network affects playlist steps.

## FMHY Rive addition (26 October 2026)

`test_fmhy_rive.py`, imported by `test_free_vod.py`, checks exact movie/episode identity, separate links, quality/language labels, playback headers, unsafe URLs, protected records and cancellation. The desktop live audit decoded all seven returned Inception links and three Breaking Bad S01E01 links independently at the start and midpoint with audio. See `docs/FMHY_ADDITIONS_2026_10_26.md`. These are sampled desktop decodes and actual Python-bridge checks, not physical-TV evidence. The separate new-source workflow checks the packaged Android player without selecting existing providers.

The initial packaged Rive audit verified two movie links and rejected five with relay errors. The redacted initial results are preserved, not relabelled as successes. A real-TCP regression reproduces raw TS served with image/jpeg during a zero-to-end read: it failed before the fix, then passed with bytes preserved and the correct TS MIME type. The new-source workflow now audits movie and exact-episode links in separate jobs; all returned links must pass. See the dated report for the follow-up result.

## Continue watching rows (27 October 2026)

`ContinueWatchingTest` (core, 13 checks at the time; see below) covers the row rules: nothing under three minutes, one row
per film whichever source, the newest record of a film deciding, a series resuming its newest episode
watched properly, a finished episode offering the next one once per series, an episode opened only
for a moment leaving it to the one before, a part-way rewatch resuming, row order, and grouping by
TMDB id, then shown title, then file id. `ContinueWatchingScreenTest` (device) builds the screen from
a fixture history: a one-minute start hidden, a film from two sources shown once, a series shown once
under its name and resuming the episode watched properly, a finished episode's series kept, and
removing a series forgetting every episode record and saved position. The next-episode lookup uses
TMDB ids that do not exist, so the real catalogue lookup (next episode found, or the row going when
the series is finished) is not exercised by fixtures.

## Live TV pause and Back to live (27 October 2026)

`ExoLiveTest.pausingShowsThePauseBarAndFastForwardGoesBackToLive` pauses a fixture channel with the
remote's pause key, checks the pause bar, carries on and checks it now says behind live, then presses
fast-forward and checks the picture plays again with the bar gone. The fixture has no live window,
so this exercises the reopen path; jumping to a real HLS live edge is not covered by fixtures.

## Subtitle size and background (27 October 2026)

`SubtitleStyleTest` (core, 12 checks) covers the size choices, damaged saved values falling back to
Normal, the LibVLC options and Media3's size and background colour. Every film device test now
starts LibVLC with the two subtitle options, so CI checks that LibVLC accepts them; how subtitles
look on screen is not checked by any test.

## Thumbs, new episodes and Anime (27 October 2026)

`TitleRatingsTest` (core, 12 checks) covers the saved ratings: keys, damaged parts skipped, a new
rating moving to the front, clearing, the 300-title bound and listing liked or disliked titles.
`ContinueWatchingTest` now has 19 checks, adding six for "New episode" (aired today or up to seven
days ago; not future, missing or invalid dates). `TmdbCategoriesTest.animeRows…` (device) checks every
Anime row is Japanese animation without adult titles, routes are unique, the airing window, a genre
row, and that film rows are typed as films and series rows as series. Not covered by tests: the
thumb buttons on screen, For you actually changing after a rating (it needs real TMDB), the New
episode label on screen, and the Anime screen loading real TMDB rows.

## Broken sources marked as watched (27 October 2026)

`PlaybackEndTest` (core, 28 checks) covers when a stop counts as a finish: a source that ends with
nothing played, a short clip, an unknown length with little played and a stream cut off part-way do
not, nor does a resume point well past the end of a shorter copy; the end, the credits window and
a reading just past the length do. `playedPosition` checks cover where a backup source, Try again and
the saved resume point carry on after a dead stream. It also covers leaving the player
and the unchanged `ResumePoint` completion rule. Not covered: the player acting on it (LibVLC's
end event, failover to another source), which no device test drives with a broken source.

## Additional FMHY route checks (27 October 2026)

The combined change includes PR #172 (ratings, recent-episode labels and Anime). Local core checks pass using Eclipse JDT with Java 17; provider-engine tests pass; the Python suite passes 68 tests. New fixtures cover the Movy decoder against a frozen independent JavaScript vector, wrong identities/seeds, separate labels for identical media URLs, omitted movie episode parameters, exact episode identity, cancellation, protected/private targets and backend failure isolation.

Earlier representative Inception desktop checks decoded Movy Boise/Miami at 3840×2160 with AAC and Rive Vanguard at 3840×1600 HEVC with separate decoded audio; full playlists were about 148 minutes. Atlantic Moscow decoded 1920×800 H.264/AAC with a similar runtime. These observations establish sampled desktop playback, not Android/TV playback. Fresh integration probes obtained eight Movy links, Rive Vanguard and a decoded Moscow URL. Local DNS is unavailable: these fresh HTTP probes used curl's network transport and did not exercise the app's DNS guard. Production URL guards remain unchanged.

The existing opt-in Android live audit now also runs for these adapters on PRs, checks movie and exact-episode sources independently, and requires every selected returned link plus every named delivery route to pass. Its source-name filter affects only the audit. Build/lint, device fixtures and live Android results must be read from the integration PR; no physical-TV claim is made.

First packaged audit: all 16 Movy movie/episode links passed video, decoded/output audio, runtime and midpoint seeking, including four measured 3840×2160 links. Vanguard's episode passed at 640×360; its movie audio/seek result did not pass, so the new backend is enabled only for episodes. Atlantic's first movie check had no decoded video and its episode process crashed. Direct inspection found raw MPEG-TS segments labelled `text/html` on range requests; its adapter now opts into the already-existing segment-container/MIME handling. The correction did not resolve either Android failure. Atlantic is excluded from this integration; its draft adapter, registry entry and cipher fixture have been removed. No shared relay/player changes were made. The ratings integration also checks that disliked titles are removed from Trending when recommendation seeds are absent.

Final integration validation: `3cd19c6` passed all 17 retained live Android checks ([40501077139](https://github.com/billyke2000s/moviehub/actions/runs/40501077139)), Android build/lint, signing/updater compatibility and both device suites ([40501077161](https://github.com/billyke2000s/moviehub/actions/runs/40501077161)), plus provider-engine CI ([40501077112](https://github.com/billyke2000s/moviehub/actions/runs/40501077112)). Movy passed eight movie and eight episode links, including measured 3840×2160 with decoded/output audio on both named routes; the sampled runtimes were 8,886 seconds and 3,501 seconds. Vanguard's sampled episode passed at 640×360. Atlantic and the new Vanguard movie route are not enabled by this change. PR #173 is merged; no APK was published.
