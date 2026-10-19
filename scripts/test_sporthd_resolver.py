import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from sporthd_resolver import direct_hls, extract_player_url, resolve

class ResolverTests(unittest.TestCase):
    def test_config_json(self):
        self.assertEqual(extract_player_url('{"src":"https://cdn.example/live.m3u8"}', "https://player.example"), "https://cdn.example/live.m3u8")
    def test_clappr(self):
        self.assertEqual(extract_player_url('new Clappr.Player({source: "https://cdn.example/live.m3u8"})', "https://player.example"), "https://cdn.example/live.m3u8")
    def test_iframe_chain(self):
        pages = {"https://site.example/event": '<iframe src="https://site.example/player"></iframe>',
                 "https://site.example/player": '<source src="https://cdn.example/live.m3u8">'}
        self.assertEqual(resolve("https://site.example/event", fetch=lambda u, r: pages[u]), "https://cdn.example/live.m3u8")
    def test_host_player_api(self):
        pages = {
            "https://nexa.st/api/player.php?id=42": '{"url":"https://player.example/embed"}',
            "https://player.example/embed": 'window.PP_CLAPPR_CONFIG = {"src":"https://cdn.example/live.m3u8"};'
        }
        calls = []
        def fetch(url, referer):
            calls.append(url)
            return pages[url]
        self.assertEqual(resolve("https://nexa.st/play?id=42", fetch=fetch),
                         "https://cdn.example/live.m3u8")
        self.assertEqual(calls[0], "https://nexa.st/api/player.php?id=42")

    def test_unsupported(self):
        self.assertIsNone(resolve("plugin://kodi/foo"))
        self.assertFalse(direct_hls("https://site.example/embed.php"))
if __name__ == "__main__":
    unittest.main()
