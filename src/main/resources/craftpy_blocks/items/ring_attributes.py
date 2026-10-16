#: title: Ring with attributes (Curios)
#: category: Items
#: doc: api.md#Attributes
#: module: luck_ring
#: about: A ring for a Curios ring slot that adds luck and max health while worn.
from craftpy import *

{{name}} = Item("{{name}}", name="Ring of Luck", texture="minecraft:gold_nugget", stack=1, tags=["curios:ring"]).extends(
    Attributes("minecraft:generic.luck", 2, slot="curios:ring"),
    Attributes("minecraft:generic.max_health", 4, slot="curios:ring"),
    Tooltip("Worn in a ring slot", color="gold"))
