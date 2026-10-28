# Generated VOD fixture

`vod.mp4` is a 150-second generated H.264 baseline/AAC fixture. It extends the existing generated
`playback.mp4`, allowing real LibVLC replacement tests to keep the two-minute rejection rule
active. The original 30-second fixture remains the short/error-video case.

Reproduce from the repository root:

```sh
ffmpeg -v error -stream_loop 4 -i android-tv-launcher/app/src/androidTest/assets/playback.mp4 \
  -t 150 -vf scale=160:90,fps=5 -c:v libx264 -preset veryfast -crf 38 -g 10 \
  -profile:v baseline -pix_fmt yuv420p -c:a aac -ac 1 -ar 16000 -b:a 16k \
  -movflags +faststart -y android-tv-launcher/app/src/androidTest/assets/vod.mp4
ffprobe -v error -show_entries format=duration,size -of default=noprint_wrappers=1 \
  android-tv-launcher/app/src/androidTest/assets/vod.mp4
ffmpeg -v error -i android-tv-launcher/app/src/androidTest/assets/vod.mp4 -f null -
```

The full local decode succeeds. This is generated test media, not a captured provider stream.
