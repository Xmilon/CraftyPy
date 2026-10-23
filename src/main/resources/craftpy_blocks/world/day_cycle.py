#: title: Time and weather
#: category: World
#: doc: api.md#World
#: module: sunny
#: about: /{{id}}:{{name}} makes it day and clear for everyone.
from craftpy import *


@command("{{name}}", level=2)
def {{name}}(ctx):
    """/{{id}}:{{name}} - day and clear weather."""
    world.set_time(1000)
    world.weather("clear", duration=12000)
    say(ctx.player.name, "brought the sun back")
