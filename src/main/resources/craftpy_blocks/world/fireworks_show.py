#: title: Particles and sounds
#: category: World
#: doc: api.md#World
#: module: sparkle
#: about: /{{id}}:{{name}} plays a sound and a burst of particles around you for 3 seconds.
from craftpy import *


@command("{{name}}")
def {{name}}(ctx):
    """/{{id}}:{{name}} - sparkles for 3 seconds."""
    player = ctx.player
    task = run_every(5, lambda: world.particle("minecraft:end_rod", player.pos.up(1), count=12))
    player.play_sound("minecraft:entity.player.levelup")
    after(3, task.cancel)
