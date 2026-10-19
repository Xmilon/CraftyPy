#: title: Axe tool extension
#: category: Items
#: doc: api.md#Extensions
#: module: axe
#: about: Native tool behavior, durability and repair material.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Item('{{name}}', texture='minecraft:iron_axe', stack=1).extends(Axe(tier='iron'), Durability(500, repair_with='minecraft:iron_ingot'))
