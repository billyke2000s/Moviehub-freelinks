"""Verify bundled provider provenance against current official Kodi packages (no code execution)."""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1] / 'app/src/main/python'
records = json.loads((root / 'moviehub_providers.json').read_text())
packages = {
    'scrubs': ('5.1.42', 'https://raw.githubusercontent.com/jewbmx/repo/master/zips/plugin.video.scrubsv2/'),
    'free99': ('2.0.1', 'https://raw.githubusercontent.com/nebulous42069/Omega/main/omega/zips/plugin.video.free99/'),
}
for family, (version, base) in packages.items():
    import xml.etree.ElementTree as ET
    metadata = urllib.request.urlopen(base + 'addon.xml', timeout=25).read()
    addon = ET.fromstring(metadata)
    current = addon.get('version')
    if current != version:
        raise SystemExit(f'{family}: upstream is now {current}; bundled {version} needs review')
    name = addon.get('id')
    archive = urllib.request.urlopen(base + name + '-' + version + '.zip', timeout=45).read()
    files = zipfile.ZipFile(io.BytesIO(archive))
    actual = {Path(path).stem: hashlib.sha256(files.read(path)).hexdigest()
              for path in files.namelist() if Path(path).parent.name == 'working' and path.endswith('.py')
              and not path.endswith('/__init__.py')}
    expected = {r['name']: r['sha256'] for r in records if r['family'] == family}
    if actual != expected:
        raise SystemExit(f'{family}: provider inventory or original hashes changed')
    print(json.dumps(dict(family=family, version=version, providers=len(actual),
                         packageSha256=hashlib.sha256(archive).hexdigest(), verified=True)))
