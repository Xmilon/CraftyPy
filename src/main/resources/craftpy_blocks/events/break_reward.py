#: title: Reward for breaking a block
#: category: Events
#: doc: api.md#Events
#: module: diamond_luck
#: about: Breaking stone sometimes gives a diamond; counts per world with storage.
import random

from craftpy import *


@on.block_break(block="stone")
def lucky_{{name}}(event):
    if random.random() < 0.02:
        event.player.give("diamond")
        event.player.actionbar("Lucky!")
        storage["lucky_finds"] = storage.get("lucky_finds", 0) + 1
