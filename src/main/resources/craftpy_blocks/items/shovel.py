#: title: Shovel tool extension
#: category: Items
#: doc: api.md#Extensions
#: module: shovel
#: about: Native tool behavior, durability and repair material.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Item('{{name}}', texture='minecraft:iron_shovel', stack=1).extends(Shovel(tier='iron'), Durability(500, repair_with='minecraft:iron_ingot'))
