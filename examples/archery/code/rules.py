# The rules of the charge bar, apart from the game so they can be tested (code/test_rules.py).

SEGMENTS = 3                       # the bar is three segments, like a Dungeons bow: a third, two thirds, full
COLORS = ("gray", "yellow", "gold")  # while the first, the second, the last segment fills
FULL_COLOR = "green"


def fraction(ticks, full):
    """How far the bow is drawn, 0..1; None when the item being used has no draw (full is 0)."""
    if full <= 0:
        return None
    return max(0.0, min(1.0, ticks / full))


def fills(frac):
    """What each segment (the bottom one first) shows: 0..1."""
    return [max(0.0, min(1.0, frac * SEGMENTS - i)) for i in range(SEGMENTS)]


def color(frac, tick=0):
    """The bar's colour: it warms up as it fills, then flashes green and white while the bow is fully drawn."""
    if frac >= 1.0:
        return "white" if tick % 6 < 2 else FULL_COLOR
    return COLORS[min(len(COLORS) - 1, int(frac * SEGMENTS))]


def notches(frac):
    """How many thresholds the draw has passed: 1 and 2 at a third and two thirds, 3 when it is full."""
    return SEGMENTS if frac >= 1.0 else min(SEGMENTS - 1, int(frac * SEGMENTS))
