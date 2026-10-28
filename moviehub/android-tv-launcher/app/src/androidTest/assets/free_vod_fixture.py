"""Trusted instrumentation-only responses for the real MP4Hydra extractor."""
import json
from unittest.mock import patch
import requests
from moviehub_compat import client

_active=[]
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
                 'servers':{'one':'https://8.8.8.8','two':'https://1.1.1.1'}}
    else:
        response.headers['Content-Type']='video/mp4';response._content=b'ftyp';return response
    response._content=json.dumps(content).encode();return response

def install():
    for item in [patch.object(client,'fetch',side_effect=fetch),patch.object(client,'safe',side_effect=lambda url:url)]:
        item.start();_active.append(item)

def restore():
    for item in reversed(_active):item.stop()
    _active.clear()
