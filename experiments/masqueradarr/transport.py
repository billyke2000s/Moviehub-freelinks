"""Bounded HTTPS client for the experimental FAST adapters. Never logs responses or URLs."""
from __future__ import annotations

import gzip
import io
import ipaddress
from urllib.error import HTTPError
from urllib.parse import urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0"
MAX_BYTES = 8 * 1024 * 1024


def https_url(value: str) -> str:
    parts = urlsplit(value)
    if (parts.scheme != "https" or not parts.hostname or parts.username is not None
            or parts.password is not None or parts.fragment or parts.port not in (None, 443)):
        raise ValueError("Invalid HTTPS endpoint")
    host = parts.hostname.lower()
    if host == "localhost" or host.endswith((".localhost", ".local", ".invalid")):
        raise ValueError("Local endpoint is not a provider")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        if not address.is_global:
            raise ValueError("Private endpoint is not a provider")
    return value


def headers(origin: str | None = None) -> dict[str, str]:
    result = {"User-Agent": USER_AGENT, "Accept": "application/json, text/plain, */*"}
    if origin:
        result.update({"Origin": origin, "Referer": origin + "/"})
    return result


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, response_headers, newurl):
        return None


def redirect_headers(original: str, destination: str, custom: dict) -> dict:
    # Session tokens, cookies and other client headers never cross authorities.
    return dict(custom) if urlsplit(original).netloc.lower() == urlsplit(destination).netloc.lower() else {}


def decode(raw: bytes, maximum: int = MAX_BYTES) -> str:
    if len(raw) > maximum:
        raise ValueError("Provider response too large")
    if raw.startswith(b"\x1f\x8b"):
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
            raw = stream.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError("Decoded response too large")
    return raw.decode("utf-8")


class Http:
    def __init__(self, timeout: float = 8):
        self.timeout = timeout
        self.opener = build_opener(NoRedirect())

    def request(self, url: str, method: str = "GET", custom: dict | None = None) -> str:
        original = current = https_url(url)
        custom = custom or {}
        if method not in ("GET", "POST"):
            raise ValueError("Unsupported request method")
        for _ in range(4):
            request = Request(current, data=b"" if method == "POST" else None,
                              headers={**headers(), **redirect_headers(original, current, custom)},
                              method=method)
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    return decode(response.read(MAX_BYTES + 1))
            except HTTPError as error:
                try:
                    if error.code not in (301, 302, 303, 307, 308):
                        raise OSError(f"Provider unavailable ({error.code})") from None
                    location = error.headers.get("Location")
                    if not location:
                        raise OSError("Provider redirect missing") from None
                    current = https_url(urljoin(current, location))
                    if error.code == 303 or (error.code in (301, 302) and method == "POST"):
                        method = "GET"
                finally:
                    error.close()
        raise OSError("Too many provider redirects")
