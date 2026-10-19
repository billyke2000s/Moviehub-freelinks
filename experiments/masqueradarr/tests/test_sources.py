import gzip
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common import channel, expand
from pluto import Pluto
from sources import FreeLiveSports, Samsung, Stirr, Whale
from transport import decode, https_url, redirect_headers


class FakeHttp:
    def __init__(self, handler):
        self.handler = handler
        self.calls = []

    def request(self, url, method="GET", custom=None):
        self.calls.append((url, method, custom or {}))
        return json.dumps(self.handler(url, method, custom or {}))


def boot():
    return {"sessionToken": "fixture-session", "session": {"countryCode": "GB"},
            "servers": {"channels": "https://channels.pluto.tv", "stitcher": "https://stitcher.pluto.tv"},
            "stitcherParams": "serverSideAds=true", "refreshInSec": 99999}


def sports_row(**extra):
    return {"_id": "sport1", "name": "Sport", "url": "https://cdn.example/live.m3u8?cb=[CB]&id=[DEVICE_ID]",
            "accessType": "public", "status": "live", **extra}


class TransportTests(unittest.TestCase):
    def test_https_only_and_no_local_targets(self):
        for url in ["http://cdn.example/live", "https://user:password@cdn.example/live",
                    "https://127.0.0.1/live", "https://[::1]/live", "https://localhost/live",
                    "https://cdn.example:444/live", "https://cdn.example/live#fragment"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                https_url(url)
        self.assertEqual("https://cdn.example/live", https_url("https://cdn.example/live"))

    def test_redirect_drops_all_custom_credentials_across_authorities(self):
        custom = {"token": "fixture", "Authorization": "Bearer fixture", "Cookie": "fixture"}
        self.assertEqual({}, redirect_headers("https://a.example", "https://b.example", custom))
        self.assertEqual(custom, redirect_headers("https://a.example/one", "https://a.example/two", custom))

    def test_gzip_decoded_limit(self):
        self.assertEqual("hello", decode(gzip.compress(b"hello"), 64))
        with self.assertRaises(ValueError):
            decode(gzip.compress(b"x" * 10000), 100)
        with self.assertRaises(ValueError):
            decode(b"x" * 101, 100)


class SportsTests(unittest.TestCase):
    def test_catalogue_filters_and_deduplicates(self):
        source = FreeLiveSports(FakeHttp(lambda *args: []))
        rows = list(source.parse([sports_row(), sports_row(), sports_row(_id="paid", accessType="paid"),
                                  sports_row(_id="drm", drm=True), sports_row(_id="soon", status="upcoming")]))
        self.assertEqual(["sport1"], [r.id for r in rows])
        self.assertEqual("freelivesports:sport1", rows[0].key)
        self.assertNotIn("stream", rows[0].metadata())
        self.assertNotIn("cb=", repr(rows[0]))

    def test_inline_guide(self):
        source = FreeLiveSports(None)
        rows = list(source.parse([sports_row(epg={"entries": [
            {"start": "2026-10-19T10:00:00Z", "stop": "2026-10-19T11:00:00Z", "title": "Match"},
            {"start": "broken", "stop": "broken"}]})]))
        self.assertEqual(1, len(rows[0].programmes))
        self.assertEqual(3600000, rows[0].programmes[0].stop - rows[0].programmes[0].start)

    def test_cached_discovery_fresh_resolution_and_membership(self):
        now = [0]
        http = FakeHttp(lambda *args: [sports_row()])
        source = FreeLiveSports(http, clock=lambda: now[0])
        with patch("sources.time.time", return_value=1):
            first, required = source.resolve("sport1")
        with patch("sources.time.time", return_value=2):
            second, _ = source.resolve("sport1")
        self.assertNotEqual(first, second)
        self.assertEqual(1, len(http.calls))
        self.assertIn("Referer", required)
        with self.assertRaises(ValueError):
            source.resolve("missing")
        with self.assertRaises(ValueError):
            source.resolve("../sport1")
        now[0] = 601
        source.catalogue()
        self.assertEqual(2, len(http.calls))

    def test_empty_catalogue_not_cached(self):
        http = FakeHttp(lambda *args: [])
        source = FreeLiveSports(http)
        with self.assertRaises(ValueError):
            source.catalogue()
        http.handler = lambda *args: [sports_row()]
        self.assertEqual(1, len(source.catalogue()))

    def test_unknown_macro_not_guessed(self):
        with self.assertRaises(ValueError):
            expand("https://cdn.example/live.m3u8?a=[UNKNOWN]", {})


class SamsungTests(unittest.TestCase):
    def test_region_drm_and_inline_guide(self):
        payload = {"regions": {"gb": {"channels": {
            "GB1": {"name": "Example", "programs": [[100, "First"], [200, "Next"]]},
            "GB2": {"name": "DRM", "license_url": "https://example/license"}}},
            "us": {"channels": {"US1": {"name": "Other"}}}}}
        source = Samsung(None)
        rows = list(source.parse(payload, "gb"))
        self.assertEqual(["GB1"], [r.id for r in rows])
        self.assertEqual(1, len(rows[0].programmes))
        self.assertEqual(200000, rows[0].programmes[0].stop)
        self.assertEqual("https://jmp2.uk/stvp-GB1", source.stream(rows[0]))
        with self.assertRaises(KeyError):
            list(source.parse(payload, "de"))

    def test_network_country_used_for_discovery(self):
        http = FakeHttp(lambda url, *args: boot() if url.startswith("https://boot.pluto.tv/") else
                        {"regions": {"gb": {"channels": {"GB1": {"name": "UK"}}}}})
        self.assertEqual("gb", Samsung(http).catalogue()[0].country)


class StirrTests(unittest.TestCase):
    def test_recursive_catalogue_and_per_play_post(self):
        def handler(url, method, custom):
            if method == "GET":
                return {"videos": {"nested": [{"videoid": 123, "title": "Example"}]}}
            self.assertTrue(url.endswith("/123/playable"))
            self.assertEqual("https://stirr.com", custom["Origin"])
            return {"data": [{"drm": [], "media": ["https://cdn.example/live.m3u8?nonce=[vx_nonce]"]}]}
        http = FakeHttp(handler)
        source = Stirr(http)
        first, _ = source.resolve("123")
        second, _ = source.resolve("123")
        self.assertNotEqual(first, second)
        self.assertEqual(2, sum(method == "POST" for _, method, _ in http.calls))

    def test_rejects_drm_and_non_hls(self):
        row = channel("stirr", "123", "Example", "News")
        for response in [{"drm": {"widevine": "fixture"}, "media": ["https://cdn.example/live.m3u8"]},
                         {"media": ["https://cdn.example/live.mpd"]}]:
            with self.subTest(response=response):
                source = Stirr(FakeHttp(lambda *args: {"data": [response]}))
                with self.assertRaises(ValueError):
                    source.stream(row)

    def test_excessive_nesting_rejected(self):
        value = {}
        for _ in range(15):
            value = {"nested": value}
        with self.assertRaises(ValueError):
            list(Stirr(None).parse(value))


class WhaleTests(unittest.TestCase):
    def test_observed_region_session_and_fresh_macros(self):
        def handler(url, method, custom):
            if url == Whale.AUTH:
                return {"data": {"areaCode": "GB", "token": "fixture"}}
            self.assertIn("countryCode=GB", url)
            self.assertEqual("fixture", custom["token"])
            return {"data": [{"ctgName": "Sports", "channels": [{"chlId": "123", "chlName": "Example",
                "chlUrl": "https://cdn.example/live.m3u8?id=[did]&session=[session_id]&country=[country_2]"}]}]}
        source = Whale(FakeHttp(handler))
        self.assertEqual("gb", source.catalogue()[0].country)
        first, _ = source.resolve("123")
        second, _ = source.resolve("123")
        self.assertIn("country=GB", first)
        self.assertNotEqual(first, second)
        self.assertNotIn("[", first)

    def test_missing_country_is_not_replaced_with_us(self):
        with self.assertRaises(KeyError):
            Whale(FakeHttp(lambda *args: {"data": {"token": "fixture"}})).catalogue()

    def test_guide_batches_only_current_catalogue(self):
        now = 1792410000
        def handler(url, method, custom):
            if url == Whale.AUTH:
                return {"data": {"areaCode": "GB", "token": "fixture"}}
            if "/epg?" in url:
                return {"data": [{"chlId": "123", "ptList": [{"prgStm": now * 1000,
                    "prgEtm": (now + 100) * 1000, "prgTitle": "Live"}]},
                    {"chlId": "foreign", "ptList": []}]}
            return {"data": [{"ctgName": "Sports", "channels": [{"chlId": "123", "chlName": "Example",
                "chlUrl": "https://cdn.example/live.m3u8"}]}]}
        source = Whale(FakeHttp(handler))
        with patch("sources.time.time", return_value=now):
            guide = source.guide()
        self.assertEqual(["123"], list(guide))
        self.assertEqual("Live", guide["123"][0].title)


class PlutoTests(unittest.TestCase):
    def test_provider_endpoint_boundary(self):
        for url in ["https://pluto.tv.evil.example", "https://api.pluto.tv/other", "http://api.pluto.tv"]:
            with self.assertRaises(ValueError):
                Pluto.endpoint(url)

    def test_session_ttl_ads_and_stable_membership(self):
        id = "5ad8d3a31b95267e225e4e09"
        http = FakeHttp(lambda url, *args: boot() if url.startswith("https://boot.pluto.tv") else
                        {"data": [{"id": id, "name": "Example"}, {"id": id, "name": "Duplicate"}]})
        now = [0]
        source = Pluto(http, clock=lambda: now[0])
        self.assertEqual(1, len(source.catalogue()))
        stream, _ = source.resolve(id)
        self.assertIn("serverSideAds=true", stream)
        self.assertIn("serverSideAds=true", http.calls[0][0])
        self.assertEqual(2, len(http.calls))
        self.assertEqual("pluto:" + id, source.catalogue()[0].key)
        now[0] = 601
        source.catalogue()
        self.assertEqual(4, len(http.calls))


if __name__ == "__main__":
    unittest.main()
