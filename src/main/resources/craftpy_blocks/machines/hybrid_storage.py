#: title: Energy and fluid storage
#: category: Machines
#: doc: api.md#Kinetic
#: module: hybrid_storage
#: about: Native NeoForge capabilities; limits and contents persist across reloads.
#: side: server
#: keywords: fluid energy battery tank FE mB
from craftpy import *

{{name}} = Block('{{name}}', texture='minecraft:block/iron_block').extends(EnergyStorage(50000, 500, 500), FluidTank(8000))
