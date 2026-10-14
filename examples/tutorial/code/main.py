# CraftPy tutorial - read top to bottom, then press Run.
#
# 1. Declare content: Block(...) and Item(...). Each returns its id ("tutorial:ruby_block").
# 2. Add recipes: shaped(...), shapeless(...), smelt(...).
# 3. React to the game with @on.<event> handlers.
# Textures are PNG files in assets/textures/ (16x16), referenced by file name.
from craftpy import *

# --- 1. Content -------------------------------------------------------------

# A plain block. Get it with: /craftpy give @s tutorial:ruby_block block
ruby_block = Block("ruby_block", name="Ruby Block", texture="ruby_block.png",
                   hardness=3, tool="pickaxe", light=6)

# Food: eating it fills 6 hunger points.
ruby_apple = Item("ruby_apple", name="Ruby Apple", texture="ruby_apple.png",
                  food=Food(hunger=6, saturation=1.0))

# An item that does something when used (see the handler below).
ruby_wand = Item("ruby_wand", name="Ruby Wand", texture="ruby_wand.png", stack=1)

# --- 2. Recipes -------------------------------------------------------------

# Letters in the pattern are mapped with keywords. Bare names like "stick" are vanilla items.
shaped(ruby_wand, "  R", " S ", "S  ", R=ruby_block, S="stick")
shapeless(ruby_apple, "apple", "redstone")

# --- 3. Events --------------------------------------------------------------


@on.player_join
def say_hello(event):
    event.player.say("Hello", event.player.name, "- the tutorial project is running!")


@on.item_use(item=ruby_wand)
def use_wand(event):
    # event.player is a Player: say(), give(), heal(), effect(), teleport(), ...
    event.player.effect("speed", seconds=10, level=2)
    event.player.actionbar("Whoosh!")
    world.particle("minecraft:happy_villager", event.player.pos.up(1), count=15)


@on.block_place(block=ruby_block)
def placed_ruby_block(event):
    event.player.say(f"You placed a ruby block at {event.pos}")
