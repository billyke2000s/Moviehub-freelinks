"""Current public native catalogue and independent mirror regressions."""
import base64
import codecs
import json
import time
import unittest
from unittest.mock import patch
import requests
from moviehub_compat import client
from moviehub_native import fourkhdhub, vixsrc


class NativeSiteTests(unittest.TestCase):
    def setUp(self):
        transport = patch.object(requests.Session, 'request', side_effect=AssertionError('Unexpected network'))
        transport.start()
        self.addCleanup(transport.stop)
        client.scope(lambda: False, timeout=30)

    def payload(self, url):
        data = json.dumps({'o': base64.b64encode(url.encode()).decode()}).encode()
        encoded = codecs.encode(base64.b64encode(data).decode(), 'rot13').encode()
        return base64.b64encode(base64.b64encode(encoded)).decode()

    def test_literal_redirect_data_is_decoded_without_executing_its_scripts(self):
        url = 'https://hubcloud.ist/video/one'
        self.assertEqual(fourkhdhub.landing_target("s('o','" + self.payload(url) + "',180000);"), url)
        self.assertIsNone(fourkhdhub.landing_target("fetch('https://unexpected.example')"))
        with self.assertRaises(Exception):
            fourkhdhub.landing_target("s('o','AAAA',180000)")

    def test_exact_catalogue_identity_and_independent_mirrors_survive_one_broken_file(self):
        provider = fourkhdhub.source()
        search = '''<a class="movie-card" href="/wrong"><h3 class="movie-card-title">Film</h3>
                    <div class="movie-card-meta">2021</div></a>
                    <a class="movie-card" href="/right"><h3 class="movie-card-title">Film</h3>
                    <div class="movie-card-meta">2020</div></a>'''
        detail = '''<div class="download-item"><div class="file-title">Film 2020 2160p.mkv</div>
                    <a href="/broken">Download HubCloud</a><a href="/landing">Download HubCloud</a></div>'''
        calls = []
        def request(url, **kwargs):
            calls.append(url)
            if '?s=' in url: return search
            if url.endswith('/right'): return detail
            if url.endswith('/broken'): raise ValueError('Unavailable mirror')
            if url.endswith('/landing'): return "s('o','" + self.payload('https://hubcloud.ist/video/one') + "',180000)"
            if url.endswith('/video/one'): return "var url = 'https://hubcloud.ist/files/one';"
            if url.endswith('/files/one'): return '''<a href="https://worker-a.example/Film + Cut.mkv">Download File</a>
                        <a href="https://worker-b.example/Film.mkv">Download File</a>
                        <a href="https://ad.example">Advert</a>'''
            raise AssertionError('Unexpected path')
        with patch.object(client, 'request', side_effect=request), patch.object(client, 'safe', side_effect=lambda x: x):
            key = provider.movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020')
            rows = provider.sources(key, [])
        self.assertEqual(len(rows), 2)
        self.assertEqual({x['source'] for x in rows}, {'HubCloud · worker-a.example', 'HubCloud · worker-b.example'})
        self.assertTrue(all(x['quality'] == '2160p' and x['direct'] and x['boundedRanges'] for x in rows))
        self.assertIn('Film%20%2B%20Cut.mkv', rows[0]['url'])
        self.assertFalse(any(x.endswith('/wrong') for x in calls))

    def test_missing_matching_year_does_not_guess_a_detail_url(self):
        provider = fourkhdhub.source()
        with patch.object(client, 'request', return_value='<a class="movie-card" href="/wrong">Film 2021</a>') as call:
            self.assertEqual(provider.sources(provider.movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020'), []), [])
        self.assertEqual(call.call_count, 1)

    def test_vixsrc_fetches_fresh_embed_preserves_token_query_and_supports_exact_episodes(self):
        provider = vixsrc.source()
        future = str(int(time.time()) + 300)
        calls = []
        def request(url, **kwargs):
            calls.append(url)
            if '/api/' in url: return {'src': '/embed/one'}
            return "var masterPlaylist = { params: {'token': 'issued', 'expires': '" + future + "'}, url: 'https://cdn.example/master.m3u8?existing=1'}"
        with patch.object(client, 'request', side_effect=request), patch.object(client, 'safe', side_effect=lambda x: x):
            series = provider.tvshow('tt1', '12', '', 'Show', 'Show', [], '2020')
            key = provider.episode(series, 'tt1', '12', '', 'Episode', '', '2', '3')
            rows = provider.sources(key, [])
        self.assertTrue(calls[0].startswith('https://vixsrc.to/api/tv/12/2/3?_='))
        self.assertEqual(len(rows), 1)
        self.assertIn('?existing=1&token=issued&expires=' + future + '&h=1', rows[0]['url'])
        self.assertEqual(rows[0]['source'], 'VixCloud')

    def test_expired_player_token_is_not_offered_as_playable(self):
        provider = vixsrc.source()
        with patch.object(client, 'request', side_effect=[{'src': '/embed/one'},
            "var p={params:{'token':'old','expires':'1'},url:'https://cdn.example/master.m3u8'}"]), \
            patch.object(client, 'safe', side_effect=lambda x: x):
            self.assertEqual(provider.sources('/api/movie/1', []), [])
        self.assertIsNone(provider.movie('tt1', 'bad', 'Film', 'Film', [], '2020'))
