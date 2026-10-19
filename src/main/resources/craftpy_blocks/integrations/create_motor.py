#: title: Create-connected custom motor
#: category: Create
#: doc: api.md#Kinetic
#: module: create_motor
#: about: Native CraftPy generator connects to Create shaft networks.
#: side: server
#: keywords: 
#: mods: create
#: requires: rotation.create
from craftpy import *

{{name}} = Block('{{name}}', texture='minecraft:block/copper_block').extends(Kinetic('generator', axis='x', speed=64, capacity=64))
