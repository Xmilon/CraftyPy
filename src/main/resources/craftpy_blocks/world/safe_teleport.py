#: title: Validated short teleport
#: category: World
#: doc: api.md#World
#: module: safe_teleport
#: about: Fails when the target is unloaded, obstructed, outside the border, or lacks solid ground.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}', level=0)
def teleport_{{name}}(ctx):
    p = ctx.player
    p.teleport(p.x + 3, p.y, p.z, dimension=p.dimension, safe=True)
