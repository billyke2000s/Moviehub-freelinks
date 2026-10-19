"""Experimental Samsung TV Plus, Free Live Sports, STIRR and Whale TV+ clients."""
from __future__ import annotations

import json
import re
import time
import uuid
from urllib.parse import urlencode

from common import Channel, Programme, Source, channel, expand, timestamp
from pluto import Pluto
from transport import USER_AGENT, headers


class Samsung(Source):
    id, name = "samsung", "Samsung TV Plus"
    URL = "https://i.mjh.nz/SamsungTVPlus/.channels.json.gz"

    def __init__(self, http, **kwargs):
        super().__init__(http, **kwargs)
        self.region = Pluto(http, clock=self.clock)

    def fetch(self):
        country = self.region.session()["country"]
        return self.parse(json.loads(self.http.request(self.URL)), country)

    def parse(self, payload, country):
        # No silent selection of US or another region if this network country is unavailable.
        rows = payload["regions"][country]["channels"]
        if len(rows) > 5000:
            raise ValueError("Catalogue too large")
        for id, row in rows.items():
            if row.get("license_url") or row.get("drm") or not row.get("name"):
                continue
            schedule, programmes = row.get("programs", []), []
            for current, following in zip(schedule[:500], schedule[1:501]):
                try:
                    start, stop = int(current[0]) * 1000, int(following[0]) * 1000
                    if start > 0 and stop > start:
                        programmes.append(Programme(start, stop, str(current[1])))
                except (ValueError, TypeError, IndexError):
                    continue
            try:
                yield channel(self.id, id, row["name"], row.get("group", "Live TV"),
                              row.get("logo", ""), country, programmes,
                              "https://jmp2.uk/stvp-" + id)
            except ValueError:
                continue

    def stream(self, row):
        # The player follows a fresh community-service redirect; no signed target is persisted.
        return row.stream


class FreeLiveSports(Source):
    id, name = "freelivesports", "Free Live Sports"
    URL = ("https://epg.unreel.me/v2/sites/freelivesports/live-channels/public/"
           "081f73704b56aaceb6b459804761ec54?__site=freelivesports&__source=web")
    ORIGIN = "https://www.freelivesports.tv"

    def __init__(self, http, **kwargs):
        super().__init__(http, **kwargs)
        self.device = str(uuid.uuid4())

    def fetch(self):
        return self.parse(json.loads(self.http.request(self.URL, custom=headers(self.ORIGIN))))

    def parse(self, rows):
        if not isinstance(rows, list) or len(rows) > 5000:
            raise ValueError("Invalid catalogue")
        seen = set()
        for row in rows:
            id, stream = str(row.get("_id", "")), row.get("url", "")
            if (id in seen or row.get("accessType", "public") != "public"
                    or row.get("status", "live") != "live" or row.get("drm")
                    or not stream.startswith("https://") or ".m3u8" not in stream):
                continue
            programmes = []
            for item in (row.get("epg") or {}).get("entries", [])[:500]:
                start, stop = timestamp(item.get("start")), timestamp(item.get("stop"))
                if start > 0 and stop > start:
                    programmes.append(Programme(start, stop, str(item.get("title", "")),
                                                str(item.get("description", ""))))
            try:
                result = channel(self.id, id, row.get("name", ""), "Sports",
                                 row.get("thumbnail", ""), programmes=programmes, stream=stream)
            except ValueError:
                continue
            seen.add(id)
            yield result

    def stream(self, row):
        return expand(row.stream, {"DEVICE_ID": self.device, "DEVICE_MODEL": "web",
                                  "REF": self.ORIGIN + "/", "UA": USER_AGENT,
                                  "CB": int(time.time()), "LAT": "1", "GDPR": "1",
                                  "CONSENT_STRING": "", "US_PRIVACY": "1---", "IP": ""})

    def playback_headers(self):
        return {**super().playback_headers(), "Referer": self.ORIGIN + "/"}


class Stirr(Source):
    id, name = "stirr", "STIRR"
    URL = "https://stirr.com/api/videos/list/?categories=all_categories&content_type=4&no_limit=true"
    ORIGIN = "https://stirr.com"

    def fetch(self):
        return self.parse(json.loads(self.http.request(self.URL, custom=headers(self.ORIGIN))))

    def parse(self, payload):
        seen, count = set(), 0
        def walk(node, depth=0):
            nonlocal count
            if depth > 12:
                raise ValueError("Catalogue nesting too deep")
            if isinstance(node, list):
                for child in node:
                    yield from walk(child, depth + 1)
            elif isinstance(node, dict):
                if "videoid" in node and "title" in node:
                    count += 1
                    if count > 5000:
                        raise ValueError("Catalogue too large")
                    yield node
                else:
                    for child in node.values():
                        yield from walk(child, depth + 1)
        for row in walk(payload):
            id = str(row.get("videoid", ""))
            if (not re.fullmatch(r"\d{1,20}", id) or id in seen or row.get("drm")
                    or row.get("content_type", 4) != 4):
                continue
            categories = row.get("categories") or [{}]
            group = categories[0].get("category_name", "Live TV").removesuffix(" Live")
            logo = row.get("logo") or next(iter((row.get("thumbs") or {}).values()), "")
            try:
                result = channel(self.id, id, row["title"], group, logo)
            except ValueError:
                continue
            seen.add(id)
            yield result

    def stream(self, row):
        payload = json.loads(self.http.request(self.ORIGIN + "/api/v2/videos/" + row.id + "/playable",
                                               "POST", headers(self.ORIGIN)))
        result = payload["data"][0]
        if result.get("drm"):
            raise ValueError("DRM stream unsupported")
        media = result["media"]
        stream = media[0] if isinstance(media, list) else media
        if not isinstance(stream, str) or ".m3u8" not in stream:
            raise ValueError("Unsupported live stream")
        return expand(stream, {"vx_nonce": uuid.uuid4().hex})


class Whale(Source):
    id, name = "whale", "Whale TV+"
    BASE = "https://rlaxx.zeasn.tv/livetv/api"
    ORIGIN = "https://watch.whaletvplus.com"
    # Public browser app identifier verified in Whale's own web bundle, not an account credential.
    AUTH = BASE + "/v1/auth/access?uuid=1&apiToken=4ef13b5f3d2744e3b0a569feb8dde298&langCode=en"

    def __init__(self, http, **kwargs):
        super().__init__(http, **kwargs)
        self.device = str(uuid.uuid4())
        self._session, self._session_expires = None, 0

    def session(self):
        if self._session and self.clock() < self._session_expires:
            return self._session
        data = json.loads(self.http.request(self.AUTH, custom=headers(self.ORIGIN)))["data"]
        country = str(data["areaCode"]).lower()
        if not re.fullmatch("[a-z]{2}", country) or not data["token"]:
            raise ValueError("Invalid regional session")
        self._session = {"country": country, "token": data["token"]}
        self._session_expires = self.clock() + 600
        return self._session

    def fetch(self):
        session = self.session()
        url = self.BASE + "/device/browser/v1/category/channels?" + urlencode(
            {"langCode": "en", "countryCode": session["country"].upper()})
        payload = json.loads(self.http.request(url, custom={**headers(self.ORIGIN), "token": session["token"]}))
        return self.parse(payload, session["country"])

    def parse(self, payload, country):
        seen = set()
        for category in payload["data"]:
            group = str(category.get("ctgName", "Live TV"))
            if group == "All" or group.startswith("Featured"):
                continue
            for row in category.get("channels", []):
                id, stream = str(row.get("chlId", "")), row.get("chlUrl", "")
                if (id in seen or row.get("drm") or str(row.get("vodChannel", "0")) not in ("0", "False")
                        or not stream.startswith("https://") or ".m3u8" not in stream):
                    continue
                programmes, current = [], row.get("currentProgram") or {}
                start, stop = int(current.get("prgStm") or 0), int(current.get("prgEtm") or 0)
                if start > 0 and stop > start:
                    programmes.append(Programme(start, stop, str(current.get("prgTitle", "")),
                                                str(current.get("prgDesc", ""))))
                image = str(row.get("imageIdentifier", ""))
                logo = ("https://d3b6luslimvglo.cloudfront.net/images/79/rlaxximages/channels-rescaled/"
                        "icon-white/" + image + "_white.png") if re.fullmatch(r"[\w-]+", image) else ""
                try:
                    result = channel(self.id, id, row.get("chlName", ""), group, logo, country,
                                     programmes, stream)
                except ValueError:
                    continue
                seen.add(id)
                if len(seen) > 5000:
                    raise ValueError("Catalogue too large")
                yield result

    def stream(self, row):
        values = {"did": self.device, "session_id": str(uuid.uuid4()), "cachebuster": int(time.time() * 1000),
                  "dnt": "1", "lmt": "1", "gdpr": "1", "consent": "",
                  "country_2": self.session()["country"].upper(), "device_language": "en",
                  "width": "1920", "height": "1080", "livestream": "1", "user_agent": USER_AGENT,
                  "page_url": self.ORIGIN + "/"}
        # Whale's own browser client fills absent optional ad macros with an empty value.
        return expand(row.stream, values, percent=True, reject_unknown=False)

    def guide(self):
        catalogue, result = self.catalogue(), {}
        session = self.session()
        now, total = int(time.time() * 1000), 0
        for offset in range(0, len(catalogue), 10):
            batch = {row.id: row for row in catalogue[offset:offset + 10]}
            query = urlencode({"channelIds": ",".join(batch), "startTime": now, "endTime": now + 86400000})
            data = json.loads(self.http.request(self.BASE + "/device/browser/v1/epg?" + query,
                                               custom={**headers(self.ORIGIN), "token": session["token"]}))
            for row in data["data"]:
                id = str(row.get("chlId", ""))
                if id not in batch:
                    continue
                programmes = []
                for item in row.get("ptList", []):
                    start, stop = int(item.get("prgStm") or 0), int(item.get("prgEtm") or 0)
                    if start > 0 and stop > start and start < now + 86400000 and stop > now:
                        programmes.append(Programme(start, stop, str(item.get("prgTitle", ""))))
                        total += 1
                        if total > 50000:
                            raise ValueError("Guide too large")
                result[id] = tuple(programmes)
        return result


SOURCES = {source.id: source for source in (Pluto, Samsung, FreeLiveSports, Stirr, Whale)}
