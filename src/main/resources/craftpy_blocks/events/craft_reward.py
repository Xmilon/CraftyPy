#: title: When an item is crafted
#: category: Events
#: doc: api.md#Events
#: module: first_pickaxe
#: about: A message the first time a player crafts a diamond pickaxe.
from craftpy import *


@on.item_craft(item="minecraft:diamond_pickaxe")
def {{name}}(event):
    key = "crafted:" + event.player.name
    if not storage.get(key):
        storage[key] = True
        event.player.title("Diamond tools!", subtitle="Your first diamond pickaxe")
