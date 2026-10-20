# Behaviour that crosses mods: a Mekanism ore, mined with this project's blade, drops an Iron's material.
import random

from craftpy import *
from gear import arcane_blade


@on.block_break(block="mekanism:osmium_ore")
def arcane_mining(event):
    if event.player.held_item.id != arcane_blade:
        return
    if random.random() < 0.35:
        world.drop("irons_spellbooks:arcane_essence", event.pos)
        event.player.actionbar("The blade pulled arcane essence out of the osmium")
        event.player.play_sound("minecraft:block.amethyst_block.chime")


@on.block_break(block="mekanism:deepslate_osmium_ore")
def arcane_mining_deep(event):
    arcane_mining(event)
