import grinding
from grinding import step

NS = "dustworks"


def test_nothing_to_grind_burns_no_coal():
    result = step(0, 10_000, 0, 0, 5, "minecraft:air", 0, "minecraft:air", 0, NS)
    assert not result["fuel_used"] and result["energy"] == 0 and result["made"] is None


def test_coal_starts_burning_when_energy_is_short():
    result = step(0, 10_000, 0, 0, 1, "minecraft:diorite", 3, "minecraft:air", 0, NS)
    assert result["fuel_used"]
    assert result["burn"] == grinding.BURN_SECONDS - 1
    # the second's burning paid for less than a step, so nothing was ground yet
    assert result["energy"] == grinding.FE_PER_BURN_SECOND
    assert result["progress"] == 0


def test_cable_energy_grinds_without_coal():
    result = step(1_000, 10_000, 0, 0, 0, "minecraft:granite", 1, "minecraft:air", 0, NS)
    assert not result["fuel_used"]
    assert result["energy"] == -grinding.FE_PER_STEP
    assert result["progress"] == 100 // grinding.STEPS


def test_the_last_step_makes_dust_of_the_right_stone():
    result = step(1_000, 10_000, 0, 75, 0, "minecraft:polished_andesite", 1, "minecraft:air", 0, NS)
    assert result["made"] == "dustworks:andesite_dust"
    assert result["progress"] == 0


def test_a_full_or_different_output_stops_it():
    full = step(1_000, 10_000, 0, 50, 0, "minecraft:diorite", 1, "dustworks:diorite_dust", 63, NS)
    assert full["made"] is None and full["progress"] == 0 and full["energy"] == 0
    other = step(1_000, 10_000, 0, 50, 0, "minecraft:diorite", 1, "dustworks:granite_dust", 4, NS)
    assert other["made"] is None and other["progress"] == 0


def test_burning_never_overfills_the_store():
    result = step(9_990, 10_000, 10, 0, 0, "minecraft:air", 0, "minecraft:air", 0, NS)
    assert result["energy"] == 10
