# 1. Items. A file that declares content only needs the craftpy import.
from craftpy import *

gem = Item("gem", name="Welcome Gem", texture="gem.png", glow=True)

# Food: eating it fills 6 hunger even when you are not hungry.
gem_apple = Item("gem_apple", texture="gem_apple.png",
                 food=Food(hunger=6, saturation=1.2, always_eat=True))

# .extends(...) adds behaviour. Sword gives mining speed, damage and a durability bar from the tier;
# effect=(name, level, seconds) is applied on right click.
gem_sword = Item("gem_sword", texture="gem_sword.png", effect=("strength", 2, 8)).extends(
    Sword(tier="diamond"), Durability(800, repair_with=gem))

# An item with its own behaviour: see @on.item_use in events.py and the LifeSteal extension in extensions.py.
gem_wand = Item("gem_wand", name="Gem Wand", texture="gem_wand.png", stack=1).extends(Cooldown(3), Fuel(20))
