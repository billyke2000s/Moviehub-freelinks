# Free VOD provider audit — 24 October 2026

The inventory exactly matches the active provider directories in Scrubs V2 5.1.42 (59)
and Free99 2.0.1 (nine): **68 modules, zero missing, zero extra**. Every recorded original-file
SHA-256 matches the downloaded upstream package. All 68 adapted constructors import locally;
the lookup-contract fixture checks the movie and series signatures and individually drives
every applicable adapter through MovieHub's query entry point with mocked network results.
Android's packaged-runtime constructor test remains part of CI.

The upstream loader walks provider modules, constructs `source()`, calls `movie(...)` or
`tvshow(...)` then `episode(...)`, and calls `sources(key, hostDict)`. MovieHub uses the same
sequence with a fresh instance per query, then calls `resolve` and free, non-popup ResolveURL
host extractors. It deliberately omits Kodi's settings/UI/database and paid/interactive hosts.
The supported host list excludes wildcard/universal resolvers, matching upstream's host-list
filter. These are compatible call contracts, not a claim of a complete Kodi runtime.

The previous 120-second overall search could leave the last modules unstarted with eight
18-second workers. The bound is now 180 seconds; first selection still occurs at three seconds
and discoveries continue as backups. Each provider records queued, searching and final outcomes.
HTTP, DNS/TLS, timeout, unsupported host, rejected media, local-library and missing-key outcomes
are shown separately from no matches. Outcomes contain no source URLs, cookies or error bodies.

Local live availability checks cannot establish provider health: this workspace's direct public
DNS lookups fail. The independent Free VOD provider audit workflow runs a public-domain film
query on GitHub's runner and saves one result per module. Neither a constructor pass nor one
film's no-match result proves a website supports a particular title or plays on the TV.

Sources: [Scrubs package](https://github.com/jewbmx/repo/tree/master/zips/plugin.video.scrubsv2),
[Free99 package](https://github.com/nebulous42069/Omega/tree/main/omega/zips/plugin.video.free99).
Archived `_duds` are not active providers. `library` uses Kodi's database; `filepursuit` requires
an API key. Their inclusion in the inventory does not make them anonymous website providers.

Independent audit [run 40094574136](https://github.com/billyke2000s/moviehub/actions/runs/40094574136)
queried all 68 modules on GitHub's runner after the HTTP correction. Both BNWMovies adapters and
Free99 Levidia returned five media-response links in total. Other outcomes included HTTP 403 (12),
DNS failure (6), TLS failure (7), connection timeouts (3), read timeouts (7), no matches (14),
unsupported media type (6), missing key (1), local library (1), other HTTP errors (4) and
rejected/unsupported URL or response limits (4). These counts include duplicate adapters/links.
This was one public-domain film, with HTTPS/public-address policy enabled. It does not
establish availability for other films, website
health from the owner's network, sustained playback, or physical-TV success.

| Provider | Constructor | Movies | Episodes | Constraint | Probe outcome |
| --- | --- | --- | --- | --- | --- |
| free99:anymovies_co | Loaded | Yes | No | Website availability varies | http_404 (0 links) |
| free99:bnwmovies_com | Loaded | Yes | No | Website availability varies | complete (2 links) |
| free99:fzmovies_live | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| free99:levidia_ch | Loaded | Yes | Yes | Website availability varies | complete (1 links) |
| free99:my4hydra_top | Loaded | Yes | Yes | Website availability varies | gaierror (0 links) |
| free99:naijavault_com | Loaded | Yes | Yes | Website availability varies | http_403 (0 links) |
| free99:seriesloaded_ng | Loaded | Yes | Yes | Website availability varies | http_403 (0 links) |
| free99:stagatv_com | Loaded | Yes | Yes | Website availability varies | http_400 (0 links) |
| free99:tvseries_in | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| scrubs:123movies_skin | Loaded | Yes | Yes | Website availability varies | gaierror (0 links) |
| scrubs:123moviestv_me | Loaded | Yes | Yes | Website availability varies | SSLError (0 links) |
| scrubs:1movies_la | Loaded | Yes | Yes | Website availability varies | SSLError (0 links) |
| scrubs:9pm_to | Loaded | Yes | Yes | Website availability varies | SSLError (0 links) |
| scrubs:anymovies_co | Loaded | Yes | No | Website availability varies | http_404 (0 links) |
| scrubs:bluray7_com | Loaded | Yes | No | Website availability varies | http_403 (0 links) |
| scrubs:bnwmovies_com | Loaded | Yes | No | Website availability varies | complete (2 links) |
| scrubs:bstsrs_one | Loaded | No | Yes | Website availability varies | unsupported_type (0 links) |
| scrubs:cinebox_cc | Loaded | Yes | Yes | Website availability varies | ReadTimeout (0 links) |
| scrubs:couchtuner_show | Loaded | No | Yes | Website availability varies | unsupported_type (0 links) |
| scrubs:doomovies_ga | Loaded | Yes | No | Website availability varies | http_403 (0 links) |
| scrubs:dopebox_to | Loaded | Yes | Yes | Website availability varies | gaierror (0 links) |
| scrubs:filepursuit | Loaded | Yes | Yes | API key required | requires_api_key (0 links) |
| scrubs:filmxy_pw | Loaded | Yes | No | Website availability varies | ReadTimeout (0 links) |
| scrubs:flixhq_to | Loaded | Yes | Yes | Website availability varies | ReadTimeout (0 links) |
| scrubs:flixwave_me | Loaded | Yes | Yes | Website availability varies | ConnectTimeout (0 links) |
| scrubs:gdriveplayer_us | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| scrubs:gogoanime_bid | Loaded | No | Yes | Website availability varies | unsupported_type (0 links) |
| scrubs:gogoanime_tel | Loaded | No | Yes | Website availability varies | unsupported_type (0 links) |
| scrubs:gogoanimes_to | Loaded | No | Yes | Website availability varies | unsupported_type (0 links) |
| scrubs:goojara_club | Loaded | Yes | No | Website availability varies | no_matches (0 links) |
| scrubs:goojara_to | Loaded | Yes | Yes | Website availability varies | http_403 (0 links) |
| scrubs:hdbest_net | Loaded | Yes | No | Website availability varies | ReadTimeout (0 links) |
| scrubs:himovies_top | Loaded | Yes | Yes | Website availability varies | ReadTimeout (0 links) |
| scrubs:iwaatch_com | Loaded | Yes | No | Website availability varies | no_matches (0 links) |
| scrubs:levidia_ch | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| scrubs:levidiamovies_club | Loaded | Yes | No | Website availability varies | http_521 (0 links) |
| scrubs:library | Loaded | Yes | Yes | Kodi library only | local_library (0 links) |
| scrubs:m4ufree_se | Loaded | Yes | No | Website availability varies | ConnectTimeout (0 links) |
| scrubs:m4uhd_tv | Loaded | Yes | No | Website availability varies | http_403 (0 links) |
| scrubs:movie4u_live | Loaded | Yes | Yes | Website availability varies | SSLError (0 links) |
| scrubs:movies2watch_tv | Loaded | Yes | Yes | Website availability varies | ReadTimeout (0 links) |
| scrubs:movies4fun_net | Loaded | Yes | Yes | Website availability varies | SSLError (0 links) |
| scrubs:moviesnipipay_me | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| scrubs:myflixer_it | Loaded | Yes | Yes | Website availability varies | ValueError (0 links) |
| scrubs:noxx_to | Loaded | No | Yes | Website availability varies | unsupported_type (0 links) |
| scrubs:okmov_to | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| scrubs:pressplay_top | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| scrubs:projectfreetv_cyou | Loaded | Yes | Yes | Website availability varies | http_403 (0 links) |
| scrubs:projectfreetv_lol | Loaded | Yes | Yes | Website availability varies | gaierror (0 links) |
| scrubs:putlocker_gives | Loaded | Yes | Yes | Website availability varies | ConnectTimeout (0 links) |
| scrubs:rarefilmm_com | Loaded | Yes | No | Website availability varies | no_matches (0 links) |
| scrubs:series9movies_com | Loaded | Yes | No | Website availability varies | http_403 (0 links) |
| scrubs:series9mx_pro | Loaded | Yes | No | Website availability varies | no_matches (0 links) |
| scrubs:sflixz_watch | Loaded | Yes | Yes | Website availability varies | SSLError (0 links) |
| scrubs:soap2day_fan | Loaded | Yes | Yes | Website availability varies | gaierror (0 links) |
| scrubs:streamlord_com | Loaded | Yes | Yes | Website availability varies | ValueError (0 links) |
| scrubs:tinyzonetv_to | Loaded | Yes | Yes | Website availability varies | ReadTimeout (0 links) |
| scrubs:tubitv_com | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| scrubs:tvids_net | Loaded | Yes | Yes | Website availability varies | gaierror (0 links) |
| scrubs:tvmovieflix_com | Loaded | Yes | No | Website availability varies | http_403 (0 links) |
| scrubs:tvseries_video | Loaded | Yes | Yes | Website availability varies | http_403 (0 links) |
| scrubs:upmovies_net | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| scrubs:vumoo_mx | Loaded | Yes | Yes | Website availability varies | no_matches (0 links) |
| scrubs:watchseries_cyou | Loaded | Yes | Yes | Website availability varies | http_403 (0 links) |
| scrubs:watchserieshd_stream | Loaded | Yes | Yes | Website availability varies | ValueError (0 links) |
| scrubs:watchtvseries_net | Loaded | Yes | Yes | Website availability varies | http_403 (0 links) |
| scrubs:watchug_com | Loaded | Yes | Yes | Website availability varies | SSLError (0 links) |
| scrubs:winnoise_com | Loaded | Yes | Yes | Website availability varies | ValueError (0 links) |

Additional confirmed compatibility defect: the bounded HTTP adapter returned an HTTP status integer in place of request headers in `output="extended"`. The actual WatchSeries provider then passed that integer as headers and swallowed the resulting exception. The adapter now returns the original five fields (body, request headers, response headers, cookies, final URL), without an extra cookie request. Default scrapePage Referer/protocol-relative handling and redirect cookie accumulation also match the upstream contract. A fixture drives the real WatchSeries provider through search, page and host lookup.


## Provider request and title compatibility follow-up (24 October 2026)

Reproduced an additional adapter defect: `Session.post(url)` with no body became GET.
The Scrubs Levidia search uses this exact request. Explicit GET/POST methods now reach
the bounded transport and retain the existing redirect semantics (301/302/303 switch
POST to GET; 307/308 retain POST), session cookies and TLS/public-address checks.
`client.request` supplies upstream's default Referer and Accept-Language without
replacing explicit values.

Movie and series searches now use each bundled add-on's `scene_title`/`scene_tvtitle`
preparation and receive original and alternative titles from TMDB detail metadata.
These aliases use TMDB rather than Kodi's Trakt account/runtime. Matching still depends
on the individual provider. Per-provider work has a 30-second cancellation budget and
the eight-worker search has 300 seconds of background headroom for the full inventory.
The three-second initial selection and existing buffering/failover rules are unchanged.
The diagnostic workflow uses the same search budget.

Regression coverage drives the real Levidia provider through its landing page, empty
POST search, result page, host and media probe; checks all five redirect codes; checks
movie punctuation/aliases and upstream episode remapping for both families; and checks
request headers. Android coverage checks movie/series alternative-title metadata.
Local validation: 17 Python tests and 814 core assertions pass (Eclipse JDT targeting
Java 17). Android build/lint/emulator CI and live-provider outcomes must be checked on
the final commit. No physical-TV or Guardians playback success is claimed.

Provider-request read timeouts now honor explicit upstream values within the remaining
search budget, with an eight-second connection cap. A regression checks a 20-second read
request and clipping to a five-second remaining budget; cancellation remains bounded.

## Dead-site retirement (25 October 2026)

From a GitHub runner (United States), 26 of the 63 websites failed three checks a few minutes apart:
broken TLS certificate (9), no DNS record or refused connection (6), Cloudflare 521/522 because the origin is
down (8), or connection timeout (3). They are listed in
`moviehub_vod.RETIRED_SITES`, skipped by `search_providers()`, and still bundled and validated so provenance
checks against Scrubs 5.1.42 and Free99 2.0.1 keep passing. Both upstream packages are unchanged: Scrubs' repository
was last updated in October 2024 and Free99 in May 2026.

Of the 37 remaining sites, in the first run 14 answered HTTP 403 (bot protection), 14 were reachable but returned
no links for "The Dark Knight" or "Night of the Living Dead", 3 had broken search pages, and only bnwmovies.com
returned playable links (for the 1968 film). The other 5 failed the first run but answered in the re-check, so
they stay; their searches were not repeated. Retirement makes searches faster; it does not add results. A site that comes
back can be removed from the list.

## Correction to the 25 October retirement decision

The old homepage recheck used bare domains rather than every scraper’s exact base URL. Levidia’s `https://www.levidia.ch` homepage and search both return real catalogue content, so its exclusion was incorrect. Restore 19 websites, keeping only seven with NXDOMAIN, parked-domain or HTTP 410 evidence excluded. Timeouts, TLS errors, 403 and temporary 521/522 failures do not establish permanent closure. Current searchable inventory: 56 websites and 59 adapters; 68 modules remain bundled.

The review made 82 direct homepage/search/alternate requests across all 26 excluded providers, plus public DNS checks. Four names returned NXDOMAIN (123-movies.golf, dopebox.to, projectfreetv.lol, soap2dayz.top); flixwave.me served a sale page; m4ufree.se and putlocker.gives returned HTTP 410 during checks. Other failures remain inconclusive. Parked alternate domains were not adopted. Levidia search reachability does not establish media playback.

Further compatibility fixes: metadata-only operations no longer read full media responses; cross-site redirects remove obsolete Host headers; Levidia tolerates no initial cookie, cookie values containing equals signs, missing optional markup and yearless results. Resolved file URLs mislabeled indirect are verified as media rather than discarded for lacking a second host resolver. Original-package hashes continue to identify provenance; the maintained Levidia edits are documented in the third-party notice.

The first recovery live probe extracted one Levidia candidate but its media connection timed out; the Scrubs fallback returned no matches. The grouped result now preserves the earlier host failure. Scrubs’ old inline Wootly protocol followed by a second resolution was removed from the active path; its links now use the bundled host resolver once. Local coverage is 30 Python tests. These fixes require a new live probe and final Android CI; no physical-TV playback is inferred.

Compression compatibility: the shared transport now filters explicit Accept-Encoding values to decoders supported by the packaged requests runtime. Desktop scrapers advertised Brotli/Zstandard even when those optional decoders were absent. An offline regression verifies negotiation and that original headers are not mutated (31 local Python tests).

## Positive verification required for restorations

The provisional restoration of 19 sites in #155 was too broad: absence of permanent-closure proof is not positive evidence of a working provider. A fresh review made 71 exact-address HTTPS/HTTP/search/DNS checks across those 19 providers and eight additional nameserver checks. Only Levidia remains enabled from this group. The other 18 are disabled in `UNVERIFIED_SITES`; this does not label every timeout a permanent closure. Seven previously retired domains stay excluded. Inventory is now 38 ordinary websites, 41 adapters, 68 bundled modules. Existing transport/Levidia repairs remain.

| Site | Observed evidence | Normal discovery |
| --- | --- | --- |
| `123moviestv.me` | ParkLogic nameservers; HTTP redirect landing page; HTTPS fails | Disabled pending verification |
| `1movies.la` | ParkLogic nameservers; HTTP redirect landing page; HTTPS fails | Disabled pending verification |
| `9pm.to` | ParkLogic nameservers; Site Unavailable placeholder | Disabled pending verification |
| `cinebox.cc` | Homepage and search timed out; no positive verification | Disabled pending verification |
| `couchtuner.show` | ParkLogic nameservers; exact www address has redirect landing page | Disabled pending verification |
| `ev01.to` | Homepage and search timed out; no positive verification | Disabled pending verification |
| `filmxy.online` | Exact www homepage timed out; no positive verification | Disabled pending verification |
| `flixhq.to` | Homepage and search timed out; no positive verification | Disabled pending verification |
| `goojara.life` | Homepage and search return Cloudflare 521 origin failure | Disabled pending verification |
| `hdbest.net` | Homepage and search timed out; no positive verification | Disabled pending verification |
| `hdtoday.cc` | Homepage and search timed out; no positive verification | Disabled pending verification |
| `levidia.ch` | Exact www HTTPS homepage and search return catalogue/results; previous runner validated two episode links | Enabled |
| `movies2watch.tv` | Homepage and search timed out; no positive verification | Disabled pending verification |
| `movies4fun.net` | Coming Soon placeholder rather than a film catalogue; HTTPS fails | Disabled pending verification |
| `mp4hydra.top` | No DNS A answer; HTTP/HTTPS failed; no positive verification | Disabled pending verification |
| `sflixz.watch` | ParkLogic nameservers; HTTP redirect landing page; HTTPS fails | Disabled pending verification |
| `streamlord.com` | Exact HTTP scraper homepage says Site Unavailable | Disabled pending verification |
| `watchug.com` | ParkLogic nameservers; HTTP redirect landing page; HTTPS fails | Disabled pending verification |
| `www1.movie4u.live` | Parent uses ParkLogic nameservers; exact address serves HTTP redirect landing page; HTTPS fails | Disabled pending verification |

DNS registration or HTTP 200 on a landing page does not establish that the old streaming service still operates. ParkLogic nameservers plus redirect/placeholder pages are recorded as parking evidence, not a working catalogue. Local proxy connection failures and timeouts remain inconclusive about global reachability. Explicit audits can still include every disabled adapter; ordinary searches cannot. HTTP diagnostics did not change runtime network policy.
