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
    for viewer in players.all():
        ui.refresh(viewer, "sky_status")
        ui.refresh(viewer, "archipelago")


# -- the window from the UI maker: ui/archipelago.json names these actions ---------------------------------------------

def _choice(values, key, options, default):
    try:
        index = int(values.get(key, default))
        return options[index] if 0 <= index < len(options) else options[default]
    except (TypeError, ValueError, IndexError):
        return options[default]


@action("raise")
def raise_islands(ctx):
    size = _choice(ctx.values, "size", SIZE_NAMES, 1)
    theme = _choice(ctx.values, "theme", NAMES, 0)
    text = str(ctx.values.get("seed") or "").strip()
    if len(text) > 20 or (text and not text.lstrip("-").isdigit()):
        ctx.player.say("Seed must be empty or an integer of at most 20 characters.")
        return
    seed = int(text) if text else None
    ctx.close()
    expedition.start(ctx.player, size, theme, seed, crystal)


@action("status")
def show_status(ctx):
    ctx.open("sky_status")


@action("collapse")
def take_down(ctx):
    expedition.collapse(ctx.player)


# Both windows are designer JSON; providers read only this viewer's expedition.

@ui.data("archipelago")
def setup_data(ctx):
    exp = expedition.EXPEDITIONS.get(ctx.player.name)
    busy = exp is not None and exp.state in ("building", "exploring", "done")
    return dict(sizes=[f"{name} ({islands.SIZES[name]} islands)" for name in SIZE_NAMES], themes=list(NAMES),
                can_raise=not busy, can_collapse=exp is not None and exp.state in ("exploring", "done"),
                setup_status="Your expedition: " + exp.state if exp else "Ready to raise a new archipelago")


@ui.data("sky_status")
def status_data(ctx):
    exp = expedition.EXPEDITIONS.get(ctx.player.name)
    if exp is None:
        return dict(title="No islands yet", status="Right click the Skyward Compass and raise some.",
                    found=0, total=1, statistics="", seed="", can_return=False, can_home=False, can_collapse=False)
    best = storage.get("best:" + exp.size)
    return dict(title=f"{exp.size} {exp.theme} archipelago",
                status={"building": "Rising...", "exploring": "Find the glowing Sky Crystals",
                        "done": "All found!" if len(exp.found) == exp.total else "Back on the ground",
                        "gone": "Taken down"}[exp.state],
                found=len(exp.found), total=max(1, exp.total),
                statistics="Crystals %d / %d · time %s · best %s" % (len(exp.found), exp.total, expedition.fmt(exp.ticks), expedition.fmt(best)),
                seed="seed %d" % exp.build.plan.seed, can_return=exp.state in ("exploring", "done"),
                can_home=exp.state != "gone", can_collapse=exp.state in ("exploring", "done"))


@action("sky_hub")
def hub(ctx):
    exp = expedition.EXPEDITIONS.get(ctx.player.name)
    if exp is not None and exp.state in ("exploring", "done"):
        exp.bring_back(ctx.player, "§bBack on the hub")


@action("sky_home")
def home(ctx):
    exp = expedition.EXPEDITIONS.get(ctx.player.name)
    if exp is not None and exp.state != "gone":
        exp.leave(ctx.player)


@action("sky_collapse")
def collapse_status(ctx):
    expedition.collapse(ctx.player)


# -- commands --------------------------------------------------------------------------------------------------------

@command("open")
def open_window(ctx):
    """/sky_islands:open - the settings window."""
    ui.open(ctx.player, "archipelago")


@keys.bind("ctrl+g", id="open_setup", name="Open Sky Islands", category="Sky Islands")
def open_shortcut(ctx):
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
