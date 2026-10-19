# Masqueradarr bridge (experimental)

Upstream: https://github.com/TheBinaryNinja/masqueradarr (MIT licence).
This is an **optional adapter** to an independently deployed Masqueradarr
instance, not a vendored copy or a running server. No changes to MovieHub Android.

Masqueradarr resolves some streams on demand through its own proxy. Importing
its M3U into FreeLinks does **not** recreate that resolver or make its proxy
publicly reachable. The service must remain running for proxy URLs to work.

## Local use

Configure your own authorised Masqueradarr installation and obtain its M3U
export URL through its own UI. Keep that URL private: it may include a token.

```bash
export MASQUERADARR_M3U_URL='https://your-own-server.example/private-export'
export OUTPUT_M3U='/private/path/masqueradarr.m3u'
python3 scripts/masqueradarr_bridge.py
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

**Never point OUTPUT_M3U into this public repository's output folder if
the playlist contains authenticated, signed, or subscription-specific URLs.**
Do not add export URLs, access keys or credentials to GitHub Actions secrets
unless explicitly designing a private publishing pipeline.

This bridge checks basic M3U structure, deduplicates links, requires HTTPS
for the export, bounds download size and preserves the previous output on
failure. It does **not** verify stream playback, filter private upstream
addresses, or provide an authentication-safe public relay. Treat imported
entries as unverified.

## Next steps

1. Run Masqueradarr privately with legitimate source access.
2. Verify actual playback and proxy reachability using a permitted channel.
3. Decide on a private endpoint for MovieHub, not a public token-bearing M3U.
4. Audit provider adapters and upstream licence obligations before copying code.

SportHD and The Loop workflows remain unchanged.
