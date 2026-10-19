#: title: Owned mob with persistent state
#: category: Entities
#: doc: api.md#Mob
#: module: owned_state
#: about: Spawn a following companion with per-module persistent JSON state.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Mob('{{name}}', behavior='follow')
@command('{{name}}')
def spawn_{{name}}(ctx):
    entity = world.spawn({{name}}, ctx.player.pos, dimension=ctx.player.dimension, owner=ctx.player)
    entity.set_state('visits', 0)
