#: title: Command with arguments
#: category: Commands
#: doc: api.md#Commands
#: module: gift_command
#: about: /{{id}}:{{name}} <player> <amount> - arguments come from the annotations.
from craftpy import *


@command("{{name}}")
def {{name}}(ctx, target: Player, amount: int):
    """/{{id}}:{{name}} <player> <amount> - give diamonds."""
    target.give("diamond", amount)
    target.say(f"You received {amount} diamond(s) from {ctx.player.name}")
