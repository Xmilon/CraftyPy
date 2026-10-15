# Ruby Rush - a small mining game that shows most of the CraftPy API, split over several files.
#
# Content: ruby ore (drops rubies), rubies, a ruby block, a ruby sword and a ruby apple.
# Gameplay: every 10 ores mined server-wide starts a "Ruby Surge" with a reward.
# Try it:  /craftpy give @s ruby_rush:ruby_ore block 16
#
# items.py, blocks.py, recipes.py   what exists in the game
# surge.py                          the Ruby Surge itself (used by events.py and commands.py)
# events.py, commands.py            how players trigger it
from craftpy import *

import items
import blocks
import recipes
import surge
import events
import commands

PARTS = (items, blocks, recipes, surge, events, commands)


def main(ctx):
    ctx.log("Ruby Rush loaded (" + str(len(PARTS)) + " files). Try /craftpy give @s ruby_rush:ruby_ore block 16")
