# 7. Windows. A window is a class with build(). Callbacks are normal methods; after each one the window is
# redrawn. Everything you keep on `self` is remembered while the window is open (one copy per player).
from craftpy import *
from items import gem


@window("counter", title="Counter", size=(260, 170))
class Counter(Window):
    count = 0

    def build(self, ctx):
        return VBox(
            Label(f"Clicked {self.count} times", style="title"),
            ProgressBar(value=min(self.count, 10), max=10),
            HBox(
                Button("-1", on_click=self.less),
                Button("+1", on_click=self.more),
                Button("Reward", on_click=self.reward),
            ),
            Button("Close", on_click=ctx.close, style="text"),
            spacing=8, padding=4,
        )

    def more(self, ctx):
        self.count += 1

    def less(self, ctx):
        self.count = max(0, self.count - 1)

    def reward(self, ctx):
        if self.count >= 10:
            ctx.player.give(gem, 3)
            ctx.player.say("Reward claimed!")
            self.count = 0
        else:
            ctx.player.actionbar("Click to 10 first.")


# ui/settings.json was drawn with Tools > UI designer. It names its button actions; these are them.
# ctx.values holds every input of the window by its id. The action "close" is built in.

@action("apply_settings")
def apply_settings(ctx):
    name = ctx.values.get("nickname") or ctx.player.name
    ctx.player.say(f"Saved: nickname={name}, hints={ctx.values.get('hints')}, volume={ctx.values.get('volume')}")
    ctx.close()
