"""Current Vixsrc public TMDB API and token-bearing embed; never executes its JavaScript."""
import json
import re
import time
from urllib.parse import urlencode, urljoin
from moviehub_compat import client
from moviehub_compat.current_hosts import direct


class source:
    base_link = 'https://vixsrc.to'

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        if not str(tmdb).isdigit():
            return None
        return '/api/movie/' + str(tmdb)

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return str(tmdb) if str(tmdb).isdigit() else None

    def episode(self, key, imdb, tmdb, tvdb, title, premiered, season, episode):
        if key is None or not str(season).isdigit() or not str(episode).isdigit():
            return None
        return '/api/tv/' + key + '/' + str(season) + '/' + str(episode)

    def sources(self, key, hosts):
        if key is None:
            return []
        api = self.base_link + key
        response = client.request(api + '?' + urlencode({'_': time.time_ns()}), output='json',
                                  referer=self.base_link + '/')
        if not isinstance(response, dict) or not isinstance(response.get('src'), str):
            return []
        embed = client.safe(urljoin(self.base_link, response['src']))
        page = client.request(embed, referer=self.base_link + '/')
        token = re.search(r'''token["']\s*:\s*["']([^"']+)''', page)
        expires = re.search(r'''expires["']\s*:\s*["'](\d+)''', page)
        playlist = re.search(r'''\burl\s*:\s*["'](https://[^"']+)''', page)
        if not token or not expires or not playlist or int(expires[1]) <= time.time() + 30:
            return []
        address = client.safe(playlist[1])
        address += ('&' if '?' in address else '?') + urlencode(dict(token=token[1], expires=expires[1], h=1))
        return [direct(address, api, 'VixCloud', 'Auto')]

    def resolve(self, url):
        return url
