#: title: Nearby entity list
#: category: World
#: doc: api.md#World
#: module: entity_inspect
#: about: Bounded nearby entity inspection, without spawning or replacing entities.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}', level=0)
def entities_{{name}}(ctx):
    p = ctx.player
    for entity in world.entities(p.pos, radius=8, dimension=p.dimension)[:20]:
        p.say(entity.type, entity.uuid)
