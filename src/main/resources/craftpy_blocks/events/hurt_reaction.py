#: title: When something is hurt
#: category: Events
#: doc: api.md#Events
#: module: thorns
#: about: A player who hits a mob takes a little damage back.
from craftpy import *


@on.entity_hurt
def {{name}}(event):
    if event.attacker_player and event.damage > 4:
        event.attacker_player.effect("slowness", seconds=2)
