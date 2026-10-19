#: title: Inspect block resources
#: category: Machines
#: doc: api.md#Kinetic
#: module: resource_inspect
#: about: Command prints a bounded capability snapshot without loading chunks.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}')
def inspect_{{name}}(ctx, x: int, y: int, z: int):
    ctx.player.say(str(world.resources((x,y,z), dimension=ctx.player.dimension, side='up')))
