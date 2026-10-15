#: title: Home: set and go back
#: category: Commands
#: doc: api.md#11. Storage
#: module: home
#: about: /{{id}}:sethome saves where you stand, /{{id}}:home takes you back (storage).
from craftpy import *


@command("sethome")
def sethome_{{name}}(ctx):
    """/{{id}}:sethome - remember this place."""
    p = ctx.player
    storage.set("home:" + p.name, [p.x, p.y, p.z])
    p.actionbar("Home set")


@command("home")
def home_{{name}}(ctx):
    """/{{id}}:home - go back home."""
    p = ctx.player
    spot = storage.get("home:" + p.name)
    if spot is None:
        p.say("No home yet: /{{id}}:sethome")
    else:
        p.teleport(spot[0], spot[1], spot[2])
