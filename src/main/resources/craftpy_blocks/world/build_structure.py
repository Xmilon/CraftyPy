#: title: Build a structure
#: category: World
#: doc: api.md#World
#: module: hut
#: about: /{{id}}:{{name}} builds a small stone hut next to you with fill and set_block.
from craftpy import *


@command("{{name}}", level=2)
def {{name}}(ctx):
    """/{{id}}:{{name}} - a 5x4x5 hut next to you."""
    x, y, z = int(ctx.player.x) + 2, int(ctx.player.y), int(ctx.player.z) + 2
    world.fill((x, y, z), (x + 4, y + 3, z + 4), "minecraft:cobblestone")
    world.fill((x + 1, y, z + 1), (x + 3, y + 2, z + 3), "minecraft:air")
    world.set_block(x + 2, y, z, "minecraft:air")
    world.set_block(x + 2, y + 1, z, "minecraft:air")
    world.set_block(x + 2, y + 2, z + 2, "minecraft:lantern")
