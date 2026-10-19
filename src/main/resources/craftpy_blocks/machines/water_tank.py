#: title: Filtered water tank
#: category: Machines
#: doc: api.md#Kinetic
#: module: water_tank
#: about: Native NeoForge capabilities; limits and contents persist across reloads.
#: side: server
#: keywords: fluid energy battery tank FE mB
from craftpy import *

{{name}} = Block('{{name}}', texture='minecraft:block/iron_block').extends(FluidTank(16000, ['minecraft:water']))
