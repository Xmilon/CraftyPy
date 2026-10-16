# 11. Tests: files named test_<something>.py, functions named test_<something> that use assert.
# Press Ctrl+Shift+F10: failures come first, a click opens the line. Change APPLE_EVERY in rewards.py and run them again.
from rewards import apple_due


def test_every_fifth_ore_gives_an_apple():
    assert apple_due(5)
    assert apple_due(10)


def test_other_ores_give_nothing():
    assert not apple_due(0)
    assert not apple_due(4)
