# {{id}} - two items: a wand that gives a speed boost and a snack. Press Run to load them.
# Get them with: /craftpy give @s {{id}}:wand   and   /craftpy give @s {{id}}:snack
from craftpy import *

# Right click the wand: Speed II for 10 seconds (effect=(name, level, seconds)).
wand = Item("wand", name="Speed Wand", texture="minecraft:stick", stack=1, glow=True,
            effect=("speed", 2, 10))

# food=Food(...): hunger points, saturation, always_eat lets you eat when full.
snack = Item("snack", texture="minecraft:apple", food=Food(hunger=4, saturation=0.8, always_eat=True))

# " D" / " S" / "S ": each letter is an ingredient given below, spaces stay empty.
shaped(wand, " D", " S", "S ", D="diamond", S="stick")
shapeless(snack, "apple", "sugar")


@on.item_use(item=wand)
def used(event):
    event.player.play_sound("minecraft:entity.blaze.shoot", pitch=1.5)
    event.player.actionbar("Whoosh!")
