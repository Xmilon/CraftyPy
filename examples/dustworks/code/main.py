from craftpy import *
import grinding

# Dust Works: stone dusts that every mod understands, and a Grinder that makes them.
#
#   - Three dusts, each in the shared tag of its stone (c:dusts/diorite...) and in c:dusts. Tags are how mods agree on
#     things: a recipe that asks for "#c:dusts/diorite" takes this dust and the diorite dust of any other mod.
#   - The Grinder is a machine block: an input slot for stone, a fuel slot for coal, an output slot, and an energy store
#     that any mod's cable can fill. Its window (ui/grinder.json) was drawn in the Minecraft-style window maker.
#   - Python runs it: every second it burns coal into energy and grinds (the rules are in grinding.py, with tests).
#   - Each player's ground stone is counted in storage (scope "stats"), saved with the world: /dustworks:stats shows it,
#     /craftpy backup create dustworks keeps a copy of it.

STONES = ["diorite", "granite", "andesite"]
dusts = {}
for stone in STONES:
    dusts[stone] = Item(stone + "_dust", name=stone.title() + " Dust", texture=stone + "_dust.png",
                        tags=["c:dusts/" + stone, "c:dusts"])
    # any mod's dust of that stone works here, not only this project's
    shaped("minecraft:" + stone, "DD", "DD", D="#c:dusts/" + stone)

grinder = Block("grinder", name="Grinder", texture="grinder.png", hardness=3.5, tool="pickaxe", sound="metal",
                item_tags=["c:machines"]).extends(
    EnergyStorage(capacity=20_000, max_receive=400, max_extract=0),      # takes energy from cables, gives none
    Inventory(input=1, fuel=1, output=1,
              accepts={"input": list(grinding.RECIPES), "fuel": ["#minecraft:coals"]}),
    Screen(title="Grinder", layout="grinder"),
)
# iron from any mod (#c:ingots/iron), any stone that crafts tools, and a furnace
shaped(grinder, "III", "IFI", "SSS", I="#c:ingots/iron", F="furnace", S="#minecraft:stone_crafting_materials")

PLACES = "grinders"          # storage: "dimension|x|y|z" -> the uuid of who placed it


def key(dimension, x, y, z):
    return "%s|%d|%d|%d" % (dimension, x, y, z)


@on.block_place(block=grinder)
def placed(event):
    places = storage.get(PLACES, {})
    places[key(event.dimension, event.x, event.y, event.z)] = event.player.uuid
    storage.set(PLACES, places)


@on.block_break(block=grinder)
def removed(event):
    places = storage.get(PLACES, {})
    places.pop(key(event.dimension, event.x, event.y, event.z), None)
    storage.set(PLACES, places)


@every(seconds=1)
def run_grinders():
    places = storage.get(PLACES, {})
    for where, owner in places.items():
        dimension, x, y, z = where.rsplit("|", 3)
        try:
            machine = world.machine(int(x), int(y), int(z), dimension=dimension)
        except RuntimeError:        # not loaded: nobody is near it
            continue
        work(machine, int(x), int(y), int(z), dimension, owner)


def work(machine, x, y, z, dimension, owner):
    values = machine.get("values", {})
    stone = machine["inventory"]["input"][0]
    fuel = machine["inventory"]["fuel"][0]
    out = machine["inventory"]["output"][0]
    change = grinding.step(machine["energy"], machine["energy_capacity"], values.get("burn", 0), values.get("progress", 0),
                           fuel["count"], stone["item"], stone["count"], out["item"], out["count"], "dustworks")
    if change["fuel_used"]:
        world.machine_slot(x, y, z, slot="fuel.0", item=fuel["item"] if fuel["count"] > 1 else None,
                           count=max(1, fuel["count"] - 1), dimension=dimension)
    if change["energy"]:
        world.machine_update(x, y, z, energy=change["energy"], dimension=dimension)
    ground = values.get("ground", 0)
    if change["made"]:
        world.machine_slot(x, y, z, slot="input.0", item=stone["item"] if stone["count"] > 1 else None,
                           count=max(1, stone["count"] - 1), dimension=dimension)
        world.machine_slot(x, y, z, slot="output.0", item=change["made"],
                           count=out["count"] + grinding.DUSTS_PER_STONE, dimension=dimension)
        ground += 1
        count_for(owner)
    new = {"burn": change["burn"], "progress": change["progress"], "ground": ground}
    if any(values.get(k) != v for k, v in new.items()):      # only what changed goes to the open windows
        world.machine_set(x, y, z, values=new, dimension=dimension)


def count_for(owner):
    """One more stone ground by the grinders of this player, and in the whole world (storage scope "stats")."""
    storage.set(owner, storage.get(owner, 0, scope="stats") + 1, scope="stats")
    storage.set("all", storage.get("all", 0, scope="stats") + 1, scope="stats")


@command("stats", level=0)
def stats(ctx):
    """/dustworks:stats - how much stone your grinders and everyone's have ground."""
    everyone = storage.get("all", 0, scope="stats")
    if ctx.player is None:
        print("Stone ground in this world:", everyone)
        return
    mine = storage.get(ctx.player.uuid, 0, scope="stats")
    ctx.player.say("Your grinders ground %d stone (%d dust); the whole world %d." % (
        mine, mine * grinding.DUSTS_PER_STONE, everyone))


@command("reset", level=2)
def reset(ctx):
    """/dustworks:reset - operators: start the statistics again (the grinders keep working)."""
    storage.clear(scope="stats")        # every key of the scope; the grinders' places are in another one
    say("Dust Works statistics were reset")


@command("kit", level=2)
def kit(ctx, target: Player):
    """/dustworks:kit <player> - operators: a grinder, coal and stone to try it."""
    target.give(grinder)
    target.give("coal", 8)
    for stone in STONES:
        target.give(stone, 16)
