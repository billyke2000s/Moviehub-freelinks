"""Small headless Kodi API adapter for bundled host extractors; no Kodi process or UI."""
import os
import sys
import time
import types
import tempfile
import certifi

ROOT = os.path.join(tempfile.gettempdir(), 'moviehub-vod')
os.makedirs(ROOT, exist_ok=True)
_SETTINGS = {'auto_pick': 'true', 'allow_universal': 'false', 'allow_popups': 'false',
             'use_cache': 'false', 'last_ua_create': '0', 'current_ua': '', 'host.count': '3'}

class UnsupportedUI(RuntimeError):
    pass

class Addon:
    def __init__(self, addon_id='script.module.resolveurl'):
        self.id = addon_id
    def getAddonInfo(self, key):
        return {'id': self.id, 'name': self.id, 'version': '21.0' if self.id == 'xbmc.addon' else '5.1.212',
                'profile': ROOT, 'path': ROOT}.get(key, '')
    def getSetting(self, key):
        if key.endswith('_enabled'): return 'true'
        if key.endswith('_priority'): return '100'
        if key.endswith('auto_update'): return 'false'
        return _SETTINGS.get(key, '')
    def setSetting(self, key, value):
        _SETTINGS[key] = value
    def getLocalizedString(self, key): return str(key)
    def openSettings(self): raise UnsupportedUI('Host requires Kodi settings')

class Dialog:
    def __init__(self, *args, **kwargs): pass
    def select(self, *args, **kwargs): raise UnsupportedUI('Host requires interactive selection')
    def yesno(self, *args, **kwargs): return False
    def input(self, *args, **kwargs): raise UnsupportedUI('Host requires interactive input')
    def notification(self, *args, **kwargs): pass
    def ok(self, *args, **kwargs): return False
    def create(self, *args, **kwargs): pass
    def update(self, *args, **kwargs): pass
    def close(self): pass
    def iscanceled(self): return False
    def isFinished(self): return False
    def doModal(self): raise UnsupportedUI('Host requires a popup')
    def show(self): raise UnsupportedUI('Host requires a popup')

class Monitor:
    def abortRequested(self): return False
    def waitForAbort(self, seconds):
        from moviehub_compat.client import check
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            check()
            time.sleep(min(.1, max(0, end-time.monotonic())))
        return False

def translate(path):
    if path.endswith('cacert.pem'): return certifi.where()
    if path.startswith('special://'): return os.path.join(ROOT, os.path.basename(path))
    return path

def module(name, **values):
    m = types.ModuleType(name)
    m.__dict__.update(values)
    sys.modules[name] = m
    return m

xbmc = module('xbmc', LOGDEBUG=0, LOGINFO=1, LOGNOTICE=1, LOGWARNING=2, LOGERROR=3,
              log=lambda *a, **kw: None, getInfoLabel=lambda label: '21.0' if 'BuildVersion' in label else '',
              getCondVisibility=lambda cond: False, translatePath=translate, Monitor=Monitor,
              sleep=lambda ms: Monitor().waitForAbort(ms/1000),
              getSupportedMedia=lambda media: '.mp4|.mkv|.avi|.m3u8|.mpd|.ts|.webm|.mov|.m4v',
              executeJSONRPC=lambda command: '{"result": {"movies": [], "tvshows": [], "episodes": []}}')
xbmcgui = module('xbmcgui', Dialog=Dialog, DialogProgress=Dialog, DialogProgressBG=Dialog,
                 WindowXMLDialog=Dialog, WindowDialog=Dialog)
xbmcaddon = module('xbmcaddon', Addon=Addon)
xbmcvfs = module('xbmcvfs', translatePath=translate, exists=os.path.exists, File=open,
                 mkdir=lambda p: os.makedirs(p, exist_ok=True), mkdirs=lambda p: os.makedirs(p, exist_ok=True),
                 listdir=lambda p: ([x for x in os.listdir(p) if os.path.isdir(os.path.join(p,x))],
                                    [x for x in os.listdir(p) if os.path.isfile(os.path.join(p,x))]))
xbmcplugin = module('xbmcplugin')
module('kodi_six', xbmc=xbmc, xbmcgui=xbmcgui, xbmcaddon=xbmcaddon, xbmcvfs=xbmcvfs, xbmcplugin=xbmcplugin)
