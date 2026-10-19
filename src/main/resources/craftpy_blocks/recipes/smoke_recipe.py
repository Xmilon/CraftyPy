#: title: Smoke Recipe
#: category: Recipes
#: doc: api.md#Recipes
#: module: smoke_recipe
#: about: Native vanilla recipe, available without integration mods.
#: side: server
#: keywords: 
from craftpy import *

smelt('minecraft:beef', 'minecraft:cooked_beef', seconds=4, using='smoker')
