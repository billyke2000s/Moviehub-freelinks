# Standalone provider engine — experimental Android port

This draft ports the available historical Masqueradarr provider code to Android. It uses no home
server. It is opt-in under Settings → Free channels → Free services. Main and
production releases are unchanged. Existing native Pluto, IPTV/M3U/Xtream and VOD remain intact.

## Source provenance and compatibility

Current public upstream SHA inspected: `e77cc7d42bdf0abfe8fc14c2ed879b5991254cc5`. Its server
submodule is `iFlip721/masqueradarr-source` at `88804afdd13461cf4d5ab00cf4eabbf0b92c6e0d`.
That repository returns 404 with available access. This blocks current-version parity and automated
retrieval, **not** implementing the available code locally. A server dependency alone is not a reason
to exclude a provider.

The port uses public MIT source snapshot `3b860294ecb4a9ebca8819adfc061162fbbb7f99`. Files under
`provider-engine/upstream/` retain original bytes. FreeLiveSports and the FAST leaf modules execute
as compiled JavaScript. DaddyLive's directory parser, extractors, multi-player resolver, transport
classification and per-operation player/cache functions are bundled together. Tubi's actual signing,
PKCE, anonymous device-token, catalog and resolve modules run together. Samsung's four catalog,
normalization and resolution functions are selected from its adapter AST; server database/EPG hooks
are not executed. The Java segment-prefix port names its Rust upstream provenance. MIT notices ship
in the APK; Rhino 1.9.1 is MPL-2.0 and TypeScript 5.7.2 is build-time Apache-2.0.

A full Node/JNI runtime is not embedded: the inspected nodejs-mobile core was Node 18.20.4 (EOL).
The alternative is an explicit compatibility layer, rather than replacing the providers with playlists.
Native Chromium login, filesystem snapshots, MongoDB writes, arbitrary Node/native packages and the
Rust ingest/remux machinery still need separate Android implementations where required.

## Runtime 2

- TypeScript is downlevelled to CommonJS/ES5. An audited import registry rejects new dependencies.
  Node crypto supports UUID/random bytes, SHA-256 and HMAC-SHA256; Java bounds base64, UTF-8, gzip and
  zlib operations. URL and URLSearchParams supply the subset used by these adapters.
- `ProviderScript` runs in interpreter mode in `ProviderService`, an unexported Android isolated UID
  with no app files/accounts or INTERNET permission. Java classes are denied. Each evaluation has a
  three-second instruction deadline (eight seconds for stream resolution), 128-frame limit and ten-second process watchdog (15 seconds for resolution). Process teardown
  discards the heap. This is not a complete engine security audit or physical ARM64 performance result.
- Async/await executes through a microtask queue and deterministic HTTP replay. An operation yields
  pending requests; the host fulfils them and resumes by replaying recorded responses without repeating
  completed network requests. A fresh seed per operation gives fresh signatures/macros; recorded completion
  times advance the clock reproducibly. DaddyLive player preference/failure hints are retained in a bounded 256-channel, five-minute host cache and restored through upstream player-memory functions; playback failures retire the current player. Cookies and anonymous tokens remain in operation-local JS/replay
  state. Bridge IPC temporarily serializes these values into app-private cache files passed by descriptor and deletes them on completion; there is no durable token/session store. Anonymous bootstrap may therefore repeat on the next channel.
- `ProviderBroker` limits network work to 64 GET/POST requests, 3 MiB per response and 4 MiB aggregate
  response data. Catalogue work has a 240-second deadline and playback resolution 45 seconds, with channel
  cancellation and bounded per-hop HTTP timeouts. Timers are bounded to three seconds per request.
- API host capabilities are fixed in the APK and checked on redirects. DaddyLive explicitly has the
  broader public-HTTPS-GET capability because its iframe/CDN hosts rotate; it cannot POST or receive app
  credentials. All paths reject userinfo, insecure schemes, nonstandard ports, localhost/private DNS targets
  and header injection. DNS checking is not DNS pinning; rebinding remains a security limitation.
- `AdapterSources` writes encrypted stable locators into existing SQLite channel storage; category groups,
  favourites and source fallback remain in MovieHub. Base64url locator ids support compound upstream ids.
  Each provider refresh is atomic and a failure keeps its previous rows. Ten-minute per-provider caches
  invalidate on refresh/package changes. LG and Tubi and Local Now inline guides have independent source scopes retained
  through XMLTV refresh. Separate remote guides for other adapters are not ported yet.
- Direct resolved streams go to LibVLC. `ProviderRelay` is used only when additional headers are required,
  or for DaddyLive's transport. It binds loopback on an ephemeral port, uses random per-session grants,
  rewrites relative playlist/URI attributes, preserves required headers and byte ranges, and streams media
  through two workers with a bounded queue and 2,048 target entries. It closes on stop/channel cancellation;
  fullscreen/mini-player handoff retains the same session. Credential headers cannot cross the original
  stream authority. A bounded Java port of upstream's five-packet/RIFF prefix detection handles disguised TS.
  Full upstream ingest/remux, keyframe repair and transport-stream normalization are not implemented.
- Settings expose individual opt-ins, channel counts, last successful refresh, failure state, package
  version, refresh/update actions and rollback. All experimental adapters default off.

## Provider status and live evidence

Counts are desktop discovery results through the same compiled modules and Java interpreter; they
are not assertions that every channel plays or that a real TV has passed playback tests.

| Provider         | Android port                                                                     | Live result during this work                                                                                                                                             |
| ---------------- | -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Pluto TV         | Existing native adapter preserved                                                | Earlier GB discovery: 187; desktop media sample decoded                                                                                                                  |
| FreeLiveSports   | Runtime 1 compatible module retained; relay now supports Origin                  | Earlier 108 channels / 12,047 guide entries; one desktop sample decoded                                                                                                  |
| STIRR            | Upstream catalog and POST resolver                                               | 123 channels; fresh stream resolved                                                                                                                                      |
| TCL TV+          | Upstream category walk and POST resolver/fallback                                | 406 channels; fresh stream and a desktop sample decoded                                                                                                                             |
| Xumo Play        | Upstream catalog, broadcast/asset resolution and macros                          | 434 channels; fresh stream resolved                                                                                                                                      |
| LG Channels      | Upstream compressed catalog, inline guide and macros                             | 188 channels; fresh stream resolved                                                                                                                                      |
| Whale TV+        | Upstream anonymous token/catalog and macros                                      | 138 channels; fresh stream resolved                                                                                                                                      |
| Vizio WatchFree+ | Upstream catalog, entitlement/DRM filtering and macros                           | 465 surfaced channels; fresh stream resolved                                                                                                                             |
| Plex             | Upstream anonymous bootstrap/catalog/tokenized resolution                        | 691 channels; fresh stream resolved but sampled media returned HTTP 404                                                                                                                                  |
| DaddyLive        | Upstream directory, decoder chain and multi-player resolver                      | 899 channels; fresh stream and a desktop sample decoded after downstream failures; Android playback unverified                                                                  |
| Tubi             | Upstream signed bootstrap, catalog, per-play resolution and inline guide mapping | 36 channels; fresh stream resolved and a desktop media sample decoded                                                                   |
| Samsung TV Plus  | Upstream functions, gzip catalog and per-play redirect; GB catalog               | 252 GB channels; fresh stream resolved and a desktop media sample decoded                                                                                    |
| Roku Channel     | Upstream cookie/CSRF session, catalog, playId and POST playback                  | Live bootstrap returned HTML without usable session; **not verified playable**                                                                                           |
| DistroTV (removed Oct 2026) | Upstream feed, geographic flow and macros                                        | Feed returned HTTP 503 from this environment                                                                                                                             |
| Vidaa (removed Oct 2026) | Upstream tenant/bootstrap, regional catalog and filters                          | Configuration endpoint returned HTTP 503 from this environment                                                                                                           |
| dulo             | Not yet ported                                                                   | Device-bound authenticated Supabase sessions and the real browser login must be implemented on Android; no authorized account/device session is available to validate it |
| Local Now | Actual local/api.ts bootstrap, auto-market, entitlement/market filtering, fresh resolution and inline guide | 183 channels discovered; playback checks recorded below |
| ZLive | `zlive.js` ticket + AES-GCM resolver (replaced the original upstream resolver, see below), encrypted contact history and refusal cooldowns, local segment transport and expiry renewal | Current catalogue `iptv.zlive.st/channels.json` (252 channels, 25 Oct 2026); played on the owner's TV in exp-15 after adding browser fetch headers |
| HDHomeRun        | Not yet ported                                                                   | LAN discovery and TS transport require separate Android work; historical upstream marks playback dormant                                                                 |
| Direct imports   | Existing M3U/Xtream retained                                                     | Independent of the JS port                                                                                                                                               |

Geographic/entitlement/DRM filtering is retained. No geo override, login bypass or DRM bypass is
implemented. Ports with unavailable live endpoints stay opt-in and report failure rather than import
invented snapshot streams. Source updates may be needed for historical endpoints that have changed.

## Signed automatic updates

Runtime-2 packages contain the audited module set (runtime 1 remains readable). Maximum signed payload
is 1 MiB, authenticated over exact bytes by SHA256withRSA and an APK-pinned RSA >=3072 public key.
Validation rejects unknown modules and native capabilities; candidates undergo actual isolated Android
runtime checks before atomic activation. Independently verified active/previous slots and monotonic
high-water version state provide rollback and downgrade protection. Explicit rollback pauses updates.
An older runtime-1 package cannot remove newer APK-bundled adapters; missing modules use that baseline.

The update workflow retrieves **all** required source files at the exact upstream gitlink, verifies the
audited MIT licence, compiles without running provider code on Node, runs actual JVM fixtures and signs
only if tests pass. Partial retrieval, unknown imports, changed adapter functions or incompatible packages
fail closed. Existing packages remain installed. The host checks during channel refresh at most every
12 hours or on request. Provider-only releases use `--latest=false`, never the production APK feed.

**Remote updates remain disabled:** `trust.json` has no public key. Maintainers must configure the
public key in the APK, `PROVIDER_SIGNING_KEY_B64`, authorized `MASQUERADARR_READ_TOKEN` source access,
and `PROVIDER_UPDATES_ENABLED=true` after review. No keys/secrets or publishing settings were changed.
New unseen providers still need an audited wrapper/capability contract and tests; this is not automatic
parity with an inaccessible repository or arbitrary new Node/native dependencies.

## Validation

- Existing 223 core checks must remain green.
- The JVM checks run actual Rhino, Java-denial/deadline/signatures/codec/URL logic and segment prefix rules.
- `runtime-check.mjs` invokes Java (never executes candidate modules on Node). Fixtures exercise module
  metadata, STIRR POST/nonce, Xumo broadcast/asset/macros, LG compressed guide, Vizio filtering, a 36-category
  TCL async walk plus POST resolve, DaddyLive directory/iframe/base64/HLS resolution, Tubi signatures checked
  against Node crypto, and Samsung gzip/DRM filtering. Fixtures prove compatibility, not live availability.
- `ProviderAdaptersTest` adds real isolated Binder POST-resolution fixtures, capability/locator rejection,
  and a real loopback relay socket using fixture upstream connections, including headers and cleanup.
- Build/lint, instrumentation and CI outcomes are recorded in PR #83. Do not carry earlier green run
  results forward as evidence for changed code. Physical S905X5M startup/RSS, rapid switching, network loss,
  live playback, guide handoff and installation remain unverified. There is no local accelerated emulator.

### Additional desktop media checks

Fresh STIRR, Xumo, LG and Whale masters and variants returned HTTP 200. One media sample from
each decoded two seconds successfully with FFmpeg (exit 0). Sample sizes were 439,356; 3,286,616;
363,780; and 355,132 bytes respectively. Vizio's first sample returned HTTP 400. These desktop
checks do not establish Android/LibVLC playback or availability of every discovered channel.

Tubi and Samsung GB also returned valid masters, variants and media samples; FFmpeg decoded two seconds
from 2,063,300 and 573,024 bytes respectively. Plex resolved a URL whose manifest returned HTTP 404.
Live redirects revealed Samsung's catalog redirect to its specific public GitHub mirror and Tubi's
Adrise CDN redirect; host capabilities now allow those observed paths rather than failing after discovery.

The initial DaddyLive live walk also hit the interpreter's three-second CPU deadline on a large embed.
The stream-resolution budget is now eight seconds per evaluation (with a 15-second process watchdog),
while other operations retain the shorter limit. Cached replay completed in 2.07 seconds after the codec
improvements; this does not establish a playable DaddyLive stream.

After extending the bounded decoder budget, the DaddyLive live walk resolved a fresh stream through a later
player. Its original default-player failures are retained as evidence rather than treated as proof that the
provider cannot run locally. Real media and Android playback remain separate checks.

DaddyLive's resolved media playlist returned HTTP 200 and a 793,924-byte TS sample decoded two seconds
with FFmpeg exit 0 (it logged an initial H.264 buffering/SPS warning). This is one desktop sample, not
proof of all 899 channels or Android playback. The regression fixtures also verify restored preferred
player selection while a failed player is excluded.

Vizio's first URL also decoded after percent-encoding the literal braces left by upstream macros, as
`ProviderUrls` now does: master and variant HTTP 200, 111,672-byte media sample, FFmpeg exit 0.
Eight newly ported providers therefore have one decoded desktop sample each. The first Runtime-2 CI
run passed provider verification, APK/lint, signing compatibility and the full emulator suite:
https://github.com/billyke2000s/moviehub/actions/runs/39335954185 . Follow-up fixes need their own run.

The final local checks passed: 223 existing core checks, 21 runtime/signature/URL/segment checks,
40 multi-provider JVM fixtures and three compiler fixtures. Debug APK, lint and instrumentation
compilation passed. All 35 vendored TypeScript files match the historical upstream bytes and the
package provenance hashes. The APK contains ARM64 LibVLC libraries; the provider runtime is pure Java.
This is ABI packaging evidence, not physical ARM64 execution.

### Cold playback startup

STIRR, Xumo, Roku, Plex, DaddyLive, Tubi and Samsung now resolve directly from the stored provider
channel identity without reloading their catalogues after app restart. TCL retains only its source/media
resolver hints encrypted through the existing Keystore store, without guide data. Hints expire after
14 days, are invalidated by module changes or a playback failure, and never replace the fresh gateway
POST. Other direct-HLS adapters retain their on-demand catalogue refresh. Instrumentation verifies
ID-only rows, compact hint fields and encrypted disk storage.

TCL also passed a real gateway POST, master/variant HTTP 200 and two-second FFmpeg sample decode
(307,944 bytes). Nine newly ported providers now have one decoded desktop sample each.

### Local Now audit correction

The earlier audit incorrectly treated Local Now as absent. Its code is in `adapters/local/api.ts` and
`adapters/local/import.ts`; both are now retained unchanged in the vendored source. The protocol module
and selected category function execute locally. Automatic market discovery and the original unlocked/
in-market filters are preserved, with its inline guide written through MovieHub's existing SQLite scope.
The market picker/search UI is not yet exposed; the upstream auto-detected market/fallback is used.
Live homepage bootstrap and market feed returned HTTP 200 and 183 channels. Fixtures verify anonymous
bootstrap, JWT/base64url handling, locked/out-of-market exclusion, guide mapping and fresh stream headers.

Local Now also resolved a fresh master. Master and variant returned HTTP 200, and a 2,064,052-byte
media sample decoded two seconds with FFmpeg exit 0. Ten newly ported providers now have one decoded
desktop sample each; this still does not prove real Android/LibVLC playback or every channel.

API redirect transport retains only public playback-context headers (User-Agent, Accept/Language,
Origin and Referer) across an approved authority change. Authorization, cookies and custom tokens are
stripped; body-preserving cross-authority POST redirects are refused. Instrumentation exercises both
GET header preservation/redaction and a rejected 307 POST using fixture connections.

### ZLive stateful Android port

_History: this section describes the first port; the resolver was replaced on 25 October 2026 (see "Current protocol" below)._

The complete reusable ZLive config/resolver plus shared domain/SSRF modules are now vendored unchanged.
The required Node `net` subset is a pure-data IP/subnet bridge; DNS is brokered and checked. The original
resolver receives manual HTTP 302 responses, learns/vets signed targets, reuses tokens, backs off after
refusal and detects/latches suspected decoys. No alternate identity, IP or automatic domain evasion is used.

An encrypted module/domain-scoped contact ledger reconstructs the original in-memory state in each isolated
VM. Historical requests are fulfilled only from recorded responses; incomplete history cannot contact the
network. A cancelled contact is replayed as cancelled, preserving per-channel backoff without blocking a
different channel. Ledger bounds are 128 contact events, two MiB and three hours; a full ledger refuses new
contacts until room expires. A module/domain change with live history conservatively pauses contacts for
up to three hours. Storage failures disable further contacts for that app process. Catalogue refresh never
bulk-resolves channels, shrink responses are rejected, and the single Live TV player stays below the
upstream's default two-channel concurrency cap. The domain form accepts only a bare HTTPS provider host;
leaving it empty follows upstream's default. It does not clear cooldowns.

ZLive uses the bounded local relay for wrapped media segments and required headers. Signed expiry can
refresh an actively playing stream before expiry; channel switches/stops cancel the renewal and a paused
player is never auto-started. Upstream full ingest/remux/keyframe repair is still not ported.

**Current protocol (25 October 2026).** The old `cast.zlive.st/channels.json` catalogue is gone (404).
`provider-engine/tools/zlive.js` now uses the site's own flow: catalogue at `iptv.zlive.st/channels.json`,
a `GET /session/ticket`, then an AES-256-GCM sealed `POST /resolve` that returns the stream location.
The key derivation was confirmed by decrypting the real web player's own requests (recorded in headless
Chromium). ZLive answers requests that lack browser fetch metadata (`sec-fetch-*`, Accept) with a looping
"refresh the site" decoy from a different host (~85 KB chunks instead of ~3 MB Full HD ones); client hints
alone are not enough. API requests therefore send those headers; media requests keep the plain ones.
exp-15 played ZLive channels on the owner's TV. The live probe (`live-probe.mjs`) still checks only the
ZLive catalogue, not playback.

## Final automated result

Code revision `4ca05fdf89eebc659e7ba8f3e0c66cdd04b72abf` passed the Android APK/debug/release
builds and lint, signing compatibility and full API-28 TV x86_64 emulator suite:
https://github.com/billyke2000s/moviehub/actions/runs/39341576650 . Provider/compiler/JVM verification
passed separately: https://github.com/billyke2000s/moviehub/actions/runs/39341576614 . The package
publication job was intentionally skipped. Physical ARM64/live-provider-on-TV checks remain outstanding.

## Current DaddyLive player protocol (20 October 2026)

The historical resolver remained byte-for-byte upstream, but the live player acquired an anonymous
`playlist-token` request and PNG media containers. `tools/dlhd-session.js` supplies this current protocol
around the upstream resolver without executing site JavaScript. Session metadata is transient and handed
to the existing native relay; see `PlaylistSession` and `ProviderPngSegments` for expiry, request scoping,
format and allocation bounds. This is normal anonymous session handling, not a server-hosted converter.

Actual rejection text `token required` distinguished the missing step from a blanket external-player ban.
The anonymous endpoint returned a token and 120-second TTL; the same playlist then returned HTTP 200.
Direct FFmpeg failed on wrapped segments, whereas unpacked samples for Sky Sports Main Event, Football,
Action, F1 and ABC USA decoded successfully. The older all-provider probe does not yet model the new
native session/PNG transport and must not be used to declare this path broken from direct FFmpeg alone.
Physical Android playback and sustained renewal need independent validation.

The new compiled resolver independently resolved Football through the actual JVM interpreter. The native
Java PNG unpacker then produced MPEG-TS from a real Football segment and FFmpeg decoded 100 video frames
over four seconds, exit 0. A full live host-JVM relay attempt was blocked by this workspace's DNS/network
transport; relay renewal/scoping and fragmented media are covered by fixtures, pending Android CI and
physical-TV verification. No host stub is included in the Android app.

## Channel selection and startup timing audit (20 October 2026)

The upstream README documents resolve-on-demand and a bounded walk across player providers, rather than storing finished stream links during catalogue refresh. MovieHub follows that design: AdapterSources stores a stable channel locator (Main Event is DaddyLive id 38), LivePlayer calls the adapter on selection, the bundled resolver walks the current player/embed pages, and the transient result/session goes to ProviderRelay and LibVLC. Guide duplicates are channel copies; the selected copy may therefore be another source before DaddyLive is tried.

The TV now reports a successful 899-channel DaddyLive refresh. A fresh bundled-module Main Event walk in Node VM with curl transport resolved Player 1 and its anonymous playlist session in about 28 seconds. This is an exploratory desktop observation; an attempted actual-JVM step hit the local processing deadline, and physical-TV execution remains unverified. A mismatched video watchdog was confirmed in code: it started a 15-second timeout before the resolver finished, while the broker has a separate longer bound. The player now says “Finding a working stream…” and starts its normal video timeout only after resolution/relay setup. Existing HTTP, interpreter, broker and cancellation bounds remain.

Documentation checked: https://github.com/TheBinaryNinja/masqueradarr/blob/main/README.md (resolve-on-demand and DaddyLive player walk).

The same fresh Main Event result returned HTTP 200 media HLS and a 6,803,517-byte PNG segment. Production ProviderPngSegments unpacked it on Java 17; FFmpeg decoded four seconds of video with exit 0. This verifies a desktop sample and the native unpacker, not physical TV playback or a complete native-network relay session.

## Sustained playback and silent freezes (20 October 2026)

Main Event startup was confirmed by the user on the TV. The later report is repeated freezing and then a fixed frame for minutes without a buffering spinner. Do not mark sustained playback or renewal verified from startup success.

The native relay must fully validate/unpack each PNG media container before delivering its TS payload. The sampled 6.8 MB, ten-second container has 4,477 rows, 3,815 using filter 0. The optimized decoder skips needless per-byte work on those rows, copies RGB bytes in bulk and increases inflate buffers while preserving all limits/checks; the explicit inflater is released even on error. A desktop before/after benchmark produced identical bytes in eight paired runs, all faster after the change (later runs roughly 2x). ARM64 throughput remains unmeasured.

LivePlayer previously recovered only from VLC buffering/error signals. It now also monitors elapsed playback time and displayed-picture count for visible, playing native/provider streams, at the existing eight-second stall threshold. A stopped video counter can trigger even while the audio clock advances. A provider receives one re-resolution/alternate-player attempt; further failure follows the owner’s ordinary backup/error handling. Pausing, hidden views, channel changes and handoffs reset the observation; no background pause is auto-resumed. Identical frozen pictures still encoded as new video frames cannot be distinguished from valid content by counters. Actual provider network/decoder/timestamp causes and sustained renewal on the TV remain unconfirmed.

Advancing displayed-picture counters take precedence over the clock: some live demuxers expose a static clock during healthy video. Missing/zero video counters fall back to clock progress.

## FreeShot UK sports

MovieHub’s own FreeShot adapter (not a historical upstream port) reads the selected public channel page’s related UK sports directory in one request. Channel IDs are stable public page slugs/IDs; no player page is fetched during directory import. Selecting a channel follows only its FreeShot embed and WideIPTV player, parses literal HLS configuration and hands media to the native player through the existing relay. It does not render the website, ads or execute fetched scripts.

The fixed anonymous renewal endpoint posts the channel and current token; the media grant is restricted to the discovered BlueTier origin and channel prefix. Renewal and playback run on demand. Turn on **FreeShot (UK sports)** in Settings → Provider engine to import it. This integration covers UK sports, not every country or category on the website.

A live host check decoded Sky Sports Action video/audio for 30 seconds with the player’s ordinary headers. That is separate from Android fixture validation and does not establish sustained playback or geography on a physical TV.

The current built-in package contains the 16 historical modules plus the MovieHub FreeShot
adapter. FreeShot Main Event subsequently passed 150 seconds of both tracks through production
relay/session code with forced renewal (run 39510770299). Only numerical results were uploaded.
