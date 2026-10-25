from craftpy import *

import rules

# Archery: a charge bar for every bow, like the one in Minecraft Dungeons.
#
#   Draw any bow (vanilla, a crossbow, a trident, or the Warbow, Twinfang and Artemis' Bow of the Armory) and a bar of three
#   segments fills beside the crosshair: grey, then yellow, then gold, and flashes green and white when the draw is full,
#   with a tick at every third and a chime when it is ready. F8 moves the bar to the other side of the crosshair.
#
# This module runs on your own computer ("side": "client"): it works on any server and needs nothing from it. It reads what
# you are using from client.player() (using, use_ticks, use_full) and draws with hud.bar(vertical=True).

BAR_IDS = ["archery_bar_%d" % i for i in range(rules.SEGMENTS)]
state = {"shown": False, "notches": 0}
side = storage.get("side", 1)          # 1: right of the crosshair, -1: left


def draw(frac, tick):
    colour = rules.color(frac, tick)
    for i, fill in enumerate(rules.fills(frac)):
        hud.bar(BAR_IDS[i], fill, max=1, anchor="center", x=side * 17, y=13 - i * 12, width=5, height=10,
                color=colour, background="dark_gray", vertical=True, frame="black")


def hide():
    for bar_id in BAR_IDS:
        hud.remove(bar_id)
    state["shown"] = False
    state["notches"] = 0


@on.client_tick
def tick(ctx):
    me = client.player()
    frac = rules.fraction(me.use_ticks, me.use_full) if me.using else None
    if frac is None:
        if state["shown"]:
            hide()
        return
    state["shown"] = True
    draw(frac, ctx.tick)
    passed = rules.notches(frac)
    if passed > state["notches"]:
        state["notches"] = passed
        if passed >= rules.SEGMENTS:
            client.sound("archery:ready", 0.7, 1.0)
        else:
            client.sound("archery:notch" if passed == 1 else "archery:notch_high", 0.5, 1.0)


@keys.bind("f8", id="archery_side", name="Archery: move the charge bar to the other side")
def flip():
    global side
    side = -side
    storage.set("side", side)
