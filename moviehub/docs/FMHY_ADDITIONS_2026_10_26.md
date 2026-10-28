# FMHY provider additions — 26 October 2026

Scope: 30 genuinely new FMHY providers, prioritising useful verified 4K/1080p sources, then reliable lower-resolution sources, plus niche coverage. Existing providers, source identities, separate website/backend entries, settings and credentials remain unchanged. Archive.org and the second directory are excluded. No experimental branch or pull request was created for this batch.

## Current batch progress

One integration is prepared: Rive, using audited Primevids/dcloud and Citadel routes. This is **one website**, not ten providers. The target of 30 has not been completed. Packaged Android playback/build validation is pending; desktop results must not be described as physical-TV verification.

FMHY lists Rive with its .app/.ru/.win addresses and CorsFlix addresses together. These are candidate mirrors of the directory entry, not extra integrated providers. The configured address is the observed www.rivestream.app website and its advertised scrapper.rivestream.app anonymous API. No mirror is counted or enabled separately.

Sources: https://github.com/fmhy/FMHY/wiki/Streaming (retrieved 26 October 2026); ordinary Rive website/API responses retrieved on the same date. The API binds the query to a numeric TMDB ID and explicit season/episode. Any explicitly contradictory response identity is rejected. Unsupported/protected response records are excluded.

## Per-link desktop results

The [redacted evidence](FMHY_RIVE_DESKTOP_AUDIT_2026_10_26.json) records every returned enabled-route link for the two tested identities. No signed playback URL is committed.

| Tested title | Links tested | Measured video | Duration | Audio | Start and midpoint |
| --- | ---: | --- | --- | --- | --- |
| Inception (2010), TMDB 27205 | 7/7 | 1920×800, three 720-class and three 480-class resources; exact dimensions in JSON | 8,884–8,888 seconds, consistent with 148 minutes | H.264 video and stereo AAC; language metadata recorded where present | Every link decoded video and audio in both samples |
| Breaking Bad S01E01, TMDB 1396 | 3/3 | 1280×720, 852×480 and 854×480 | 3,479.8–3,486.5 seconds, consistent with 58 minutes | Citadel resources contain Hindi and English stereo AAC; Primevids/dcloud has stereo AAC | All links decoded video and audio in both samples |

The Primevids/dcloud start and midpoint samples ran for 30 seconds each after an initial eight-second sample did not reach video. Citadel samples ran for eight seconds each. Primevids/dcloud stays labelled Auto until the app measures it: one observed 1080-class film does not justify labelling every title 1080p.

Playback tests map video and audio explicitly; they preserve the original HLS resource without remuxing, stripping tracks or replacing its audio. These are sampled decoding/seek tests, not full-duration viewing or a guarantee of all future title availability. Returned format/quality strings are claims; this adapter adds no verified-quality badge by itself.

The actual moviehub_vod.query_site bridge returned all seven movie links and three episode links through the new adapter. This workspace requires its supplied HTTP proxy and external DNS resolution for direct-host checks; only the audit process was configured for those transport constraints. No proxy, DNS substitution or TLS bypass was added to the app.

## Candidates not integrated

| Candidate / route | Observation | Decision |
| --- | --- | --- |
| Rive Vanguard | Master advertises 3840×2160/1920×1080/1280×720 and separate audio; FFprobe did not complete playback validation | Excluded. No claim of verified 4K |
| Rive Primevids tcloud/ipcloud | Episode manifests reached PNG carriers without verified audio in the desktop decoder | Excluded. Only the separately verified dcloud route is enabled |
| Cno | Current frontend requires a person-verification flow for playback | No headless integration verified; excluded |
| NEPU / RidoMovies | HTTP 403 on the inspected addresses from this environment | Unverified; not treated as closed and not added |
| Abibli | Inception search returned no matches; another current title led to an unverified external player | No playback integration verified; not added |
| Streaming Unity | Exact Inception metadata and watch route worked, but the issued VixCloud film embed returned HTTP 410 | Not added |
| FshareTV | Inception autocomplete returned a detail URL which returned 404; a different current film detail loaded | No playback integration verified; not added |

Existing Rive-independent providers were not removed, repaired or reclassified by this batch. Existing enabled site count increases from 36 to 37; public adapters from 39 to 40; bundled constructors from 70 to 71. Original Kodi module inventory/provenance remains unchanged.

## Validation

- Python provider suite: 61 tests pass, including six new Rive protocol regressions.
- Local core suite passes using Eclipse JDT targeting Java 17 (the workspace has a Java runtime but no javac).
- Working-tree whitespace checks pass.
- New-source Android workflow selects only native:rive and requires every returned link to report verified video/audio, plausible runtime, midpoint decoding and separate-source identity. Empty or partially failing audits fail the job.
- Android build/lint, instrumentation and new-source live-player results are pending. No APK release has been published for this addition.

## Initial packaged Android audit and targeted transport fix

[Initial run 40375703384](https://github.com/billyke2000s/moviehub/actions/runs/40375703384) failed its strict verification gate: **two of seven movie links verified**, five did not. Primevids/dcloud and one Citadel route decoded video/audio, had plausible runtimes and decoded after seeking to the midpoint. The other routes raised relay IOExceptions. The [initial per-link results](FMHY_RIVE_ANDROID_INITIAL_2026_10_26.jsonl) are preserved. Desktop decoding did not establish packaged-player compatibility for those five links.

Live inspection found raw MPEG-TS beginning with the TS sync byte served as image/jpeg on the failing English routes, while a passing route used application/octet-stream. A real-TCP regression reproduced the misleading image MIME type on an opted-in whole-entity zero-range read and failed before the fix. The relay now sniffs those opted-in whole-entity payloads too; Citadel selects the existing carrier flag. Media bytes and audio are unchanged, and ordinary unflagged range handling stays on its existing path. The targeted relay suite passes, including the existing finite-file and wrapped-media cases. No existing provider was removed or reconfigured.

The follow-up workflow tests both new-provider movie links and exact-episode links. Its results remain pending; the failed initial audit is not counted as full verification. The first main commit's APK/build job and experimental device suite passed; the normal device suite is tracked separately, and signing compatibility was skipped by that push workflow's configuration.
