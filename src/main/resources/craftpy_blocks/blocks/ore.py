#: title: Ore that drops an item
#: category: Blocks
#: doc: api.md#Blocks
#: module: ruby_ore
#: about: An ore that needs a pickaxe and drops two of a new item; the item smelts back from the ore.
from craftpy import *

{{name}}_gem = Item("{{name}}_gem", name="Ruby", texture="minecraft:emerald")
{{name}} = Block("{{name}}", name="Ruby Ore", texture="minecraft:emerald_ore", hardness=3, resistance=3,
                 tool="pickaxe", needs_tool=True, sound="stone", drops=({{name}}_gem, 2))

smelt({{name}}, {{name}}_gem, xp=1.0)
