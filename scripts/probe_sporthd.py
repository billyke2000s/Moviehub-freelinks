#!/usr/bin/env python3
"""Probe one representative player per host, without publishing unverified URLs."""
import json
from collections import defaultdict
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from sporthd import SCHEDULES, MAX_BYTES, is_direct_hls
from sporthd_resolver import resolve

HOSTS = ("kora.st", "nexa.st", "vertex.st")


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
        try:
            stream = resolve(url)
            print(host + ": " + ("HLS URL extracted (playback unverified)" if is_direct_hls(stream) else "no HLS extracted"))
        except Exception as exc:
            print(host + ": resolver error " + type(exc).__name__)
    # No full stream URLs or auth tokens are logged or saved.


if __name__ == "__main__":
    main()
