import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from masqueradarr_bridge import build, fetch_export, parse_m3u


class MasqueradarrBridgeTests(unittest.TestCase):
    def test_parse_deduplicates_and_ignores_embeds(self):
        text = "#EXTM3U\n#EXTINF:-1,Channel\nhttps://example.org/a.m3u8\n#EXTINF:-1,Duplicate\nhttps://example.org/a.m3u8\n#EXTINF:-1,Embed\njavascript:alert(1)\n"
        self.assertEqual(parse_m3u(text), [("#EXTINF:-1,Channel", "https://example.org/a.m3u8")])

    def test_reject_non_playlist(self):
        with self.assertRaises(ValueError):
            parse_m3u("<html>player</html>")

    def test_reject_insecure_export(self):
        with self.assertRaises(ValueError):
            fetch_export("http://example.org/list.m3u")

    def test_preserve_old_output_on_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "channels.m3u"
            target.write_text("old")
            with patch("masqueradarr_bridge.fetch_export", return_value="#EXTM3U\n"):
                with self.assertRaises(ValueError):
                    build("https://example.org/list", target)
            self.assertEqual(target.read_text(), "old")

    def test_build(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "channels.m3u"
            with patch("masqueradarr_bridge.fetch_export", return_value="#EXTM3U\n#EXTINF:-1,Test\nhttps://example.org/live.m3u8\n"):
                self.assertEqual(build("https://example.org/list", target), 1)
            self.assertIn("https://example.org/live.m3u8", target.read_text())


if __name__ == "__main__":
    unittest.main()
