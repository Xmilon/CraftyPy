#: title: Settings: checkbox, slider, dropdown
#: category: UI elements
#: doc: api.md#Slider
#: module: settings_window
#: about: Three kinds of inputs with ids, saved per player with storage.
from craftpy import *


@window("{{name}}", title="Settings", size=(300, 220))
class SettingsWindow(Window):
    def build(self, ctx):
        saved = storage.get("settings:" + ctx.player.name, {}) or {}
        return VBox(
            Checkbox("Show hints", checked=saved.get("hints", True), id="hints"),
            Label("Volume"),
            Slider(value=saved.get("volume", 50), min=0, max=100, id="volume"),
            Label("Difficulty"),
            Dropdown(["Easy", "Normal", "Hard"], selected=saved.get("difficulty", 1), id="difficulty"),
            HBox(
                Button("Apply", on_click=self.apply),
                Button("Close", on_click=ctx.close, style="text"),
            ),
            spacing=6, padding=4,
        )

    def apply(self, ctx):
        storage.set("settings:" + ctx.player.name, {
            "hints": bool(ctx.values.get("hints")),
            "volume": ctx.values.get("volume"),
            "difficulty": ctx.values.get("difficulty"),
        })
        ctx.player.actionbar("Settings saved")


@command("{{name}}")
def open_{{name}}(ctx):
    """/{{id}}:{{name}} - open the settings."""
    ui.open(ctx.player, "{{name}}")
