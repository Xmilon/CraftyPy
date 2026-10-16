#: title: Fuel
#: category: Items
#: doc: api.md#Items
#: module: coal_brick
#: about: An item that burns in furnaces for 40 seconds.
from craftpy import *

{{name}} = Item("{{name}}", name="Coal Brick", texture="minecraft:brick").extends(Fuel(40))
shapeless({{name}}, "minecraft:coal", "minecraft:coal", "minecraft:clay_ball")
