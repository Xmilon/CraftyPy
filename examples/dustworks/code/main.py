from craftpy import *
import grinding

# Dust Works: stone dusts that every mod understands, and a Grinder that makes them.
#
#   - Three dusts, each in the shared tag of its stone (c:dusts/diorite...) and in c:dusts. Tags are how mods agree on
#     things: a recipe that asks for "#c:dusts/diorite" takes this dust and the diorite dust of any other mod.
#   - The Grinder is a machine block: an input slot for stone, a fuel slot for coal, two output slots, a lava tank (pour a
#     bucket into the slot under it, or pipe lava in) and an energy store that any mod's cable can fill. Its window
#     (ui/grinder.json) was drawn in the Minecraft-style window maker.
#   - What it makes are machine recipes (machine_recipe below): JEI and EMI show them under the Grinder, and
#     world.machine_recipe / world.machine_finish find and make them with the slots' real items.
#   - Python runs it: five times a second it burns coal or lava into energy and grinds (the rules are in grinding.py,
#     with tests).
#   - The Adventurer's Pack (a placeable 3D backpack with tanks and energy) and Starbrine, a glowing liquid, further down.
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
    FluidTank(4000, allowed=["minecraft:lava"]),                       # burns 80 mB for each 40 s of fire
    Inventory(input=1, fuel=1, output=2, fluid_in=1,
              accepts={"input": list(grinding.STONES), "fuel": ["#minecraft:coals"]}),
    Screen(title="Grinder", layout="grinder"),
)
# a stone gives two dusts, and one more a quarter of the times
for stone, dust in grinding.STONES.items():
    machine_recipe(grinder, inputs=[stone], outputs=[(dusts[dust], 2), (dusts[dust], 1, 0.25)],
                   energy=grinding.GRIND_ENERGY, seconds=grinding.GRIND_SECONDS,
                   id="grind_" + stone.split(":")[1])

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


@every(ticks=grinding.TICK)
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
    fuel = machine["inventory"]["fuel"][0]
    lava = machine["tanks"][0]["amount"]
    recipe = world.machine_recipe(x, y, z, dimension=dimension)
    change = grinding.step(machine["energy"], machine["energy_capacity"], values.get("burn", 0), values.get("ticks", 0),
                           fuel["count"], lava, recipe)
    if change["fuel_used"]:
        world.machine_slot(x, y, z, slot="fuel.0", item=fuel["item"] if fuel["count"] > 1 else None,
                           count=max(1, fuel["count"] - 1), dimension=dimension)
    if change["energy"] or change["lava_used"]:
        world.machine_update(x, y, z, energy=change["energy"], fluid=-change["lava_used"], dimension=dimension)
    ground, ticks = values.get("ground", 0), change["progress"]
    if change["finish"] and world.machine_finish(x, y, z, recipe=recipe, dimension=dimension):
        ground += 1
        ticks = 0
        count_for(owner)
    # a full output keeps the arrow at its end until there is room again
    percent = 100 * ticks // recipe["ticks"] if recipe else 0
    new = {"burn": change["burn"], "ticks": ticks, "progress": percent, "ground": ground}
    if any(values.get(k) != v for k, v in new.items()):      # only what changed goes to the open windows
        world.machine_set(x, y, z, values=new, dimension=dimension)


def count_for(owner):
    """One more stone ground by the grinders of this player, and in the whole world (storage scope "stats")."""
    storage.set(owner, storage.get(owner, 0, scope="stats") + 1, scope="stats")
    storage.set("all", storage.get("all", 0, scope="stats") + 1, scope="stats")


# --- The Adventurer's Pack: a backpack that is an item, and sets down as one --------------------------------------------
#   Right click with it: 54 slots, two liquid tanks and an energy store, all kept inside the item (ui/adventure_pack.json).
#   Shift + right click a block: it stands there as itself and opens with a right click; hit it, or shift + right click it
#   with an empty hand, and it comes back with everything in it. Its own 3D model is assets/models/item/backpack.json.
#   Starbrine is a glowing liquid of the night sky; the Distill button turns 100 mB of it into 4000 FE, which charges the
#   batteries and tools of any mod in the pack's energy slots (and the Grinder takes the same energy from a cable).

starbrine = Fluid("starbrine", name="Starbrine", color="#7C5CFF",
                  density=1100, viscosity=1400, temperature=260,   # a little heavier and thicker than water, and cold
                  light=12,                                        # it glows like a lantern
                  effects=[("night_vision", 15), ("slow_falling", 3)],
                  still="starbrine_still.png", flowing="starbrine_flow.png", bucket_texture="starbrine_bucket.png",
                  tags=["c:starbrine"])
shapeless(starbrine.bucket, "bucket", "prismarine_crystals", "amethyst_shard", "glowstone_dust")

pack = Item("adventure_pack", name="Adventurer's Pack", model="backpack", stack=1).extends(
    Placeable(width=0.7, height=0.8),
    Tooltip("54 slots, two tanks, 40 000 FE", "Right click: open; shift + right click a block: set it down"),
)
shaped(pack, "SLS", "LCL", "LWL", S="string", L="leather", C="chest", W="#minecraft:wool")

DISTILL_MB = 100           # Starbrine one press of Distill uses
DISTILL_FE = 4000          # energy it gives: a bucket is 40 000 FE, a full store

PACK = dict(inventory=Inventory(storage=54, fluid_in=1, fluid_out=1, energy_in=1, energy_out=1),
            energy=40_000, tanks=2, tank_capacity=8000)


@on.item_use(item=pack)
def open_held(ctx):
    """Right click with the pack: its window, kept in the item itself."""
    ui.open_screen(ctx.player, "adventure_pack", hand="off" if ctx.hand == "OFF_HAND" else "main", **PACK)


@on.entity_interact(entity=pack)
def open_placed(ctx):
    """Right click a pack on the floor: the very same window (shift + right click with an empty hand picks it up)."""
    if ctx.sneaking:
        return
    ui.open_screen(ctx.player, "adventure_pack", entity=ctx.entity, **PACK)
    ctx.cancel()


@on.screen_button(button="distill", window="adventure_pack")
def distill(ctx):
    """Distill: 100 mB of Starbrine from either tank becomes 4000 FE, if the store has room."""
    window = ui.screen_update(ctx.player)
    room = window["energy_capacity"] - window["energy"]
    for index, tank in enumerate(window["tanks"]):
        if tank["fluid"] == str(starbrine) and tank["amount"] >= DISTILL_MB:
            if room < DISTILL_FE:
                ui.screen_set(ctx.player, {"status": "The store is full"})
                return
            window = ui.screen_update(ctx.player, energy=DISTILL_FE, fluid=-DISTILL_MB, tank=index)
            ui.screen_set(ctx.player, {"status": "+%d FE" % DISTILL_FE})
            return
    ui.screen_set(ctx.player, {"status": "Needs %d mB of Starbrine" % DISTILL_MB})


@command("stats", level=0)
def stats(ctx):
    """/dustworks:stats - how much stone your grinders and everyone's have ground."""
    everyone = storage.get("all", 0, scope="stats")
    if ctx.player is None:
        print("Stone ground in this world:", everyone)
        return
    mine = storage.get(ctx.player.uuid, 0, scope="stats")
    ctx.player.say("Your grinders ground %d stone; the whole world %d." % (mine, everyone))


@command("reset", level=2)
def reset(ctx):
    """/dustworks:reset - operators: start the statistics again (the grinders keep working)."""
    storage.clear(scope="stats")        # every key of the scope; the grinders' places are in another one
    say("Dust Works statistics were reset")


@command("kit", level=2)
def kit(ctx, target: Player):
    """/dustworks:kit <player> - operators: a grinder, coal, lava, stone, the pack and Starbrine to try them."""
    target.give(grinder)
    target.give("coal", 8)
    target.give("lava_bucket")
    target.give(pack)
    target.give(starbrine.bucket)
    for stone in STONES:
        target.give(stone, 16)
