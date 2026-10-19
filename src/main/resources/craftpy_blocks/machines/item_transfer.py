#: title: Item capability transfer
#: category: Machines
#: doc: api.md#Kinetic
#: module: item_transfer
#: about: Transfers between two loaded blocks through their selected NeoForge faces.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}')
def transfer_{{name}}(ctx, x: int, y: int, z: int):
    moved = world.transfer((x,y,z), (x+1,y,z), 'item', 16, source_side='east', target_side='west', dimension=ctx.player.dimension)
    ctx.player.say('Moved', moved)
