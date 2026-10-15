#: title: Window with buttons
#: category: UI elements
#: doc: api.md#Windows
#: module: counter_window
#: about: A window class with a label, a progress bar and buttons; /{{id}}:{{name}} opens it.
# A window: build() returns the widgets, callbacks are methods, the window redraws after each one.
from craftpy import *


@window("{{name}}", title="Counter", size=(260, 170))
class CounterWindow(Window):
    count = 0

    def build(self, ctx):
        return VBox(
            Label(f"Clicked {self.count} times", style="title"),
            ProgressBar(value=min(self.count, 10), max=10),
            HBox(
                Button("-1", on_click=self.less),
                Button("+1", on_click=self.more),
            ),
            Button("Close", on_click=ctx.close, style="text"),
            spacing=8, padding=4,
        )

    def more(self, ctx):
        self.count += 1

    def less(self, ctx):
        self.count = max(0, self.count - 1)


@command("{{name}}")
def open_{{name}}(ctx):
    """/{{id}}:{{name}} - open the window."""
    ui.open(ctx.player, "{{name}}")
