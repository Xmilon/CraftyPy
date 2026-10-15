# 5. Timers: @every(seconds=...), after(seconds, fn), run_every(ticks, fn).
from craftpy import *


@every(seconds=120)
def reminder():
    for p in players.online():
        p.actionbar("Tip: /welcome:menu opens a window, /welcome:selftest checks the API")
