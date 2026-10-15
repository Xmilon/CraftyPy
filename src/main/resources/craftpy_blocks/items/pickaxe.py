#: title: Pickaxe
#: category: Items
#: doc: api.md#Pickaxe
#: module: ruby_pickaxe
#: about: A mining tool: tier decides what it can mine, speed how fast.
from craftpy import *

{{name}} = Item("{{name}}", name="Ruby Pickaxe", texture="minecraft:diamond_pickaxe", stack=1).extends(
    Pickaxe(tier="diamond", speed=10), Durability(1500))
