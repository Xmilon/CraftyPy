# Mod Mashup - one chain of crafting that runs through four mods.
#
#   Mekanism osmium + Iron's arcane essence --(Create mixer, heated)--> Arcane Alloy
#   Arcane Alloy --(Mekanism enrichment chamber)--> 2 Arcane Dust
#   Arcane Alloy --(Create press)--> Arcane Plate
#   Arcane Plates + Iron's mithril --(crafting table)--> Arcane Ring (a Curios ring) and the Arcane Blade
#
#   materials.py  the three materials
#   machines.py   the recipes, each in the machine of the mod it belongs to
#   gear.py       a ring for the Curios ring slot and a sword with Iron's spell power
#   events.py     cross-mod behaviour: the blade pulls arcane essence out of osmium ore
#   commands.py   /mod_mashup:mods shows which of the mods are installed
#
# Every recipe of another mod is written with recipe("<mod>:<type>", ...): the fields are that mod's own JSON (see the
# Mod catalog, Alt+0, for the fields of each recipe type). CraftPy adds a "mod_loaded" condition, so a recipe of a mod
# that is not installed is skipped and the rest still works.
from craftpy import *

import materials
import machines
import gear
import events
import commands


def main(ctx):
    ctx.log("Mod Mashup loaded: try /mod_mashup:mods, then look the items up in JEI")
