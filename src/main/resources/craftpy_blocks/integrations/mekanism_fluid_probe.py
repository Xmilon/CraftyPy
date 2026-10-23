#: title: Mekanism fluid probe
#: category: Mekanism
#: doc: api.md#Kinetic
#: module: mekanism_fluid_probe
#: about: Inspect a loaded machine through standard NeoForge capabilities; no reflection permission needed.
#: side: server
#: keywords: fluid
#: mods: mekanism
from craftpy import *

@command('{{name}}', level=0)
def probe_{{name}}(ctx, x: int, y: int, z: int):
    ctx.player.say(str(world.resources((x,y,z), dimension=ctx.player.dimension, side='up')))
