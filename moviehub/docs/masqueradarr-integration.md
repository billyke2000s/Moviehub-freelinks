# Masqueradarr integration — staged migration

Status: optional hosted connection implemented; see [native implementation and verification](NATIVE_FREE_SOURCES.md) for the server-free path.

## Existing functionality we can reuse

MovieHub already supports user-entered M3U playlists in Settings → Accounts → M3U playlist, with encrypted on-device storage, background channel import, 12-hour refresh, SQLite channel management, guide display and a separate LibVLC Live TV player. No new video player is needed for a standards-compliant Masqueradarr M3U.

## First smoke test (no code change needed)

1. Deploy your own Masqueradarr instance according to https://github.com/TheBinaryNinja/masqueradarr and configure sources you are authorised to access.
2. Open its web interface and obtain your own tokenised M3U export URL. **Never commit or paste it in a public issue.**
3. Confirm the playlist returns `#EXTM3U` and a channel entry. Check that the URL is reachable **from the TV box**, not only from the server.
4. In MovieHub, open Settings → Accounts → M3U playlist, enter the export URL and connect. Enable HTTP only if needed for a trusted local server.
5. Verify channels import, EPG alignment, playback, back-to-guide continuity, and channel switching on the TV box.
6. Test an unavailable source to ensure it doesn't break other channels. Do not retire the existing public lists or FreeLinks until this passes.

## Constraints

- Masqueradarr is a **running server**, not a static GitHub playlist converter. Resolve-on-play and proxy URLs require it to remain online and reachable.
- The M3U export may contain bearer-like tokens. Keep it private and store it only through MovieHub's existing encrypted provider account path.
- Do not embed personal server URLs, credentials, provider tokens or access-control workarounds in the app or public repository.
- Existing Live TV favourites, channel numbers and database migrations must remain intact.
- Films/series and Premiumize/Torrentio remain separate and unchanged.
- Upstream MIT source code can be reused with attribution if copying any of it; none is copied in this stage.
- Do not replace the existing Free-TV, iptv-org or The Loop sources before real-device validation.

## After the smoke test

If the current M3U integration works, prefer a clearly named optional Masqueradarr connection in Settings, backed by the **existing** M3U importer. Add tests for reconnect, empty exports, token redaction and server outages. Avoid a new playback stack or duplicate database.

## Acceptance criteria

- A valid user-authorised export connects without exposing credentials.
- Live TV loads channels and plays at least one authorised test stream.
- Going from fullscreen to mini-player doesn't reconnect the stream.
- An offline Masqueradarr server produces a clear error and preserves the last good channel list.
- Movies, series, Premiumize, Xtream and ordinary M3U continue to work.
- Automated checks pass; playback verified separately on an actual TV device.

## Related experimental work

The independent FreeLinks branch `feature/masqueradarr-bridge` contains an optional Python M3U importer. It is **not required** for the direct MovieHub-to-Masqueradarr approach and should not be merged just to enable this smoke test.

## Implementation update (19 October 2026)

- Accounts has a separate **Masqueradarr** connection, stored under encrypted key `masqueradarr`.
- It accepts a user-entered M3U export URL, checks it using the existing channel probe, and refreshes its own database source `masqueradarr`.
- Xtream and personal M3U remain separate and usable alongside Masqueradarr.
- FreeLinks' `loophost` entry in `FreeChannels.EXTRA` is commented out, not deleted. The independent FreeLinks repository remains unchanged.
- The existing Live TV player and movies/series providers were not changed.
- No Masqueradarr server URL is bundled. Real connectivity and playback are **not verified**.
- The app is **not released**. Run CI/build/lint and TV testing before merge or publishing.
