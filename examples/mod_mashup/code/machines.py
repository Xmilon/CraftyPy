# Each recipe runs in the machine of the mod that owns its type. The dicts are that mod's recipe JSON.
from craftpy import *
from materials import arcane_alloy, arcane_dust, arcane_plate

# Create mixer on a heated basin (blaze burner): Mekanism osmium + 2 Iron's arcane essence + 100 mB lava.
recipe("create:mixing", id="arcane_alloy_mixing",
       ingredients=[{"item": "mekanism:ingot_osmium"},
                    {"item": "irons_spellbooks:arcane_essence"},
                    {"item": "irons_spellbooks:arcane_essence"},
                    {"type": "neoforge:single", "fluid": "minecraft:lava", "amount": 100}],
       results=[{"id": arcane_alloy}],
       heat_requirement="heated")

# Mekanism enrichment chamber: 1 alloy -> 2 dust.
recipe("mekanism:enriching", id="arcane_dust_enriching",
       input={"item": arcane_alloy, "count": 1},
       output={"id": arcane_dust, "count": 2})

# Create mechanical press: alloy -> plate.
recipe("create:pressing", id="arcane_plate_pressing",
       ingredients=[{"item": arcane_alloy}],
       results=[{"id": arcane_plate}])

# Without the machines (or the mods) there is still a slow way: the dust smelts back into an alloy.
smelt(arcane_dust, arcane_alloy, xp=0.5, seconds=20, using="blast_furnace")
