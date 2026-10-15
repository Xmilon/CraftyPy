# {{id}} - commands. Press Run, then in game try:  /{{id}}:greet <player>   and   /{{id}}:boom <player> 4
# Arguments come from the function signature: int, float, bool, str and Player are supported.
from craftpy import *


@command("greet")
def greet(ctx, target: Player):
    """/{{id}}:greet <player> - say hello."""
    target.say("Hello,", target.name, "!")


@command("boom")
def boom(ctx, target: Player, power: int = 2):
    """/{{id}}:boom <player> [power] - a harmless-ish explosion above the player."""
    world.explode(target.pos.up(3), power=power, break_blocks=False)
