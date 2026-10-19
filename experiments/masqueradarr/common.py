"""Stable metadata contracts; resolved media URLs and anonymous sessions are memory-only."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
import re
import time
from urllib.parse import quote

from transport import https_url

ID = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")


@dataclass(frozen=True)
class Programme:
    start: int
    stop: int
    title: str
    description: str = ""


@dataclass(frozen=True)
class Channel:
    provider: str
    id: str
    name: str
    group: str
    logo: str = ""
    country: str = ""
    programmes: tuple[Programme, ...] = ()
    # Never serialise this provider state into public outputs.
    stream: str = field(default="", repr=False)

    @property
    def key(self) -> str:
        return f"{self.provider}:{self.id}"

    def metadata(self) -> dict:
        return {"provider": self.provider, "id": self.id, "name": self.name,
                "group": self.group, "logo": self.logo, "country": self.country}


def channel(provider, id, name, group, logo="", country="", programmes=(), stream=""):
    id, name = str(id), str(name).strip()
    if not ID.fullmatch(id) or not name:
        raise ValueError("Invalid channel identity")
    try:
        logo = https_url(logo)
    except (TypeError, ValueError):
        logo = ""
    return Channel(provider, id, name, str(group), logo, country, tuple(programmes), stream)


def timestamp(value) -> int:
    try:
        return int(datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp() * 1000)
    except (ValueError, TypeError, OverflowError):
        return 0


def expand(url: str, values: dict, *, percent=False, reject_unknown=True) -> str:
    pattern = re.compile(r"\[(%?)(\w+?)\1\]" if percent else r"\[([A-Za-z0-9_]+)\]")
    def replace(match):
        key = match.group(2 if percent else 1)
        if key not in values and reject_unknown:
            raise ValueError("Unknown stream macro")
        return quote(str(values.get(key, "")), safe="")
    result = pattern.sub(replace, url)
    if "[" in result or "]" in result:
        raise ValueError("Unresolved stream macro")
    return https_url(result)


class Source:
    id = ""
    name = ""

    def __init__(self, http, clock=time.monotonic):
        self.http, self.clock = http, clock
        self._channels = ()
        self._expires = 0

    def catalogue(self) -> tuple[Channel, ...]:
        if self._channels and self.clock() < self._expires:
            return self._channels
        rows = tuple(self.fetch())
        if not rows or len(rows) > 5000 or sum(len(c.programmes) for c in rows) > 50000:
            raise ValueError("No usable bounded catalogue")
        self._channels, self._expires = rows, self.clock() + 600
        return rows

    def resolve(self, channel_id: str) -> tuple[str, dict]:
        if not ID.fullmatch(channel_id):
            raise ValueError("Invalid channel identity")
        for row in self.catalogue():
            if row.id == channel_id:
                return https_url(self.stream(row)), self.playback_headers()
        raise ValueError("Channel unavailable in the current catalogue")

    def playback_headers(self) -> dict:
        from transport import USER_AGENT
        return {"User-Agent": USER_AGENT}
