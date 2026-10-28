"""Protocol fixtures for the additional FMHY delivery routes."""
import json
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

from moviehub_compat import client
from moviehub_native import movy


# Frozen output from the public JavaScript decoder's independent implementation,
# with a synthetic seed and URL. No live token or media URL is stored here.
PAYLOAD = ('AG6B5t03hXSLg0sajqMQsxEqKNLwQhVB8hgbk9CUUWRE0FaJ52cWkKzGJPEkF59O'
           'rSgtVulmmToAiMAND2ySrnbSFTEy_KAcp2cW')


class MovyTests(unittest.TestCase):
    def setUp(self):
        client.scope(lambda: False, timeout=30)

    def movie(self, provider):
        return provider.movie('tt1375666', '27205', 'Inception', 'Inception', [], '2010')

    def test_movy_decoder_fixed_vector_and_wrong_seed(self):
        expected = {'sources': [dict(quality='2160p', url='https://media.example/4k.m3u8')]}
        self.assertEqual(movy.decode(PAYLOAD, 'fixture.seed', 27205), expected)
        for seed, identity in [('wrong.seed', 27205), ('fixture.seed', 1396)]:
            with self.assertRaises(ValueError):
                movy.decode(PAYLOAD, seed, identity)
        with self.assertRaises(ValueError):
            movy.decode('bad payload', 'fixture.seed', 27205)

    def test_movy_keeps_both_delivery_routes_even_with_the_same_media_url(self):
        calls = []
        def request(url, **kwargs):
            calls.append(url)
            return {'seed': 'fixture.seed'} if '/seed?' in url else PAYLOAD
        with patch.object(client, 'request', side_effect=request), \
             patch.object(client, 'safe', side_effect=lambda url: url):
            results = movy.source().sources(self.movie(movy.source()), [])
        self.assertEqual([r['originProvider'] for r in results], ['Boise', 'Miami'])
        self.assertTrue(all(r['quality'] == '2160p' and r['direct'] for r in results))
        self.assertEqual(len(calls), 4)
        for url in (calls[1], calls[3]):
            query = parse_qs(urlparse(url).query)
            self.assertEqual(query['tmdbId'], ['27205'])
            self.assertEqual(query['mediaType'], ['movie'])
            self.assertNotIn('seasonId', query)
            self.assertNotIn('episodeId', query)
        self.assertIn('Referer=https%3A%2F%2Fwww.movy.sx%2F', results[0]['url'])

    def test_episode_identity_does_not_replace_series_title_with_episode_title(self):
        for provider in (movy.source(),):
            key = provider.tvshow('tt0903747', '1396', None, 'Breaking Bad', '', [], '2008')
            query = json.loads(provider.episode(key, '', '1396', None, 'Pilot', '', 1, 1))
            self.assertEqual(query['tmdbId'], '1396')
            self.assertEqual(query['seasonId'], '1')
            self.assertEqual(query['episodeId'], '1')
            if 'title' in query:
                self.assertEqual(query['title'], 'Breaking%20Bad')
            self.assertIsNone(provider.movie('', '0', '', '', [], ''))
            self.assertIsNone(provider.episode(key, '', '', None, '', '', 1, 0))

    def test_failed_movy_route_does_not_remove_the_other_route(self):
        def request(url, **kwargs):
            if '/boise/' in url:
                raise ValueError('Fixture failure')
            return {'seed': 'fixture.seed'} if '/seed?' in url else PAYLOAD
        with patch.object(client, 'request', side_effect=request), \
             patch.object(client, 'safe', side_effect=lambda url: url):
            results = movy.source().sources(self.movie(movy.source()), [])
        self.assertEqual([r['originProvider'] for r in results], ['Miami'])

    def test_protected_and_private_media_are_not_returned(self):
        rows = [dict(drm=True, url='https://media.example/protected'),
                dict(url='https://127.0.0.1/private'), dict(url='http://media.example/plain')]
        with patch.object(client, 'request', return_value={'seed': 'fixture.seed'}), \
             patch.object(movy, 'decode', return_value={'sources': rows}):
            self.assertEqual(movy.source().sources(self.movie(movy.source()), []), [])
    def test_cancellation_is_not_swallowed(self):
        client.scope(lambda: True, timeout=30)
        for provider in (movy.source(),):
            with self.assertRaises(client.Cancelled):
                provider.sources(self.movie(provider), [])
