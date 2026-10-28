from ast import literal_eval
# -*- coding: utf-8 -*-

import re

from moviehub_scrubs import cleantitle
from moviehub_scrubs import client
from moviehub_scrubs import client_utils
from moviehub_scrubs import scrape_sources
#from moviehub_scrubs import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = ['rarefilmm.com']
        self.base_link = 'https://rarefilmm.com'
        self.search_link = '/?s=%s+%s'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        try:
            url = self.base_link + self.search_link % (cleantitle.get_plus(title), year)
            searchPage = client.scrapePage(url).text
            sections = client_utils.parseDOM(searchPage, 'h2', attrs={'class': 'excerpt-title'})
            for item in sections:
                links = client_utils.parseDOM(item, 'a', ret='href')
                labels = client_utils.parseDOM(item, 'span', attrs={'class': 'entry-title-primary'})
                if not labels:
                    labels = client_utils.parseDOM(item, 'a')
                if not links or not labels:
                    continue
                label = client_utils.remove_tags(labels[0]).strip()
                match = re.match(r'(.+?)\s*\((\d{4})\)', label)
                if match and match[2] == str(year) and cleantitle.match_alias(match[1], aliases):
                    return links[0]
            return
        except:
            #log_utils.log('movie', 1)
            return


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            html = client.scrapePage(url).text
            links = []
            links += client_utils.parseDOM(html, 'iframe', ret='src')
            links += re.compile(r'href="(.+?)"><strong>').findall(html)
            for link in links:
                try:
                    for source in scrape_sources.process(hostDict, link):
                        if scrape_sources.check_host_limit(source['source'], self.results):
                            continue
                        self.results.append(source)
                except:
                    #log_utils.log('sources', 1)
                    pass
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url


