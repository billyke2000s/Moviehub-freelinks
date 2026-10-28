"""Bounded requests adapter shared by bundled provider modules."""
import ipaddress
import json
import socket
import threading
import time
from urllib.parse import urlparse, parse_qsl
import requests

UserAgent = 'Mozilla/5.0 (Android 11; TV) AppleWebKit/537.36 Chrome/130.0 Safari/537.36'
dnt_headers = {'User-Agent': UserAgent, 'Accept': '*/*', 'DNT': '1'}
_STATE = threading.local()
MAX_BODY = 8 * 1024 * 1024

class Cancelled(BaseException): pass

def scope(cancel, timeout=18, http=False, preserve=False):
    _STATE.cancel, _STATE.deadline, _STATE.http = cancel, time.monotonic()+timeout, http
    _STATE.failure = None
    if not preserve:
        _STATE.trace, _STATE.hosts = [], {}

def check():
    callback = getattr(_STATE, 'cancel', None)
    if callback and callback(): raise Cancelled()
    if time.monotonic() >= getattr(_STATE, 'deadline', float('inf')): raise Cancelled()

def safe(url):
    check()
    parsed = urlparse(url)
    schemes = ('http','https') if getattr(_STATE, 'http', False) else ('https',)
    if parsed.scheme not in schemes or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError('Unsupported provider URL')
    if len(url)>16384 or '\r' in url or '\n' in url: raise ValueError('Provider URL limit')
    for item in socket.getaddrinfo(parsed.hostname, parsed.port or (443 if parsed.scheme=='https' else 80), type=socket.SOCK_STREAM):
        address=ipaddress.ip_address(item[4][0])
        if not address.is_global: raise ValueError('Private provider address')
    return url

def fetch(url, headers=None, post=None, timeout=8, redirect=True, max_bytes=MAX_BODY, session=None, method=None):
    headers = dict(headers or dnt_headers)
    # Some desktop add-ons explicitly request Brotli/Zstandard, but the Android
    # requests runtime may not contain those optional decoders. Negotiate only
    # encodings that this runtime can decode instead of feeding compressed bytes
    # to the scraper's HTML/JSON parser.
    supported = {value.strip() for value in requests.utils.DEFAULT_ACCEPT_ENCODING.split(',')}
    for key, value in headers.items():
        if key.lower() == 'accept-encoding' and value is not None:
            encodings = [part.strip() for part in value.split(',')
                         if part.split(';')[0].strip() in supported | {'identity'}]
            headers[key] = ', '.join(encodings) or 'identity'
    try:
        current = safe(url)
    except Exception as error:
        _STATE.failure = type(error).__name__
        raise
    owned = session is None
    session = session or requests.Session()
    session.trust_env = False
    method = method or ('POST' if post is not None else 'GET')
    data = post
    try:
        for attempt in range(6):
            check()
            remaining = max(.1, min(float(timeout), getattr(_STATE, 'deadline', time.monotonic()+8)-time.monotonic()))
            response=session.request(method, current, data=data, headers=headers or dnt_headers,
                                     timeout=(min(8, remaining), remaining), allow_redirects=False, stream=True)
            if response.is_redirect and redirect:
                target = requests.compat.urljoin(current, response.headers['Location'])
                safe(target)
                if urlparse(target).netloc != urlparse(current).netloc:
                    # Let requests generate Host for the destination. A scraper's explicit
                    # Host for its own site must not follow a redirect to an external host.
                    headers={k:v for k,v in (headers or {}).items()
                             if k.lower() not in ('cookie','authorization','host')}
                    # None masks Session-level defaults during requests' header merge.
                    for key in session.headers:
                        if key.lower() in ('cookie', 'authorization', 'host'):
                            headers[key] = None
                if response.status_code == 303 or response.status_code in (301,302) and method == 'POST':
                    method, data = 'GET', None
                response.close()
                current=target
                continue
            chunks=[]; size=0
            for chunk in response.iter_content(32768) if max_bytes else ():
                check(); size+=len(chunk)
                if size>max_bytes:
                    if max_bytes == MAX_BODY: raise ValueError('Provider response limit')
                    chunks.append(chunk[:max(0,max_bytes-(size-len(chunk)))]); break
                chunks.append(chunk)
                if max_bytes != MAX_BODY and size >= max_bytes: break
            trace = getattr(_STATE, 'trace', [])
            if len(trace) < 256:
                trace.append(dict(host=urlparse(current).hostname, method=method, status=response.status_code))
            if response.status_code >= 400: _STATE.failure = 'http_' + str(response.status_code)
            response.cookies = session.cookies.copy()
            response._content=b''.join(chunks)
            response._content_consumed=True
            response.encoding='utf-8'
            response.close()
            return response
        raise ValueError('Redirect limit')
    except Exception as error:
        _STATE.failure = type(error).__name__
        raise
    finally:
        if owned: session.close()

def scrapePage(url, referer=None, headers=None, post=None, cookie=None, timeout=8):
    url = 'https:' + url if url.startswith('//') else url
    out=dict(dnt_headers); out.update(headers or {})
    if not any(key.lower() == 'referer' for key in out):
        parsed = urlparse(url)
        out['Referer'] = referer or parsed.scheme + '://' + parsed.netloc
    if cookie: out['Cookie']=cookie
    return fetch(url, out, post, timeout)

def request(url, close=True, redirect=True, error=False, verify=True, post=None, headers=None,
            mobile=False, XHR=False, limit=None, referer=None, cookie=None, compression=False,
            output='', timeout=8, as_bytes=False):
    out=dict(dnt_headers); out.update(headers or {})
    if not any(key.lower() == 'referer' for key in out):
        parsed = urlparse(url)
        out['Referer'] = referer or parsed.scheme + '://' + parsed.netloc + '/'
    if not any(key.lower() == 'accept-language' for key in out):
        out['Accept-Language'] = 'en-US,en'
    if cookie: out['Cookie']=cookie
    if XHR: out['X-Requested-With']='XMLHttpRequest'
    raw, sep, suffix=url.partition('|')
    if sep: out.update(dict(parse_qsl(suffix)))
    if output=='file_size':
        # Kodi's client returns the size from the headers without downloading the file; fetching
        # the body here would hit the response limit on any real film (ValueError).
        result=fetch(raw,out,post,timeout,redirect,max_bytes=0)
        if result.status_code>=400: return 0
        total=(result.headers.get('Content-Range') or '').rpartition('/')[2]
        length=total if total.isdigit() else result.headers.get('Content-Length','')
        return int(length) if str(length).isdigit() else 0
    # Redirect/header/cookie lookup must not download the destination video. Several
    # scrapers resolve directly to full files, which exceeded MAX_BODY before this.
    if output in ('geturl', 'headers', 'cookie'):
        result=fetch(raw,out,post,timeout,redirect,max_bytes=0)
    else:
        result=fetch(raw,out,post,timeout,redirect)
    if output=='cookie': return '; '.join(k+'='+v for k,v in result.cookies.items())
    if output=='geturl': return result.url
    if output=='headers': return result.headers
    if output=='response': return str(result.status_code),result.content if as_bytes else result.text
    if output=='json': return result.json()
    if output=='extended':
        cookies = '; '.join(k+'='+v for k,v in result.cookies.items())
        return result.content if as_bytes else result.text,out,result.headers,cookies,result.url
    return result.content if as_bytes else result.text

def url_ok(url):
    try: return fetch(url).status_code < 400
    except Exception: return False

def failure():
    return getattr(_STATE, "failure", None)


def host_result(value, status):
    """Record separate playback providers without retaining signed URLs or credentials."""
    host = urlparse(value.partition('|')[0]).hostname if '://' in value else value
    host = ''.join(c for c in str(host or '') if c.isalnum() or c in ' ._·()-')[:100]
    hosts = getattr(_STATE, 'hosts', None)
    if host and hosts is not None and len(hosts) < 200:
        previous = hosts.get(host)
        if previous != 'complete':
            hosts[host] = status


def diagnostics():
    return dict(requests=list(getattr(_STATE, 'trace', [])),
                hostAttempts=[dict(provider=host, status=status)
                              for host, status in getattr(_STATE, 'hosts', {}).items()])
