# AI / New Chat Handoff

## App rewrite baseline — 28 October 2026

The owner requested the whole MovieHub codebase here to start a rewrite. The exact tracked source
tree from `billyke2000s/moviehub` commit `fae35002a60879b213011c85322329c2bc3728cc` is in
`moviehub/`. It contains the 1.0.12 code restored by the 1.0.16 rollback, with all original project
documentation, tests, build files, provider sources and license notices. No application code was
changed during the copy. Chipset-packaging changes were paused and are not included.

Work on the app copy inside this repository for the rewrite; keep the original MovieHub repository
unchanged unless separately instructed. Read `moviehub/AGENTS.md` before app work. Existing root
playlist tooling and outputs remain intact. Package identity, update URLs and release setup in the
copied project still refer to the original app, so this copy has not established a separate release
channel. Nested workflows are inert source copies. Root `verify-moviehub-copy.yml` runs existing
offline checks without publishing an APK or modifying the original repo.

The older converter-only boundaries and architecture notes below describe the original FreeLinks
project. They do not forbid the owner's newly authorized work on this app copy.

Copy verification: all 814 copied tracked files produce Git tree
`bfb646212811139a66e6672d1cbabf03e9dc7f94`, identical to the source commit. Existing Java core checks,
100 app Python fixture tests, three provider-engine Node tests and 15 FreeLinks converter tests
passed locally. Android APK/lint/device checks were not rerun for this unchanged source copy.

## User intent
Continue the existing **MovieHub-FreeLinks** repo, which contains source conversion/discovery outside the main MovieHub Android TV repository. The long-term goal covers **Live TV, Movies, and Series**, but only Live TV adapters exist so far. Do not restart or replace existing working code.

Repositories:
- Converter: https://github.com/billyke2000s/moviehub-freelinks
- Android app: https://github.com/billyke2000s/moviehub

## Hard boundaries
- **Work only in MovieHub-FreeLinks** until explicitly authorised to edit the Android app.
- Keep the existing names **SportHD** and **The Loop** for diagnostics. Numbered source labels are a future user-requested change, not permission to rename now.
- Do not claim that movie/series adapters or direct SportHD playback are complete.
- Do not bypass DRM, authentication, CAPTCHA, geo/access controls or publish private stream tokens.
- Do not conflate GitHub Actions passing with playback verification.

## Verified project state (19 Oct 2026)
- `scripts/loop_pluto.py`, `scripts/build_loop_playlist.py` publish `output/theloop.m3u`. A previous successful run generated hundreds of channel entries; individual playback not comprehensively verified.
- SportHD adapter files: `sporthd.py`, `sporthd_resolver.py`, `probe_sporthd.py`, `build_sporthd.py`, and tests.
- SportHD schedule: `https://tvsport.guide/proxy.php?source=index`. It yielded event/channel dictionaries and embedded hosts `kora.st`, `nexa.st`, `vertex.st`.
- Player API pages have returned HTTP 200 and iframe URLs. Some `ppcfg=1` JSON responses included nonempty `src` and `srcBase` pointing to PHP endpoints. The sampled source endpoints returned **HTTP 404**, not HLS video. No confirmed direct playable SportHD URLs yet.
- SportHD workflow refreshes every 15 minutes and may legitimately publish an empty M3U. The workflow also probes one player per host. The Loop workflow refreshes separately.
- Last known test count was nine, before subsequent test additions; always inspect the latest CI status rather than trusting this historical count.
- A previous user upload `repository.bugatsinho-2.8.zip` supplied the Kodi add-on repository reference. Check whether it is still available before claiming to have inspected it.

## Next engineering steps
1. Inspect the **current** repo tree, README, workflow runs, logs, output playlists and tests. Establish a baseline.
2. Fix any failing tests or publishing regressions before expanding functionality.
3. For SportHD, sample **currently live** events (not just upcoming ones). Confirm event time windows and selected channel links.
4. Inspect public player configuration and source response shape with bounded timeouts; never print full signed URLs. Check manifest body begins with `#EXTM3U` and if appropriate validate segment availability. Some endpoints use `.php`, not `.m3u8`.
5. If no live event is active or source endpoints return 404, report that honestly; don't invent URLs or publish embeds as direct streams.
6. Once direct streams are verified, provide a small testable set to the user, with expiry/region caveats, **only if sharing is appropriate and doesn't expose credentials**.
7. For future movies/series, first implement the versioned source-result schema and fixtures/tests. Then add one independent legitimate provider adapter at a time. See `docs/ARCHITECTURE.md`.
8. Keep all provider logic out of the Android app until separately approved.

## Useful commands
```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/probe_sporthd.py
python3 scripts/build_sporthd.py
python3 scripts/build_loop_playlist.py
```

## Quality bar
- No regressions to working providers.
- Versioned, documented inputs/outputs for on-demand content.
- Deterministic offline tests and bounded network calls.
- Per-source failures isolated, outputs written atomically.
- Security: validate input URLs and redirects, prevent SSRF when adding remotely requested lookups, no secrets in logs.
- A provider is only called 'working' after actual stream/manifest testing; Android playback needs its own later device test.

When handing off again, update this document with verified current state, known bugs, test results and next steps.
