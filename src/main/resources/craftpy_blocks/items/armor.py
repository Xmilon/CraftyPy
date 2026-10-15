#: title: Armor piece
#: category: Items
#: doc: api.md#Armor
#: module: ruby_chestplate
#: about: A chestplate with defense and toughness.
from craftpy import *

{{name}} = Item("{{name}}", name="Ruby Chestplate", texture="minecraft:diamond_chestplate", stack=1).extends(
    Armor(slot="chest", defense=7, toughness=2.0), Durability(600))
