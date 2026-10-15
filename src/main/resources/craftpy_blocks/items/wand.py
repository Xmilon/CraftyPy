#: title: Wand with a cooldown
#: category: Items
#: doc: api.md#Cooldown
#: module: speed_wand
#: about: Right click for an effect; a cooldown stops spamming.
from craftpy import *

{{name}} = Item("{{name}}", name="Speed Wand", texture="minecraft:blaze_rod", stack=1).extends(Cooldown(5))


@on.item_use(item={{name}})
def use_{{name}}(event):
    event.player.effect("speed", seconds=10, level=2)
    event.player.play_sound("minecraft:entity.illusioner.cast_spell")
