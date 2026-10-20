# HUD Pack - Python that runs on YOUR computer, not in the world. It draws on the screen, listens to keys and opens a window.
# It cannot change blocks, give items or run commands: client code only sees what your game already knows.
#
#   hud_view.py   what is drawn, from a few numbers
#   settings.py   the settings window (Ctrl+H) and what it changes
#
# Run it: press Run in the editor (a project with "side": "client" runs here). Build module > "Client only" to share it.
from craftpy import *

import hud_view
import settings


@on.client_tick
def refresh(event):
    if event.tick % 5 == 0:          # every quarter of a second is plenty
        hud_view.draw(client.player(), settings.state)


@on.chat_received
def watch_chat(event):
    if "welcome" in event.message.lower() and settings.state["toasts"]:
        client.toast("Chat", event.message)


@keys.bind("f7")
def toggle(ctx):
    settings.state["visible"] = not settings.state["visible"]
    if not settings.state["visible"]:
        hud.clear()
    client.chat("HUD " + ("on" if settings.state["visible"] else "off"))


@keys.bind("ctrl+h")
def open_settings(ctx):
    client.open_window("hud_settings")


def main(ctx):
    ctx.log("HUD Pack running on this computer: F7 hides it, Ctrl+H opens the settings")
