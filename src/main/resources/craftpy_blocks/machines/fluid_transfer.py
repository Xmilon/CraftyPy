#: title: Fluid capability transfer
#: category: Machines
#: doc: api.md#Kinetic
#: module: fluid_transfer
#: about: Transfers between two loaded blocks through their selected NeoForge faces.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}', level=2)
def transfer_{{name}}(ctx, x: int, y: int, z: int):
    moved = world.transfer((x,y,z), (x+1,y,z), 'fluid', 1000, source_side='east', target_side='west', dimension=ctx.player.dimension)
    ctx.player.say('Moved', moved)
