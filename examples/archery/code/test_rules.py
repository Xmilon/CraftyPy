import rules


def test_fraction_is_the_draw_and_none_for_items_without_one():
    assert rules.fraction(10, 20) == 0.5
    assert rules.fraction(40, 20) == 1.0 and rules.fraction(-3, 20) == 0.0
    assert rules.fraction(10, 0) is None


def test_the_segments_fill_one_after_the_other():
    assert rules.fills(0.0) == [0.0, 0.0, 0.0]
    assert rules.fills(1 / 6) == [0.5, 0.0, 0.0]
    assert [round(f, 3) for f in rules.fills(0.5)] == [1.0, 0.5, 0.0]
    assert rules.fills(1.0) == [1.0, 1.0, 1.0]


def test_the_colour_warms_up_then_flashes_when_full():
    assert rules.color(0.1) == "gray" and rules.color(0.5) == "yellow" and rules.color(0.9) == "gold"
    assert {rules.color(1.0, t) for t in range(6)} == {"white", "green"}


def test_a_notch_is_counted_once_per_third():
    assert [rules.notches(f) for f in (0.0, 0.2, 0.34, 0.7, 0.99, 1.0)] == [0, 0, 1, 2, 2, 3]
