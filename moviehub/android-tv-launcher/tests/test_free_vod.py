"""Offline tests of real bundled provider code and its MovieHub adapter."""
import importlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import requests
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'app/src/main/python'))
import moviehub_vod as vod
from moviehub_compat import client

class FreeVodTests(unittest.TestCase):
    def setUp(self):
        # Upstream constructors sometimes fetch their homepage. Offline regressions must
        # never depend on its availability; individual transport fixtures override this.
        def offline_request(session, method, url, **kwargs):
            raise AssertionError('Unexpected live HTTP request in an offline regression')
        transport = patch.object(requests.Session, 'request', new=offline_request)
        transport.start()
        self.addCleanup(transport.stop)

    def test_search_inventory_counts_websites_without_losing_bundled_adapters(self):
        everything = json.loads(vod.search_providers(include_retired=True))
        self.assertEqual(len(everything), 67)
        self.assertEqual(len({r['site'] for r in everything}), 67)
        self.assertEqual(sum(len(r['adapters']) for r in everything), 70)
        rows = json.loads(vod.search_providers())
        # Eleven confirmed retired/repurposed domains and 18 unverified restorations stay out of discovery.
        self.assertEqual(len(vod.RETIRED_SITES), 11)
        self.assertEqual(len(vod.UNVERIFIED_SITES), 18)
        self.assertEqual(len(rows), 38)
        self.assertEqual(sum(len(r['adapters']) for r in rows), 41)
        excluded = vod.RETIRED_SITES | vod.UNVERIFIED_SITES
        self.assertFalse(any(r['site'] in excluded for r in rows))
        self.assertTrue(vod.RETIRED_SITES.isdisjoint(vod.UNVERIFIED_SITES))
        self.assertTrue(vod.RETIRED_SITES <= {r['site'] for r in everything})
        self.assertFalse(any('library' in r['id'] or 'filepursuit' in r['id'] for r in everything))
        levidia = next(r for r in rows if r['site'] == 'levidia.ch')
        self.assertEqual(levidia['adapters'], ['free99:levidia_ch', 'scrubs:levidia_ch'])
        self.assertTrue(levidia['movies'] and levidia['series'])
        self.assertEqual(vod.validate(), 72)

    def test_only_levidia_is_restored_from_the_original_26_exclusions(self):
        originally_excluded = set(('123-movies.golf 123moviestv.me 1movies.la 9pm.to cinebox.cc '
            'couchtuner.show dopebox.to ev01.to filmxy.online flixhq.to flixwave.me goojara.life '
            'hdbest.net hdtoday.cc levidia.ch m4ufree.se movies2watch.tv movies4fun.net mp4hydra.top '
            'projectfreetv.lol putlocker.gives sflixz.watch soap2dayz.top streamlord.com watchug.com '
            'www1.movie4u.live').split())
        active = {row['site'] for row in json.loads(vod.search_providers())}
        self.assertEqual(active & originally_excluded, {'levidia.ch'})

    def test_same_site_uses_alternate_adapter_only_after_primary_has_no_links(self):
        from unittest.mock import Mock
        class Emit:
            def __init__(self): self.items=[]
            def ready(self,value): self.items.append(json.loads(value))
        emit=Emit()
        item=dict(url='https://media.example/movie.mp4',headers={},provider='scrubs:levidia_ch')
        def query(adapter, raw, cancelled, callback, deadline):
            if adapter.startswith('scrubs:'): callback.ready(json.dumps(item))
            return json.dumps(dict(status='complete' if adapter.startswith('scrubs:') else 'no_matches',sources=[]))
        with patch.object(vod,'query',side_effect=query) as call:
            result=json.loads(vod.query_site('free99:levidia_ch',json.dumps({'season':-1}),lambda:False,emit))
        self.assertEqual(call.call_count,2)
        self.assertEqual(result['status'],'complete')
        self.assertEqual(emit.items[0]['providerName'],'levidia.ch · media.example')
        self.assertEqual(emit.items[0]['provider'],'free99:levidia_ch')
        self.assertEqual(emit.items[0]['adapter'],'scrubs:levidia_ch')

    def test_empty_fallback_cannot_hide_a_host_failure(self):
        reports=[dict(status='ConnectTimeout',sources=[],extracted=1,resolutionErrors=1),
                 dict(status='no_matches',sources=[])]
        with patch.object(vod,'query',side_effect=[json.dumps(r) for r in reports]):
            result=json.loads(vod.query_site('free99:levidia_ch',json.dumps({'season':-1}),lambda:False))
        self.assertEqual(result['status'],'ConnectTimeout')
        self.assertEqual(result['extracted'],1)
        self.assertEqual(result['resolutionErrors'],1)

    def test_media_redirect_preserves_final_host_cookie_without_leaking_original_credentials(self):
        response=requests.Response();response.status_code=200
        response.url='https://cdn.example/final.mp4';response._content=b'ftyp'
        response.headers['Content-Type']='video/mp4'
        response.cookies.set('cdn','issued',domain='cdn.example',path='/')
        response.cookies.set('other','private',domain='other.example',path='/')
        with patch.object(client,'safe',side_effect=lambda url:url),patch.object(client,'fetch',return_value=response) as fetch:
            result=vod.playable('https://original.example/film.mp4|Cookie=old%3Dprivate&Authorization=private')
        self.assertEqual(result['url'],response.url)
        self.assertEqual(result['headers']['Cookie'],'cdn=issued')
        self.assertNotIn('Authorization',result['headers'])
        self.assertEqual(fetch.call_args.kwargs['timeout'],8)

    def test_requests_session_uses_browser_identity_and_encodes_query_lists(self):
        from moviehub_compat import http_requests
        with patch.object(client,'fetch') as fetch:
            with http_requests.Session() as session:
                session.get('https://site.example/search',params={'id':['one','two']})
            self.assertEqual(fetch.call_args.args[1]['User-Agent'],client.UserAgent)
            self.assertEqual(fetch.call_args.args[0],'https://site.example/search?id=one&id=two')

    def test_cross_site_redirect_drops_the_old_host_and_credentials(self):
        first=requests.Response();first.status_code=302;first.url='https://site.example/out'
        first.headers['Location']='https://host.example/embed';first._content=b''
        second=requests.Response();second.status_code=200;second.url='https://host.example/embed'
        second._content=b'ok'
        first._content_consumed=True;second._content_consumed=True
        client.scope(lambda:False)
        with patch.object(client,'safe',side_effect=lambda url:url), \
             patch.object(requests.Session,'request',side_effect=[first,second]) as request:
            client.fetch(first.url,headers={'Host':'site.example','Cookie':'private=1',
                                          'Authorization':'private','Referer':first.url})
        headers=request.call_args_list[1].kwargs['headers']
        self.assertNotIn('Host',headers)
        self.assertNotIn('Cookie',headers)
        self.assertNotIn('Authorization',headers)
        self.assertEqual(headers['Referer'],first.url)

    def test_metadata_requests_never_read_a_video_body(self):
        for output in ('headers','geturl','cookie','file_size'):
            with self.subTest(output=output):
                response=requests.Response();response.status_code=200
                response.url='https://media.example/large.mp4'
                response.headers['Content-Length']='2000000000'
                response.iter_content=unittest.mock.Mock(side_effect=AssertionError('body downloaded'))
                response.close=lambda:None
                client.scope(lambda:False)
                with patch.object(client,'safe',side_effect=lambda url:url), \
                     patch.object(requests.Session,'request',return_value=response):
                    result=client.request(response.url,output=output)
                response.iter_content.assert_not_called()
                if output=='file_size': self.assertEqual(result,2000000000)
                if output=='geturl': self.assertEqual(result,response.url)

    def test_scrapers_only_negotiate_compression_the_runtime_can_decode(self):
        response=requests.Response();response.status_code=200;response.url='https://site.example/'
        response._content=b'ok';response._content_consumed=True
        original={'Accept-Encoding':'gzip, deflate, br, zstd'}
        client.scope(lambda:False)
        with patch.object(client,'safe',side_effect=lambda url:url), \
             patch.object(requests.utils,'DEFAULT_ACCEPT_ENCODING','gzip, deflate'), \
             patch.object(requests.Session,'request',return_value=response) as request:
            client.fetch(response.url,headers=original)
            self.assertEqual(request.call_args.kwargs['headers']['Accept-Encoding'],'gzip, deflate')
        self.assertEqual(original['Accept-Encoding'],'gzip, deflate, br, zstd')

    def test_free99_levidia_with_no_landing_cookie_and_a_yearless_search_result(self):
        from moviehub_free99 import scrape_sources
        query={'imdb':'tt1234567','tmdb':12,'title':'Fixture Film','year':'2020','season':-1,'episode':-1}
        def request(session,method,url,**kwargs):
            response=requests.Response();response.status_code=200;response.url=url
            response.headers['Content-Type']='text/html'
            if 'search.php' in url:
                response._content=(b'<div class="mainlink"><a href="bad.php">Other item without year</a></div>'
                                   b'<div class="mainlink"><a href="movie.php?id=1">Fixture Film (2020)</a></div>')
            elif 'movie.php' in url:
                response._content=b'<a target="_blank" href="https://host.example/embed">Play</a>'
            elif 'media.example' in url:
                response.headers['Content-Type']='video/mp4';response._content=b'ftyp'
            else: response._content=b''
            response._content_consumed=True
            return response
        item={'url':'https://media.example/film.mp4','direct':True,'quality':'HD','source':'fixture'}
        with patch.object(requests.Session,'request',autospec=True,side_effect=request), \
             patch.object(client,'safe',side_effect=lambda url:url), \
             patch.object(scrape_sources,'process',return_value=[item]) as process:
            result=json.loads(vod.query('free99:levidia_ch',json.dumps(query),lambda:False))
        self.assertEqual(result['status'],'complete')
        self.assertEqual(len(result['sources']),1)
        # Without a readable picture size the website's own claim is kept, and marked as a claim.
        self.assertEqual(result['sources'][0]['quality'],'HD')
        self.assertEqual(result['sources'][0]['claimedQuality'],'HD')
        self.assertFalse(result['sources'][0]['qualityVerified'])
        process.assert_called_once()

    def test_resolved_files_mislabeled_indirect_still_require_valid_media(self):
        from unittest.mock import Mock
        module=importlib.import_module(vod._MODULES['scrubs:levidia_ch']['module'])
        query={'imdb':'tt1234567','tmdb':12,'title':'Fixture Film','year':'2020','season':-1,'episode':-1}
        provider=Mock()
        provider.base_link='https://site.example'
        provider.sources.return_value=[{'url':'https://media.example/film.mp4','direct':False}]
        provider.resolve.side_effect=lambda url:url
        resolver=Mock();resolver.valid_url.return_value=False
        response=requests.Response();response.status_code=200;response.url='https://media.example/film.mp4'
        response._content=b'ftyp';response.headers['Content-Type']='video/mp4'
        with patch.object(module,'source',return_value=provider), \
             patch.object(vod.resolveurl,'HostedMediaFile',return_value=resolver), \
             patch.object(client,'safe',side_effect=lambda url:url), \
             patch.object(client,'fetch',return_value=response):
            result=json.loads(vod.query('scrubs:levidia_ch',json.dumps(query),lambda:False))
            self.assertEqual(result['status'],'complete')
            resolver.resolve.assert_not_called()
            response.headers['Content-Type']='text/html';response._content=b'<html>Error</html>'
            result=json.loads(vod.query('scrubs:levidia_ch',json.dumps(query),lambda:False))
            self.assertEqual(result['status'],'rejected_media')

    def test_provider_requested_read_timeout_is_honored_within_search_budget(self):
        response=requests.Response();response.status_code=200;response.url='https://site.example/path'
        response._content=b'ok';response._content_consumed=True
        client.scope(lambda:False,timeout=30)
        with patch.object(client,'safe',side_effect=lambda url:url), patch.object(requests.Session,'request',return_value=response) as request:
            client.request(response.url,timeout=20)
            connect,read=request.call_args.kwargs['timeout']
            self.assertLessEqual(connect,8)
            self.assertEqual(read,20)
        client.scope(lambda:False,timeout=5)
        with patch.object(client,'safe',side_effect=lambda url:url), patch.object(requests.Session,'request',return_value=response) as request:
            client.request(response.url,timeout=30)
            self.assertGreater(request.call_args.kwargs['timeout'][1],0)
            self.assertLessEqual(request.call_args.kwargs['timeout'][1],5)

    def test_real_levidia_search_preserves_empty_post(self):
        from moviehub_scrubs import scrape_sources
        query={'imdb':'tt1234567','tmdb':12,'title':'Fixture Film','year':'2020','season':-1,'episode':-1}
        calls=[]
        def request(session, method, url, **kwargs):
            calls.append((method,url))
            response=requests.Response();response.status_code=200;response.url=url
            response.headers['Content-Type']='text/html'
            if 'search.php' in url:
                self.assertEqual(method,'POST')
                response._content=b'<div class="mainlink"><a href="movie.php?id=1">Fixture Film (2020)</a></div>'
            elif 'movie.php' in url:
                response._content=b"<li class=\"xxx0\"><span class=\"kiri xxx1\">Fixture</span><a class=\"xxx xflv\" href=\"https://host.example/embed\">Play</a></li><script>_3chk('session','fixture')</script>"
            elif 'media.example' in url:
                response.headers['Content-Type']='video/mp4';response._content=b'ftyp'
            else:
                response._content=b''
            response._content_consumed=True
            return response
        item={'url':'https://media.example/film.mp4','direct':True,'quality':'HD','source':'fixture'}
        with patch.object(requests.Session,'request',autospec=True,side_effect=request), patch.object(client,'safe',side_effect=lambda url:url), patch.object(scrape_sources,'process',return_value=[item]):
            result=json.loads(vod.query('scrubs:levidia_ch',json.dumps(query),lambda:False))
        self.assertEqual(result['status'],'complete')
        self.assertEqual(len(result['sources']),1)
        self.assertEqual([method for method,url in calls],['GET','POST','GET','GET','GET'])

    def test_scrubs_levidia_uses_the_current_wootly_resolver(self):
        from moviehub_scrubs import scrape_sources
        from moviehub_scrubs.providers import levidia_ch
        provider=levidia_ch.source()
        provider.wootly=unittest.mock.Mock(side_effect=AssertionError("old inline resolver used"))
        query={'imdb':'tt1234567','tmdb':12,'title':'Fixture Film','year':'2020','season':-1,'episode':-1}
        calls=[]
        def request(session, method, url, **kwargs):
            calls.append((method,url))
            response=requests.Response();response.status_code=200;response.url=url
            response.headers['Content-Type']='text/html'
            if 'search.php' in url:
                self.assertEqual(method,'POST')
                response._content=b'<div class="mainlink"><a href="movie.php?id=1">Fixture Film (2020)</a></div>'
            elif 'movie.php' in url:
                response._content=b"<li class=\"xxx0\"><span class=\"kiri xxx1\">Wootly</span><a class=\"xxx xflv\" href=\"https://host.example/embed\">Play</a></li><script>_3chk('session','fixture')</script>"
            elif 'host.example' in url:
                response.url="https://www.wootly.ch/?v=FIXTURE";response._content=b'host'
            elif 'media.example' in url:
                response.headers['Content-Type']='video/mp4';response._content=b'ftyp'
            else:
                response._content=b''
            response._content_consumed=True
            return response
        item={'url':'https://media.example/film.mp4','direct':True,'quality':'HD','source':'fixture'}
        with patch.object(levidia_ch,'source',return_value=provider),patch.object(requests.Session,'request',autospec=True,side_effect=request), patch.object(client,'safe',side_effect=lambda url:url), patch.object(scrape_sources,'process',return_value=[item]):
            result=json.loads(vod.query('scrubs:levidia_ch',json.dumps(query),lambda:False))
        self.assertEqual(result['status'],'complete')
        self.assertEqual(len(result['sources']),1)
        self.assertEqual([method for method,url in calls],['GET','POST','GET','GET','GET'])
        provider.wootly.assert_not_called()

    def test_post_redirect_method_and_cookie_contract(self):
        from moviehub_compat import http_requests
        for status, expected in [(301,'GET'),(302,'GET'),(303,'GET'),(307,'POST'),(308,'POST')]:
            with self.subTest(status=status):
                first=requests.Response();first.status_code=status;first.url='https://site.example/start'
                first.headers['Location']='/target';first._content=b'';first._content_consumed=True
                second=requests.Response();second.status_code=200;second.url='https://site.example/target';second._content=b'ok';second._content_consumed=True
                client.scope(lambda:False)
                with patch.object(client,'safe',side_effect=lambda url:url), patch.object(requests.Session,'request',side_effect=[first,second]) as request:
                    with http_requests.Session() as session:
                        session.post(first.url,cookies={'session':'fixture'})
                        self.assertEqual(session.cookies.get('session'),'fixture')
                self.assertEqual([call.args[0] for call in request.call_args_list],['POST',expected])

    def test_upstream_movie_and_episode_title_preparation(self):
        from unittest.mock import Mock
        for family in ('scrubs','free99'):
            module=importlib.import_module('moviehub_'+family+'.providers.levidia_ch')
            provider=module.source()
            provider.movie=Mock(return_value='movie')
            provider.tvshow=Mock(return_value='show')
            provider.episode=Mock(return_value='episode')
            provider.sources=Mock(return_value=[])
            query={'imdb':'tt3896198','tmdb':283995,'title':'Guardians of the Galaxy Vol. 2','localTitle':'Local Title',
                   'year':'2017','season':-1,'episode':-1,'aliases':['Alternate Title','alternate title','',None]}
            with patch.object(module,'source',return_value=provider):
                vod.query(family+':levidia_ch',json.dumps(query),lambda:False)
                args=provider.movie.call_args.args
                self.assertEqual(args[2],'Guardians of the Galaxy Vol 2')
                self.assertEqual(args[3],'Local Title')
                self.assertEqual([row['title'] for row in args[4]],['Guardians of the Galaxy Vol 2','Guardians of the Galaxy Vol. 2','Local Title','Alternate Title'])
                query.update(title='The Haunting',year='2018',season=2,episode=3)
                vod.query(family+':levidia_ch',json.dumps(query),lambda:False)
                self.assertEqual(provider.tvshow.call_args.args[3],'The Haunting of Bly Manor')
                self.assertEqual(provider.tvshow.call_args.args[-1],'2020')
                self.assertEqual(provider.episode.call_args.args[-2:],('1','3'))

    def test_request_default_headers_and_explicit_referer(self):
        response=requests.Response();response.status_code=200;response._content=b'ok'
        with patch.object(client,'fetch',return_value=response) as fetch:
            client.request('https://site.example/path')
            self.assertEqual(fetch.call_args.args[1]['Referer'],'https://site.example/')
            self.assertEqual(fetch.call_args.args[1]['Accept-Language'],'en-US,en')
            client.request('https://site.example/path',headers={'referer':'https://original.example/'})
            self.assertEqual(fetch.call_args.args[1]['referer'],'https://original.example/')
            self.assertNotIn('Referer',fetch.call_args.args[1])

    def test_extended_transport_contract_drives_real_watchseries_provider(self):
        from moviehub_scrubs import scrape_sources
        query={'imdb':'tt1234567','tmdb':12,'title':'Fixture Film','year':'2020','season':-1,'episode':-1}
        calls=[]
        def fetch(url, headers=None, post=None, timeout=8, redirect=True, max_bytes=client.MAX_BODY):
            self.assertIsInstance(headers,dict)
            calls.append((url,headers))
            response=requests.Response();response.status_code=200;response.url=url
            response.headers['Content-Type']='text/html'
            response.cookies.set('fixture','session')
            if '?s=' in url:
                response._content=b'<div class="item"><a href="https://fixture.example/film" title="Fixture Film (2020)">Film</a></div>'
            elif url.endswith('/film'):
                response._content=b'<div data-vs="https://fixture.example/redirect"></div>'
            elif url.endswith('/redirect'):
                response.url='https://host.example/embed';response._content=b''
            else:
                response.headers['Content-Type']='video/mp4';response._content=b'ftyp'
            return response
        item={'url':'https://media.example/film.mp4','direct':True,'quality':'HD','source':'fixture'}
        with patch.object(client,'fetch',side_effect=fetch),patch.object(client,'safe',side_effect=lambda url:url),patch.object(scrape_sources,'process',return_value=[item]):
            result=json.loads(vod.query('scrubs:watchserieshd_stream',json.dumps(query),lambda:False))
        self.assertEqual(result['status'],'complete')
        self.assertEqual(len(result['sources']),1)
        self.assertEqual(len(calls),4) # extended output must not make a second cookie request
        self.assertIn('User-Agent',calls[1][1])

    def test_scrape_page_keeps_upstream_default_referer_and_protocol_relative_url(self):
        with patch.object(client,'fetch') as fetch:
            client.scrapePage('//site.example/path')
            self.assertEqual(fetch.call_args.args[0],'https://site.example/path')
            self.assertEqual(fetch.call_args.args[1]['Referer'],'https://site.example')
            client.scrapePage('https://site.example/path',headers={'Referer':'https://original.example'})
            self.assertEqual(fetch.call_args.args[1]['Referer'],'https://original.example')

    def test_every_provider_lookup_contract_and_individual_outcome(self):
        import inspect
        from unittest.mock import Mock
        query={'imdb':'tt0063350','tmdb':10331,'title':'Night of the Living Dead',
               'year':'1968','season':-1,'episode':-1}
        for row in json.loads(vod.providers()):
            module=importlib.import_module(row['module'])
            provider=module.source()
            for series in (False,True):
                with self.subTest(provider=row['id'],series=series):
                    info=dict(query,season=1 if series else -1,episode=1 if series else -1)
                    calls={}
                    for method,args in [('movie',('i','t','title','title',[], '2020')),
                                        ('tvshow',('i','t','','title','title',[], '2020')),
                                        ('episode',('show','i','t','','ep','2020-01-01','1','1')),
                                        ('sources',('key',[])),('resolve',('url',))]:
                        if hasattr(provider,method):
                            inspect.signature(getattr(provider,method)).bind(*args)
                            calls[method]=Mock(return_value=[] if method=='sources' else 'key')
                    for name,call in calls.items():setattr(provider,name,call)
                    with patch.object(module,'source',return_value=provider):
                        result=json.loads(vod.query(row['id'],json.dumps(info),lambda:False))
                    if row['name'] in ('library','filepursuit'):
                        self.assertEqual(result['status'],'local_library' if row['name']=='library' else 'requires_api_key')
                        calls['sources'].assert_not_called()
                    elif not hasattr(provider,'tvshow' if series else 'movie'):
                        self.assertEqual(result['status'],'unsupported_type')
                        calls['sources'].assert_not_called()
                    else:
                        self.assertEqual(result['status'],'no_matches')
                        calls['sources'].assert_called_once()
                        self.assertNotIn('*',calls['sources'].call_args.args[1])

    def test_short_media_rejected_without_treating_unknown_duration_as_short(self):
        import struct
        from moviehub_compat.media import duration_seconds
        def mp4(seconds):
            body=bytes(12)+struct.pack('>II',1000,int(seconds*1000))+bytes(80)
            mvhd=struct.pack('>I4s',len(body)+8,b'mvhd')+body
            return struct.pack('>I4s',len(mvhd)+8,b'moov')+mvhd
        response=requests.Response();response.status_code=200
        response.headers['Content-Type']='video/mp4'
        with patch.object(client,'fetch',return_value=response),patch.object(client,'safe',side_effect=lambda url:url):
            for seconds in (7,30,119.9):
                response._content=mp4(seconds)
                self.assertAlmostEqual(duration_seconds(response.content,'video/mp4'),seconds)
                self.assertIsNone(vod.playable('https://media.example/error.mp4'))
            for seconds in (120,5400):
                response._content=mp4(seconds)
                result=vod.playable('https://media.example/movie.mp4')
                self.assertEqual(result['durationSeconds'],seconds)
                self.assertEqual(result['headers']['User-Agent'],client.UserAgent)
            response._content=b'unknown container metadata'
            self.assertIsNotNone(vod.playable('https://media.example/movie.mp4'))
            response.headers['Content-Type']='application/vnd.apple.mpegurl'
            response._content=b'#EXTM3U\n#EXTINF:7,\na.ts\n#EXT-X-ENDLIST\n'
            self.assertIsNone(vod.playable('https://media.example/error.m3u8'))
            response._content=b'#EXTM3U\n#EXTINF:7,\na.ts\n'
            self.assertIsNone(duration_seconds(response.content,'application/vnd.apple.mpegurl'))
            self.assertIsNotNone(vod.playable('https://media.example/event.m3u8'))
        self.assertIsNone(duration_seconds(b'bad bytes','video/mp4'))
        self.assertEqual(duration_seconds(b'<MPD mediaPresentationDuration="PT1M59S">','application/dash+xml'),119)

    def test_probe_stops_at_the_payload_limit(self):
        response=requests.Response();response.status_code=206;response.url='https://media.example/file.mp4'
        response.headers['Content-Type']='video/mp4'
        reads=[]
        def chunks(size):
            for n in range(3):
                reads.append(n)
                yield b'x'*32768
        response.iter_content=chunks
        response.close=lambda:None
        session=unittest.mock.Mock()
        session.request.return_value=response
        client.scope(lambda:False)
        with patch.object(client,'safe',side_effect=lambda url:url):
            result=client.fetch(response.url,max_bytes=65536,session=session)
        self.assertEqual(len(result.content),65536)
        self.assertEqual(reads,[0,1])

    def test_a_scraper_cannot_change_the_headers_every_scraper_shares(self):
        shared = dict(client.dnt_headers)
        module = importlib.import_module(vod._MODULES['free99:stagatv_com']['module'])
        original = module.source.sources
        def leaky(self, url, hosts):
            # What stagatv, seriesloaded and naijavault do: edit self.headers in place.
            self.headers.update({'Host': 'loadedfiles.org', 'Referer': 'https://m.stagatv.com'})
            return []
        info = json.dumps({'imdb':'tt0063350','tmdb':10331,'title':'Night of the Living Dead',
                           'year':'1968','season':-1,'episode':-1})
        with patch.object(module.source, 'sources', leaky):
            vod.query('free99:stagatv_com', info, lambda: False)
        self.assertIs(module.source.sources, original)
        self.assertEqual(client.dnt_headers, shared)

    def test_file_size_reads_headers_without_downloading_the_film(self):
        # fzmovies' resolve() asks for output='file_size'; the whole film used to be fetched.
        response=requests.Response();response.status_code=200;response.url='https://files.example/film.mp4'
        response.headers['Content-Length']='503371211'
        reads=[]
        def chunks(size):
            for n in range(20):
                reads.append(n)
                yield b'x'*32768
        response.iter_content=chunks
        response.close=lambda:None
        session=unittest.mock.Mock()
        session.request.return_value=response
        client.scope(lambda:False)
        with patch.object(client,'safe',side_effect=lambda url:url), \
             patch.object(client.requests,'Session',return_value=session):
            self.assertEqual(client.request(response.url,output='file_size'),503371211)
            self.assertEqual(reads,[])
            response.status_code=206;response.headers['Content-Range']='bytes 0-0/700000000'
            reads.clear()
            self.assertEqual(client.request(response.url,output='file_size'),700000000)
            response.status_code=404
            self.assertEqual(client.request(response.url,output='file_size'),0)

    def test_manifest_resolution_and_media_transfer_sample(self):
        response=requests.Response();response.status_code=200
        response.headers['Content-Type']='application/vnd.apple.mpegurl'
        response._content=b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\nb.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160\nc.m3u8\n'
        with patch.object(client,'fetch',return_value=response),patch.object(client,'safe',side_effect=lambda url:url):
            result=vod.playable('https://media.example/master.m3u8',1080)
            self.assertEqual(result['verifiedQuality'],'1080p')
            self.assertEqual(result['probeMbps'],0)
            response.headers['Content-Type']='video/mp4';response._content=b'x'*65536
            with patch.object(vod.time,'monotonic',side_effect=[1,1.1]):
                result=vod.playable('https://media.example/file.mp4')
            self.assertAlmostEqual(result['probeMbps'],5.24288,places=4)
            self.assertEqual(result['responseMs'],100)

    HEADERS = Path(__file__).resolve().parent/'media-headers'

    def media_response(self, content, status=206, total=None, kind='video/mp4'):
        response=requests.Response();response.status_code=status
        response.headers['Content-Type']=kind
        if total: response.headers['Content-Range']='bytes 0-%d/%d'%(len(content)-1,total)
        response._content=content
        return response

    def test_real_file_headers_give_the_actual_picture_size(self):
        from moviehub_compat.media import video_size, quality_label, mp4_next_box, mp4_moov_size
        cases={'854x480-faststart.mp4':((854,480),'480p'),
               '1920x800-faststart.mp4':((1920,800),'1080p'),
               '1280x720.mkv':((1280,720),'720p'),
               '640x360.webm':((640,360),'360p')}
        for name,(size,label) in cases.items():
            with self.subTest(file=name):
                data=(self.HEADERS/name).read_bytes()
                self.assertEqual(video_size(data),size)
                self.assertEqual(quality_label(*size),label)
        start=(self.HEADERS/'854x480-moov-last-start.mp4').read_bytes()
        index=(self.HEADERS/'854x480-moov-last-index.mp4').read_bytes()
        self.assertIsNone(video_size(start))
        self.assertEqual(mp4_next_box(start),1140867)
        self.assertEqual(mp4_moov_size(index),(854,480))
        self.assertEqual(quality_label(3840,1600),'2160p')
        for junk in (b'',b'garbage'*50,b'\x1a\x45\xdf\xa3\xff',b'\x00\x00\x00\x01ftyp'):
            self.assertIsNone(video_size(junk))

    def lengthened(self, name):
        """A real MP4 header with its declared duration changed to 90 minutes (the files are 3 s)."""
        import struct
        index=bytearray((self.HEADERS/name).read_bytes())
        body=index.find(b'mvhd')+4
        self.assertEqual(index[body],0)
        scale=struct.unpack_from('>I',index,body+12)[0]
        struct.pack_into('>I',index,body+16,scale*5400)
        return bytes(index)

    def test_probe_labels_sources_from_the_file_instead_of_the_website(self):
        response=self.media_response(self.lengthened('854x480-faststart.mp4'))
        with patch.object(client,'fetch',return_value=response),patch.object(client,'safe',side_effect=lambda url:url):
            result=vod.playable('https://media.example/film.1080p.mp4')
        self.assertEqual(result['verifiedQuality'],'480p')
        self.assertTrue(result['qualityVerified'])
        self.assertFalse(result['playlist'])
        self.assertEqual((result['videoWidth'],result['videoHeight']),(854,480))

    def test_probe_reads_an_index_stored_at_the_end_with_one_bounded_request(self):
        start=self.media_response((self.HEADERS/'854x480-moov-last-start.mp4').read_bytes(),total=1143973)
        calls=[]
        def fetch(url,headers,**kwargs):
            calls.append((headers.get('Range'),kwargs.get('max_bytes')))
            return start if len(calls)==1 else self.media_response(self.lengthened('854x480-moov-last-index.mp4'))
        with patch.object(client,'fetch',side_effect=fetch),patch.object(client,'safe',side_effect=lambda url:url):
            result=vod.playable('https://media.example/film.mp4')
        self.assertEqual(calls,[('bytes=0-65535',65536),('bytes=1140867-1206402',65536)])
        self.assertEqual(result['verifiedQuality'],'480p')
        self.assertEqual(result['durationSeconds'],5400)
        # The real three-second file is now recognised as a short clip, which was unknown before.
        short=self.media_response((self.HEADERS/'854x480-moov-last-index.mp4').read_bytes())
        with patch.object(client,'fetch',side_effect=[start,short]),patch.object(client,'safe',side_effect=lambda url:url):
            self.assertIsNone(vod.playable('https://media.example/clip.mp4'))

    def test_failed_index_read_leaves_size_unknown_without_rejecting(self):
        start=self.media_response((self.HEADERS/'854x480-moov-last-start.mp4').read_bytes(),total=1143973)
        for second in (requests.ConnectionError('reset'),self.media_response(b'<html>',status=200,kind='text/html')):
            with self.subTest(second=type(second).__name__):
                with patch.object(client,'fetch',side_effect=[start,second]),patch.object(client,'safe',side_effect=lambda url:url):
                    result=vod.playable('https://media.example/film.mp4')
                self.assertIsNotNone(result)
                self.assertIsNone(result['verifiedQuality'])
                self.assertFalse(result['qualityVerified'])
        # An index beyond the file's reported end is never requested.
        truncated=self.media_response((self.HEADERS/'854x480-moov-last-start.mp4').read_bytes(),total=1000)
        with patch.object(client,'fetch',return_value=truncated) as fetch,patch.object(client,'safe',side_effect=lambda url:url):
            self.assertIsNotNone(vod.playable('https://media.example/film.mp4'))
        self.assertEqual(fetch.call_count,1)
        # Cancelling the search still stops it during the extra read.
        with patch.object(client,'fetch',side_effect=[start,client.Cancelled()]),patch.object(client,'safe',side_effect=lambda url:url):
            with self.assertRaises(client.Cancelled):
                vod.playable('https://media.example/film.mp4')

    def test_all_bundled_provider_constructors_import(self):
        rows=json.loads(vod.providers())
        self.assertEqual(len(rows),72)
        self.assertEqual(len({r['id'] for r in rows}),72)
        for row in rows:
            with self.subTest(provider=row['id']):
                self.assertTrue(hasattr(importlib.import_module(row['module']).source(),'sources'))

    def test_mp4hydra_real_provider_emits_multiple_servers(self):
        query={'imdb':'tt1234567','tmdb':12,'title':'Fixture Film','year':'2020','season':-1,'episode':-1}
        def fetch(url, headers=None, post=None, timeout=8, redirect=True, max_bytes=client.MAX_BODY):
            response=requests.Response();response.status_code=200;response.url=url
            response.headers['Content-Type']='application/json'
            if '/s?q=' in url:
                content=[{'title':'Fixture Film (2020)','type':'movie','link':'/film'}]
            elif url.endswith('/film'):
                response._content=b"<script>var opts = {'v':'id','s':'x','t':'x','i':'/playlist'};</script>"
                return response
            elif url.endswith('/playlist'):
                content={'playlist':[{'title':'Fixture Film (2020)','src':'video.mp4'}],
                         'servers':{'one':'https://one.example','two':'https://two.example'}}
            else:
                response.headers['Content-Type']='video/mp4';response._content=b'ftyp';return response
            response._content=json.dumps(content).encode();return response
        class Results:
            def __init__(self):self.items=[]
            def ready(self,text):self.items.append(json.loads(text))
        results=Results()
        with patch.object(client,'fetch',side_effect=fetch),patch.object(client,'safe',side_effect=lambda url:url):
            result=json.loads(vod.query('free99:my4hydra_top',json.dumps(query),lambda:False,results))
        self.assertEqual(result['status'],'complete')
        self.assertEqual(len(results.items),2)
        self.assertEqual({x['url'] for x in results.items},{'https://one.example/video.mp4','https://two.example/video.mp4'})

    def test_html_error_cannot_win_and_headers_survive(self):
        response=requests.Response();response.status_code=200;response.headers['Content-Type']='text/html';response._content=b'<html>Error</html>'
        with patch.object(client,'safe',side_effect=lambda url:url),patch.object(client,'fetch',return_value=response):
            self.assertIsNone(vod.playable('https://video.example/file.mp4'))
            response.headers['Content-Type']='video/mp4'
            item=vod.playable('https://video.example/file.mp4|Referer=https%3A%2F%2Fsite.example%2F&Cookie=session%3Dfixture')
            self.assertEqual(item['headers']['Cookie'],'session=fixture')
            self.assertEqual(item['headers']['Referer'],'https://site.example/')

    def test_cancelled_search_emits_nothing(self):
        result=json.loads(vod.query('free99:my4hydra_top',json.dumps({'http':False}),lambda:True))
        self.assertEqual(result['status'],'timeout')
        self.assertEqual(result['sources'],[])

    def test_cancelled_website_search_stops_before_reading_title_fields(self):
        # Mirrors the device test: Java cancels before any title field (even season) is sent.
        result=json.loads(vod.query_site('free99:my4hydra_top',json.dumps({'http':False}),lambda:True))
        self.assertEqual(result['status'],'timeout')
        self.assertEqual(result['sources'],[])

    def test_private_and_plain_http_targets_rejected(self):
        client.scope(lambda:False)
        for url in ['file:///tmp/video.mp4','http://example.com/video.mp4','https://127.0.0.1/video.mp4','https://user:pass@example.com/video.mp4']:
            with self.subTest(url=url),self.assertRaises(ValueError):client.safe(url)

    def test_decoder_expressions_never_execute_website_code(self):
        from moviehub_compat.expression import evaluate
        self.assertEqual(evaluate("(3 + 4) * 2"),14)
        self.assertEqual(evaluate("'one' + 'two'"),'onetwo')
        self.assertEqual(evaluate("seed + 2", {'seed':3}),5)
        for expression in ["__import__('os').getcwd()", "(1).__class__", "[x for x in []]", "2**1000", "'a'*99999999"]:
            with self.subTest(expression=expression),self.assertRaises(ValueError):evaluate(expression)

from test_current_vod_sites import CurrentSiteTests
from test_native_vod_sites import NativeSiteTests
from test_fmhy_rive import RiveTests
from test_fmhy_movy import MovyTests

if __name__=='__main__':unittest.main()
