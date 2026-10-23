"""The rules of the generator as plain functions: how much liquid is burned for how much energy.

Nothing in here knows about the game, so code/test_conversion.py can check it on its own.
"""

FE_PER_MB = 80        # energy made from one millibucket of Volt Sap
MAX_MB_PER_STEP = 100  # the generator burns at most this much per step (a step is about a second)


def plan(fluid_mb, energy, capacity, fe_per_mb=FE_PER_MB, max_mb=MAX_MB_PER_STEP):
    """(mb_to_burn, fe_to_add) for one step.

    Burns only what fits: the free room in the energy store limits it, so liquid is never wasted into a full store.
    Returns (0, 0) when there is nothing to do.
    """
    if fluid_mb <= 0 or capacity <= energy or fe_per_mb <= 0:
        return 0, 0
    room = (capacity - energy) // fe_per_mb
    burn = min(int(fluid_mb), int(room), int(max_mb))
    return (burn, burn * fe_per_mb) if burn > 0 else (0, 0)
