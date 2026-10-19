"""Resolve ordinary SportHD iframe/player configuration into direct HLS URLs.

Does not circumvent DRM, authentication, CAPTCHA, or access restrictions.
"""
import json
import re
from html import unescape
from urllib.parse import urljoin, urlsplit, parse_qs, urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

USER_AGENT = "Mozilla/5.0 (compatible; MovieHub-FreeLinks/1.0)"
MAX_BYTES = 1024 * 1024


def direct_hls(url):
    p = urlsplit(url) if isinstance(url, str) else urlsplit("")
    return p.scheme in ("https", "http") and bool(p.hostname) and p.path.lower().endswith(".m3u8")


def verified_hls(url, referer=None):
    """Accept .php or .m3u8 endpoints only if their response is an HLS manifest."""
    if not isinstance(url, str):
        return False
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname:
        return False
    headers = {"User-Agent": USER_AGENT, "Range": "bytes=0-2047"}
    if referer:
        headers["Referer"] = referer
        origin = urlsplit(referer)
        headers["Origin"] = f"{origin.scheme}://{origin.netloc}"
    try:
        with urlopen(Request(url, headers=headers), timeout=8) as response:
            return response.read(2048).lstrip().startswith(b"#EXTM3U")
    except (OSError, ValueError):
        return False


def extract_player_url(body, base_url):
    try:
        payload = json.loads(body)
        for key in ("src", "srcBase", "file", "source", "url"):
            candidate = payload.get(key)
            if isinstance(candidate, str) and direct_hls(candidate):
                return candidate
    except (ValueError, AttributeError):
        pass
    patterns = [
        r"""(?:src|srcBase|file|source)\s*:\s*["']([^"']+)["']""",
        r"""["'](?:src|srcBase|file|source)["']\s*:\s*["']([^"']+)["']""",
        r"""player\.setSrc\(\s*["']([^"']+)["']""",
        r"""<source[^>]+src=["']([^"']+)["']""",
        r"""(https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?)""",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, body, re.I):
            candidate = unescape(match.group(1)).replace(r"\/", "/")
            candidate = urljoin(base_url, candidate)
            if direct_hls(candidate):
                return candidate
    return None



SUPPORTED_HOSTS = {"kora.st", "nexa.st", "vertex.st"}

def resolve_host_api(url, fetch):
    """Follow the public SportHD player API; no credentials or protection bypass."""
    parsed = urlsplit(url)
    host = (parsed.hostname or "").lower()
    base = host.removeprefix("s1.").removeprefix("s2.").removeprefix("s3.")
    if base not in SUPPORTED_HOSTS or parsed.scheme != "https":
        return None
    channel_id = parse_qs(parsed.query).get("id", [None])[0]
    if not channel_id or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", channel_id):
        return None
    mirrors = [host]
    for prefix in ("", "s2.", "s1.", "s3."):
        candidate = prefix + base
        if candidate not in mirrors:
            mirrors.append(candidate)
    for mirror in mirrors:
        origin = f"https://{mirror}/"
        endpoint = urljoin(origin, "api/player.php?" + urlencode({"id": channel_id}))
        try:
            data = json.loads(fetch(endpoint, origin))
            frame = data.get("url")
            if not isinstance(frame, str) or urlsplit(frame).scheme != "https":
                continue
            if direct_hls(frame) and verified_hls(frame, origin):
                return frame
            # These player hosts expose their normal configuration through ppcfg=1.
            separator = "&" if "?" in frame else "?"
            try:
                config = fetch(frame + separator + "ppcfg=1", origin)
                stream = extract_player_url(config, frame)
                if stream and verified_hls(stream, frame):
                    return stream
                # Some hosts serve manifests from PHP endpoints.
                try:
                    payload = json.loads(config)
                    for field in ("src", "srcBase"):
                        candidate = payload.get(field)
                        if isinstance(candidate, str) and verified_hls(candidate, frame):
                            return candidate
                except (ValueError, AttributeError):
                    pass
            except (OSError, ValueError, KeyError):
                pass
            try:
                stream = resolve(frame, fetch=fetch, max_hops=3, use_api=False)
                if stream:
                    return stream
            except (OSError, ValueError):
                pass
        except (OSError, ValueError, TypeError, KeyError):
            continue
    return None


def resolve(url, fetch=None, max_hops=2, use_api=True):
    if direct_hls(url):
        return url
    if not urlsplit(url).scheme == "https":
        return None
    def default_fetch(target, referer):
        req = Request(target, headers={"User-Agent": USER_AGENT, "Referer": referer})
        with urlopen(req, timeout=12) as resp:
            if len(data := resp.read(MAX_BYTES + 1)) > MAX_BYTES:
                raise ValueError("Player page too large")
            return data.decode("utf-8", errors="replace")
    fetch = fetch or default_fetch
    api_stream = resolve_host_api(url, fetch) if use_api else None
    if api_stream:
        return api_stream
    current = url
    visited = set()
    for _ in range(max_hops):
        if current in visited:
            break
        visited.add(current)
        page = fetch(current, url)
        stream = extract_player_url(page, current)
        if stream:
            return stream
        match = re.search(r"""<iframe[^>]+src=["']([^"']+)["']""", page, re.I)
        if not match:
            break
        next_url = urljoin(current, unescape(match.group(1)))
        if urlsplit(next_url).scheme != "https":
            break
        current = next_url
    return None
