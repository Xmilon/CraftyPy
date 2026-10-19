#: title: Hoe tool extension
#: category: Items
#: doc: api.md#Extensions
#: module: hoe
#: about: Native tool behavior, durability and repair material.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Item('{{name}}', texture='minecraft:iron_hoe', stack=1).extends(Hoe(tier='iron'), Durability(500, repair_with='minecraft:iron_ingot'))
