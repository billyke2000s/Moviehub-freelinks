# Masqueradarr provider experiments

**Experimental code, preserved here at the owner's request. Not connected to MovieHub or the published FreeLinks playlists.**

The unfinished multi-provider work originally lived in an unmerged Android working copy. Workspace maintenance removed that copy before it was committed. These Python adapters reconstruct its provider discovery/resolution mechanisms to fit this repository; they are not a byte-for-byte copy of the Java changes. Existing MovieHub releases and existing FreeLinks adapters/outputs are unchanged by this branch.

## Included experiments

| Adapter | Implemented | Dependencies / limits |
| --- | --- | --- |
| Pluto TV | Anonymous regional bootstrap, catalogue, play-time HLS URL, ten-minute session cache | Actual provider-reported country; ads retained; this does not replace `scripts/loop_pluto.py` |
| Samsung TV Plus | Region-selected catalogue, DRM exclusion, inline schedule, fresh redirect at playback | Community `i.mjh.nz` catalogue and `jmp2.uk` redirect service; network region comes from anonymous Pluto bootstrap |
| Free Live Sports | Public/live channels, inline guide, fresh per-play macro expansion | No tracking consent or invented geographic IP; unknown macros fail |
| STIRR | Public catalogue, per-play POST/nonce, DRM and non-HLS rejection | Native STIRR guide is not implemented |
| Whale TV+ | Anonymous browser bootstrap, provider-reported country, per-play macros, batched 24-hour guide | Public browser application identifier; session tokens remain in memory |

Each source exposes `catalogue()` and `resolve(channel_id)`. Stable `provider:id` metadata keys are separate from expiring media URLs. `Channel.metadata()` excludes stream state; object representations hide it. `resolve()` returns a URL and required playback headers **in memory only**.

The transport restricts requests to HTTPS, has eight-second socket timeouts, four redirect attempts and eight-MiB limits before/after gzip decoding. Custom headers never follow a cross-authority redirect. Basic literal/local-target validation is included, but this is a local experimental client, **not a hardened public resolver service**; DNS rebinding protection and a deployment threat model would be required before exposing it remotely.

## Run tests

Python standard library only:

```sh
python3 -m unittest discover -s experiments/masqueradarr/tests -v
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

There are 18 new deterministic fixture tests plus the existing 15 tests. A passing fixture suite does not prove provider playback.

Opt-in network probes print only counts and status, never stream URLs, tokens or response bodies:

```sh
python3 experiments/masqueradarr/probe.py pluto --check-hls
python3 experiments/masqueradarr/probe.py samsung --check-hls
python3 experiments/masqueradarr/probe.py freelivesports --check-hls
python3 experiments/masqueradarr/probe.py stirr --check-hls
python3 experiments/masqueradarr/probe.py whale --check-hls
```

These make real requests. `--check-hls` verifies one channel's master playlist; it does not decode video or prove all channels work. There is no default network execution and no scheduled publication.

## Evidence from the earlier Android experiment

On 19 October 2026 the actual Java adapters discovered 252 compatible UK Samsung entries, 108 Free Live Sports entries, 123 STIRR entries and 170 GB Whale entries; each first-channel resolve returned HLS. The released Pluto implementation had discovered 187 GB entries. Counts precede cross-provider deduplication and are not individual playback counts.

Three downloaded segments from one sample per added provider decoded for six seconds with FFmpeg, exit 0: Samsung TV Plus, Free Live Sports (Overtime), STIRR (Drone TV), Whale (Red Bull TV). The Sports and Whale samples emitted timestamp-continuity warnings. These were desktop media decodes, not Android TV playback. That evidence applies to the earlier implementation; reconstruction-specific network checks are recorded separately in the PR.

## Other platforms: unresolved, not advertised as working

- **Plex:** the corrected anonymous POST to `clients.plex.tv` returned a session and 691 catalogue entries. Multiple manifest requests still returned 404, including after successful tune with a consistent client identity. No playback adapter is included here.
- **Xumo:** a historical market catalogue responded, while its actual web client returned a geo-block page in the test environment. Catalogue access is not playback entitlement.
- **Roku:** normal navigation seeded cookies, but the CSRF/EPG endpoints returned HTML instead of JSON. No challenge or access restriction workaround was used.
- **LG:** the required web headers, including wildcard Accept and actual GB country, still returned 500.
- **DistroTV:** the known feed returned 503, including with its Android client User-Agent.
- **Vidaa:** configuration returned 503 even with English language and Android client headers.
- **Vizio:** public catalogue discovery was not playback verification; the intended mobile flow has account/region restrictions.
- **Tubi:** public live-page metadata was found, but the earlier investigation had not completed current anonymous session, regional entitlement and genuine playback verification.
- **TCL:** no generic UK flow was verified; do not claim an Ohio/US device identity for a UK client.
- **Other authenticated/scraping adapters:** no credentials, protected session capture, DRM or access-control workarounds are included.

These are concrete remaining investigation points, not proof that every provider requires a server. No claim of complete Masqueradarr parity is made.

## Next integration decisions

A static GitHub playlist cannot reliably supply every expiring stream. Before enabling these in published outputs, choose and test a delivery mechanism that resolves on play and conveys provider headers without publishing anonymous session tokens. This branch does not deploy a server, enable the disabled MovieHub FreeLinks integration, modify Android settings/player code, publish an APK or change existing scheduled jobs.

MovieHub keeps its existing guide layout, channel numbering, favourites, player and fullscreen/mini-player handoff. Those UI concerns belong to the Android repository and need separate integration tests when this experiment is connected.

## Sources and licensing boundary

Mechanisms were investigated using the last public [Masqueradarr snapshot](https://github.com/TheBinaryNinja/masqueradarr/tree/3b860294ecb4a9ebca8819adfc061162fbbb7f99) (MIT, BinaryNinja 2025–2026), current public provider web clients and actual responses. The current private adapter submodule was unavailable and none of its code is included. This reconstruction does not bundle Node.js, MongoDB, Rust or Chromium. No signed URLs, session responses, credentials or signing keys are committed.
