# 5. Your own extensions. An extension is a class: its constructor arguments are its settings, and methods
# named like a hook (on_use, on_hit, on_mine, on_hold_tick for items; on_interact, on_break, on_place, on_tick for
# blocks) run for every item or block it is attached to. Attach it with .extends(...).
from craftpy import *
from items import gem_sword


@extension
class LifeSteal(Extension):
    """Heals the player a little with every hit."""

    def __init__(self, hearts=1):
        self.hearts = hearts

    def on_hit(self, event):
        event.player.heal(self.hearts * 2)
        event.player.actionbar(f"+{self.hearts} heart(s)")


gem_sword.extends(LifeSteal(hearts=1))
