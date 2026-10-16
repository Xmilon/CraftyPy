#: title: Kit on respawn
#: category: Events
#: doc: api.md#Events
#: module: respawn_kit
#: about: Gives a few items every time a player respawns.
from craftpy import *


@on.player_respawn
def {{name}}(event):
    event.player.give("minecraft:bread", 8)
    event.player.give("minecraft:stone_sword")
    event.player.actionbar("Fresh start: bread and a sword")
