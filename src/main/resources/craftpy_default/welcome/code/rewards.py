# 11. Plain logic in its own file, so test_rewards.py can check it (Ctrl+Shift+F10 runs the tests).
APPLE_EVERY = 5


def apple_due(ores_mined):
    """True on every APPLE_EVERY-th gem ore (5, 10, 15, ...)."""
    return ores_mined > 0 and ores_mined % APPLE_EVERY == 0
