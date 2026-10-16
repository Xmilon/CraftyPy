#: title: Glowing block
#: category: Blocks
#: doc: api.md#Blocks
#: module: crystal_lamp
#: about: A block that gives light (0-15) and a recipe for it.
from craftpy import *

{{name}} = Block("{{name}}", name="Crystal Lamp", texture="minecraft:sea_lantern", hardness=1, light=15,
                 sound="glass")

shaped({{name}}, "GGG", "GTG", "GGG", G="glass", T="torch")
