#: title: Block that does something on its own
#: category: Blocks
#: doc: api.md#Events
#: module: heater
#: about: Every 2 seconds the block warms players standing near it.
from craftpy import *

{{name}} = Block("{{name}}", name="Heater", texture="minecraft:magma_block", hardness=2, light=7, tool="pickaxe")


@on("block_tick", block={{name}}, every=40)
def {{name}}_warm(event):
    for player in players.online():
        dx, dy, dz = player.x - event.pos[0], player.y - event.pos[1], player.z - event.pos[2]
        if dx * dx + dy * dy + dz * dz < 16:
            player.effect("regeneration", seconds=3)
