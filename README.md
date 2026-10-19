# MovieHub FreeLinks

External provider discovery and playlist publishing for [MovieHub](https://github.com/billyke2000s/moviehub).

## Currently implemented

- **The Loop / Pluto TV:** reads Pluto's live channel guide and exports direct HTTPS HLS URLs.
- Per-source failure isolation; invalid or unsupported Kodi `plugin://` entries are ignored.
- Duplicate URL removal, atomic publication, and preservation of the last successful output.
- Scheduled refresh every six hours via GitHub Actions.
- Offline regression tests.

Published playlist (after the first successful workflow run):
https://raw.githubusercontent.com/billyke2000s/moviehub-freelinks/main/output/theloop.m3u

This is **not** a complete port of The Loop or SportHD. Other source adapters must be implemented and tested separately. Stream availability and regional restrictions are not guaranteed. The Android app downloads M3U files; it never executes Python from this repository.

## Development

```bash
python3 -m unittest discover -s scripts -p 'test_build_loop_playlist.py' -v
python3 scripts/build_loop_playlist.py
```
