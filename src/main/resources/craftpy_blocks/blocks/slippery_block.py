#: title: Slippery block
#: category: Blocks
#: doc: api.md#Blocks
#: module: polished_ice
#: about: A block players slide on, like ice.
from craftpy import *

{{name}} = Block("{{name}}", name="Polished Ice", texture="minecraft:packed_ice", hardness=0.5).extends(Friction(0.99))
