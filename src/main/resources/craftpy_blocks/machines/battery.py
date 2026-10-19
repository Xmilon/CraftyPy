#: title: FE battery
#: category: Machines
#: doc: api.md#Kinetic
#: module: battery
#: about: Native NeoForge capabilities; limits and contents persist across reloads.
#: side: server
#: keywords: fluid energy battery tank FE mB
from craftpy import *

{{name}} = Block('{{name}}', texture='minecraft:block/iron_block').extends(EnergyStorage(100000, 1000, 1000))
