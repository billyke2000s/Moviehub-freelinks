from ast import literal_eval
# -*- coding: utf-8 -*-

import re

from six.moves.urllib_parse import parse_qs, urlencode

from moviehub_scrubs import cleantitle
from moviehub_scrubs import client
from moviehub_scrubs import client_utils
from moviehub_scrubs import scrape_sources
#from moviehub_scrubs import log_utils

DOM = client_utils.parseDOM


class source:
    def __init__(self):
        self.results = []
        self.domains = ['pressplay.top', 'pressplayz.top']
        self.base_link = 'https://pressplayz.top'
        self.search_link = '/?s=%s'
        self.notes = 'dupe site of movies4fun_net or soap2day_fan and 123movies_skin.'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'tmdb': tmdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tmdb': tmdb, 'tvshowtitle': tvshowtitle, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return
        url = parse_qs(url)
        url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
        url['title'], url['premiered'], url['season'], url['episode'] = title, premiered, season, episode
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        if not url:
            return self.results
        data = {key: value[0] for key, value in parse_qs(url).items()}
        # The current site's server selector obtains this public list, then expands TMDB IDs.
        servers = client.request(self.base_link + '/api/player-servers', output='json')
        replacements = {'id': data['tmdb'], 'season': data.get('season', '1'),
                        'episode': data.get('episode', '1'), 'time': '0'}
        field = 'tvUrl' if 'tvshowtitle' in data else 'movieUrl'
        for server in servers:
            if not server.get('active') or not server.get(field):
                continue
            link = server[field]
            for key, value in replacements.items():
                link = link.replace('{' + key + '}', value)
            try:
                self.results.extend(scrape_sources.process(hostDict, link))
            except Exception:
                continue
        return self.results


    def resolve(self, url):
        return url


