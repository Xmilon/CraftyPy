#: title: Mekanism crushing
#: category: Mekanism
#: doc: api.md#recipe
#: module: mekanism_crushing
#: about: Native Mekanism processing recipe using its installed JSON format.
#: side: server
#: keywords: 
#: mods: mekanism
from craftpy import *

recipe('mekanism:crushing', id='{{name}}', input={'count':1,'item':'minecraft:iron_ingot'}, output={'count':1,'id':'mekanism:dust_iron'})
