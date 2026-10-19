#: title: Blast Recipe
#: category: Recipes
#: doc: api.md#Recipes
#: module: blast_recipe
#: about: Native vanilla recipe, available without integration mods.
#: side: server
#: keywords: 
from craftpy import *

smelt('minecraft:raw_iron', 'minecraft:iron_ingot', seconds=8, using='blast_furnace')
