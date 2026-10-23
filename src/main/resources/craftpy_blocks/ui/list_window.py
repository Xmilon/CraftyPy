#: title: List of online players
#: category: UI elements
#: doc: api.md#ListView
#: module: player_list
#: about: A ListView filled when the window opens, with a refresh button.
from craftpy import *


@window("{{name}}", title="Online players", size=(260, 220))
class PlayerList(Window):
    def build(self, ctx):
        names = [p.name for p in players.online()]
        return VBox(
            Label(f"{len(names)} online", style="title"),
            ListView(names),
            HBox(
                Button("Refresh", on_click=self.refresh),
                Button("Close", on_click=ctx.close, style="text"),
            ),
            spacing=6, padding=4,
        )

    def refresh(self, ctx):
        pass  # the window is rebuilt after every callback


@command("{{name}}", level=0)
def open_{{name}}(ctx):
    """/{{id}}:{{name}} - show who is online."""
    ui.open(ctx.player, "{{name}}")
