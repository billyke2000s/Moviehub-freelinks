# Architecture and Future Content Contracts

## Separation of responsibilities
**MovieHub Android TV**: TMDB browsing, posters, navigation, authentication/settings, player UI, favourites, resume and binge/next episode logic.

**MovieHub-FreeLinks**: independent source adapters, conversion, normalization, scheduled publishing and validation. The Android app should never execute Python or parse arbitrary provider-specific player scripts.

## Layout now and proposed growth
```text
scripts/                      # existing live-TV adapters and tests
streams/                      # live feed configuration
output/theloop.m3u            # published live channel playlist
output/sporthd.m3u            # experimental live sports playlist
docs/                         # handoff and future contract
# Future only; not yet implemented:
providers/live/               # live channel and sport adapters
providers/movies/             # movie lookup adapters
providers/series/             # episode lookup adapters
schemas/source-result-v1.json # shared validated result contract
tests/fixtures/               # offline fixtures without secrets
```
Do not move existing modules simply to match this proposal. Migrate incrementally with tests.

## Proposed versioned source result (NOT an implemented API)
For on-demand discovery, use JSON, not M3U. Example illustrating shape only:
```json
{
  "schema_version": 1,
  "content": {
    "type": "episode",
    "tmdb_id": 12345,
    "season_number": 2,
    "episode_number": 3
  },
  "generated_at": "2026-10-19T12:00:00Z",
  "sources": [
    {
      "provider_id": "example-provider",
      "source_id": "opaque-id",
      "url": "https://media.example.org/episode.m3u8",
      "format": "hls",
      "quality": null,
      "audio_languages": [],
      "subtitle_languages": [],
      "expires_at": null,
      "validation": "unverified"
    }
  ]
}
```
Example domains and IDs are illustrative, not actual sources. For movies, set `type: movie` and omit season/episode. Source candidates must be independently testable and deduplicated; the provider ID is internal, and presentation labels can later be changed without losing debugging information.

## Implementation decisions still required
- Choose how Android will fetch on-demand lookups: authenticated HTTPS endpoint, per-title generated JSON, or another secure distribution mechanism. **No server/API currently exists.**
- Define caching, rate limits, source ranking, expiry and how request headers can be conveyed safely.
- Validate IDs and URL targets; avoid SSRF, malicious redirects and leakage of credentials.
- Decide whether on-demand providers require user-authorised service accounts; never publish those credentials to GitHub.
- Build provider-specific adapters with fixtures and unit tests before integration.

## Live TV format
Live outputs are M3U files with `#EXTINF` metadata followed by direct media URLs. A playlist containing `#EXTM3U` alone is valid but has zero channels. Source URLs can expire, be geo-restricted or require headers. Test actual manifest responses before treating a source as playable.

## Release gates
1. Unit tests pass.
2. External calls are bounded; errors isolated and logged without secrets.
3. Format/schema validation and deduplication pass.
4. Real media endpoint verified where legally and technically accessible.
5. Android integration only after separate approval and on-device tests.


## Isolated FAST experiments

`experiments/masqueradarr/` contains standard-library Python provider clients with stable metadata identities and in-memory resolve-on-play results. It reconstructs the unfinished Android experiment in the repository chosen by the owner. This is an implemented local library/probe, not an implemented public resolver API. Nothing calls it from existing builders or publishing workflows. Its README documents source dependencies, guide support, tests, prior media evidence and unresolved platforms. A production delivery mechanism is still required before connecting expiring links to MovieHub.
