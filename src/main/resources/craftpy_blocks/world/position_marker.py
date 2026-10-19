#: title: Sound and particle marker
#: category: World
#: doc: api.md#World
#: module: position_marker
#: about: An explicit command marks the caller's position without changing terrain.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}')
def marker_{{name}}(ctx):
    world.particle('minecraft:happy_villager', ctx.player.pos, count=12, dimension=ctx.player.dimension)
    ctx.player.play_sound('minecraft:block.note_block.bell')
