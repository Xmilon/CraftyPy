#: title: HUD status line (client)
#: category: Client side
#: doc: api.md#HUD
#: module: status
#: side: client
#: about: A line and a bar on the screen, updated every second on the player's own computer.
from craftpy import *


@on.client_tick
def {{name}}(event):
    if event.tick % 20:
        return
    player = client.player()
    hud.text("{{name}}", "%s  %d %d %d" % (player.name, player.x, player.y, player.z), anchor="top_left", x=4, y=4)
    hud.bar("{{name}}_health", player.health, max=20, anchor="top_left", x=4, y=16, color="red")
