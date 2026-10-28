# Media header fixtures

The first bytes of real files made with FFmpeg, used by `test_free_vod.py` to check that the
probe reads a file's real picture size. They are not playable on their own.

| File | What it is |
| --- | --- |
| `854x480-faststart.mp4` | First 4 KiB of an MP4 with its index (moov) at the start |
| `1920x800-faststart.mp4` | Same, a wide film frame (counts as 1080p) |
| `1280x720.mkv` | First 4 KiB of a Matroska file |
| `640x360.webm` | First 4 KiB of a WebM (VP8) file |
| `854x480-moov-last-start.mp4` | First 48 bytes (ftyp, free, mdat header) of an MP4 with its index at the end |
| `854x480-moov-last-index.mp4` | That file's moov box, which starts at byte 1140867 |

Reproduce (FFmpeg 6 or later):

```sh
src() { ffmpeg -y -f lavfi -i "testsrc2=size=$1:rate=24:duration=3" -f lavfi -i "sine=duration=3" -shortest "${@:2}"; }
src 854x480 -c:v libx264 -preset ultrafast -c:a aac -movflags +faststart a.mp4      # 854x480-faststart
src 1920x800 -c:v libx264 -preset ultrafast -c:a aac -movflags +faststart b.mp4     # 1920x800-faststart
src 1280x720 -c:v libx264 -preset ultrafast -c:a aac c.mkv                          # 1280x720.mkv
src 640x360 -c:v libvpx -deadline realtime -c:a libvorbis d.webm                    # 640x360.webm
src 854x480 -c:v libx264 -preset ultrafast -c:a aac e.mp4                           # moov-last pair
```

Exact byte offsets depend on the FFmpeg version; the tests read the moov offset from the
start bytes rather than assuming it.
