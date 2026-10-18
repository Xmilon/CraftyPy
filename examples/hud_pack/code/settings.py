from craftpy import *

# What the window changes. storage keeps it between sessions (on this computer).
state = {
    "visible": storage.get("hud_visible", True),
    "coords": storage.get("hud_coords", True),
    "held": storage.get("hud_held", True),
    "toasts": storage.get("hud_toasts", True),
}


@window("hud_settings", title="HUD settings", size=(280, 220))
class HudSettings(Window):
    def build(self, ctx):
        return VBox(
            Label("What to show", style="title"),
            Checkbox("Coordinates", checked=state["coords"], id="coords", on_change=self.changed),
            Checkbox("Held item", checked=state["held"], id="held", on_change=self.changed),
            Checkbox("Toast when chat says welcome", checked=state["toasts"], id="toasts", on_change=self.changed),
            Button("Close", on_click=ctx.close, style="text"),
            spacing=8, padding=6,
        )

    def changed(self, ctx):
        for key in ("coords", "held", "toasts"):
            if key in ctx.values:
                state[key] = bool(ctx.values[key])
                storage.set("hud_" + key, state[key])
