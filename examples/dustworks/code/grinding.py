# The rules of the Grinder, apart from the game so they can be tested (code/test_grinding.py).
#
# Every second a grinder:
#   1. burns a piece of coal when it has no energy left to grind with and no burn time left, if one is in its fuel slot;
#   2. turns burn time into energy (any mod's cable can also bring energy in);
#   3. grinds: each second of grinding costs FE_PER_STEP; after STEPS seconds one stone becomes DUSTS_PER_STONE dusts.

STEPS = 4                  # seconds to grind one stone
FE_PER_STEP = 40           # energy each second of grinding costs
BURN_SECONDS = 40          # how long a coal burns
FE_PER_BURN_SECOND = 30    # energy a second of burning gives
DUSTS_PER_STONE = 2

# what each stone grinds into (the dust's id in this project)
RECIPES = {
    "minecraft:diorite": "diorite_dust", "minecraft:polished_diorite": "diorite_dust",
    "minecraft:granite": "granite_dust", "minecraft:polished_granite": "granite_dust",
    "minecraft:andesite": "andesite_dust", "minecraft:polished_andesite": "andesite_dust",
}


def step(energy, capacity, burn, progress, fuel_count, stone, stone_count, output, output_count, namespace):
    """One second of a grinder. Takes what the machine holds now, returns what changes:

    {"energy": added (may be negative), "burn": seconds left, "progress": 0..100, "fuel_used": bool,
     "made": the dust's id or None (one stone used, DUSTS_PER_STONE dusts out)}"""
    result = {"energy": 0, "burn": burn, "progress": progress, "fuel_used": False, "made": None}
    dust = RECIPES.get(stone) if stone_count > 0 else None
    target = "%s:%s" % (namespace, dust) if dust else None
    room = target is not None and (output_count == 0 or output == target) and output_count + DUSTS_PER_STONE <= 64
    # 1. a new coal only when it is needed: something to grind, too little energy, nothing burning
    if burn <= 0 and fuel_count > 0 and room and energy < FE_PER_STEP:
        result["fuel_used"] = True
        result["burn"] = burn = BURN_SECONDS
    # 2. burning fills the store (never over its capacity)
    if burn > 0:
        gained = min(FE_PER_BURN_SECOND, capacity - energy)
        result["energy"] += gained
        energy += gained
        result["burn"] = burn - 1
    # 3. grinding
    if not room:
        result["progress"] = 0
        return result
    if energy < FE_PER_STEP:
        return result
    result["energy"] -= FE_PER_STEP
    progress += 100 // STEPS
    if progress >= 100:
        result["made"] = target
        progress = 0
    result["progress"] = progress
    return result
