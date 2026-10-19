#: title: Two-slot processing machine
#: category: Machines
#: doc: api.md#Kinetic
#: module: processor
#: about: Native rotation; works with CraftPy alone and connects through supported adapters.
#: side: server
#: keywords: rotation rpm stress capacity
from craftpy import *

{{name}} = Block('{{name}}', texture='minecraft:block/copper_block').extends(Kinetic('processor', axis='x', impact=8))
recipe('craftpy:kinetic_processing', id='{{name}}', ingredient={'item':'minecraft:cobblestone'}, result={'id':'minecraft:gravel'}, duration=100, minimum_speed=16)
