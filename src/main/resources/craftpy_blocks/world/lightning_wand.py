#: title: Lightning wand
#: category: World
#: doc: api.md#World
#: module: lightning_wand
#: about: Right click: lightning strikes where you stand (a few blocks ahead is up to you).
from craftpy import *

{{name}} = Item("{{name}}", name="Lightning Wand", texture="minecraft:lightning_rod", stack=1).extends(Cooldown(3))


@on.item_use(item={{name}})
def strike_{{name}}(event):
    world.lightning(event.player.pos)
