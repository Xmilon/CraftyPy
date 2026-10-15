#: title: Protect a block from breaking
#: category: Events
#: doc: api.md#6. Events
#: module: protected_blocks
#: about: Players in survival cannot break bedrock-like blocks you name (event.cancel()).
from craftpy import *

PROTECTED = ("minecraft:diamond_block", "minecraft:beacon")


@on.block_break
def protect_{{name}}(event):
    if event.block.id in PROTECTED and event.player.gamemode != "creative":
        event.cancel()
        event.player.actionbar("This block is protected")
