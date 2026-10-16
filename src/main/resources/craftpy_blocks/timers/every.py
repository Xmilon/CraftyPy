#: title: Repeat every few seconds
#: category: Timers
#: doc: api.md#Timers
#: module: reminders
#: about: Every 5 minutes, a tip in every player's action bar.
from craftpy import *


@every(seconds=300)
def remind_{{name}}():
    for p in players.online():
        p.actionbar("Tip: drink water, touch grass")
