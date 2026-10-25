# Cookbook

Complete examples to copy into `code/main.py` and change. Each one says what you get, gives the whole code, then explains
the parts that are not obvious. They build on each other a little, but every one runs alone. Press **Run** (`Ctrl+R`) after
pasting, and get your things with `/craftpy give @s <project>:<id>`.

## A block and the item it drops

You get an ore that needs an iron pickaxe, drops one to three rubies, and a block of rubies crafted from nine of them.

```python
from craftpy import *

ruby = Item("ruby", name="Ruby", texture="minecraft:emerald", glow=True)

ruby_ore = Block("ruby_ore", name="Ruby Ore", texture="minecraft:emerald_ore",
                 hardness=3, tool="pickaxe", needs_tool=True,
                 drops=[(ruby, 1, 3), (ruby, 2, 2), (ruby, 3, 1)])   # (item, count, weight): one of them is picked
ruby_ore.extends(MineableBy("pickaxe", tier="iron"))

ruby_block = Block("ruby_block", texture="minecraft:emerald_block", hardness=5, sound="metal")

shaped(ruby_block, "RRR", "RRR", "RRR", R=ruby)       # nine rubies make a block
shapeless(ruby, ruby_block, count=9)                  # and back
smelt(ruby_ore, ruby, xp=1.0)                         # silk touch, then the furnace
```

How it works:

- `Item(...)` and `Block(...)` only describe the content. Run turns the descriptions into game data and reloads it, so there
  is no restart. The ids are `<project>:ruby` and so on.
- `needs_tool=True` drops nothing without the right tool, and `MineableBy` says which tool and tier that is.
- A recipe takes the variables (`ruby`), vanilla names (`"stick"`), full ids (`"create:wrench"`) or tags (`"#c:gems"`).

## Items other mods understand: tags

You get dusts that other mods can use and recipes that take any mod's dust. A tag is a named group of items (`c:dusts/diorite`)
that recipes, machines and other mods read. The `c:` tags are the ones every NeoForge mod agrees on.

```python
from craftpy import *

diorite_dust = Item("diorite_dust", texture="minecraft:bone_meal",
                    tags=["c:dusts/diorite", "c:dusts"])        # the tag of its stone, and of all dusts
granite_dust = Item("granite_dust", texture="minecraft:redstone",
                    tags=["c:dusts/granite", "c:dusts"])

# anyone's diorite dust works, not only yours: the recipe asks for the tag
shapeless("diorite", "#c:dusts/diorite", "#c:dusts/diorite", "cobblestone")
shapeless("granite", "#c:dusts/granite", "#c:dusts/granite", "cobblestone")

# a tag of your own (<project>:stone_dusts) and an addition to a vanilla tag
tag("stone_dusts", [diorite_dust, granite_dust])
tag("minecraft:logs", ["minecraft:stripped_oak_log"], type="block")

# a block is in block tags (what mines it) and its item in item tags (what recipes read)
dust_block = Block("diorite_dust_block", texture="minecraft:white_concrete_powder", sound="sand",
                   tags=["minecraft:mineable/shovel"], item_tags=["c:storage_blocks/diorite_dust"])
shaped(dust_block, "DDD", "DDD", "DDD", D="#c:dusts/diorite")
```

How it works:

- `tags=[...]` on `Item(...)`, `Block(...)` or `Fluid(...)` puts it in those tags (a block's `item_tags=` for its item);
  `tag(name, values)` makes or extends one. A bare name (`"stone_dusts"`) is your project's tag; a full id (`"c:dusts"`) is
  shared with every mod.
- A `#tag` works as a recipe ingredient (`"#c:dusts/diorite"`) and in `Inventory(accepts=...)`. A recipe's result is always
  one item, never a tag.
- Several projects that add to the same tag are merged: nothing is replaced unless you pass `replace=True`.
- Use the conventional names so other mods find your items: `c:ingots/<metal>`, `c:dusts/<thing>`, `c:gems/<gem>`,
  `c:ores/<ore>`, `c:storage_blocks/<thing>`, `c:nuggets/<metal>`, `c:rods/<thing>`. In the **Assets** window (`Alt+9`)
  `#c:dusts` finds what the installed mods put in a tag.

## Something that happens: events

You get a greeting when a player joins, a reward for mining your ore, and a stop sign for breaking a protected block.

```python
from craftpy import *

ruby = Item("ruby", texture="minecraft:emerald")
ruby_ore = Block("ruby_ore", texture="minecraft:emerald_ore", hardness=3, tool="pickaxe")
safe = Block("safe_block", texture="minecraft:bedrock", hardness=50)


@on.player_join
def hello(event):
    event.player.say("Welcome,", event.player.name)


@on.block_break(block=ruby_ore)          # only for this block
def mined(event):
    event.player.give(ruby)
    event.player.actionbar("+1 ruby")


@on.block_break(block=safe)
def protected(event):
    if event.player.gamemode != "creative":
        event.cancel()                   # the block stays
        event.player.say("This block is protected")
```

How it works:

- `@on.<event>` runs the function each time it happens; `block=`, `item=` and `entity=` keep only the ones you want.
- `event.cancel()` stops `block_break`, `item_use`, `item_hit`, `item_mine` and `entity_interact`. The other events only tell
  you.
- A handler gets about 50 ms. Long work goes in a timer (next recipe), never in a loop inside an event.

## Remembering things: storage and timers

You get a counter of rubies each player found, saved with the world, and a reminder every ten minutes for whoever has none.

```python
from craftpy import *

ruby = Item("ruby", texture="minecraft:emerald")
ruby_ore = Block("ruby_ore", texture="minecraft:emerald_ore", hardness=3, tool="pickaxe", drops=ruby)


@on.block_break(block=ruby_ore)
def count(event):
    key = "found:" + event.player.uuid
    storage.set(key, storage.get(key, 0) + 1)


@every(seconds=600)
def remind():
    for p in players.online():
        if storage.get("found:" + p.uuid, 0) == 0:
            p.actionbar("Rubies hide deep below, near lava")
```

How it works:

- `storage` keeps JSON values (numbers, text, lists, dicts) in the world's save, apart for each project: another project
  cannot read or change yours. Use the player's `uuid` in keys, not the name, which can change.
- `/craftpy backup create` writes all of it to a JSON file, and `/craftpy backup restore` puts it back.
- `@every(seconds=...)` repeats, `after(seconds, function)` runs once later.

## A machine with energy and its own window

You get a generator that burns coal in its window, fills an energy bar on the right and gives that energy to any cable of any
mod, with a progress flame driven by Python.

```python
from craftpy import *

generator = Block("coal_generator", name="Coal Generator", texture="minecraft:furnace_front",
                  hardness=3, tool="pickaxe", sound="metal").extends(
    EnergyStorage(capacity=100_000, max_receive=0, max_extract=1000),    # gives energy out only
    Inventory(fuel=1, accepts={"fuel": ["minecraft:coal", "minecraft:charcoal"]}),
    Screen(title="Coal Generator", layout="coal_generator"),            # ui/coal_generator.json
)
BURN = 40          # FE for each second of burning
places = set()     # the generators the world has (the timer below visits them)


@on.block_place(block=generator)
def placed(event):
    places.add((event.x, event.y, event.z, event.dimension))


@on.block_break(block=generator)
def removed(event):
    places.discard((event.x, event.y, event.z, event.dimension))


@every(seconds=1)
def burn():
    for x, y, z, dimension in list(places):
        try:
            machine = world.machine(x, y, z, dimension=dimension)
        except RuntimeError:              # its chunk is not loaded: nobody is near it
            continue
        left = machine["values"].get("burn", 0)
        fuel = machine["inventory"]["fuel"][0]
        if left <= 0 and fuel["count"] > 0 and machine["energy"] < machine["energy_capacity"]:
            world.machine_slot(x, y, z, slot="fuel.0", item=fuel["item"] if fuel["count"] > 1 else None,
                               count=max(1, fuel["count"] - 1), dimension=dimension)
            left = 80                                                     # a coal burns 80 seconds
        if left > 0:
            world.machine_update(x, y, z, energy=BURN, dimension=dimension)
            left -= 1
        world.machine_set(x, y, z, values={"burn": left}, dimension=dimension)   # the flame reads value:burn
```

Draw the window: **Tools > New UI window > Minecraft-style window, furnace size**, id `coal_generator`. Add an *Item slot*
with slot `fuel.0`, a *Flame* with bind `value:burn` and max `80`, an *Energy bar* on the right and the *Player inventory*.

How it works:

- `EnergyStorage`, `FluidTank` and `Inventory` are the standard NeoForge storages, so cables, pipes and hoppers of every mod
  use them without anything written for that mod. `max_receive=0` makes it a pure generator.
- The window's pieces show the machine's own numbers (`energy`, `fluid`, `progress`) or any value you set with
  `world.machine_set` (`value:burn`). Labels show values too: `{burn} s left`.
- `places` is kept in memory: after a restart the timer finds generators again as they are placed. Keep the places in `storage`
  when they must survive a restart.

## A liquid with buckets and effects

You get a liquid that flows, is heavier and thicker than water, glows, gives Speed and Poison to whoever swims in it, and
comes in a bucket that tanks and pipes of every mod can hold.

```python
from craftpy import *

sap = Fluid("volt_sap", name="Volt Sap", color="#5CE03C",
            density=1400,         # water is 1000: things sink in it
            viscosity=2600,       # thicker than water: it flows and moves you slowly
            temperature=340, light=7,
            effects=[("speed", 8, 2), ("poison", 3)])      # (effect, seconds, level) while you are in it

tank = Block("sap_tank", texture="minecraft:glass", hardness=1).extends(
    FluidTank(capacity=16_000, allowed=[sap]),
    Screen(title="Sap Tank"),                               # the standard window: bucket slots and the gauge
)
shaped(tank, "GGG", "G G", "GGG", G="glass")
```

How it works:

- `sap.bucket` is the bucket item: give it, use it in recipes, accept it in an `Inventory`.
- A bucket in the window's first slot is poured into the tank; the empty bucket comes out in the second.
- Liquids are slots registered at start (eight by default, `fluids=` in `craftpy-slots.properties`).

## Machine recipes that JEI and EMI show

You get a crusher whose recipes are real data-pack recipes: JEI and EMI list them on a page of the crusher (click the block
there to see everything it makes), tags work as inputs, outputs can have a chance, and the timer only says *when* to work.

```python
from craftpy import *

crusher = Block("crusher", name="Crusher", texture="minecraft:smooth_stone", hardness=3, tool="pickaxe").extends(
    EnergyStorage(capacity=20_000, max_receive=200, max_extract=0),
    Inventory(input=1, output=2),
    Screen(title="Crusher"),
)
machine_recipe(crusher, inputs=["#c:cobblestones"], outputs=[("gravel", 1), ("flint", 1, 0.1)], energy=200, seconds=2)
machine_recipe(crusher, inputs=[("gravel", 2)], outputs=["sand"], energy=100, seconds=1)
places = set()


@on.block_place(block=crusher)
def placed(event):
    places.add((event.x, event.y, event.z, event.dimension))


@on.block_break(block=crusher)
def removed(event):
    places.discard((event.x, event.y, event.z, event.dimension))


@every(ticks=5)
def crush():
    for x, y, z, dimension in list(places):
        try:
            machine = world.machine(x, y, z, dimension=dimension)
        except RuntimeError:
            continue
        recipe = world.machine_recipe(x, y, z, dimension=dimension)
        ticks = machine["values"].get("ticks", 0) + 5 if recipe else 0
        if recipe and ticks >= recipe["ticks"] and world.machine_finish(x, y, z, recipe=recipe, dimension=dimension):
            ticks = 0
        world.machine_set(x, y, z, values={"ticks": ticks}, dimension=dimension)
```

How it works:

- `machine_recipe` writes a `craftpy:machine` recipe into the project's data pack. Inputs are items, `(item, count)` or
  `#tags`; outputs `(item, count, chance)`; `fluid_input` and `fluid_output` take liquids from and put them into the
  block's tanks.
- `world.machine_recipe` tells which recipe the input slots and tanks can make now; `world.machine_finish` makes it in one
  step: it takes the inputs, rolls the chances, puts the outputs into the output slots and spends the energy, or changes
  nothing when the outputs are full or the energy is short.
- JEI and EMI read the recipes the server sends, so they show the same list on every client.

## A block with its own mesh and hitbox

You get a pedestal shaped like your Blockbench model, whose outline and collision follow the model.

1. Model it in Blockbench, export it as **Java Block/Item**, and save it as `assets/models/block/pedestal.json`; its textures
   are PNG files of the project (the model names them `<project>:block/name`).
2. Name the model in Python. The hitbox comes from the model's boxes; `shape` replaces it with your own boxes in sixteenths
   of a block (0 to 16).

```python
from craftpy import *

Block("pedestal", name="Pedestal", model="pedestal")

# the same model with a simpler hitbox: a base and a column
Block("pedestal_simple", name="Simple Pedestal", model="pedestal",
      shape=[(2, 0, 2, 14, 3, 14), (5, 3, 5, 11, 12, 11)])
```

How it works:

- Without `model` the block is a cube with its `texture`. A rotation set on a box in Blockbench is not applied to the
  hitbox: write `shape` then.
- A model that does not fill the cube can show the sky or a cave through the sides of blocks next to it, because the game
  hides those faces as if it were a full block. Keep the footprint close to the whole cube where that matters.

## A window that belongs to a player or a mob

You get a personal backpack with nine slots, opened with a command, that keeps its items in the player's own data.

```python
from craftpy import *


@command("backpack", level=0)
def open_backpack(ctx):
    """/<project>:backpack - your own nine slots"""
    ui.open_screen(ctx.player, title=ctx.player.name + "'s backpack", inventory=Inventory(storage=9))


@on.entity_interact
def pockets(ctx):
    if ctx.sneaking and ctx.entity.native_type == "minecraft:villager":
        ui.open_screen(ctx.player, title="Pockets", entity=ctx.entity, inventory=Inventory(pockets=6))
        ctx.cancel()                     # no trading window this time
```

How it works:

- Without `layout=` the standard window of the inventory is shown; with `layout="name"` your own Minecraft-style window.
- The items are saved in the entity's data under your project's name, so they stay with it and no other project reaches them.

## An item you can set down: a backpack

You get a backpack with its own 3D model: right click opens 27 slots and an energy store kept in the item; shift + right
click on a block sets it down, a right click there opens the very same contents, and hitting it picks it up again.

```python
from craftpy import *

bag = Item("satchel", name="Satchel", model="satchel", stack=1).extends(Placeable(width=0.6, height=0.6))
BAG = dict(inventory=Inventory(storage=27, energy_out=1), energy=10_000)


@on.item_use(item=bag)
def open_held(ctx):
    ui.open_screen(ctx.player, hand="off" if ctx.hand == "OFF_HAND" else "main", **BAG)


@on.entity_interact(entity=bag)
def open_placed(ctx):
    if not ctx.sneaking:            # shift + right click with an empty hand picks it up
        ui.open_screen(ctx.player, entity=ctx.entity, **BAG)
        ctx.cancel()
```

How it works:

- `model="satchel"` is `assets/models/item/satchel.json`, a Blockbench **Java Block/Item** model: its `display` part
  places it in the hand and the inventory; set down, it stands on the floor at full size.
- A set-down item *is* its stack: the slots, energy and liquids of its window live in the stack's data, so they are the
  same in the hand and on the floor, and they leave with it when it is picked up.
- Without a layout the standard window is shown; draw your own with **Tools > New UI window > Minecraft-style window** and
  name it as the second argument of `ui.open_screen`.

## A bow, and something to throw

You get a bow that is drawn with right click and shoots two arrows, its picture changing while it is drawn, and a spear that
is thrown and picked up again.

```python
from craftpy import *

fangbow = Item("fangbow", name="Fang Bow", texture="fangbow.png").extends(
    Bow(draw_seconds=0.9, power=3.0, damage=5.5, arrows=2, spread=1.5,
        draw_sound="mymod:bow_draw", full_sound="mymod:bow_ready", shoot_sound="mymod:bow_shoot"),
    Durability(uses=500))
shaped(fangbow, " BS", "B S", " BS", B="bone", S="string")

javelin = Item("javelin", name="Javelin", model="javelin").extends(
    Weapon(damage=5, attack_speed=1.2),
    Throwable(speed=2.6, damage=9, charge_seconds=0.5, pierce=1, hit_sound="mymod:thunk"))
shaped(javelin, " I", " S", "S ", I="iron_ingot", S="stick")
```

How it works:

- `Bow` makes the item drawn while the button is held and shoots when it is let go; the arrows come from the inventory (`ammo=`
  takes an item or a `#tag`) unless `infinite=True`. Make three more pictures next to the texture, `fangbow_pulling_0.png`,
  `fangbow_pulling_1.png` and `fangbow_pulling_2.png`: they are shown while it is drawn, like the vanilla bow's.
- `Throwable` makes the item fly as itself when it is let go, hurt like an arrow, go through `pierce` mobs and stick in the
  ground; walking over it picks the very same stack up again.
- The sounds are the ones of your `assets/sounds.json`. A client-side module can draw a charge bar: `client.player()` has
  `using`, `use_ticks` and `use_full`, and `hud.bar(..., vertical=True)` fills from the bottom.

## A GeckoLib item with animations

You get a sword that is a real 3D model with a bone that sways, a glowing part and a texture that moves, shown in the hand, the
inventory and on the ground; without GeckoLib the flat icon is shown instead.

```python
from craftpy import *

lantern_blade = Item("lantern_blade", name="Lantern Blade", texture="lantern_blade.png", geo="lantern_blade",
                     display={"thirdperson_righthand": {"rotation": [90, -45, -35], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]}}).extends(
    Weapon(damage=8, attack_speed=1.6), Durability(uses=800))


@extension
class Flare(Extension):
    def on_hit(self, event):
        event.entity.effect("glowing", 5)
```

How it works:

- `geo="lantern_blade"` reads `assets/models/lantern_blade.geo.json` (a Blockbench "GeckoLib" model, authored upright),
  `assets/animations/lantern_blade.animation.json` and `assets/textures/lantern_blade_geo.png`.
- The clip whose name ends in `idle` loops while the item is shown; `use` and `attack` play on a right click and a hit.
- A texture with a `.png.mcmeta` next to it animates (eight frames stacked downwards); a file named `<texture>_glowmask.png`
  shines in the dark where it is not transparent.
- `display` says how the model sits in a hand; an upright model needs the tilt of a tool sprite folded into it.

## Commands players type

You get a command every player may use, one for operators with an optional argument, and a check that the player typed it
(not the server console).

```python
from craftpy import *

ruby = Item("ruby", texture="minecraft:emerald")


@command("rubies", level=0)                       # every player
def rubies(ctx):
    """/<project>:rubies - how many rubies you carry"""
    if ctx.player is None:
        print("Only players have rubies")
        return
    ctx.player.say("You carry", ctx.player.count(ruby), "rubies")


@command("gift", level=2)                         # operators only, like /give
def gift(ctx, target: Player, amount: int = 1):
    """/<project>:gift <player> [amount]"""
    target.give(ruby, max(1, min(amount, 64)))
    target.actionbar("A gift of %d rubies" % amount)
```

How it works:

- `level`: 0 every player, 2 operators, 3 server admins, 4 the server owner. It is required, so a command is never open to
  everyone by accident.
- Arguments come from the annotations (`int`, `float`, `bool`, `str`, `Player`); a default makes one optional. The game
  completes them while typing.
- A command runs only in the project that declared it, and disappears when the project stops.
- Check what a player may give: here at most 64. Never trust a number typed by a player.

## A window with buttons

You get a small shop window: a button per offer, a label that changes, and the player's choice read in Python.

```python
from craftpy import *

OFFERS = {"bread": ("bread", 4, 1), "torches": ("torch", 16, 2)}     # name: (item, count, emeralds)


@window("shop", title="Shop", size=(260, 170))
class Shop(Window):
    message = "Pick an offer"

    def build(self, ctx):
        return VBox(
            Label(self.message, style="title"),
            *[Button("%d %s for %d emeralds" % (count, item, price), on_click=lambda c, n=name: self.buy(c, n))
              for name, (item, count, price) in OFFERS.items()],
            Button("Close", on_click=ctx.close, style="text"))

    def buy(self, ctx, name):
        item, count, price = OFFERS[name]
        if ctx.player.has("emerald", price):
            ctx.player.take("emerald", price)
            ctx.player.give(item, count)
            self.message = "Thank you!"
        else:
            self.message = "You need %d emeralds" % price


@command("shop", level=0)
def open_shop(ctx):
    ui.open(ctx.player, "shop")
```

How it works:

- `build` returns the widgets; after a callback the window is built again, so changing `self.message` updates the label.
- The same window can be drawn with the mouse: **Tools > New UI window > Modern UI window**; its buttons call
  `@action("name")` functions.

## Something on your own screen: client side

You get coordinates and a health bar on your screen, and a key that hides them. This runs on the player's computer, in a
project whose `project.json` has `"side": "client"`.

```python
from craftpy import *

shown = True


@on.client_tick
def draw(event):
    if not shown:
        return
    me = client.player()
    hud.text("coords", "XYZ %d %d %d" % (me.x, me.y, me.z), anchor="bottom_left", x=4, y=-4, color="aqua")
    hud.bar("health", me.health, max=me.max_health, anchor="top_left", x=4, y=4, label="HP")


@keys.bind("f7")
def toggle(ctx):
    global shown
    shown = not shown
    if not shown:
        hud.clear()
```

How it works:

- Client-side code can draw, listen to keys and open windows, but never change the world or talk to the server.
- `storage` on the client keeps values on that computer, not in the world.
