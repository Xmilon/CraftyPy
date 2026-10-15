#: title: Sword
#: category: Items
#: doc: api.md#Sword
#: module: ruby_sword
#: about: A sword with a tier, durability and a crafting recipe.
from craftpy import *

{{name}} = Item("{{name}}", name="Ruby Sword", texture="minecraft:diamond_sword", stack=1).extends(
    Sword(tier="diamond"), Durability(1200, repair_with="minecraft:diamond"))

shaped({{name}}, "D", "D", "S", D="diamond", S="stick")
