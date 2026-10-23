#: title: Spawn a mob
#: category: World
#: doc: api.md#world.spawn
#: module: guard_command
#: about: /{{id}}:{{name}} spawns an iron golem next to you and names it.
from craftpy import *


@command("{{name}}", level=2)
def {{name}}(ctx):
    """/{{id}}:{{name}} - call a guard."""
    golem = world.spawn("minecraft:iron_golem", ctx.player.pos.up(1))
    if golem is not None and hasattr(golem, "set_name"):
        golem.set_name(ctx.player.name + "'s guard")
