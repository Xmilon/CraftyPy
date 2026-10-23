# {{id}} - a window app. Python describes the window, the game draws it and calls your functions back.
# Callbacks are methods; after each one the window is redrawn from build(). Keep your state on self.
# For a window drawn with the mouse, create ui/main.json (Tools > New UI window, then Tools > UI designer)
# and open it with ui.open(player, "main"); its buttons call @action("name") functions.
from craftpy import *


@window("hello", title="Hello window", size=(260, 150))
class HelloWindow(Window):
    clicks = 0

    def build(self, ctx):
        return VBox(
            Label("Hello from Python!", style="title"),
            Label(f"You clicked {self.clicks} time(s)"),
            Button("Click me", on_click=self.click),
            Button("Close", on_click=ctx.close, style="text"),
            spacing=8, padding=4,
        )

    def click(self, ctx):
        self.clicks += 1
        ctx.player.actionbar(f"Clicked {self.clicks}")


@command("open", level=2)
def open_window(ctx, target: Player):
    """/{{id}}:open <player> - open the window."""
    ui.open(target, "hello")
