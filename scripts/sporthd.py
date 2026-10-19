"""SportHD event adapter: exports directly playable HTTP(S) HLS URLs only.

Based on the event/channel schema used by Bugatsinho's SportHD Kodi add-on.
Embed pages, Kodi plugin URLs, DRM and session-gated links are not converted.
"""
import json
import re
from datetime import datetime, timezone
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from sporthd_resolver import resolve as resolve_embed, verified_hls

SCHEDULES = ("https://tvsport.guide/proxy.php?source=index",)
MAX_BYTES = 8 * 1024 * 1024
HLS = re.compile(r"\.m3u8(?:$|[?#])", re.I)


def is_direct_hls(url):
    if not isinstance(url, str):
        return False
    parts = urlsplit(url.strip())
    return parts.scheme in ("https", "http") and bool(parts.hostname) and bool(HLS.search(parts.path))


def diagnose(payload, now_ms=None):
    """Inspect all event shapes without logging sensitive URLs or tokens."""
    data = json.loads(payload) if isinstance(payload, (str, bytes)) else payload
    matches = data.get("matches", []) if isinstance(data, dict) else data
    if not isinstance(matches, list):
        raise ValueError("Invalid matches data")
    now_ms = now_ms if now_ms is not None else int(datetime.now(timezone.utc).timestamp() * 1000)
    counts = dict(events=len(matches), live=0, upcoming=0, expired=0,
                  missing_time=0, channel_dicts=0, channel_names=0,
                  direct_hls=0, embeds=0, other_links=0,
                  events_with_channels=0, events_without_channels=0,
                  channel_dicts_with_links=0, channel_dicts_without_links=0,
                  additional_links_events=0, embed_hosts={}, event_keys={},
                  channel_keys={}, first_start_ms=None, last_start_ms=None)
    for event in matches:
        if not isinstance(event, dict):
            continue
        for key in event:
            counts["event_keys"][key] = counts["event_keys"].get(key, 0) + 1
        channels = event.get("channels") or []
        if isinstance(channels, list) and channels:
            counts["events_with_channels"] += 1
        else:
            counts["events_without_channels"] += 1
        if event.get("additionalLinks"):
            counts["additional_links_events"] += 1
        for channel in channels if isinstance(channels, list) else []:
            if isinstance(channel, str):
                counts["channel_names"] += 1
                continue
            if not isinstance(channel, dict):
                counts["other_links"] += 1
                continue
            counts["channel_dicts"] += 1
            for key in channel:
                counts["channel_keys"][key] = counts["channel_keys"].get(key, 0) + 1
            links = channel.get("links") or []
            if links:
                counts["channel_dicts_with_links"] += 1
            else:
                counts["channel_dicts_without_links"] += 1
            for link in links if isinstance(links, list) else []:
                if is_direct_hls(link):
                    counts["direct_hls"] += 1
                elif isinstance(link, str) and urlsplit(link).scheme in ("http", "https"):
                    counts["embeds"] += 1
                    host = urlsplit(link).hostname or "unknown"
                    counts["embed_hosts"][host] = counts["embed_hosts"].get(host, 0) + 1
                else:
                    counts["other_links"] += 1
        try:
            start = int(event["startTimestamp"])
            if start < 10**11:
                start *= 1000
            duration = max(1, min(int(event.get("duration", 180)), 1440))
        except (KeyError, ValueError, TypeError):
            counts["missing_time"] += 1
            continue
        counts["first_start_ms"] = start if counts["first_start_ms"] is None else min(start, counts["first_start_ms"])
        counts["last_start_ms"] = start if counts["last_start_ms"] is None else max(start, counts["last_start_ms"])
        if now_ms < start:
            counts["upcoming"] += 1
        elif now_ms > start + duration * 60000:
            counts["expired"] += 1
        else:
            counts["live"] += 1
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
                try:
                    stream = url if is_direct_hls(url) else resolver(url)
                    playable = is_direct_hls(stream) or (isinstance(stream, str) and verified_hls(stream))
                except (OSError, ValueError, TypeError):
                    continue
                if not playable or stream in seen:
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
