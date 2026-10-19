#!/usr/bin/env python3
"""Publish currently live, directly playable SportHD HLS links, when available."""
import os
from pathlib import Path
from sporthd import fetch_channels

OUTPUT = Path(__file__).resolve().parents[1] / "output" / "sporthd.m3u"

def build():
    channels = fetch_channels()
    lines = ["#EXTM3U"]
    for metadata, url in channels:
        lines.extend((metadata, url))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUTPUT.with_suffix(".m3u.tmp")
    tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.replace(tmp, OUTPUT)
    print(f"SportHD: published {len(channels)} direct HLS links")

if __name__ == "__main__":
    build()
