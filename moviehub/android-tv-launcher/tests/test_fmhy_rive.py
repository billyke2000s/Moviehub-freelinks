"""Offline protocol regressions; live decoding evidence is recorded separately."""
import json
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

from moviehub_compat import client
from moviehub_native import rive


class RiveTests(unittest.TestCase):
    def setUp(self):
        client.scope(lambda: False, timeout=30)
        self.provider = rive.source()

    def movie(self, tmdb='27205'):
        return self.provider.movie('tt1375666', tmdb, 'Inception', 'Inception', [], '2010')

    def test_numeric_movie_and_exact_episode_identity(self):
        self.assertEqual(json.loads(self.movie()), {'id': '27205'})
        for invalid in ['', '0', '-1', '27205&episode=9']:
            self.assertIsNone(self.movie(invalid))
        key = self.provider.tvshow('', '1396', None, 'Breaking Bad', '', [], '2008')
        episode = self.provider.episode(key, '', '1396', None, 'Pilot', '', 1, 1)
        with patch.object(client, 'request', return_value={'data': {'sources': []}}) as request:
            self.assertEqual(self.provider.sources(episode, []), [])
        self.assertEqual(parse_qs(urlparse(request.call_args.args[0]).query),
                         dict(id=['1396'], season=['1'], episode=['1'], provider=['citadel']))
        self.assertIsNone(self.provider.episode(key, '', '', None, '', '', 1, 0))

    def test_separate_resources_keep_quality_language_and_playback_headers(self):
        rows = [dict(format='hls', quality=quality, url='https://media.example/' + str(i))
                for i, quality in enumerate(['720p | English', '720p | English', '480p | Tamil'])]
        with patch.object(client, 'request', side_effect=lambda url, **kw: {'data': {'sources': rows if 'provider=citadel' in url else []}}), \
             patch.object(client, 'safe', side_effect=lambda url: url):
            results = self.provider.sources(self.movie(), [])
        self.assertEqual(len(results), 3)
        self.assertEqual(len({row['url'] for row in results}), 3)
        self.assertEqual([row['quality'] for row in results], ['720p', '720p', '480p'])
        self.assertEqual(results[-1]['originProvider'], 'Citadel · Tamil')
        self.assertTrue(all('Referer=https%3A%2F%2Fwww.rivestream.app%2F' in row['url']
                            for row in results))
        self.assertTrue(all(row['direct'] for row in results))
        self.assertTrue(all(row['segmentContainers'] for row in results))

    def test_wrong_title_episode_protected_and_unsupported_rows_are_rejected(self):
        for data in [dict(tmdbId=1), dict(tmdb_id=1), dict(season=1), dict(episode=1)]:
            with patch.object(client, 'request', return_value={'data': data}):
                self.assertEqual(self.provider.sources(self.movie(), []), [])
        rows = [None, dict(format='hls', drm=True, url='https://media.example/drm'),
                dict(format='embed', url='https://media.example/page'),
                dict(format='hls', url='http://media.example/plain'),
                dict(format='hls', url='https://127.0.0.1/private')]
        with patch.object(client, 'request', return_value={'data': {'sources': rows}}):
            self.assertEqual(self.provider.sources(self.movie(), []), [])

    def test_cancellation_stops_before_processing_more_sources(self):
        client.scope(lambda: True, timeout=30)
        with patch.object(client, 'request', return_value={'data': {'sources': [{}]}}):
            with self.assertRaises(client.Cancelled):
                self.provider.sources(self.movie(), [])

    def test_only_audited_primevids_mirror_is_kept_without_inventing_its_quality(self):
        rows = [dict(format='hls', quality=mirror, url='https://media.example/' + mirror)
                for mirror in ['tcloud', 'dcloud', 'ipcloud']]
        def request(url, **kwargs):
            return {'data': {'sources': rows if 'provider=primevids' in url else []}}
        with patch.object(client, 'request', side_effect=request), \
             patch.object(client, 'safe', side_effect=lambda url: url):
            results = self.provider.sources(self.movie(), [])
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]['quality'], 'Auto')
        self.assertEqual(results[0]['originProvider'], 'Primevids · dcloud')
        self.assertFalse(results[0]['segmentContainers'])

    def test_one_backend_failure_does_not_hide_other_separate_links(self):
        def request(url, **kwargs):
            if 'provider=primevids' in url:
                raise ValueError('Fixture failure')
            if 'provider=vanguard' in url:
                return {'data': {'sources': []}}
            return {'data': {'sources': [dict(format='hls', quality='720p | English',
                                            url='https://media.example/video.m3u8')]}}
        with patch.object(client, 'request', side_effect=request), \
             patch.object(client, 'safe', side_effect=lambda url: url):
            results = self.provider.sources(self.movie(), [])
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]['originProvider'], 'Citadel · English')

    def test_vanguard_is_separate_for_episodes_but_not_enabled_for_movies(self):
        def request(url, **kwargs):
            rows = [dict(format='hls', quality='4K HDR', url='https://media.example/vanguard')]
            return {'data': {'sources': rows if 'provider=vanguard' in url else []}}
        with patch.object(client, 'request', side_effect=request), \
             patch.object(client, 'safe', side_effect=lambda url: url):
            key = self.provider.tvshow('', '1396', None, 'Breaking Bad', '', [], '2008')
            episode = self.provider.episode(key, '', '1396', None, 'Pilot', '', 1, 1)
            results = self.provider.sources(episode, [])
            self.assertEqual(self.provider.sources(self.movie(), []), [])
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]['originProvider'], 'Vanguard · 4K HDR')
        self.assertEqual(results[0]['quality'], '2160p')
        self.assertFalse(results[0]['segmentContainers'])
