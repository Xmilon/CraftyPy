# The rules of the Grinder, apart from the game so they can be tested (code/test_grinding.py).
#
# A grinder runs every TICK game ticks (5 times a second, so its arrow moves smoothly):
#   1. it needs energy for the recipe it can make (world.machine_recipe); when its store has less and nothing burns, it
#      burns a piece of coal from its fuel slot, else LAVA_PER_BURN mB of lava from its tank (a lava bucket is 12.5 coal,
#      like in a furnace). Any mod's cable can bring energy in too;
#   2. burning fills the store: FE_PER_RUN every run, COAL_BURN ticks long;
#   3. with enough energy stored the recipe advances; when its time is up, world.machine_finish takes the stone, puts the
#      dusts in and spends the recipe's energy. A full output keeps the arrow at its end until there is room.

TICK = 4                   # game ticks between runs
COAL_BURN = 800            # ticks one burn lasts (40 s, as long as coal in a furnace)
FE_PER_RUN = 6             # energy a run of burning gives: 1200 FE per coal
LAVA_PER_BURN = 80         # mB of lava for one burn: 1000 mB is 12.5 burns
GRIND_ENERGY = 160         # energy one stone costs: a coal grinds 7 or 8 stones
GRIND_SECONDS = 4

# what each stone grinds into (the dust's name in this project)
STONES = {
    "minecraft:diorite": "diorite", "minecraft:polished_diorite": "diorite",
    "minecraft:granite": "granite", "minecraft:polished_granite": "granite",
    "minecraft:andesite": "andesite", "minecraft:polished_andesite": "andesite",
}


def step(energy, capacity, burn, progress, fuel_count, lava, recipe):
    """One run of a grinder. Takes what the machine holds now and the recipe it can make (world.machine_recipe, or None),
    returns what changes:

    {"energy": added, "burn": ticks left, "progress": ticks done, "fuel_used": bool, "lava_used": mB, "finish": bool}"""
    result = {"energy": 0, "burn": burn, "progress": progress, "fuel_used": False, "lava_used": 0, "finish": False}
    need = recipe["energy"] if recipe else 0
    # 1. a new burn only when it is needed: something to grind, too little energy, nothing burning
    if burn <= 0 and recipe and energy < need:
        if fuel_count > 0:
            result["fuel_used"] = True
            burn = COAL_BURN
        elif lava >= LAVA_PER_BURN:
            result["lava_used"] = LAVA_PER_BURN
            burn = COAL_BURN
    # 2. burning fills the store (never over its capacity)
    if burn > 0:
        gained = max(0, min(FE_PER_RUN, capacity - energy))
        result["energy"] = gained
        energy += gained
        burn = max(0, burn - TICK)
    result["burn"] = burn
    # 3. grinding
    if not recipe:
        result["progress"] = 0
        return result
    if energy >= need:
        progress = min(recipe["ticks"], progress + TICK)
    result["progress"] = progress
    result["finish"] = progress >= recipe["ticks"]
    return result
