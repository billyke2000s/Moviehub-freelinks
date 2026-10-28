"""Read-only comparison of app/browser HTTP identity on reachable, challenge-blocked sites."""
import concurrent.futures
import json
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlparse
import requests
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
from moviehub_compat import client

BROWSER = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
PAGES = [
    'https://watch-tvseries.net/search?q=inception',
    'https://www.watch-tvseries.tv/movies/inception-51338',
    'https://www.watch-tvseries.tv/search?q=inception',
    'https://www.tvids.to/search?q=inception',
    'https://www.tvids.net/movies/inception-51338',
    'https://www.tvids.net/search?q=inception',
    'https://www.tvseries.video/search?q=inception',
    'https://bluray7.com/?s=Crawlers',
    'https://freeprojecttv.cyou/movies/inception-2010/',
    'https://watchseries.cyou/movies/inception-2010/',
    'https://www.naijavault.com/search/inception/',
    'https://bstsrs.in/show/star-trek-s01e01/season/1/episode/1',
    'https://tvmovieflix.com/?s=Inception',
]


def probe(task):
    url, mode = task
    host = urlparse(url).hostname
    try:
        if mode == 'curl-browser':
            result = subprocess.run(['curl', '--compressed', '-sS', '-L', '--max-time', '15',
                                     '--max-filesize', '1500000', '-A', BROWSER,
                                     '-w', '\nHTTP:%{http_code}', url], capture_output=True, text=True)
            body, unused, status = result.stdout.rpartition('\nHTTP:')
        else:
            with requests.Session() as session:
                session.headers.update({'User-Agent': client.UserAgent if mode == 'app' else BROWSER})
                if mode == 'browser-session':
                    session.headers.update({'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                                            'Accept-Language': 'en-US,en;q=0.9'})
                    landing = 'https://' + host + '/'
                    session.get(landing, timeout=8)
                    session.headers['Referer'] = landing
                response = session.get(url, timeout=8)
                status, body = response.status_code, response.text
        challenge = any(value in body.lower() for value in ('just a moment', 'cf-chl-', 'verify you are human'))
        return dict(host=host, mode=mode, status=status, bytes=len(body), challenge=challenge)
    except Exception as error:
        return dict(host=host, mode=mode, error=type(error).__name__)


if __name__ == '__main__':
    tasks = [(url, mode) for url in PAGES for mode in ('app', 'browser-session', 'curl-browser')]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
        for result in workers.map(probe, tasks):
            print(json.dumps(result), flush=True)
