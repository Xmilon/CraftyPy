# {{id}} - a CraftPy project. Press Run to load it into the game.
# Full reference: the Docs page. Textures go in assets/textures/ (16x16 PNG).
from craftpy import *

# A block that uses a vanilla texture. Get it with: /craftpy give @s {{id}}:shiny_stone
shiny_stone = Block("shiny_stone", texture="minecraft:amethyst_block", hardness=1.5, light=8)

# An item, and a recipe for it: 4 amethyst shards in a square.
gem = Item("gem", texture="minecraft:amethyst_shard", glow=True)
shaped(gem, "AA", "AA", A="amethyst_shard")


@on.block_break(block=shiny_stone)
def broke_shiny_stone(event):
    event.player.say("You broke the shiny stone!")
    event.player.give(gem)


@on.player_join
def welcome(event):
    event.player.title("Welcome!", subtitle="{{id}} is running")
