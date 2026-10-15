#: title: Do something a bit later
#: category: Timers
#: doc: api.md#after
#: module: delayed_hello
#: about: after(seconds, fn): a second message a few seconds after a player joins.
from craftpy import *


@on.player_join
def later_{{name}}(event):
    name = event.player.name
    after(5, lambda: say("Say hi to", name + "!"))
