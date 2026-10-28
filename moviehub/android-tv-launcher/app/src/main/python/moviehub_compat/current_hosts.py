"""Current public player flows observed on catalogue pages, without executing remote JS."""
import base64
import hashlib
import json
import re
from urllib.parse import parse_qs, urlencode, urljoin, urlparse
from . import client


def script_strings(script):
    """Read literal strings from the player's encoded string table, never evaluate code."""
    table = str.maketrans('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/',
                         'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/')
    for value in re.findall(r'''["']([a-zA-Z0-9+/=._?|-]{5,1000})["']''', script):
        yield value
        try:
            yield base64.b64decode(value.translate(table) + '===').decode('utf-8')
        except (ValueError, UnicodeError):
            pass


def direct(url, referer, provider, quality='SD'):
    return dict(url=url + '|' + urlencode({'Referer': referer}), direct=True,
                source=provider, originProvider=provider, quality=quality, info='')


def process(url, delegate=None):
    """None delegates to the bundled resolver; [] means a known flow returned no media."""
    host = (urlparse(url).hostname or '').removeprefix('www.')
    if host in ('watchseries.cyou', 'freeprojecttv.cyou') and '/vembed/' in url and delegate:
        page = client.request(url)
        results = []
        # The selector fetches one /external/asset/ document per displayed provider.
        values = re.findall(r'<option\b[^>]*\bvalue=["\']([^"\']+)', page, re.I)
        for value in dict.fromkeys(values):
            if not re.fullmatch(r'[a-zA-Z0-9_-]{1,100}', value):
                continue
            try:
                asset = client.request(urljoin(url, '/external/asset/' + value + '/'), referer=url)
                for child in re.findall(r'<iframe\b[^>]*\bsrc=["\']([^"\']+)', asset, re.I):
                    results.extend(delegate(urljoin(url, child)))
            except Exception:
                continue
        return results
    if (host == 'waffi.cloud' or host.endswith('.waffi.cloud')) and delegate:
        return waffi(url, delegate)
    if host == 'embed.wplay.me':
        page = client.request(url)
        links = re.findall(r'<iframe\b[^>]*\bsrc=["\']([^"\']+)', page, re.I)
        results = []
        for link in links:
            link = urljoin(url, link)
            if urlparse(link).hostname == 'play.xpass.top':
                results.extend(xpass(link))
        return results
    if host == 'play.xpass.top':
        return xpass(url)
    if host in ('moviesapi.to', 'vidspark.to'):
        return moviesapi(url)
    return None


def waffi(url, delegate):
    from moviehub_scrubs import client_utils
    response = client.fetch(url, max_bytes=65536)
    page, page_url = response.text, response.url
    if response.headers.get('Content-Type', '').lower().startswith(('video/', 'audio/')):
        return [direct(page_url, url, urlparse(page_url).hostname)]
    if 'data-download-page' not in page:
        return None
    results = []
    links = client_utils.parseDOM(page, 'a', attrs={'class': 'mirror-download'}, ret='href')
    for link in dict.fromkeys(links):
        client.check()
        try:
            target = client.fetch(urljoin(page_url, link), headers={'Referer': page_url}, max_bytes=0)
            if target.status_code >= 400:
                client.host_result(target.url, 'http_' + str(target.status_code))
                continue
            if urlparse(target.url).hostname == urlparse(page_url).hostname:
                continue
            if target.headers.get('Content-Type', '').lower().startswith(('video/', 'audio/')):
                results.append(direct(target.url, page_url, urlparse(target.url).hostname))
            else:
                results.extend(delegate(target.url))
        except Exception:
            client.host_result(urljoin(page_url, link), 'resolution_failed')
            continue
    # The online player is a separate backend, not a synonym for every download mirror.
    watches = client_utils.parseDOM(page, 'a', attrs={'class': r'watch watch-online.*'}, ret='href')
    for watch in watches:
        try:
            watch_url = urljoin(page_url, watch)
            player = client.request(watch_url, referer=page_url)
            endpoints = client_utils.parseDOM(player, 'movi-player', ret='data-source')
            for endpoint in endpoints:
                reply = client.scrapePage(urljoin(watch_url, endpoint), referer=watch_url)
                data = reply.json()
                if reply.status_code == 202:
                    client.host_result('new.waffi.cloud · Watch', 'preparing')
                elif reply.status_code == 200 and data.get('url'):
                    results.append(direct(urljoin(watch_url, data['url']), watch_url, 'new.waffi.cloud · Watch'))
        except Exception:
            client.host_result('new.waffi.cloud · Watch', 'resolution_failed')
    return results


def xpass(url):
    page = client.request(url)
    match = re.search(r'\bdataUrl\s*=\s*["\']([^"\']+)', page)
    if not match:
        return []
    data_url = urljoin(url, match[1])
    # Follow the script actually advertised by this player, including changing build names.
    loader_url = next((urljoin(url, x) for x in re.findall(r'<script[^>]+src=["\']([^"\']+)', page)
                       if '/static/main' in x), None)
    if not loader_url:
        return []
    loader = client.request(loader_url, referer=url)
    player_path = next((x for x in script_strings(loader)
                        if x.startswith('/static/') and '.js' in x), None)
    if not player_path:
        return []
    player = client.request(urljoin(url, player_path), referer=url)
    build = next((x for x in script_strings(player) if x.startswith('spv3-build-')), None)
    if not build:
        return []
    token = parse_qs(urlparse(data_url).query).get('token', [''])[0]
    if not token:
        return []
    key = hashlib.sha256(('spv3-data-response|' + build + '|' + urlparse(data_url).path + '|' + token).encode()).digest()
    body = client.request(data_url, referer=url).strip()
    packed = base64.urlsafe_b64decode(body + '===')
    from resolveurl.lib.aesgcm import python_aesgcm
    clear = python_aesgcm.new(bytearray(key)).open(bytearray(packed[:12]), bytearray(packed[12:]), bytearray())
    if clear is None:
        raise ValueError('Player response authentication failed')
    backups = json.loads(clear)
    results = []
    # Each advertised backend is independent: failure of the first cannot hide the others.
    for backup in backups[:64]:
        client.check()
        try:
            playlist_url = urljoin(url, backup['url'])
            reply = client.request(playlist_url, referer=url, output='json')
            entries = reply.get('playlist', []) if isinstance(reply, dict) else reply
            for entry in entries:
                for source in entry.get('sources', []):
                    media = urljoin(playlist_url, source['file'])
                    provider = (urlparse(media).hostname or 'play.xpass.top') + ' · ' + str(backup.get('name', ''))
                    item = direct(media, url, provider, str(source.get('label', 'SD')))
                    item['segmentContainers'] = True
                    results.append(item)
        except Exception:
            continue
    return results


def moviesapi(url):
    match = re.search(r'/(movie|tv)/([^/?]+)', urlparse(url).path)
    if not match:
        return []
    kind, identity = match.groups()
    if kind == 'tv':
        parts = identity.rsplit('-', 2)
        if len(parts) != 3 or not all(x.isdigit() for x in parts[1:]):
            return []
        path = '/v1/tv/' + '/'.join(parts)
    else:
        path = '/v1/movie/' + identity
    page = client.request(url)
    scripts = re.findall(r'<script[^>]+src=["\']([^"\']+)', page)
    asset = next((urljoin(url, x) for x in scripts if x.startswith('/assets/index-') and x.endswith('.js')), None)
    if not asset:
        return []
    script = client.request(asset, referer=url)
    # The public player supplies this value in its own JS; do not pin or store it in the APK.
    key = re.search(r'\b\w+\s*=\s*[`"\']([a-f0-9]{64})[`"\'][,;].{0,80}/api/vidora/subs/', script)
    if not key:
        return []
    reply = client.request(urljoin(url, '/api/vidora' + path),
                           headers={'x-player-key': key[1]}, referer=url, output='json')
    if not reply.get('result'):
        return []
    return [direct(urljoin(url, source['url']), url, urlparse(url).hostname,
                   str(source.get('quality', 'SD')))
            for source in reply.get('sources', []) if source.get('url')]
