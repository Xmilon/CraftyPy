#: title: Your own extension
#: category: Items
#: doc: api.md#Your own extensions
#: module: life_steal
#: about: A behaviour you can add to any item with .extends(...): heals on every hit.
from craftpy import *


@extension
class LifeSteal(Extension):
    """Heals the player a little with every hit."""

    def __init__(self, hearts=1):
        self.hearts = hearts

    def on_hit(self, event):
        event.player.heal(self.hearts * 2)


{{name}}_blade = Item("{{name}}_blade", name="Vampire Blade", texture="minecraft:netherite_sword", stack=1).extends(
    Sword(tier="netherite"), LifeSteal(hearts=1))
