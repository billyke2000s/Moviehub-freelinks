"""MovieHub's separately maintained public website adapters; no Kodi provenance claim."""
PROVIDERS = [dict(id='native:fourkhdhub', module='moviehub_native.fourkhdhub', family='native',
    name='fourkhdhub', site='4khdhub.one', movies=True, series=False),
    dict(id='native:vixsrc', module='moviehub_native.vixsrc', family='native',
    name='vixsrc', site='vixsrc.to', movies=True, series=True),
    dict(id='native:rive', module='moviehub_native.rive', family='native',
    name='rive', site='rivestream.app', movies=True, series=True),
    dict(id='native:movy', module='moviehub_native.movy', family='native',
    name='movy', site='movy.sx', movies=True, series=True)]
