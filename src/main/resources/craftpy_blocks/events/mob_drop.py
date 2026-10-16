#: title: Extra drop from mobs
#: category: Events
#: doc: api.md#Events
#: module: zombie_loot
#: about: Zombies killed by a player sometimes drop an extra item.
import random

from craftpy import *


@on.entity_death
def loot_{{name}}(event):
    if event.killer and event.entity.type == "minecraft:zombie" and random.random() < 0.3:
        world.drop("minecraft:gold_nugget", event.entity.pos, count=2)
