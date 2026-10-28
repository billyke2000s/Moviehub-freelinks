"""Movie lookup and public media flow used by the current Iwaatch web player."""
import base64
import hashlib
import json
import re
import time
from ast import literal_eval
from urllib.parse import parse_qs, urlencode, urljoin, quote
from moviehub_scrubs import cleantitle
from moviehub_compat import client
from moviehub_compat.current_hosts import direct


class source:
    def __init__(self):
        self.base_link = 'https://iwaatch.com'
        self.domains = ['iwaatch.com']
        self.results = []

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return urlencode(dict(title=title, aliases=aliases, year=year))

    def sources(self, url, hostDict):
        data = {key: values[0] for key, values in parse_qs(url).items()}
        aliases = literal_eval(data['aliases'])
        response = client.request(self.base_link + '/api/v1/movie/search?' +
                                  urlencode(dict(q=data['title'], offset=0, limit=40)), output='json')
        movie = next((item for item in response.get('data', [])
                      if str(item.get('release_year')) == data['year']
                      and any(cleantitle.match_alias(item.get(key, ''), aliases)
                              for key in ('title_en', 'title_ar'))), None)
        if not movie:
            return self.results
        # These short-lived values are issued to every anonymous web player. Obtain fresh
        # values together instead of reusing an HTML cache or baking tokens into the APK.
        nonce = str(time.time_ns())
        page_url = self.base_link + '/movie/' + quote(movie['slug'], safe='')
        page = client.request(page_url + '?_=' + nonce)
        token = None
        for chunk in re.findall(r'self\.__next_f\.push\(\[1,("(?:\\.|[^"\\])*")\]\)', page):
            match = re.search(r'"watchToken":"([^"]+)"', json.loads(chunk))
            if match:
                token = match[1]
                break
        if not token:
            return self.results
        challenge = client.request(self.base_link + '/api/v1/pow/challenge?_=' + nonce,
                                   output='json')['data']
        maximum = int(challenge['maxnumber'])
        if challenge['algorithm'] != 'SHA-256' or not 0 <= maximum <= 1000000:
            raise ValueError('Unsupported player challenge')
        proof = None
        for number in range(maximum + 1):
            if number % 1000 == 0:
                client.check()
            if hashlib.sha256((challenge['salt'] + str(number)).encode()).hexdigest() == challenge['challenge']:
                proof = {key: challenge[key] for key in ('algorithm', 'challenge', 'salt', 'signature')}
                proof['number'] = number
                break
        if proof is None:
            return self.results
        headers = {'X-Watch-Token': token,
                   'X-Pow-Token': base64.b64encode(json.dumps(proof, separators=(',', ':')).encode()).decode()}
        endpoint = self.base_link + '/api/v1/movie/' + quote(movie['id'], safe='') + '/files?_=' + nonce
        reply = client.request(endpoint, headers=headers, referer=page_url, output='json')
        files = reply.get('data', {}).get('urls', {}).get('stream', {})
        for quality, link in files.items():
            from urllib.parse import urlparse
            self.results.append(direct(urljoin(page_url, link), page_url, urlparse(link).hostname, quality))
        return self.results

    def resolve(self, url):
        return url
