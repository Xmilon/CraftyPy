# Welcome to CraftPy!  Read the files in this folder top to bottom, then press Run (or /craftpy load welcome).
#
# A project is a folder of Python files. main.py is the entry point; every file it imports is run too, so
# each file below only has to do one job:
#
#   items.py       1. Items and food        Item(...), Food(...)
#   blocks.py      2. Blocks and drops      Block(...)
#   recipes.py     3. Recipes, tags, loot   shaped, shapeless, smelt, stonecut, tag, lang, loot_table
#   extensions.py  4. Your own behaviours  class LifeSteal(Extension)  ->  item.extends(LifeSteal())
#   events.py      4. Events                @on.<event>
#   timers.py      5. Timers                @every, after, run_every
#   commands.py    6. Commands + self test  @command
#   windows.py     7. Windows               a Python window; the second one is drawn in ui/settings.json
#
# Get the content:  /craftpy give @s welcome:gem_ore 16     (block or item is detected for you)
# Textures are PNG files in assets/textures/ and are referenced by file name.
from craftpy import *

# Importing a file runs it, which is what registers its blocks, items, events... Keep the imports in
# the order the files depend on each other (blocks.py needs the gem from items.py, and so on).
import items
import blocks
import recipes
import extensions
import events
import timers
import commands
import windows

PARTS = (items, blocks, recipes, extensions, events, timers, commands, windows)


def main(ctx):
    ctx.log("Welcome project loaded (" + str(len(PARTS)) + " files). Try: /craftpy give @s welcome:gem_ore 16")
