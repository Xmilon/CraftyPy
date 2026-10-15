# 2. Blocks. Other files of the project are imported by their file name: items.py is `items`.
from craftpy import *
from items import gem

# drops=(item, count) is what mining gives. needs_tool=True means the right tool is required.
gem_ore = Block("gem_ore", texture="gem_ore.png", hardness=3, resistance=3, tool="pickaxe",
                needs_tool=True, sound="stone", drops=(gem, 2))

# light=0..15, sound is one of the vanilla sound types ("stone", "wood", "metal", "glass", ...).
gem_block = Block("gem_block", texture="gem_block.png", hardness=4, tool="pickaxe", sound="metal", light=8)
