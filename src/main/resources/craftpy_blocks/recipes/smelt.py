#: title: Smelting recipe
#: category: Recipes
#: doc: api.md#smelt
#: module: leather_smelting
#: about: Furnace (or blast furnace, smoker) recipe with experience.
from craftpy import *

smelt("rotten_flesh", "leather", xp=0.2, seconds=10)
