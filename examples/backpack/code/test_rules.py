import rules


def test_distill_needs_room_for_the_whole_press():
    assert rules.fits(rules.DISTILL_FE) and rules.fits(50_000)
    assert not rules.fits(rules.DISTILL_FE - 1) and not rules.fits(0)


def test_eat_picks_the_meal_that_fits_the_hunger():
    slots = [{"item": "minecraft:stone", "count": 5}, {"item": "bread", "count": 2, "food": 5},
             {"item": "steak", "count": 1, "food": 8}, {"item": "apple", "count": 0, "food": 4}]
    assert rules.best_food(slots, 8) == 2            # the steak fills exactly what is missing
    assert rules.best_food(slots, 5) == 1            # the steak would waste too much: bread
    assert rules.best_food(slots, 2) == 1            # nothing fits: the smallest meal there is (an empty slot is no meal)
    assert rules.best_food([{"item": "minecraft:stone", "count": 3}], 10) is None
