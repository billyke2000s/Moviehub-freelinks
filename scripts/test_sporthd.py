import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from sporthd import extract_events, is_direct_hls, diagnose

class SportHDTests(unittest.TestCase):
    def test_only_direct_hls(self):
        self.assertTrue(is_direct_hls("https://example.com/live.m3u8?token=abc"))
        self.assertFalse(is_direct_hls("https://example.com/embed.php?id=1"))
        self.assertFalse(is_direct_hls("plugin://sporthd/play"))
        self.assertFalse(is_direct_hls("https://example.com/notm3u8"))
    def test_live_event_filter_and_dedup(self):
        data = {"matches": [
            {"team1": "A", "team2": "B", "startTimestamp": 1700000000000, "duration": 90,
             "channels": [{"name": "Example", "links": ["https://example.com/live.m3u8", "https://example.com/live.m3u8", "https://example.com/embed"]}]},
            {"team1": "Old", "startTimestamp": 1, "duration": 1,
             "channels": [{"name": "Old", "links": ["https://example.com/old.m3u8"]}]}
        ]}
        items = extract_events(data, now_ms=1700000100000)
        self.assertEqual(len(items), 1)
        self.assertIn("A vs B", items[0][0])
    def test_diagnostics(self):
        data = {"matches": [
            {"startTimestamp": 1700000000000, "duration": 90,
             "channels": ["Named Channel", {"name": "Embedded", "links": [
                 "https://example.com/embed", "https://example.com/live.m3u8"]}]},
            {"startTimestamp": 1700001000000, "channels": []},
            {"startTimestamp": 1690000000000, "channels": []},
            {"channels": []}
        ]}
        counts = diagnose(data, now_ms=1700000100000)
        self.assertEqual(counts["events"], 4)
        self.assertEqual(counts["live"], 1)
        self.assertEqual(counts["channel_names"], 1)
        self.assertEqual(counts["embeds"], 1)
        self.assertEqual(counts["direct_hls"], 1)
        self.assertEqual(counts["upcoming"], 1)
        self.assertEqual(counts["expired"], 1)
        self.assertEqual(counts["missing_time"], 1)

    def test_broken_embed_does_not_abort_other_channels(self):
        data = {"matches": [{"team1": "A", "startTimestamp": 1700000000000,
            "duration": 90, "channels": [{"name": "Sources",
            "links": ["https://example.com/broken", "https://example.com/live.m3u8"]}]}]}
        def resolver(url):
            raise OSError("player offline")
        items = extract_events(data, now_ms=1700000100000, resolver=resolver)
        self.assertEqual(len(items), 1)

    def test_invalid_payload(self):
        with self.assertRaises(ValueError):
            extract_events({"matches": "invalid"})
if __name__ == "__main__":
    unittest.main()
