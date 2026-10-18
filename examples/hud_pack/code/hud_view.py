from craftpy import *


def draw(me, state):
    """Draws everything from the local player; an element with the same text costs nothing to draw again."""
    if not state["visible"]:
        return
    if state["coords"]:
        hud.text("coords", "XYZ  %d  %d  %d" % (me.x, me.y, me.z), anchor="bottom_left", x=4, y=-4, color="aqua")
    else:
        hud.remove("coords")
    hud.bar("health", me.health, max=me.max_health, anchor="top_left", x=4, y=4, width=90, height=7,
            color="red" if me.health < 8 else "green", label="%d HP" % me.health)
    if state["held"] and me.held_item != "minecraft:air":
        hud.item("held", me.held_item, anchor="bottom_right", x=-4, y=-24, count=me.held_count, scale=1.5)
    else:
        hud.remove("held")
    target = client.looking_at()
    hud.text("target", target["block"] if target else " ", anchor="top_center", y=6, color="yellow")
    hud.text("fps", "%d fps" % client.fps(), anchor="top_right", x=-4, y=4, color="gray")
