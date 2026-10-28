"""Requests subset used by upstream providers, retaining cookies through bounded transport."""
import json as _json
from urllib.parse import urlencode, urlparse
import requests as _requests
from moviehub_compat import client

class Session:
    def __init__(self):
        self._session=_requests.Session()
        self._session.trust_env=False
        self._session.headers.update(client.dnt_headers)
        self.headers=self._session.headers
        self.cookies=self._session.cookies
    def _request(self,method,url,headers=None,timeout=8,data=None,cookies=None,params=None,allow_redirects=True,json=None,**kwargs):
        if params:
            url += ('&' if '?' in url else '?') + urlencode(params, doseq=True)
        if cookies:
            if hasattr(cookies,'get_dict'): cookies=cookies.get_dict()
            for key,value in cookies.items():
                self.cookies.set(key,value,domain=urlparse(url).hostname,path='/')
        out=dict(self.headers);out.update(headers or {})
        if json is not None:
            data = _json.dumps(json)
            out.setdefault('Content-Type', 'application/json')
        return client.fetch(url,out,data,timeout,allow_redirects,session=self._session,method=method)
    def get(self,url,**kwargs): return self._request("GET",url,**kwargs)
    def post(self,url,data=None,**kwargs): return self._request("POST",url,data=data,**kwargs)
    def __enter__(self): return self
    def __exit__(self,*args): self.close()
    def close(self): self._session.close()

def get(url,**kwargs):
    with Session() as session:return session.get(url,**kwargs)
def post(url,**kwargs):
    with Session() as session:return session.post(url,**kwargs)


def request(method, url, **kwargs):
    with Session() as session: return session._request(method, url, **kwargs)
