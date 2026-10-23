# {{id}} - a custom ore block with drops, a tool requirement and a smelting recipe.
# Press Run, then: /craftpy give @s {{id}}:gem_ore 16
from craftpy import *

gem = Item("gem", texture="minecraft:amethyst_shard")

# hardness / resistance: how long it takes to mine and how well it survives explosions.
# tool + needs_tool: only a pickaxe mines it and drops anything.
# drops=(gem, 2): mining it gives 2 gems. Use drops=[] for nothing, or leave it out to drop the block.
gem_ore = Block("gem_ore", name="Gem Ore", texture="minecraft:amethyst_block",
                hardness=3, resistance=3, tool="pickaxe", needs_tool=True,
                sound="stone", light=4, drops=(gem, 2))

# Smelt the ore for a gem and some experience.
smelt(gem_ore, gem, xp=0.5)


@on.block_break(block=gem_ore)
def mined(event):
    event.player.actionbar("You found a gem ore!")
    world.play_sound("minecraft:block.amethyst_cluster.break", event.pos)
