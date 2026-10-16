#: title: Window with tabs
#: category: UI elements
#: doc: api.md#Windows
#: module: guide
#: about: A window with three tabs; /{{id}}:{{name}} opens it.
from craftpy import *


@window("{{name}}", title="Guide", size=(320, 220))
class {{name}}_window(Window):
    def build(self, ctx):
        return TabView(
            VBox(Label("Welcome", style="title"), Label("Start by crafting a pickaxe."), title="Start"),
            VBox(Label("Tips", style="title"), Label("Sneak to place blocks on chests."), title="Tips"),
            VBox(Label("About", style="title"), Label("Made with CraftPy."), title="About"),
        )


@command("{{name}}")
def open_{{name}}(ctx):
    """/{{id}}:{{name}} - open the guide."""
    ui.open(ctx.player, "{{name}}")
