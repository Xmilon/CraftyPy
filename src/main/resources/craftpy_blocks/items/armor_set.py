#: title: Full armor set
#: category: Items
#: doc: api.md#Armor
#: module: ruby_armor
#: about: Helmet, chestplate, leggings and boots with defense, toughness and durability.
from craftpy import *

{{name}}_pieces = []
for slot, defense, base in (("head", 3, "helmet"), ("chest", 8, "chestplate"), ("legs", 6, "leggings"), ("feet", 3, "boots")):
    {{name}}_pieces.append(Item("{{name}}_" + base, name="Ruby " + base.title(), texture="minecraft:diamond_" + base,
                               stack=1).extends(Armor(slot=slot, defense=defense, toughness=2.0), Durability(500)))
