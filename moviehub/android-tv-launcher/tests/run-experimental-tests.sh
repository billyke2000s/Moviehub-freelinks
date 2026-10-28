#!/usr/bin/env bash
set -uo pipefail
build=android-tv-launcher/app/build
mkdir -p "$build/reports/experimental"
result=0
for suite in DlhdStartupTest ExoLiveTest; do
    # Run filters separately: every required class must produce its own checked report.
    gradle -p android-tv-launcher --no-daemon -Pexperimental connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=local.tv.home.$suite" 2>&1 | tee "$build/experimental-$suite.log"
    current=${PIPESTATUS[0]}
    if [ "$current" -ne 0 ]; then
        result=1
        python3 android-tv-launcher/tests/report-test-failures.py "$build/outputs/androidTest-results" || true
    fi
    if ! python3 - "$build/outputs/androidTest-results" "$suite" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET
cases=[]
for path in pathlib.Path(sys.argv[1]).rglob('*.xml'):
    root=ET.parse(path).getroot()
    cases.extend(c for c in root.iter('testcase') if c.get('classname')=='local.tv.home.'+sys.argv[2])
if len(cases)<7 or any(c.find('failure') is not None or c.find('error') is not None or c.find('skipped') is not None for c in cases):
    raise SystemExit('Required experimental suite missing or unsuccessful: '+sys.argv[2]+' ('+str(len(cases))+' reported)')
print('Verified '+sys.argv[2]+': '+str(len(cases))+' passing tests, none skipped')
PY
    then
        result=1
    fi
    mkdir -p "$build/reports/experimental/$suite"
    cp -a "$build/reports/androidTests" "$build/reports/experimental/$suite/" || true
done
gradle -p android-tv-launcher --no-daemon -Pexperimental lintDebug lintRelease 2>&1 | tee "$build/experimental-lint.log"
if [ "${PIPESTATUS[0]}" -ne 0 ]; then result=1; fi
# Collect while the emulator is still alive; emulator-runner stops it after this script returns.
timeout 15s adb logcat -d -b crash > "$build/reports/experimental/crash.log" 2>/dev/null || true
timeout 15s adb pull /sdcard/Download/moviehub-test-screenshots/ "$build/reports/experimental/" || true
exit "$result"
