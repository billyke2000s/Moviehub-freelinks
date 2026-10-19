"""Regional anonymous Pluto bootstrap/catalogue/resolve, reconstructed from the Android experiment."""
import json
import re
import time
import uuid
from urllib.parse import urlencode, urlsplit

from common import Source, channel
from transport import https_url


class Pluto(Source):
    id, name = "pluto", "Pluto TV"

    def __init__(self, http, **kwargs):
        super().__init__(http, **kwargs)
        self.client = str(uuid.uuid4())
        self._session = None
        self._session_expires = 0

    @staticmethod
    def endpoint(value):
        parts = urlsplit(https_url(value))
        if (not parts.hostname.endswith(".pluto.tv") or parts.port is not None
                or parts.path not in ("", "/") or parts.query):
            raise ValueError("Invalid Pluto endpoint")
        return value.rstrip("/")

    def session(self):
        if self._session and self.clock() < self._session_expires:
            return self._session
        query = urlencode({"appName": "web", "appVersion": "9.0.0", "deviceVersion": "145.0",
                           "deviceModel": "web", "deviceMake": "firefox", "deviceType": "web",
                           "clientModelNumber": "1.0.0", "serverSideAds": "true", "clientID": self.client})
        boot = json.loads(self.http.request("https://boot.pluto.tv/v4/start?" + query))
        country = boot["session"]["countryCode"].lower()
        if not re.fullmatch("[a-z]{2}", country) or not boot["sessionToken"]:
            raise ValueError("Invalid regional session")
        self._session = {"country": country, "token": boot["sessionToken"],
                         "channels": self.endpoint(boot["servers"]["channels"]),
                         "stitcher": self.endpoint(boot["servers"]["stitcher"]),
                         "params": boot["stitcherParams"]}
        self._session_expires = self.clock() + min(600, max(1, int(boot.get("refreshInSec", 600))))
        return self._session

    def fetch(self):
        session = self.session()
        payload = json.loads(self.http.request(session["channels"] + "/v2/guide/channels",
                                               custom={"Authorization": "Bearer " + session["token"]}))
        rows = payload["data"]
        if payload.get("meta", {}).get("totalCount", len(rows)) > len(rows):
            raise ValueError("Incomplete Pluto catalogue")
        seen = set()
        for row in rows:
            id = str(row.get("id", ""))
            if (not re.fullmatch("[0-9a-f]{24}", id) or id in seen or not row.get("name")
                    or row.get("plutoOfficeOnly") or not row.get("isStitched", True)):
                continue
            seen.add(id)
            logo = next((i.get("url", "") for i in row.get("images", [])
                         if i.get("type") == "colorLogoPNG"), "")
            yield channel(self.id, id, row["name"], self.name, logo, session["country"])

    def stream(self, row):
        session = self.session()
        return (session["stitcher"] + "/v2/stitch/hls/channel/" + row.id + "/master.m3u8?"
                + session["params"] + "&" + urlencode({"jwt": session["token"],
                                                       "masterJWTPassthrough": "true"}))
