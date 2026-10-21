# 5. Timers: @every(seconds=...), after(seconds, fn), run_every(ticks, fn).
from craftpy import *


@every(seconds=120)
def reminder():
    for p in players.online():
        p.actionbar("Tip: /{{id}}:menu opens a window, /{{id}}:selftest checks the API")
