"""SportHD event adapter: exports directly playable HTTP(S) HLS URLs only.

Based on the event/channel schema used by Bugatsinho's SportHD Kodi add-on.
Embed pages, Kodi plugin URLs, DRM and session-gated links are not converted.
"""
import json
import re
from datetime import datetime, timezone
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from sporthd_resolver import resolve as resolve_embed

SCHEDULES = ("https://tvsport.guide/proxy.php?source=index",)
MAX_BYTES = 8 * 1024 * 1024
HLS = re.compile(r"\.m3u8(?:$|[?#])", re.I)


def is_direct_hls(url):
    if not isinstance(url, str):
        return False
    parts = urlsplit(url.strip())
    return parts.scheme in ("https", "http") and bool(parts.hostname) and bool(HLS.search(parts.path))


def diagnose(payload, now_ms=None):
    """Count event/channel/link shapes without exposing URLs or tokens in logs."""
    data = json.loads(payload) if isinstance(payload, (str, bytes)) else payload
    matches = data.get("matches", []) if isinstance(data, dict) else data
    if not isinstance(matches, list):
        raise ValueError("Invalid matches data")
    now_ms = now_ms if now_ms is not None else int(datetime.now(timezone.utc).timestamp() * 1000)
    counts = dict(events=len(matches), live=0, upcoming=0, expired=0,
                  missing_time=0, channel_dicts=0, channel_names=0,
                  direct_hls=0, embeds=0, other_links=0)
    for event in matches:
        if not isinstance(event, dict):
            continue
        try:
            start = int(event["startTimestamp"])
            if start < 10**11:
                start *= 1000
            duration = max(1, min(int(event.get("duration", 180)), 1440))
        except (KeyError, ValueError, TypeError):
            counts["missing_time"] += 1
            continue
        if now_ms < start:
            counts["upcoming"] += 1
            continue
        if now_ms > start + duration * 60000:
            counts["expired"] += 1
            continue
        counts["live"] += 1
        for channel in event.get("channels") or []:
            if isinstance(channel, str):
                counts["channel_names"] += 1
                continue
            if not isinstance(channel, dict):
                continue
            counts["channel_dicts"] += 1
            for link in channel.get("links") or []:
                if is_direct_hls(link):
                    counts["direct_hls"] += 1
                elif isinstance(link, str) and urlsplit(link).scheme in ("http", "https"):
                    counts["embeds"] += 1
                else:
                    counts["other_links"] += 1
    return counts


def extract_events(payload, now_ms=None, resolver=None):
    data = json.loads(payload) if isinstance(payload, (str, bytes)) else payload
    matches = data.get("matches", []) if isinstance(data, dict) else data
    if not isinstance(matches, list):
        raise ValueError("Invalid matches data")
    now_ms = now_ms if now_ms is not None else int(datetime.now(timezone.utc).timestamp() * 1000)
    resolver = resolver or (lambda url: None)
    result = []
    seen = set()
    for event in matches:
        if not isinstance(event, dict):
            continue
        start = event.get("startTimestamp")
        duration = event.get("duration", 180)
        try:
            start = int(start)
            if start < 10**11:
                start *= 1000
            duration = max(1, min(int(duration), 1440))
        except (TypeError, ValueError):
            continue
        if not start <= now_ms <= start + duration * 60_000:
            continue
        teams = [str(event.get(k) or "").strip() for k in ("team1", "team2")]
        title = " vs ".join(t for t in teams if t) or str(event.get("league") or event.get("sport") or "SportHD event")
        title = re.sub(r"[\\r\\n\\\"]", " ", title)
        for channel in event.get("channels") or []:
            if not isinstance(channel, dict):
                continue
            label = str(channel.get("name") or "").strip()
            for url in channel.get("links") or []:
                stream = url if is_direct_hls(url) else resolver(url)
                if not is_direct_hls(stream) or stream in seen:
                    continue
                seen.add(stream)
                safe_label = re.sub(r"[\\r\\n\\\"]", " ", label)
                result.append((f'#EXTINF:-1 group-title="SportHD" tvg-name="{title}",{title} - {safe_label}', stream))
    return result


def fetch_channels():
    errors = []
    for endpoint in SCHEDULES:
        try:
            request = Request(endpoint, headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"})
            with urlopen(request, timeout=20) as response:
                raw = response.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                raise ValueError("Schedule exceeds size limit")
            print("SportHD diagnostics:", json.dumps(diagnose(raw), sort_keys=True))
            return extract_events(raw, resolver=resolve_embed)
        except Exception as exc:
            errors.append(f"{endpoint}: {exc}")
    raise RuntimeError("; ".join(errors))
