"""Opt-in network probe. Prints counts/status only; does not publish URLs, tokens or playlists."""
import argparse
import json
import sys

from sources import SOURCES
from transport import Http


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("provider", choices=SOURCES)
    parser.add_argument("--check-hls", action="store_true", help="Resolve and fetch one channel's HLS master")
    args = parser.parse_args()
    source = SOURCES[args.provider](Http())
    try:
        rows = source.catalogue()
        result = {"provider": source.id, "channels": len(rows), "hls_master_verified": False}
        if args.check_hls:
            url, headers = source.resolve(rows[0].id)
            manifest = source.http.request(url, custom=headers)
            if not manifest.lstrip().startswith("#EXTM3U"):
                raise ValueError("Not an HLS playlist")
            result["hls_master_verified"] = True
        print(json.dumps(result))
        return 0
    except Exception as error:
        # Exception messages may include signed URLs. Type only is intentional.
        print(json.dumps({"provider": source.id, "error_type": type(error).__name__}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
