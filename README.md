# MovieHub FreeLinks

## MovieHub app source for the rewrite

The MovieHub rewrite project is in [`moviehub/`](moviehub/), copied
without source changes from
[`billyke2000s/moviehub@fae35002a60879b213011c85322329c2bc3728cc`](https://github.com/billyke2000s/moviehub/commit/fae35002a60879b213011c85322329c2bc3728cc).
That import is the 1.0.12 code restored by the 1.0.16 rollback. Subsequent ARM64/backend
optimization work is documented in [moviehub/STATUS.md](moviehub/STATUS.md).

- App code and build project: [`moviehub/android-tv-launcher/`](moviehub/android-tv-launcher/).
- Provider compiler and upstream source: [`moviehub/provider-engine/`](moviehub/provider-engine/).
- Start with [`moviehub/AGENTS.md`](moviehub/AGENTS.md),
  [`moviehub/ARCHITECTURE.md`](moviehub/ARCHITECTURE.md) and
  [`moviehub/DEVELOPMENT.md`](moviehub/DEVELOPMENT.md).
- Run app commands from `moviehub/`, for example
  `gradle -p android-tv-launcher assembleDebug lintDebug`.

The existing FreeLinks scripts, playlists and outputs below remain in place. The app copy is a
rewrite starting point, not a separately published app: its package identity and updater URLs still
match the original project. No APK or signing secrets were copied. Its workflows are preserved
under `moviehub/.github/workflows/` as source files; GitHub does not run nested workflows.
The root **Verify copied MovieHub project** workflow runs the existing offline app checks.

**External source discovery, conversion and publishing for [MovieHub Android TV](https://github.com/billyke2000s/moviehub).**

> **START HERE — NEW CHAT / AI HANDOFF:** Read this README and [docs/AI_HANDOFF.md](docs/AI_HANDOFF.md) before editing. Inspect the existing code and workflows. Continue the project; do not rewrite working adapters or change the Android app without explicit permission.

## What this repository does

This is a separate Python-based source-conversion and playlist-publication repository. The Android MovieHub app consumes published outputs over HTTPS; **it does not run these Python scripts**. Keeping converters separate prevents host-specific scraping/format changes from cluttering the Android application.

**Three distinct content types are planned:**

| Type | Purpose | Current status |
| --- | --- | --- |
| **Live TV** | Public channels and live sports event streams, with periodic refresh | The Loop/Pluto playlist exists; SportHD is experimental |
| **Movies** | Resolve sources by a movie's stable ID and return playable options and metadata | **Not implemented** |
| **Series** | Resolve by series ID + season + episode; preserve episode identity and source options | **Not implemented** |

**Do not represent planned movie/series support as working.** MovieHub's own TMDB catalogue, user accounts, playback history, watch progress and player UI belong to the Android app, not this repository.

## Current files

| File | Role |
| --- | --- |
| `scripts/loop_pluto.py` | Pluto TV guide adapter |
| `scripts/build_loop_playlist.py` | Build The Loop/Pluto-compatible M3U; isolate feed failures |
| `streams/loop-feeds.txt` | Inputs for the Loop builder |
| `output/theloop.m3u` | Published live-channel M3U |
| `scripts/sporthd.py` | SportHD schedule parsing and live-event filtering |
| `scripts/sporthd_resolver.py` | Experimental SportHD iframe/player resolver |
| `scripts/probe_sporthd.py` | Safe, limited host diagnostics; no stream tokens printed |
| `scripts/build_sporthd.py` | Publish only resolved live SportHD sources |
| `output/sporthd.m3u` | SportHD output; may contain **zero streams** |
| `.github/workflows/refresh-loop-playlist.yml` | Scheduled Loop refresh |
| `.github/workflows/refresh-sporthd.yml` | Scheduled SportHD refresh (every 15 minutes) |
| `scripts/test_*.py` | Offline regression tests |

### Published outputs

- [The Loop / Pluto M3U](https://raw.githubusercontent.com/billyke2000s/moviehub-freelinks/main/output/theloop.m3u)
- [SportHD M3U](https://raw.githubusercontent.com/billyke2000s/moviehub-freelinks/main/output/sporthd.m3u) — **may contain only `#EXTM3U`**; a passing workflow does not mean playback works.

The Loop/Pluto adapter has previously produced a playlist containing hundreds of entries; this is **not** a guarantee that every entry plays in every country or client. SportHD's sampled player pages and APIs have responded, but source endpoints returned HTTP 404 during testing and no direct streams were confirmed. SportHD and The Loop are retained as provider names for debugging.

## Design for movies and series (future work)

Use separate provider adapters and shared, versioned schemas; **do not shoehorn on-demand content into a live-channel M3U**.

- **Movie query:** `tmdb_id`, optional language/region, quality preferences; return independently labelled source candidates.
- **Series query:** `tmdb_id`, `season_number`, `episode_number`, optional language/region; resolve the **specific episode**, never just the show.
- **Source candidate:** provider identifier, content type, source URL, delivery format, quality, language, subtitles/audio metadata where known, expiration and validation status. Unknown fields stay unknown; never invent resolution/codec/bitrate.
- **Playback:** direct HTTPS streams when accessible and permitted, plus required non-secret request headers when supported by the client. No DRM, authentication, CAPTCHA, paywall or access-control bypass. Never publish private credentials or short-lived tokens into a public repo.
- **Failure handling:** timeouts, bounded responses, retries/backoff, per-provider isolation, duplicate removal and clear diagnostics. Return no result rather than an unverified link.
- **Discovery vs playback:** TMDB provides metadata, **not** a playable stream. Do not assume a movie/episode exists because TMDB lists it.
- **Integration:** define a versioned JSON contract first; the Android app may adopt it only after explicit approval. No live app modifications as part of this repo's work.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the proposed API/schema and rollout.

## Run locally

Python 3.12 is used in GitHub Actions. The current scripts use Python's standard library.

```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/build_loop_playlist.py
python3 scripts/probe_sporthd.py
python3 scripts/build_sporthd.py
```

These build/probe commands make network requests. Stream availability changes with time and region. Do not run aggressive parallel probes or log signed URLs.

## Operating rules

1. **Do not modify `billyke2000s/moviehub`** unless explicitly authorised. Keep converter work here.
2. Preserve working providers and outputs; add adapters independently, with offline tests.
3. Test real stream responses, not just HTTP 200 from an embed page. Treat all direct-link claims as unverified until the media endpoint is tested.
4. Never commit access tokens, secrets, user data or captured protected streams.
5. Do not silently rename providers to numbered sources; retain recognisable names until the user requests renaming.
6. Keep the README, architecture and AI handoff updated when functionality changes.

**Current priority:** obtain and verify a few legitimate, directly playable SportHD links from an actual live event before integrating SportHD into MovieHub. A successful CI run or a populated schedule is not proof of playable video.
