#: title: Settings with a slider
#: category: UI elements
#: doc: api.md#Windows
#: module: volume
#: about: A slider and a checkbox whose values are saved per player.
from craftpy import *


@window("{{name}}", title="Settings", size=(280, 160))
class {{name}}_window(Window):
    def build(self, ctx):
        key = "{{name}}:" + ctx.player.name
        saved = storage.get(key, {"level": 50, "on": True})
        return VBox(
            Label("Level %d" % saved["level"]),
            Slider(value=saved["level"], min=0, max=100, on_change=self.level),
            Checkbox("Enabled", checked=saved["on"], on_change=self.toggle),
            spacing=6, padding=6,
        )

    def level(self, ctx):
        key = "{{name}}:" + ctx.player.name
        storage[key] = dict(storage.get(key, {"level": 50, "on": True}), level=int(ctx.value))

    def toggle(self, ctx):
        key = "{{name}}:" + ctx.player.name
        saved = storage.get(key, {"level": 50, "on": True})
        storage[key] = dict(saved, on=not saved["on"])


@command("{{name}}", level=0)
def open_{{name}}(ctx):
    """/{{id}}:{{name}} - open the settings."""
    ui.open(ctx.player, "{{name}}")
