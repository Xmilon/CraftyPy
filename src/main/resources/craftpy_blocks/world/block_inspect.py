#: title: Loaded block inspection
#: category: World
#: doc: api.md#World
#: module: block_inspect
#: about: Print the block at the caller, without modifying it.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}')
def block_{{name}}(ctx):
    p = ctx.player
    p.say(str(world.get_block(int(p.x), int(p.y)-1, int(p.z), dimension=p.dimension)))
