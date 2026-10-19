#!/usr/bin/env python3
"""Build a static, VLC-compatible M3U from explicitly listed public M3U feeds.

This does not execute The Loop Kodi add-on, resolve Kodi plugin:// links, or
attempt to bypass session-based/DRM streams. Only direct HTTP(S) stream URLs
are exported. A failed update never overwrites the last published playlist.
"""
import os
from loop_pluto import fetch_channels as fetch_pluto_channels
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "streams" / "loop-feeds.txt"
OUTPUT = ROOT / "output" / "theloop.m3u"
MAX_BYTES = 12 * 1024 * 1024


def fetch(url):
    if urlsplit(url).scheme != "https":
        raise ValueError("Feed must use HTTPS")
    req = Request(url, headers={"User-Agent": "MovieHub-PlaylistBuilder/1.0"})
    with urlopen(req, timeout=25) as response:
        data = response.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("Feed exceeds size limit")
    return data.decode("utf-8-sig", errors="replace")


def extract(text):
    pending = None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF:"):
            pending = line
        elif line.startswith("#"):
            continue
        elif line:
            if pending and urlsplit(line.split("|", 1)[0]).scheme in ("https", "http"):
                yield pending, line
            pending = None


def build():
    urls = [
        line.strip() for line in MANIFEST.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    if not urls:
        raise RuntimeError("No feeds configured; keeping previous playlist")
    lines = ["#EXTM3U"]
    seen = set()
    errors = []
    for url in urls:
        try:
            channels = fetch_pluto_channels() if url == 'loop:pluto' else list(extract(fetch(url)))
            if not channels:
                raise ValueError("No direct playable URLs")
            for meta, stream in channels:
                if stream not in seen:
                    seen.add(stream)
                    lines.extend((meta, stream))
            print(f"{url}: {len(channels)} streams")
        except Exception as exc:
            errors.append(f"{url}: {type(exc).__name__}: {exc}")
    for error in errors:
        print("WARNING: skipped feed: " + error)
    if not seen:
        raise RuntimeError("No usable streams from configured feeds; keeping previous playlist")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUTPUT.with_suffix(".m3u.tmp")
    tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.replace(tmp, OUTPUT)
    print(f"Published {len(seen)} unique streams")


if __name__ == "__main__":
    build()
