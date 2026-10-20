# Sky Islands - a floating archipelago grown live above you: islands with groves, waterfalls, ruins, gardens and
# crystal fields, rope bridges between them, a spire on the hub, and a Sky Crystal on every island to find.
#
#   islands.py      the plan: where islands float and which bridges join them (plain Python, see test_islands.py)
#   themes.py       what they are made of: verdant, frost, blossom, ember, void (add your own)
#   builder.py      plan -> blocks, a few pieces per tick; collapse() takes it all down again
#   expedition.py   a run: raising, the crystal hunt, records, the net that catches you if you fall
#   ui/archipelago.json   the settings window, made in the UI maker (its buttons call the @action functions below)
#
# Craft the Skyward Compass and right click it (or /sky_islands:open). Nothing here needs a permission: no web, no
# files, no commands - it only places blocks, so it runs as soon as you press Run.
from craftpy import *

import expedition
import islands
from themes import NAMES

SIZE_NAMES = list(islands.SIZES)

shard = Item("sky_shard", name="Sky Shard", texture="sky_shard.png", glow=True).extends(
    Tooltip("A piece of a floating island's heart", color="aqua"))
crystal = Block("sky_crystal", name="Sky Crystal", texture="sky_crystal.png", hardness=0.6, light=15,
                sound="glass", drops=(shard, 1))
compass = Item("skyward_compass", name="Skyward Compass", texture="skyward_compass.png", stack=1, glow=True).extends(
    Tooltip("Right click: raise islands into the sky", color="aqua"))

shaped(compass, " F ", "FCF", " F ", F="minecraft:feather", C="minecraft:compass")
shaped("minecraft:elytra", "SSS", "SPS", "S S", S=shard, P="minecraft:phantom_membrane")


@on.item_use(item=compass)
def open_from_compass(event):
    ui.open(event.player, "archipelago")


@on.block_break(block=crystal)
def crystal_found(event):
    expedition.crystal_broken(event.player, event.pos)


@every(seconds=1)
def tick():
    expedition.every_second()


# -- the window from the UI maker: ui/archipelago.json names these actions ---------------------------------------------

def _choice(values, key, options, default):
    try:
        return options[int(values.get(key, default))]
    except (TypeError, ValueError, IndexError):
        return options[default]


@action("raise")
def raise_islands(ctx):
    size = _choice(ctx.values, "size", SIZE_NAMES, 1)
    theme = _choice(ctx.values, "theme", NAMES, 0)
    text = str(ctx.values.get("seed") or "").strip()
    seed = int(text) if text.lstrip("-").isdigit() else None
    ctx.close()
    expedition.start(ctx.player, size, theme, seed, crystal)


@action("status")
def show_status(ctx):
    ctx.open("sky_status")


@action("collapse")
def take_down(ctx):
    expedition.collapse(ctx.player)


# -- the live window: written in Python because it changes while you play ---------------------------------------------

@window("sky_status", title="☁ Expedition", size=(300, 230))
class Status(Window):
    def build(self, ctx):
        exp = expedition.EXPEDITIONS.get(ctx.player.name) if ctx.player else None
        if exp is None:
            return VBox(Label("No islands yet", style="title"),
                        Label("Right click the Skyward Compass and raise some."), padding=8)
        best = storage.get("best:" + exp.size)
        return VBox(
            Label("%s %s archipelago" % (exp.size, exp.theme), style="title"),
            Label({"building": "Rising...", "exploring": "Find the glowing Sky Crystals",
                   "done": "All found!" if len(exp.found) == exp.total else "Back on the ground",
                   "gone": "Taken down"}[exp.state]),
            ProgressBar(value=len(exp.found), max=max(1, exp.total)),
            Label("Crystals %d / %d   ·   time %s   ·   best %s" % (len(exp.found), exp.total,
                                                                     expedition.fmt(exp.ticks), expedition.fmt(best))),
            Label("seed %d" % exp.build.plan.seed),
            HBox(Button("Back to the hub", on_click=self.hub), Button("Go home", on_click=self.home)),
            Button("Take them down", on_click=self.collapse, style="text"),
            spacing=6, padding=8)

    def hub(self, ctx):
        exp = expedition.EXPEDITIONS.get(ctx.player.name)
        if exp is not None and exp.state in ("exploring", "done"):
            exp.bring_back(ctx.player, "§bBack on the hub")

    def home(self, ctx):
        exp = expedition.EXPEDITIONS.get(ctx.player.name)
        if exp is not None:
            exp.leave(ctx.player)

    def collapse(self, ctx):
        expedition.collapse(ctx.player)


# -- commands --------------------------------------------------------------------------------------------------------

@command("open")
def open_window(ctx):
    """/sky_islands:open - the settings window."""
    ui.open(ctx.player, "archipelago")


@command("raise")
def raise_now(ctx, size: str, theme: str):
    """/sky_islands:raise medium frost - raise islands without the window."""
    if size not in islands.SIZES or theme not in NAMES:
        ctx.player.say("Sizes: %s. Themes: %s." % (", ".join(islands.SIZES), ", ".join(NAMES)))
        return
    expedition.start(ctx.player, size, theme, None, crystal)


@command("home")
def go_home(ctx):
    """/sky_islands:home - back to where you raised them."""
    exp = expedition.EXPEDITIONS.get(ctx.player.name)
    if exp is None:
        ctx.player.say("You have no islands.")
    else:
        exp.leave(ctx.player)


@command("collapse")
def collapse_now(ctx):
    """/sky_islands:collapse - take your islands down."""
    expedition.collapse(ctx.player)


def main(ctx):
    ctx.log("Sky Islands ready: craft the Skyward Compass (or /sky_islands:open)")
