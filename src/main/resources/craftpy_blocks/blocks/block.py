#: title: Simple block
#: category: Blocks
#: doc: api.md#2. Blocks
#: module: ruby_block
#: about: A decorative block that drops itself, mined with a pickaxe.
from craftpy import *

{{name}} = Block("{{name}}", name="Ruby Block", texture="minecraft:redstone_block", hardness=4, tool="pickaxe",
                 sound="metal")
