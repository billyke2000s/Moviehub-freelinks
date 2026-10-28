#!/usr/bin/env bash
set -uo pipefail
build=android-tv-launcher/app/build
mkdir -p "$build"
# The Gradle output is kept in a file so a failure that writes no test results (an app crash, an
# install or emulator problem) can still be reported by report-test-failures.py.
gradle -p android-tv-launcher --no-daemon testDebugUnitTest connectedDebugAndroidTest 2>&1 | tee "$build/device-tests.log"
result=${PIPESTATUS[0]}
# Print fixture-only handoff timing evidence; never dump provider playback logs.
adb logcat -d -s MovieHubHandoff:I '*:S' || true
if [ "$result" -ne 0 ]; then
  adb logcat -d -b crash > "$build/device-crash.log" 2>/dev/null || true
  # Preserve Java exceptions as well as native faults; the filtered video tail can omit them.
  cat "$build/device-crash.log"
  # VLC's own lines about video output and decoding (fixture streams only), for the report.
  adb logcat -d -v time 2>/dev/null | grep -E -i "vlc|vout|mediacodec|awindow|surface|egl|codec2|omx|TestRunner|Fatal signal|backtrace|  #[0-9][0-9] pc" | tail -n 400 > "$build/device-video.log" || true
fi
# Preserve screenshots even when an assertion fails, before the emulator is shut down.
adb pull /sdcard/Download/moviehub-test-screenshots/ "$build/reports/tv-screenshots/" || true
exit "$result"
