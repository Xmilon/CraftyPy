#: title: When a player dies
#: category: Events
#: doc: api.md#Events
#: module: death_counter
#: about: Counts deaths per player (saved with the world) and tells everyone.
from craftpy import *


@on.player_death
def {{name}}(event):
    key = "deaths:" + event.player.name
    storage[key] = storage.get(key, 0) + 1
    say(event.player.name, "died", storage[key], "time(s)")
