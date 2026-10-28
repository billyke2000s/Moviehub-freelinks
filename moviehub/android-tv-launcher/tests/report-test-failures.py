"""Turn failed JUnit results into GitHub annotations and a job summary.

CI artifacts and raw logs are often not convenient to open. Annotations appear directly on the pull
request and in the API, so the names and messages of failing device tests are always visible.

Usage: python3 report-test-failures.py <results-directory>
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 10  # GitHub shows at most 10 error annotations per step


def escape(text):
    # Workflow command encoding for annotation messages.
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def main(root):
    passed, failed = 0, []
    paths = sorted(glob.glob(os.path.join(root, '**', '*.xml'), recursive=True))
    if not paths:
        # Newer Android Gradle plugins may write the results elsewhere in the build folder.
        build = os.path.dirname(os.path.dirname(os.path.abspath(root)))
        paths = sorted(glob.glob(os.path.join(build, '**', 'TEST-*.xml'), recursive=True))
        print(f'No results under {root}; found {len(paths)} result files elsewhere in {build}')
    for path in paths:
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for case in tree.iter('testcase'):
            problem = case.find('failure')
            if problem is None:
                problem = case.find('error')
            if problem is None:
                if case.find('skipped') is None:
                    passed += 1
                continue
            name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}"
            detail = (problem.get('message') or problem.text or '').strip()
            failed.append((name, detail))

    unique = sorted({name for name, _ in failed})
    print(f'Device tests: {passed} passed, {len(failed)} failed ({len(unique)} distinct)')
    for name, detail in failed[:MAX_ANNOTATIONS]:
        first_lines = '\n'.join(detail.splitlines()[:6])[:1500]
        print(f'::error title=Device test failed: {name}::{escape(first_lines)}')
    video = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(root))), 'device-video.log')
    if os.path.exists(video) and os.path.getsize(video) > 0:
        with open(video, errors='replace') as source:
            lines = source.read().splitlines()
        crash = [i for i, line in enumerate(lines) if 'Fatal signal' in line]
        if crash:
            started = [line for line in lines[:crash[-1]] if 'TestRunner' in line and 'started' in line]
            frames = [line for line in lines[crash[-1]:] if ' pc ' in line][:12]
            text = (started[-1] if started else 'no test start found') + '\n' + lines[crash[-1]] + '\n' + '\n'.join(frames)
            print(f'::error title=Native crash (test running, backtrace)::{escape(text[:3500])}')
    if failed and os.path.exists(video) and os.path.getsize(video) > 0:
        with open(video, errors='replace') as source:
            lines = source.read().splitlines()
        keys = ('error', 'fail', 'cannot', 'unable', 'destroy', 'surface', 'vout', 'window', 'decoder')
        useful = [line for line in lines if any(key in line.lower() for key in keys)]
        print(f'::error title=Video log (VLC, last lines)::{escape(chr(10).join(useful[-25:])[-3500:])}')
    if not failed:
        # The step failed but no test did: the run stopped before results were written (an app
        # crash, an install failure, the emulator). Report why from the saved output.
        for title, text in why_no_results(os.path.dirname(os.path.dirname(os.path.abspath(root)))):
            print(f'::error title={title}::{escape(text[:1500])}')

    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as out:
            out.write(f'### Device tests: {passed} passed, {len(failed)} failed\n\n')
            for name, detail in failed:
                out.write(f'- `{name}`: {detail.splitlines()[0][:300] if detail else "no message"}\n')
    return 0


def why_no_results(build):
    """The useful lines from the Gradle output and the device's crash log, as (title, text)."""
    found = []
    log = os.path.join(build, 'device-tests.log')
    if os.path.exists(log):
        with open(log, errors='replace') as source:
            lines = source.read().splitlines()
        wrong = []
        for i, line in enumerate(lines):
            if line.startswith('* What went wrong:'):
                wrong = lines[i + 1:i + 12]
                break
        if wrong:
            found.append(('Device tests stopped (Gradle)', '\n'.join(wrong)))
        marks = ('Process crashed', 'INSTRUMENTATION_', 'Test run failed', 'Exception', 'FAILED')
        hits = [line for line in lines if any(mark in line for mark in marks)]
        if hits:
            found.append(('Device tests: key lines', '\n'.join(hits[-15:])))
        if not found:
            found.append(('Device tests: end of output', '\n'.join(lines[-15:])))
    crash = os.path.join(build, 'device-crash.log')
    if os.path.exists(crash) and os.path.getsize(crash) > 0:
        with open(crash, errors='replace') as source:
            found.append(('App crash on the emulator', '\n'.join(source.read().splitlines()[:25])))
    if not found:
        found.append(('Device tests: no results', 'No test results and no saved output were found.'))
    return found[:MAX_ANNOTATIONS]


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else 'android-tv-launcher/app/build'))
