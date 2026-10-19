"""The Loop 7.9 Pluto TV guide adapter (no Kodi runtime required).

The Loop's plutotv.py obtains channels from the Pluto guide and reads
channels[].stitched.urls[].url. This adapter deliberately avoids its
per-episode URLs and Kodi-only headers.
"""
import datetime
import json
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

GUIDE = "https://service-channels.clusters.pluto.tv/v1/guide"
MAX_BYTES = 12 * 1024 * 1024


def channels_from_guide(payload):
    data = json.loads(payload) if isinstance(payload, str) else payload
    for channel in data.get("channels", []):
        name = str(channel.get("name") or "").strip()
        urls = channel.get("stitched", {}).get("urls") or []
        stream = next((entry.get("url") for entry in urls
                       if isinstance(entry, dict) and
                       urlsplit(entry.get("url") or "").scheme == "https"), None)
        if not name or not stream:
            continue
        logo = next((image.get("url") for image in channel.get("images", [])
                     if image.get("type") in ("logo", "colorLogoPNG")
                     and image.get("url")), "")
        safe_name = name.replace("\n", " ").replace("\r", " ").replace('"', "'")
        safe_logo = str(logo).replace('"', "")
        yield (f'#EXTINF:-1 tvg-logo="{safe_logo}" group-title="The Loop / Pluto TV",{safe_name}', stream)


def fetch_channels():
    now = datetime.datetime.now(datetime.timezone.utc)
    args = {
        "start": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "stop": (now + datetime.timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "deviceType": "web", "deviceMake": "Chrome", "appName": "web"
    }
    req = Request(GUIDE + "?" + urlencode(args), headers={"User-Agent": "Mozilla/5.0"})
    with urlopen(req, timeout=25) as response:
        raw = response.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("Pluto guide too large")
    return list(channels_from_guide(raw.decode("utf-8")))
