"""Movy's public TMDB lookup, using the site's Boise and Miami delivery routes."""
import base64
import json
from urllib.parse import quote, urlencode

from moviehub_compat import client
from moviehub_compat.current_hosts import direct
from .rive import positive


MASK = 0xffffffff


def mix(value):
    value &= MASK
    value = ((value ^ (value >> 16)) * 0x85ebca6b) & MASK
    value = ((value ^ (value >> 13)) * 0xc2b2ae35) & MASK
    return (value ^ (value >> 16)) & MASK


def rotate(value, shift):
    value &= MASK
    shift &= 31
    return ((value << shift) | (value >> ((32 - shift) & 31))) & MASK


def decode(payload, seed, media_id):
    """Translate the public player's data decoder; never execute downloaded code."""
    if not isinstance(payload, str) or len(payload) > client.MAX_BODY:
        raise ValueError('Invalid Movy payload')
    data = bytearray(base64.b64decode(payload.replace('-', '+').replace('_', '/') + '===',
                                    validate=False))
    hashed = 0x811c9dc5
    for char in seed:
        hashed = ((hashed ^ ord(char)) * 0x1000193) & MASK
    state = mix(mix(hashed) ^ mix(int(media_id) ^ 0x9e3779b9))
    table = {}
    for index in range(8):
        slot = state % 61
        state = rotate(state + 0x9e3779b9, 7 + index)
        table[slot] = state ^ mix(state)
        state = mix(state + slot)
    state = mix(0xa5a5a5a5 ^ state)
    for offset in range(0, len(data), 4):
        slot = state % 61
        other = table.get(slot, 0) ^ ((0x9e3779b9 * (offset // 4 + 1)) & MASK)
        combined = (state ^ other) | (state & other if slot in table else 0)
        state = mix((rotate(combined + state, slot) ^ rotate(state, slot * 7)) + 0x9e3779b9)
        table[slot] = state
        for index in range(min(4, len(data) - offset)):
            data[offset + index] ^= (state >> (index * 8)) & 255
    if not data.startswith(b'mvm1'):
        raise ValueError('Invalid Movy seed or payload')
    return json.loads(data[4:])


class source:
    base_link = 'https://www.movy.sx'
    api = 'https://api.wecollege.net'

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        if not positive(tmdb):
            return None
        return json.dumps(dict(title=quote(title, safe="~()*!.'-"), mediaType='movie',
                               year=str(year), tmdbId=str(tmdb), imdbId=imdb))

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        key = self.movie(imdb, tmdb, title, localtitle, aliases, year)
        if key is None:
            return None
        query = json.loads(key)
        query['mediaType'] = 'tv'
        return json.dumps(query)

    def episode(self, key, imdb, tmdb, tvdb, title, premiered, season, episode):
        if key is None or not str(season).isdigit() or not positive(episode):
            return None
        query = json.loads(key)
        query.update(seasonId=str(season), episodeId=str(episode))
        return json.dumps(query)

    def sources(self, key, hosts):
        if key is None:
            return []
        query = json.loads(key)
        headers = {'Origin': self.base_link}
        results = []
        for backend in ('boise', 'miami'):
            client.check()
            try:
                # A fresh anonymous seed is short lived; never persist it or media URLs.
                reply = client.request(self.api + '/seed?' + urlencode({'mediaId': query['tmdbId']}),
                                       headers=headers, referer=self.base_link + '/', output='json')
                seed = reply['seed']
                if not isinstance(seed, str) or not seed.isascii() or len(seed) > 256:
                    raise ValueError('Invalid Movy seed')
                endpoint = self.api + '/' + backend + '/sources?'
                payload = client.request(endpoint + urlencode(dict(query, enc='2', seed=seed)),
                                         headers=headers, referer=self.base_link + '/')
                data = decode(payload, seed, query['tmdbId'])
                for row in data.get('sources', [])[:32]:
                    client.check()
                    if not isinstance(row, dict) or row.get('drm'):
                        continue
                    url = row.get('url')
                    if not isinstance(url, str):
                        continue
                    client.safe(url)
                    claim = str(row.get('quality', ''))
                    quality = claim if claim in ('2160p', '1080p', '720p', '480p', '360p') else 'Auto'
                    results.append(direct(url, self.base_link + '/', backend.capitalize(), quality))
            except Exception as error:
                client.host_result('Movy ' + backend, type(error).__name__)
        return results

    def resolve(self, url):
        return url
