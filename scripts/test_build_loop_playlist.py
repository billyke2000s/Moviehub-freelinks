"""Offline regression checks for the hosted M3U converter."""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_loop_playlist as builder
from loop_pluto import channels_from_guide


class ConverterTests(unittest.TestCase):
    def test_loop_pluto_guide_extracts_direct_stream(self):
        payload = {'channels': [{'name': 'Sample', 'stitched': {'urls': [{'url': 'https://example.org/live.m3u8'}]}, 'images': []}, {'name': 'Broken', 'stitched': {'urls': []}}]}
        items = list(channels_from_guide(payload))
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0][1], 'https://example.org/live.m3u8')

    def test_extract_direct_streams_only(self):
        feed = """#EXTM3U
#EXTINF:-1 tvg-id="example",Example
https://example.org/live.m3u8
#EXTINF:-1,Unsupported
plugin://plugin.video.loop/play/123
#EXTINF:-1,Second
http://example.org/live.ts
"""
        self.assertEqual(len(list(builder.extract(feed))), 2)

    def test_publish_and_deduplicate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "feeds.txt"
            output = root / "output.m3u"
            manifest.write_text("https://example.org/playlist.m3u\n", encoding="utf-8")
            feed = "#EXTM3U\n#EXTINF:-1,One\nhttps://example.org/1.m3u8\n#EXTINF:-1,Duplicate\nhttps://example.org/1.m3u8\n"
            with patch.object(builder, "MANIFEST", manifest), patch.object(builder, "OUTPUT", output), patch.object(builder, "fetch", return_value=feed):
                builder.build()
            self.assertEqual(output.read_text().count("https://example.org/1.m3u8"), 1)

    def test_one_broken_feed_does_not_block_good_feed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "feeds.txt"
            output = root / "output.m3u"
            manifest.write_text("https://example.org/broken.m3u\nhttps://example.org/good.m3u\n", encoding="utf-8")

            def fake_fetch(url):
                if "broken" in url:
                    raise OSError("404")
                return "#EXTM3U\n#EXTINF:-1,Working\nhttps://example.org/live.m3u8\n"

            with patch.object(builder, "MANIFEST", manifest), patch.object(builder, "OUTPUT", output), patch.object(builder, "fetch", side_effect=fake_fetch):
                builder.build()
            self.assertIn("Working", output.read_text())

    def test_failure_preserves_previous_playlist(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "feeds.txt"
            output = root / "output.m3u"
            manifest.write_text("https://example.org/playlist.m3u\n", encoding="utf-8")
            output.write_text("#EXTM3U\n#EXTINF:-1,Old\nhttps://example.org/old.m3u8\n", encoding="utf-8")
            with patch.object(builder, "MANIFEST", manifest), patch.object(builder, "OUTPUT", output), patch.object(builder, "fetch", side_effect=OSError("network down")):
                with self.assertRaises(RuntimeError):
                    builder.build()
            self.assertIn("Old", output.read_text())


if __name__ == "__main__":
    unittest.main()
