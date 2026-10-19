#!/usr/bin/env python3
"""Diagnose a single player from each SportHD host without logging URLs or secrets."""
import json
import re
from collections import Counter
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from sporthd import SCHEDULES, MAX_BYTES, is_direct_hls
from sporthd_resolver import resolve, extract_player_url

HOSTS = ("kora.st", "nexa.st", "vertex.st")
MAX_PLAYER_BYTES = 1024 * 1024


def select_samples(payload):
    data = json.loads(payload)
    matches = data.get("matches", []) if isinstance(data, dict) else data
    found = {}
    for event in matches:
        if not isinstance(event, dict):
            continue
        for channel in event.get("channels") or []:
            if not isinstance(channel, dict):
                continue
            for link in channel.get("links") or []:
                if not isinstance(link, str):
                    continue
                host = (urlsplit(link).hostname or "").lower()
                if host in HOSTS and host not in found:
                    found[host] = link
    return found


def make_diagnostic_fetch(stats):
    def fetch(url, referer):
        path = urlsplit(url).path
        stage = "player_api" if path.endswith("/api/player.php") else "embed_page"
        stats["stages"][stage] += 1
        req = Request(url, headers={"User-Agent": "Mozilla/5.0", "Referer": referer})
        try:
            with urlopen(req, timeout=10) as response:
                raw = response.read(MAX_PLAYER_BYTES + 1)
                mime = response.headers.get_content_type()
                stats["responses"].append({"stage": stage, "status": response.status,
                                           "mime": mime, "bytes": len(raw)})
        except HTTPError as exc:
            stats["responses"].append({"stage": stage, "status": exc.code})
            raise
        if len(raw) > MAX_PLAYER_BYTES:
            raise ValueError("Player page exceeds size limit")
        body = raw.decode("utf-8", errors="replace")
        try:
            obj = json.loads(body)
            if isinstance(obj, dict):
                # Only known structural key names, never values or raw response.
                stats["json_shapes"].append({"stage": stage, "src_present": bool(obj.get("src")), "srcBase_present": bool(obj.get("srcBase")), "keys": sorted(
                    k for k in obj if k in ("url", "src", "srcBase", "file", "source",
                                             "error", "message", "status", "data", "iframe"))})
        except ValueError:
            stats["html_shapes"].append({"stage": stage,
                                         "iframe": "<iframe" in body.lower(),
                                         "hls": bool(extract_player_url(body, url)),
                                         "clappr": "clappr" in body.lower(),
                                         "videojs": "videojs" in body.lower(),
                                         "ppcfg": "ppcfg" in body.lower(),
                                         "source_key": bool(re.search(r"\\bsource\\s*:", body)),
                                         "src_key": bool(re.search(r"\\bsrc\\s*:", body)),
                                         "srcbase_key": "srcBase" in body,
                                         "hls_literal": ".m3u8" in body,
                                         "script_count": len(re.findall(r"<script\\b", body, re.I)),
                                         "player_config": "PP_CLAPPR_CONFIG" in body})
        return body
    return fetch


def main():
    req = Request(SCHEDULES[0], headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"})
    with urlopen(req, timeout=20) as response:
        raw = response.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("Schedule too large")
    samples = select_samples(raw)
    print("SportHD player probe: sample size", len(samples))
    for host in HOSTS:
        url = samples.get(host)
        if not url:
            print(host + ": no sample")
            continue
        stats = {"stages": Counter(), "responses": [], "json_shapes": [], "html_shapes": []}
        try:
            stream = resolve(url, fetch=make_diagnostic_fetch(stats))
            outcome = "HLS extracted (playback unverified)" if is_direct_hls(stream) else "no HLS extracted"
        except Exception as exc:
            outcome = "resolver error " + type(exc).__name__
        print(host + ": " + outcome + " " + json.dumps(stats, default=dict, sort_keys=True))
    # Never print stream URLs, player HTML, query tokens, or response bodies.


if __name__ == "__main__":
    main()
