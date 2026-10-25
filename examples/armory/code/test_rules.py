import rules


def test_the_thread_snaps_only_on_a_low_roll():
    assert rules.thread_snaps(0.0) and rules.thread_snaps(rules.SNAP_CHANCE - 0.001)
    assert not rules.thread_snaps(rules.SNAP_CHANCE) and not rules.thread_snaps(0.9)


def test_a_snap_hits_hard_and_costs_the_wielder_but_never_kills():
    assert rules.snap_blow(10) == 30
    assert rules.backlash(20) == 14
    assert rules.backlash(3) == 1.0 and rules.backlash(1.0) == 1.0


def test_excalibur_doubles_against_the_undead_only():
    assert rules.holy_blow(8, "minecraft:zombie") == 16
    assert rules.holy_blow(8, "minecraft:cow") == 8


def test_tyrfing_gives_back_a_part_of_the_blow():
    assert rules.blood_heal(10) == 3.0


def test_three_quick_hits_earn_a_triumph_and_slow_ones_do_not():
    history, earned = rules.triumph([], 0.0)
    assert not earned
    history, earned = rules.triumph(history, 1.0)
    assert not earned
    history, earned = rules.triumph(history, 2.0)
    assert earned and history == []
    history, earned = rules.triumph([0.0, 1.0], 10.0)       # the first two are too old
    assert not earned and history == [10.0]
