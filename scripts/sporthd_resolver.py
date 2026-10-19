"""Resolve ordinary SportHD iframe/player configuration into direct HLS URLs.

Does not circumvent DRM, authentication, CAPTCHA, or access restrictions.
"""
import json
import re
from html import unescape
from urllib.parse import urljoin, urlsplit
from urllib.request import Request, urlopen

USER_AGENT = "Mozilla/5.0 (compatible; MovieHub-FreeLinks/1.0)"
MAX_BYTES = 1024 * 1024


def direct_hls(url):
    p = urlsplit(url) if isinstance(url, str) else urlsplit("")
    return p.scheme in ("https", "http") and bool(p.hostname) and p.path.lower().endswith(".m3u8")


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


def resolve(url, fetch=None, max_hops=2):
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
        match = re.search(r'<iframe[^>]+src=["\\']([^"\\']+)["\\']', page, re.I)
        if not match:
            break
        next_url = urljoin(current, unescape(match.group(1)))
        if urlsplit(next_url).scheme != "https":
            break
        current = next_url
    return None
