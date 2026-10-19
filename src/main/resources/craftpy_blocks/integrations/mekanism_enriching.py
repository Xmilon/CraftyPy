#: title: Mekanism enriching
#: category: Mekanism
#: doc: api.md#recipe
#: module: mekanism_enriching
#: about: Native Mekanism processing recipe using its installed JSON format.
#: side: server
#: keywords: 
#: mods: mekanism
from craftpy import *

recipe('mekanism:enriching', id='{{name}}', input={'count':1,'item':'minecraft:coal'}, output={'count':1,'id':'mekanism:dust_coal'})
