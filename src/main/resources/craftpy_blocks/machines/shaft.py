#: title: Native rotation shaft
#: category: Machines
#: doc: api.md#Kinetic
#: module: shaft
#: about: Native rotation; works with CraftPy alone and connects through supported adapters.
#: side: server
#: keywords: rotation rpm stress capacity
from craftpy import *

{{name}} = Block('{{name}}', texture='minecraft:block/copper_block').extends(Kinetic('shaft', axis='x'))
