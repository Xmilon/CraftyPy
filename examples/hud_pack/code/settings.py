from craftpy import *

state = {key: storage.get("hud_" + key, True) for key in ("visible", "coords", "held", "toasts")}


@ui.data("hud_settings")
def settings_data(ctx):
    return dict(state)


@action("hud_changed")
def changed(ctx):
    for key in state:
        if key in ctx.values and isinstance(ctx.values[key], bool):
            state[key] = ctx.values[key]
            storage.set("hud_" + key, state[key])
    if not state["visible"]:
        hud.clear()
