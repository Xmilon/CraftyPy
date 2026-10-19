#: title: Held movement attribute
#: category: Items
#: doc: api.md#Attributes
#: module: held_attribute
#: about: Native main-hand attribute modifier; no polling callbacks.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Item('{{name}}', texture='minecraft:feather', stack=1).extends(Attributes('minecraft:generic.movement_speed', 0.02, slot='mainhand'))
