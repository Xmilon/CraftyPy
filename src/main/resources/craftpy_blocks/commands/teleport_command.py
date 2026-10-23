#: title: Command with a player and a number
#: category: Commands
#: doc: api.md#Commands
#: module: lift
#: about: /{{id}}:{{name}} <player> <blocks> lifts a player up.
from craftpy import *


@command("{{name}}", level=2)
def {{name}}(ctx, target: Player, blocks: int):
    """/{{id}}:{{name}} <player> <blocks> - lift a player."""
    target.teleport(target.x, target.y + max(1, min(blocks, 100)), target.z)
    ctx.player.say("Lifted", target.name, "by", blocks, "blocks")
