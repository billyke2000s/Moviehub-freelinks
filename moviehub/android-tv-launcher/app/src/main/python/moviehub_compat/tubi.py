"""Anonymous movie search used by the current public Tubi web client."""
import base64
from datetime import datetime, timezone
import hashlib
import hmac
import json
import re
import secrets
import uuid
from urllib.parse import urlencode, urlparse
from . import client
from .current_hosts import direct

ACCOUNT = 'https://account.production-public.tubi.io'
SEARCH = 'https://search.production-public.tubi.io/api/v3/search'


def anonymous_token():
    # Current public web-client handshake: server-issued signing key bound to our verifier.
    # This creates a guest session, not an account or a reusable embedded credential.
    verifier, device = secrets.token_hex(16), str(uuid.uuid4())
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode()
    headers = {'Content-Type': 'application/json', 'Origin': 'https://tubitv.com'}
    signing = client.request(ACCOUNT + '/device/anonymous/signing_key', headers=headers,
        post=json.dumps(dict(challenge=challenge, version='1.0.0', platform='web', device_id=device),
                        separators=(',', ':')), output='json')
    body = json.dumps(dict(verifier=verifier, id=signing['id'], platform='web', device_id=device),
                      separators=(',', ':'))
    path = '/device/anonymous/token'
    canonical = 'POST\n' + path + '\n\ncontent-type:application/json\n\ncontent-type\n' + hashlib.sha256(body.encode()).hexdigest()
    date = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    algorithm = 'TUBI-HMAC-SHA256'
    message = algorithm + '\n' + date + '\n' + hashlib.sha256(canonical.encode()).hexdigest()
    key = hmac.new(b'TUBI' + base64.b64decode(signing['key']), date[:8].encode(), hashlib.sha256).digest()
    key = hmac.new(key, b'tubi_request', hashlib.sha256).digest()
    signature = hmac.new(key, message.encode(), hashlib.sha256).hexdigest()
    query = urlencode({'X-Tubi-Algorithm': algorithm, 'X-Tubi-Date': date, 'X-Tubi-Expires': 30,
                       'X-Tubi-SignedHeaders': 'content-type', 'X-Tubi-Signature': signature})
    reply = client.request(ACCOUNT + path + '?' + query, headers=headers, post=body, output='json')
    return reply['access_token']


def movies(title, year, aliases):
    from moviehub_scrubs import cleantitle
    token = anonymous_token()
    headers = {'Authorization': 'Bearer ' + token, 'x-token': token, 'Origin': 'https://tubitv.com'}
    search = client.request(SEARCH + '?' + urlencode({'search': title}), headers=headers, output='json')
    contents = search.get('contents', {})
    contents = contents.values() if isinstance(contents, dict) else contents
    results = []
    for item in contents:
        if item.get('type') != 'v' or item.get('needs_login'):
            continue
        if str(item.get('year')) != str(year) or not cleantitle.match_alias(item.get('title', ''), aliases):
            continue
        if not str(item.get('id', '')).isdigit():
            continue
        page_url = 'https://tubitv.com/embed/' + str(item['id'])
        page = client.request(page_url, headers=headers)
        match = re.search(r'"video_resources"\s*:\s*', page)
        if not match:
            continue
        resources, unused = json.JSONDecoder().raw_decode(page[match.end():])
        for resource in resources:
            # Retain only the clear HLS resource type advertised by the public player.
            if resource.get('type') != 'hlsv6' or resource.get('drm') or resource.get('license_url'):
                continue
            url = resource.get('manifest', {}).get('url')
            if url:
                result = direct(url, page_url, urlparse(url).hostname,
                                str(resource.get('resolution', 'SD')))
                result['info'] = str(resource.get('codec', ''))
                results.append(result)
    return results
