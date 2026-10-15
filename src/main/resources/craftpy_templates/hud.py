# {{id}} - a HUD: a status line in the action bar (above the hotbar), refreshed every second.
# Press Run. It shows health, food and your position to every online player.
from craftpy import *


def bar(value, maximum, size=10):
    filled = round(size * value / maximum) if maximum else 0
    return "#" * filled + "-" * (size - filled)


@every(seconds=1)
def hud():
    for p in players.online():
        pos = p.pos
        p.actionbar(f"HP [{bar(p.health or 0, p.max_health or 20)}]  Food [{bar(p.food or 0, 20)}]  "
                    f"XYZ {int(pos.x)} {int(pos.y)} {int(pos.z)}")
