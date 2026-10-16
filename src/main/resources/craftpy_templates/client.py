from craftpy import *

# A client-side project: this Python runs on each player's own computer, on any server (even one without CraftPy).
# It can draw on the HUD, react to keys and open windows. It cannot add blocks, items or change the world:
# for that, switch the side in Tools > Project side.

presses = storage.get("presses", 0)
hud.text("{{id}}_hello", "{{id}}: press K", anchor="top_left", x=4, y=4, color="aqua")


@keys.bind("k")
def pressed():
    global presses
    presses += 1
    storage["presses"] = presses
    hud.text("{{id}}_hello", f"{{id}}: K pressed {presses} times", anchor="top_left", x=4, y=4, color="gold")
