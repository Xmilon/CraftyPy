import grinding
from grinding import step

RECIPE = {"id": "dustworks:grind_diorite", "energy": grinding.GRIND_ENERGY, "ticks": grinding.GRIND_SECONDS * 20}


def test_nothing_to_grind_burns_nothing():
    result = step(0, 10_000, 0, 0, 5, 4000, None)
    assert not result["fuel_used"] and result["lava_used"] == 0 and result["energy"] == 0 and not result["finish"]


def test_coal_starts_burning_when_energy_is_short():
    result = step(0, 10_000, 0, 0, 1, 4000, RECIPE)
    assert result["fuel_used"] and result["lava_used"] == 0      # coal first, the lava waits
    assert result["burn"] == grinding.COAL_BURN - grinding.TICK
    assert result["energy"] == grinding.FE_PER_RUN
    assert result["progress"] == 0                                # not enough stored yet to grind


def test_lava_burns_when_there_is_no_coal():
    result = step(0, 10_000, 0, 0, 0, 1000, RECIPE)
    assert not result["fuel_used"] and result["lava_used"] == grinding.LAVA_PER_BURN
    too_little = step(0, 10_000, 0, 0, 0, grinding.LAVA_PER_BURN - 1, RECIPE)
    assert too_little["lava_used"] == 0 and too_little["burn"] == 0


def test_burning_gives_more_than_grinding_costs():
    per_coal = grinding.COAL_BURN // grinding.TICK * grinding.FE_PER_RUN
    assert per_coal >= 7 * grinding.GRIND_ENERGY
    assert 1000 / grinding.LAVA_PER_BURN == 12.5    # a bucket of lava is 12.5 coal, like in a furnace


def test_the_arrow_moves_a_little_every_run():
    result = step(1_000, 10_000, 0, 0, 0, 0, RECIPE)
    assert not result["fuel_used"] and result["energy"] == 0
    assert result["progress"] == grinding.TICK and not result["finish"]


def test_the_last_run_finishes_and_a_full_output_waits():
    result = step(1_000, 10_000, 0, RECIPE["ticks"] - grinding.TICK, 0, 0, RECIPE)
    assert result["finish"] and result["progress"] == RECIPE["ticks"]
    # main.py keeps the progress when machine_finish says the output is full; the next run finishes again
    again = step(1_000, 10_000, 0, RECIPE["ticks"], 0, 0, RECIPE)
    assert again["finish"] and again["progress"] == RECIPE["ticks"]


def test_burning_never_overfills_the_store():
    result = step(9_998, 10_000, 40, 0, 0, 0, RECIPE)
    assert result["energy"] == 2
