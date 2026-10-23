#: title: Persistent command cooldown
#: category: Commands
#: doc: api.md#Storage
#: module: cooldown
#: about: A world-time cooldown scoped by player UUID.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}', level=0)
def cooldown_{{name}}(ctx):
    p = ctx.player
    now = server.info().get('game_time', 0)
    key = '{{name}}:' + p.uuid
    if now - storage.get(key, -10000) < 200:
        p.say('Wait ten seconds.')
        return
    storage.set(key, now)
    p.say('Action accepted.')
