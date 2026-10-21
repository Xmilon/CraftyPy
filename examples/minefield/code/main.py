# Minefield - a minesweeper window. Hit a mine and a TNT explosion goes off right next to you in the world.
#
# Try it:  /minefield:open   or press Ctrl+Shift+M
#
# game.py       the rules (no game calls in it)
# main.py       the window's data and buttons, the explosion and the animation
# ui/minefield.json   the window itself, drawn in the UI maker
from craftpy import *
import random

from game import Game, SIZE

GAMES = {}      # player name -> {"game", "flag", "frame", "blast"}
WINDOW = "minefield"


def state_of(player):
    st = GAMES.get(player.name)
    if st is None:
        st = GAMES[player.name] = dict(game=Game(), flag=False, frame=0, blast=[], shown={})
    return st


# -- what the window shows ---------------------------------------------------------------------------------------------

def cell_text(st, r, c):
    g = st["game"]
    if (r, c) in st["shown"]:                       # mines revealed one by one after a loss
        return "X" if st["shown"][(r, c)] % 2 else "*"
    if g.opened[r][c]:
        if g.mine[r][c]:
            return "X"
        return str(g.near[r][c]) if g.near[r][c] else "."
    if g.flagged[r][c]:
        return "F"
    return ""


def status_text(st):
    g, frame = st["game"], st["frame"]
    if g.state == "lost":
        return ("!! BOOM !!", "** BOOM **")[frame % 2]
    if g.state == "won":
        return ("Field cleared!", "*  Field cleared!  *")[frame % 2]
    if not g.placed:
        return "Pick a tile. The first one is always safe."
    if g.safe_left() <= 3:
        return ("So close... %d tiles left" % g.safe_left(), "Careful!!! %d tiles left" % g.safe_left())[frame % 2]
    return "%d safe tiles left" % g.safe_left()


@ui.data(WINDOW)
def window_data(ctx):
    st = state_of(ctx.player)
    g = st["game"]
    over = g.state != "playing"
    data = {
        "status": status_text(st),
        "info": "Mines: %d    Flags: %d" % (g.mines, g.flags()),
        "cleared": g.cleared(),
        "safe_total": g.safe_total(),
        "flag_text": "Flag mode: ON" if st["flag"] else "Flag mode: off",
    }
    for r in range(SIZE):
        for c in range(SIZE):
            data["t_%d_%d" % (r, c)] = cell_text(st, r, c)
            data["e_%d_%d" % (r, c)] = not over and not g.opened[r][c]
    return data


# -- the explosion in the world ----------------------------------------------------------------------------------------

def boom(player):
    """TNT goes off two blocks beside the player. No blocks are destroyed; it hurts, it should not kill."""
    p = player.refresh()
    dx, dz = random.choice(((2, 0), (-2, 0), (0, 2), (0, -2)))
    x, y, z = p.x + dx, p.y, p.z + dz
    world.explode(x, y, z, power=2, fire=False, break_blocks=False, dimension=p.dimension)
    for kind, count in (("minecraft:explosion_emitter", 1), ("minecraft:flame", 40), ("minecraft:smoke", 30)):
        world.particle(kind, x, y + 1, z, count=count, dimension=p.dimension)
    p.title("BOOM!", subtitle="You hit a mine", seconds=2)


def celebrate(player):
    p = player.refresh()
    world.particle("minecraft:firework", p.x, p.y + 2, p.z, count=60, dimension=p.dimension)
    world.play_sound("minecraft:entity.player.levelup", p.x, p.y, p.z, dimension=p.dimension)
    p.title("Field cleared!", subtitle="Not a scratch", seconds=3)


# -- the buttons -------------------------------------------------------------------------------------------------------

def click(player, r, c):
    st = state_of(player)
    g = st["game"]
    if st["flag"]:
        g.flag(r, c)
        return
    result = g.open(r, c)
    st["frame"] = 0
    if result == "boom":
        boom(player)
        hit = g.hit
        # the rest of the mines blow up one after the other, starting next to the one that was hit
        st["blast"] = sorted((m for m in g.mine_cells() if m != hit),
                             key=lambda m: (m[0] - hit[0]) ** 2 + (m[1] - hit[1]) ** 2)
        st["shown"] = {hit: 0}
    elif result == "won":
        celebrate(player)


def make_cell_action(r, c):
    def cell(ctx):
        click(ctx.player, r, c)
    return cell


for _r in range(SIZE):
    for _c in range(SIZE):
        action("cell_%d_%d" % (_r, _c))(make_cell_action(_r, _c))


@action("new_game")
def new_game(ctx):
    st = state_of(ctx.player)
    GAMES[ctx.player.name] = dict(game=Game(), flag=st["flag"], frame=0, blast=[], shown={})


@action("toggle_flag")
def toggle_flag(ctx):
    st = state_of(ctx.player)
    st["flag"] = not st["flag"]


# -- the animation: the window is redrawn every few ticks while something is happening ----------------------------------

@every(ticks=4)
def animate():
    for name, st in list(GAMES.items()):
        g = st["game"]
        near_end = g.state == "playing" and g.placed and g.safe_left() <= 3
        busy = near_end or g.state == "won" or (g.state == "lost" and (st["blast"] or st["frame"] < 30))
        if not busy:
            continue
        st["frame"] += 1
        for _ in range(2):                          # two mines per step: a ripple through the field
            if st["blast"]:
                m = st["blast"].pop(0)
                st["shown"][m] = 0
                p = Player.named(name)
                if p is not None:
                    world.play_sound("minecraft:entity.generic.explode", p.x, p.y, p.z, volume=0.4,
                                     pitch=1.2 + 0.05 * len(st["shown"]), dimension=p.dimension)
        for m in st["shown"]:                       # revealed mines flicker between two shapes
            st["shown"][m] += 1
        p = Player.named(name)
        if p is not None:
            ui.refresh(p, WINDOW)                   # False when the window is closed: nothing to redraw


@command("open")
def open_window(ctx):
    """/minefield:open - the minefield window."""
    if ctx.player:
        ui.open(ctx.player, WINDOW)


@keys.bind("ctrl+shift+m", id="open_minefield", name="Open Minefield", category="Minefield")
def open_shortcut(ctx):
    ui.open(ctx.player, WINDOW)


def main(ctx):
    ctx.log("Minefield ready: /minefield:open")
