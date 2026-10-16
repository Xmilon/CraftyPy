#: title: Simple item
#: category: Items
#: doc: api.md#Items
#: module: gem
#: about: An item with a name and a texture (a vanilla one until you add assets/textures/{{name}}.png).
from craftpy import *

{{name}} = Item("{{name}}", name="Gem", texture="minecraft:emerald", glow=True)
