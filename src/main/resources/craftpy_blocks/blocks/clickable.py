#: title: Block that reacts to a right click
#: category: Blocks
#: doc: api.md#6. Events
#: module: healing_altar
#: about: Right click the block: heal the player and show particles.
from craftpy import *

{{name}} = Block("{{name}}", name="Healing Altar", texture="minecraft:enchanting_table_top", hardness=2)


@on.block_interact(block={{name}})
def use_{{name}}(event):
    event.player.heal()
    world.particle("minecraft:heart", event.pos.up(1), count=8)
    event.player.actionbar("You feel better")
