# Individual Android movie-source playback audit — 26 October 2026

This is evidence for the 1.0.4 source tree, not a promise of current availability on every network.
The signed APK was downloaded and its SHA-256 matched the published update manifest:
`22e212e08cb5de065065a025678487d577391acc056f8c5d6c3171f16126fde0` (code 104).

[Android audit run](https://github.com/billyke2000s/moviehub/actions/runs/40316308177)
completed all eight shards. All **33 enabled movie websites** were queried; the other five enabled
websites support series only. Three full catalogue identities were tried until one returned links.
The packaged Python adapter, Java conversion, session relay and real LibVLC played **every returned
source separately**, with fallback disabled.

There were **21 returned sources: 18 passed decoding, plausible duration and midpoint playback**,
two Levidia alternatives failed to decode within the audit limit, and one TVMovieFlix film decoded
but had no independently verified expected runtime, so it remains unverified. Seven websites had
at least one fully verified source. No tested source established 4K playback.

A pass needs decoded frames, a duration within 15% of the reference runtime (Inception 148 minutes;
Night of the Living Dead 96 minutes), and new decoded frames near the midpoint. It is a bounded
sample, not whole-film playback or visual identification. It does not measure long-session
buffering or prove operation on the owner's TV. Actual dimensions can start below a manifest's
advertised maximum because HLS adapts its quality; a zero dimension means that snapshot did not
expose the track dimensions, not a 4K claim.

`no_matches` does not establish closure or a broken scraper: a niche catalogue may not contain
any of the three test films. HTTP 403 and DNS/TLS failures also do not establish closure. Browser
challenges and unsupported downstream hosts remain unresolved; the report does not call them
working. The separate 63-site website audit contains catalogue/parking observations.

| Website ID                  | Returned sources | Fully verified | Lookup outcomes                |
| --------------------------- | ---------------: | -------------: | ------------------------------ |
| free99:anymovies_co         |                1 |              1 | complete                       |
| free99:bnwmovies_com        |                2 |              2 | complete, no_matches           |
| free99:fzmovies_live        |                4 |              4 | complete                       |
| free99:levidia_ch           |                6 |              4 | complete                       |
| free99:naijavault_com       |                0 |              0 | no_matches                     |
| free99:seriesloaded_ng      |                0 |              0 | ValueError, no_matches         |
| free99:stagatv_com          |                0 |              0 | no_matches                     |
| free99:tvseries_in          |                0 |              0 | no_matches                     |
| scrubs:bluray7_com          |                0 |              0 | http_403, no_matches           |
| scrubs:doomovies_ga         |                0 |              0 | http_403                       |
| scrubs:gdriveplayer_us      |                0 |              0 | no_matches                     |
| scrubs:goojara_club         |                0 |              0 | no_matches                     |
| scrubs:goojara_to           |                0 |              0 | http_403                       |
| scrubs:iwaatch_com          |                2 |              2 | complete                       |
| scrubs:m4uhd_tv             |                1 |              1 | complete                       |
| scrubs:moviesnipipay_me     |                0 |              0 | unsupported_host               |
| scrubs:myflixer_it          |                0 |              0 | ValueError, no_matches         |
| scrubs:okmov_to             |                0 |              0 | no_matches                     |
| scrubs:pressplay_top        |                0 |              0 | http_404, rejected_media       |
| scrubs:projectfreetv_cyou   |                0 |              0 | http_403                       |
| scrubs:rarefilmm_com        |                0 |              0 | no_matches                     |
| scrubs:series9movies_com    |                0 |              0 | http_403                       |
| scrubs:series9mx_pro        |                0 |              0 | no_matches                     |
| scrubs:tubitv_com           |                4 |              4 | complete, no_matches           |
| scrubs:tvids_net            |                0 |              0 | gaierror                       |
| scrubs:tvmovieflix_com      |                1 |              0 | complete, http_403, no_matches |
| scrubs:tvseries_video       |                0 |              0 | gaierror, no_matches           |
| scrubs:upmovies_net         |                0 |              0 | ConnectionError, SSLError      |
| scrubs:vumoo_mx             |                0 |              0 | ConnectionError, no_matches    |
| scrubs:watchseries_cyou     |                0 |              0 | http_403                       |
| scrubs:watchserieshd_stream |                0 |              0 | SSLError                       |
| scrubs:watchtvseries_net    |                0 |              0 | gaierror                       |
| scrubs:winnoise_com         |                0 |              0 | ValueError, no_matches         |

The companion JSONL records every query, individual source result and final shard marker. It
contains provider labels and measurements, with no signed media URLs, cookies, player tokens or
account data. Source uniqueness uses the same provider-plus-URI key as the app's selection window;
all 21 returned source entries retained distinct keys.

## Additional source research

The Nuvio Streams public instance redirected to a deprecated service. Eletroclay returned 503,
and the referenced Videasy seed endpoint returned 404. These were not added from catalogue claims.
A live 4KHDHub search/detail flow exposed HubCloud mirrors. A bounded 4 MiB sample from one actual
mirror had HEVC video at **3840×1600**, English and Hindi audio, an 8,888.299-second duration, and
three frames decoded locally. That is evidence of a real cropped 4K film file, but not yet proof of
reliable Android streaming or midpoint seeking. Native 4KHDHub/Vixsrc integration remains a
separate staged expansion until its Android audit is reviewed.

Fresh homepage checks confirmed anitaku.pe parking, goojara.club and okmov.to GoDaddy sale
listings, and series9mx.pro redirection to an unrelated gambling service. The expansion excludes
these four from ordinary discovery while retaining their auditable modules. Fresh Upmovies and
WatchSeriesHD errors were inconclusive, so no new retirement decision was inferred from them.

The first native Android audit resolved seven 4KHDHub links but none decoded; Vixsrc passed
decoding, duration and midpoint playback. Follow-up requests isolated a concrete transport
restriction: no Range or `bytes=0-` returned 403, while `bytes=0-65535` returned 206 with the
correct Matroska bytes and full file size. `ProviderFileRanges` addresses that observed difference
by joining finite requests without changing playback offsets. Its final Android results remain
pending; the first audit is not presented as seven working 4K links.

The first range-aware Android pass played three new 1080p files and Vixsrc. One actual
3840×1600 file decoded and sought but recorded no decoded audio buffers, so its audio remained
unverified. Four 1080p files were incorrectly badged 4K because the old UI searched the provider
name for the substring `4k`; the badge logic now uses the release resolution instead. Follow-up
requests to the SDR 4K edition returned 206 through 4 MiB and 403 at 8 MiB, so the relay's parts
are capped at the observed 4 MiB limit. The final audit also measures played audio buffers,
which distinguishes valid passthrough from missing audio. Results remain pending.

## Final focused pass

[Focused Android audit on f5b4e63](https://github.com/billyke2000s/moviehub/actions/runs/40323734300)
completed with the 4 MiB cap, corrected quality badges and decoded/output audio counters.
Its companion `NATIVE_VOD_PLAYBACK_AUDIT_2026_10_26.jsonl` records every result. Levidia returned
six links: four verified and two did not decode. 4KHDHub returned three 1080p links: one verified,
one failed to decode and one decoded but did not verify midpoint playback. Vixsrc verified.
Rarefilmm was retested with its actual catalogue film Anna (1967), TMDB 45486, reference runtime
87 minutes: decoded video/audio and midpoint playback verified. This avoids treating absence of
three mainstream test films as a scraper failure for that niche collection.

Seven of eleven returned links in this focused pass verified; it did not establish a reliable
4K stream with verified audio. An earlier real 3840×1600 file did decode and seek, but its audio
was unverified. Availability varied across requests; do not relabel these failures as successes,
and do not infer permanent site closure from them. Socket exceptions may also result from a player
closing a no-longer-needed range during seeking, so exception presence alone does not reverse a
successful decode/duration/seek/audio measurement.

[Full Android CI](https://github.com/billyke2000s/moviehub/actions/runs/40323741854) and
[standalone engine verification](https://github.com/billyke2000s/moviehub/actions/runs/40323741898)
passed on the same product-code head. The final documentation-only commit records those results
and prepares the numbered release notes without changing the APK source.
