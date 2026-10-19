#!/usr/bin/env python3
"""Import a user-authorised Masqueradarr M3U export without exposing tokens.

Run locally with MASQUERADARR_M3U_URL and OUTPUT_M3U set. Never publish
authenticated exports to a public repository.
"""
import os
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

MAX_BYTES = 12 * 1024 * 1024


def parse_m3u(content):
    """Keep only complete HTTP(S) entries, preserving EXTINF metadata."""
    if not content.lstrip("\ufeff \r\n").startswith("#EXTM3U"):
        raise ValueError("Not an M3U playlist")
    entries = []
    metadata = None
    seen = set()
    for raw in content.splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF:"):
            metadata = line
        elif line.startswith("#"):
            continue
        elif line:
            parsed = urlsplit(line)
            if metadata and parsed.scheme in ("http", "https") and parsed.netloc and line not in seen:
                entries.append((metadata, line))
                seen.add(line)
            metadata = None
    return entries


def fetch_export(url):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Export URL must use HTTPS and must not contain URL userinfo")
    request = Request(url, headers={"User-Agent": "MovieHub-FreeLinks/1.0"})
    with urlopen(request, timeout=20) as response:
        data = response.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("Playlist too large")
    return data.decode("utf-8-sig", errors="replace")


def build(source_url, output):
    entries = parse_m3u(fetch_export(source_url))
    if not entries:
        raise ValueError("No valid entries; previous output preserved")
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    try:
        with temporary.open("w", encoding="utf-8") as handle:
            handle.write("#EXTM3U\n")
            for metadata, stream in entries:
                handle.write(metadata + "\n" + stream + "\n")
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return len(entries)


if __name__ == "__main__":
    url = os.environ.get("MASQUERADARR_M3U_URL")
    destination = os.environ.get("OUTPUT_M3U")
    if not url or not destination:
        raise SystemExit("Set MASQUERADARR_M3U_URL and OUTPUT_M3U (private output only)")
    print(f"Imported {build(url, destination)} entries to private playlist")
