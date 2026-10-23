#: title: Leaderboard
#: category: Commands
#: doc: api.md#Storage
#: module: top
#: about: Counts blocks broken per player and /{{id}}:{{name}} shows the top 5.
from craftpy import *


@on.block_break
def {{name}}_count(event):
    scores = storage.get("{{name}}", {})
    scores[event.player.name] = scores.get(event.player.name, 0) + 1
    storage["{{name}}"] = scores


@command("{{name}}", level=0)
def {{name}}(ctx):
    """/{{id}}:{{name}} - the top 5 block breakers."""
    scores = storage.get("{{name}}", {})
    best = sorted(scores.items(), key=lambda pair: -pair[1])[:5]
    for place, (name, count) in enumerate(best, 1):
        ctx.player.say(place, name, count)
