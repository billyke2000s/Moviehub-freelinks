#!/usr/bin/env bash
set -euo pipefail
shard=${1:?Audit shard required}
shards=${2:?Audit shard count required}
providers=${3:-}
media=${4:-movies}
source_names=${5:-}
series=false
if [ "$media" = series ]; then
    series=true
elif [ "$media" != movies ]; then
    echo 'Media must be movies or series' >&2
    exit 1
fi
gradle -p android-tv-launcher --no-daemon assembleDebug assembleDebugAndroidTest
# connectedDebugAndroidTest uninstalls the app, destroying its private report before collection.
# Install and invoke instrumentation explicitly, then validate the report before declaring success.
adb install -r android-tv-launcher/app/build/outputs/apk/debug/app-debug.apk
adb install -r android-tv-launcher/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class local.tv.home.FreeVodLiveAuditTest \
  -e vodLiveAudit true -e vodShard "$shard" -e vodShards "$shards" -e vodProviders "$providers" \
  -e vodSeries "$series" \
  -e vodSourceNames "$source_names" \
  local.tv.home.test/local.tv.home.MovieHubTestRunner
adb exec-out run-as local.tv.home cat files/vod-live-audit.jsonl > vod-live-audit.jsonl
python - "$shard" "$shards" <<'PY'
import json, pathlib, sys
rows = [json.loads(line) for line in pathlib.Path('vod-live-audit.jsonl').read_text().splitlines()]
if not rows or rows[-1] != dict(kind='finished', shard=int(sys.argv[1]), shards=int(sys.argv[2])):
    raise SystemExit('Incomplete audit: missing final shard marker')
for row in rows:
    print(json.dumps(row))
PY
