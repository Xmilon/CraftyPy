#: title: Block that burns and conducts redstone
#: category: Blocks
#: doc: api.md#Blocks
#: module: dry_hay
#: about: A block fire spreads to, which also gives a redstone signal.
from craftpy import *

{{name}} = Block("{{name}}", name="Dry Hay", texture="minecraft:hay_block", hardness=0.5, tool="hoe").extends(
    Flammable(chance=30, spread=60), Redstone(power=4))
