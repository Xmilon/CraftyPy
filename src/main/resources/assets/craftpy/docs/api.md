# CraftPy Python API

> **In one line:** write Python in your project, press **Run**, and it is in the game.

Every section below has the same shape, so you can skim:

- **What it is**: one sentence
- **Smallest example**: copy, paste, change
- **Good to know**: at most a few bullets
- The full list of parameters is in the **Reference** at the end of this page

---

## Start here

1. Open the IDE: right click a **CraftPy Computer**, or press `I`.
2. Make a project (**New Project** on Home) or open `welcome` and read its files.
3. Edit `code/main.py`, press **Run** (`Ctrl+R`), then get your things: `/craftpy give @s <project>:<id>`.

```python
from craftpy import *

ruby = Item("ruby", texture="minecraft:emerald", glow=True)
ruby_ore = Block("ruby_ore", texture="minecraft:emerald_ore", hardness=3, tool="pickaxe", drops=ruby)

shaped(ruby_ore, "RR", "RR", R=ruby)


@on.block_break(block=ruby_ore)
def found(event):
    event.player.say("You found a ruby!")
```

Shortcut: the **Building blocks** window (`Alt+8`, right edge) inserts ready-made windows, items, blocks, events and
commands for you.

---

## How it works (the 2-minute version)

```
your .py files ──Run──> Python worker ──declarations──> CraftPy ──> game
                        (sandboxed)      (items, blocks,  (fills slots,   (reloads packs,
                                          recipes, events)  writes packs)   calls your events)
```

1. **Your project is a folder.** `code/` holds the Python, `assets/` the textures and sounds, `ui/` the windows you draw.
2. **Run starts your code** in a separate, sandboxed Python process (no files, network or OS access).
   `main.py` runs first; every file it imports runs too.
3. **Declaring is not doing.** `Item(...)`, `Block(...)`, `shaped(...)` only *describe* content. CraftPy turns the
   descriptions into game data: blocks and items fill slots that were registered when the game started, and recipes,
   tags, loot and textures go into a generated resource and data pack.
4. **The game reloads** those packs and your content appears. No restart.
5. **While you play**, game events (a block broken, a player joining...) are sent to your `@on` handlers. A handler gets
   about 50 ms; after that the game carries on without waiting.
6. **Saving reloads.** If the new version fails, the previous one keeps running and the error shows with file and line.

---

## Find what you need

- split my code into several files: [Files and imports](#files-and-imports)
- add an item, food, a sword, armor: [Items](#items), [Extensions](#extensions)
- add a block, an ore, a lamp: [Blocks](#blocks)
- use my own pictures: [Textures](#textures)
- put things in tags (`minecraft:swords`...): [Tags](#tags)
- give items and blocks behaviour: [Extensions](#extensions)
- add crafting or furnace recipes: [Recipes](#recipes)
- choose what a block drops: [Loot and drops](#loot-and-drops)
- react when something happens: [Events](#events)
- add a `/command`: [Commands](#commands)
- do something every few seconds: [Timers](#timers)
- talk to players, give items, teleport: [Players](#players)
- change blocks, spawn mobs, explode: [World](#world)
- remember numbers between events: [Storage](#storage)
- open a window with buttons: [Windows](#windows)
- draw on the screen, add hotkeys, make a client-side mod: [Client-side code](#client-side-code)
- use a web API, save files: [Web and files](#web-and-files)
- print, debug, try code live: [Chat, console and debugging](#chat-console-and-debugging)
- check my code with tests: [Tests](#tests)
- see what changed between versions: [API versions](#api-versions)
- use another mod's items or recipes: [Other mods](#other-mods)
- reach Java directly: [Java bridge](#java-bridge)

---

## Files and imports

**What it is:** a project can be one file or many. `code/main.py` is the start; whatever it imports runs too.

```python
# code/main.py
from craftpy import *
import items          # runs code/items.py
import blocks         # runs code/blocks.py (it may import items itself)


def main(ctx):        # optional: runs last, on every Run
    ctx.log("loaded")
```

- `from items import ruby` works between your files, like normal Python. Folders with `__init__.py` are packages.
- Import order matters: a file that uses `ruby` must come after the file that makes it.
- **New file** in the Building blocks window writes the `import` line in `main.py` for you.

## Ids and names

**What it is:** every item and block has an id. Yours live in your project's namespace.

- In project `ruby_rush`, `Item("ruby")` is `ruby_rush:ruby`.
- Ids are lowercase letters, digits and `_`: `ruby_ore`, not `Ruby Ore`.
- Wherever an item or block is expected you may write: the variable (`ruby`), a bare name (`"stick"` is
  `minecraft:stick`; `"ruby"` is yours if you declared it), a full id (`"create:wrench"`), or a tag in recipes
  (`"#minecraft:logs"`).
- Typos are caught: **Validate** (`Ctrl+Enter`) warns about ids no installed mod has.

## Items

**What it is:** something you hold. Looks first; behaviour comes from [Extensions](#extensions).

```python
ruby = Item("ruby", name="Ruby", texture="ruby.png", glow=True)
apple = Item("magic_apple", food=Food(hunger=6, saturation=1.2, always_eat=True))
coal_gem = Item("coal_gem", fuel=40)                           # burns 40 seconds
wand = Item("wand", stack=1, effect=("speed", 2, 10))           # right click: Speed II, 10 s
blade = Item("blade", tags=["minecraft:swords"]).extends(Sword(tier="diamond"))
```

- `stack` is 1-64. `food` takes `Food(...)` or just hunger points (`food=4`).
- `effect` takes `"speed"` (5 s, level I), `("speed", level, seconds)`, or another mod's effect id.
- `tags=[...]` puts it in item tags; see [Tags](#tags).

## Blocks

**What it is:** something you place. Hardness, tool, light, sound and drops are set right here.

```python
ruby_ore = Block("ruby_ore",
                 texture="ruby_ore.png",
                 hardness=3,            # stone is 1.5
                 tool="pickaxe",        # pickaxe, axe, shovel, hoe
                 needs_tool=True,       # no drop without the right tool
                 light=0,               # 0-15
                 sound="stone",         # stone, wood, metal, glass, grass, gravel, sand, wool, anvil, snow
                 drops=(ruby, 2))
```

- Holding a block places it against yours, like against any block. An empty hand or another item triggers
  `block_interact` (see [Events](#events)).
- Behaviour such as redstone, burning or minimum tool tier: [Extensions](#extensions).

## Textures

**What it is:** the picture of an item or block.

- Your own: put a 16x16 PNG in `assets/textures/` and write `texture="ruby.png"`.
- Vanilla: `texture="minecraft:diamond_ore"` (the `block/` or `item/` folder is found for you).
- Any texture id: `texture="minecraft:block/dirt"`, `texture="create:item/wrench"`.
- The **Assets** window (`Alt+9`) shows every texture; drag one into your code to insert its id. Edit it with the
  texture editor; your copy is saved in your project.

## Tags

**What it is:** named groups of items or blocks. Recipes, tools and other mods read them (`#minecraft:swords`).

```python
blade = Item("blade", tags=["minecraft:swords", "weapons"])        # "weapons" = <project>:weapons
gem_ore = Block("gem_ore", tags=["minecraft:mineable/pickaxe"])

tag("gems", [ruby, "diamond", "emerald"])                          # make your own tag
tag("minecraft:logs", [magic_log], type="block")                   # add to a vanilla tag
```

- `type` is `"item"` (default) or `"block"`; other registries work too (`"entity_type"`, `"fluid"`...).
- Several declarations of the same tag are merged; nothing is replaced unless you pass `replace=True`.
- Use a tag in recipes with `#`: `shapeless(stick, "#weapons")`.

## Extensions

**What it is:** behaviour you snap onto an item or block: being a sword, burning, giving redstone power, your own code.

```python
ruby_sword = Item("ruby_sword", texture="ruby_sword.png", stack=1).extends(
    Sword(tier="diamond", damage=8),          # mines cobwebs fast, hits for 8
    Durability(1200, repair_with=ruby),       # wears down; rubies repair it
    Cooldown(2),                              # right click every 2 s at most
    Tooltip("Forged in the deep", color="gold"))

ruby_ore.extends(MineableBy("pickaxe", tier="iron"), Flammable(chance=5, spread=20))
```

- `Sword`, `Pickaxe`, `Axe`, `Shovel`, `Hoe`, `Tool` (goes on: item; what it adds: mining speed, damage, attack speed, durability and enchantability from the tier)
- `Durability` (goes on: item; what it adds: a durability bar and an anvil repair item)
- `Weapon` (goes on: item; what it adds: damage, attack speed and knockback for any item)
- `Armor` (goes on: item; what it adds: armor points in a slot (right click equips it))
- `Attributes` (goes on: item; what it adds: any attribute of the game or a mod while held or worn)
- `Fuel`, `Enchantable`, `Cooldown`, `Tooltip` (goes on: item; what it adds: furnace fuel, enchanting, use cooldown, tooltip lines)
- `MineableBy`, `Drops` (goes on: block; what it adds: which tool and tier mine it, what it drops)
- `Redstone`, `Flammable`, `Friction`, `Piston` (goes on: block; what it adds: signal strength, fire, slipperiness, piston reaction)

- `.extends(...)` returns the same item or block, so it chains. `extensions=[...]` in `Item(...)`/`Block(...)` is the same.
- In the editor, `extends(` lists the extensions that fit (yours and the modules' too), and `tier="`, `slot="`,
  `operation="` list the accepted values.
- Swords and weapons do not break blocks in creative, like vanilla ones.

### Your own extensions

```python
@extension
class LifeSteal(Extension):
    applies_to = ("item",)                    # optional: only items

    def __init__(self, hearts=1):             # settings
        self.hearts = hearts

    def on_hit(self, event):                  # a hook: runs for every item it is on
        event.player.heal(self.hearts * 2)


fang = Item("fang").extends(LifeSteal(hearts=2))
```

- `on_use` (on: item; when: right click with it; `event` has: `player`, `item`, `hand`)
- `on_hit` (on: item; when: a hit while holding it; `event` has: `player`, `entity`, `item`, `damage` (you may change it))
- `on_mine` (on: item; when: a block broken with it; `event` has: `player`, `item`, `block`, `pos`)
- `on_hold_tick` (on: item; when: every second while held; `event` has: `player`, `item`)
- `on_interact` (on: block; when: right click on it; `event` has: `player`, `block`, `item`, `pos`)
- `on_break` / `on_place` (on: block; when: broken / placed; `event` has: `player`, `block`, `pos`)
- `on_tick` (on: block; when: every tick; `event` has: `block`, `pos`)

- `event.cancel()` stops the action in `on_use`, `on_hit`, `on_mine` and `on_break`.
- `configure(self, target)` may change the item or block itself: `target.set("glow", True)`.
- An extension made in a module can be used by name in every project.

### Class form

```python
class RubyBlade(ItemType):
    id = "ruby_blade"
    texture = "ruby_blade.png"
    extensions = [Sword("iron")]

    def on_mine(self, event):                 # hooks work as methods
        event.player.actionbar("Chip!")
```

`BlockType` works the same for blocks. The class can be used wherever an id is expected.

## Recipes

**What it is:** how to make things at a crafting table, furnace or stonecutter.

```python
shaped(sword, " R ", " R ", " S ", R=ruby, S="stick")    # rows of the grid, one letter per ingredient
shapeless(ruby, ruby_block, count=9)                       # any arrangement
smelt(ruby_ore, ruby, xp=0.7, seconds=10)                  # using="blast_furnace", "smoker", "campfire"
stonecut("stone", "stone_bricks")
recipe("create:mixing", ingredients=[...], results=[...])  # another mod's recipe type
```

- A space in a row is an empty slot. Ingredients may be tags: `"#minecraft:planks"`.
- Recipes of other mods are only loaded while that mod is installed. Validate warns about fields the type never uses.

## Loot and drops

**What it is:** what a block drops, or a loot table you name.

```python
Block("geode", drops=[(ruby, 1, 3), ("amethyst_shard", 2, 1)])   # (item, count, weight): one is picked
Block("glass_box", drops=[])                                       # drops nothing
loot_table("treasure", [loot_entry("diamond", weight=1), loot_entry("bread", count=5, weight=8)])
```

- No `drops`: the block drops itself. `needs_tool=True` drops nothing without the right tool.

## Events

**What it is:** your function runs when something happens in the game.

```python
@on.player_join
def hello(event):
    event.player.say("Welcome,", event.player.name)


@on.block_break(block=ruby_ore)          # filters: block=, item=, entity=
def mined(event):
    event.player.give(ruby)
```

- `player_join`, `player_leave`, `player_respawn`: `player`
- `chat`: `player`, `message`
- `block_break`, `block_place`: `player`, `block`, `pos`
- `block_interact` (right click a block): `player`, `block`, `item`, `hand`, `pos`
- `block_tick`: `block`, `pos`
- `item_use`, `item_hit`, `item_mine`, `item_hold_tick`, `item_craft`, `item_pickup`: `player`, `item` (+ `entity`, `damage` / `block`, `pos`)
- `entity_death`, `player_death`: `entity`, `cause`, `killer` (a player) or `attacker`
- `entity_hurt`: `entity`, `damage`, `cause`, `attacker`, `attacker_player`

- `event.block` and `event.item` have `.id` and `.name`; items also `.count`. Your content has your ids:
  `event.block.id == ruby_ore`.
- `event.cancel()` stops `block_break`, `item_use`, `item_hit` and `item_mine`. Other events are notifications.
- In the editor, `event.` completes with exactly these fields.

## Commands

**What it is:** a `/command` for your project, with tab completion.

```python
@command("gift")
def gift(ctx, target: Player, amount: int):
    """/<project>:gift <target> <amount> - give rubies."""
    target.give(ruby, amount)
```

- Argument types come from the annotations: `int`, `float`, `bool`, `str`, `Player`.
- `ctx.player` is who ran it. Commands need operator permission (level 2).

## Timers

**What it is:** do something later, or again and again.

```python
@every(seconds=30)
def reminder():
    for p in players.online():
        p.actionbar("Drink water!")

after(3, lambda: say("three seconds later"))
task = run_every(20, lambda tick: world.particle("flame", 0, 65, 0))   # 20 ticks = 1 second
task.cancel()
```

## Players

**What it is:** the player objects you get from events, `player("Steve")` and `players.online()`.

```python
p = event.player
p.say("hi"); p.actionbar("above the hotbar"); p.title("Big", subtitle="small")
p.give(ruby, 5); p.take("dirt", 10); p.has(ruby, 3); p.count(ruby)
p.heal(); p.feed(20); p.effect("speed", seconds=30, level=2); p.clear_effects()
p.teleport(0, 100, 0); p.set_gamemode("creative"); p.add_xp(levels=5)
p.play_sound("minecraft:entity.player.levelup"); p.set_fire(3)
```

- Data (a snapshot): `name`, `uuid`, `pos`/`x`/`y`/`z`, `dimension`, `health`, `max_health`, `food`, `level`,
  `gamemode`, `held_item`. `p.refresh()` gives a fresh snapshot.

## World

**What it is:** read and change blocks, spawn things, make effects.

```python
world[0, 64, 0]                                   # "minecraft:stone"
world[0, 64, 0] = "gold_block"                    # your blocks work too
world.fill((0, 64, 0), (4, 68, 4), "glass")
world.explode(event.pos, power=3)
world.lightning(event.pos)
zombie = world.spawn("zombie", event.pos.up(1))
world.particle("minecraft:flame", event.pos, count=10)
world.set_time("night"); world.weather("rain")
```

- Positions are `x, y, z` or a `Pos` (`event.pos`, `player.pos`, `Pos(0, 64, 0)`, `pos.up(2)`).
- Every call takes `dimension="minecraft:the_nether"` for another dimension.

## Entities

**What it is:** mobs and other entities from `world.spawn`, `world.entities` and events.

- Data: `type`, `uuid`, `name`, `pos`, `health`, `max_health`.
- Actions: `kill()`, `teleport(x, y, z)`, `set_name("Bob")`, `refresh()`.

## Storage

**What it is:** remember values between events.

```python
storage["ores_mined"] = storage.get("ores_mined", 0) + 1
storage.set("home:" + p.name, [p.x, p.y, p.z])
```

- Shared by all your handlers and saved with the world: values are still there after a restart.
- Values must be JSON: numbers, text, `True`/`False`, `None`, lists and dicts.

## Client-side code

**What it is:** Python that runs on the player's own computer, in a second sandboxed worker. It can draw on the screen,
listen to keys, react to client events and open windows. It can **not** change the world, give items, run commands or
talk to the server: it only sees what the game already shows the player. Think of a client-side mod.

```python
from craftpy import *

@on.client_tick
def refresh(event):
    me = client.player()
    hud.text("coords", "XYZ %d %d %d" % (me.x, me.y, me.z), anchor="bottom_left", x=4, y=-4, color="aqua")
    hud.bar("health", me.health, max=me.max_health, anchor="top_left", x=4, y=4, label="HP")

@keys.bind("f7")
def hide(ctx):
    hud.clear()
    client.chat("HUD hidden")
```

- **Where it lives:** a project whose `project.json` has `"side": "client"` (Run starts it on this computer), or a module built
  as **Client only** (installed modules of that kind start when you join a world). Both are in the Modules page.
- **On the screen:** `hud.text`, `hud.bar`, `hud.item`; the same id replaces the element, `hud.remove` / `hud.clear` take it off.
- **Keys:** `@keys.bind("ctrl+k")`, one key plus optional ctrl / shift / alt. Works while no screen is open.
- **Events:** `client_tick` (every game tick), `chat_received`, `screen_open`, `client_join`, `client_leave`.
- **Read:** `client.player()`, `client.looking_at()`, `client.fps()`. **Show to you only:** `client.chat`, `client.sound`, `client.toast`.
- **Windows:** `@window` classes work as usual; open them with `client.open_window("id")`.
- `storage` keeps values on this computer (not in the world), `after` / `every` timers work.
- In server-side code the names `hud`, `keys` and `client` exist but say that they only work in client-side code.
- Switch it off with `enableClientPython` in the config.

## Web and files

**What it is:** web requests and text files, done by the game for your code, never by Python itself.

```python
# project.json:  "permissions": {"net": ["open-meteo.com"], "files": true}
def show(response):
    temp = response.json()["current"]["temperature_2m"]
    say("It is %s degrees in Berlin" % temp)
    files.write_json("last_weather.json", {"temp": temp})

http.get("https://api.open-meteo.com/v1/forecast?latitude=52.5&longitude=13.4&current=temperature_2m", show)
```

- **Declare** what you need in `project.json` (or the module's `module.json`): `"permissions": {"net": ["site.com"], "files": true}`.
  An entry covers its subdomains (`wikipedia.org` covers `en.wikipedia.org`).
- **Your own projects** may use what they declare. **Modules** (other people's code) also need **Approve access** on the Modules
  page; every module that declares sites shows a red warning there, in its README and in the console:
  *"This module wants to access ... continue at your own risk!"* An approval holds for that version of the module file
  only: a changed `.cpm` asks again.
- Each project and module runs in **its own Python process** and the game knows which one is calling, so code cannot use
  another module's sites, data folder or saved values, nor read its files.
- Two more permissions: `"commands": true` for `server.run_command` (runs at level 2, like a command block) and
  `"java": true` for the [Java bridge](java.md) (full trust: it can do anything the game can).
- **Any site may be declared**, with the module's `"permissions": {"net": [...]}`; say in its README what it requests
  (for example *"requests wikipedia.org for summaries"*). `config/craftpy-access.toml` keeps a **trusted list**
  (`net.allowedDomains`: reference, weather, Minecraft and API sites): a site on it is shown plainly. A site that is not
  on it is **flagged** on the Modules page, in the README warning and when the module is switched on, and one in
  `net.dangerDomains` (GitHub, pastebins, Discord webhooks, anyone-can-upload sites) is shown as DANGER ZONE. Nothing is
  blocked because of the lists; the player's approval of that exact module file is what allows a request.
- `https://` only, named sites only (no IP numbers, no addresses of your computer or home network), at most 30 requests a
  minute, 2 MB answers, 15 seconds. Redirects are checked like the first request.
- **API keys** go into `net.httpKeys` (`openai=api.openai.com=Authorization: Bearer sk-...`); code writes `key="openai"` and
  never sees the key.
- **Files**: `files.read / write / delete / exists / list / read_json / write_json` work in `pyforge/data/<project>/` only
  (20 MB, no `..`, no links). Folders the server owner lists in `files.sharedFolders` can be read with `folder="name"`
  when the project declares that name under `"shared"` (and, for a module, it is approved).

## Windows

**What it is:** a window with labels, buttons, fields and sliders that opens for a player.

```python
@window("counter", title="Counter", size=(260, 160))
class Counter(Window):
    count = 0                                   # kept per player while the window is open

    def build(self, ctx):
        return VBox(
            Label(f"Clicked {self.count} times", style="title"),
            Button("+1", on_click=self.more),
            Button("Close", on_click=ctx.close, style="text"))

    def more(self, ctx):                        # after a callback the window is redrawn
        self.count += 1


@command("counter")
def open_counter(ctx):
    ui.open(ctx.player, "counter")
```

- Widgets: `Label`, `Button`, `TextInput`, `TextArea`, `Checkbox`, `Slider`, `Dropdown`, `ListView`, `ProgressBar`,
  `Image`, `ItemIcon`; layouts `VBox`, `HBox`, `Grid`, `TabView`, `ScrollView`.
- Give inputs an `id`; callbacks read them all in `ctx.values`.
- Or draw a window with the mouse: **Tools > New UI window**, then **Tools > UI designer** (`ui/<name>.json`);
  its buttons call `@action("name")` functions.

## Chat, console and debugging

**What it is:** talk to everyone, run commands, see what your code does.

```python
say("Hello everyone")         # chat to everyone
run("time set day")           # any Minecraft command, as the server
print("debug")                # shows in the IDE console
```

- The **Console** (`Alt+4`) runs Python in your project: try `player("Dev").give("diamond", 3)`. A line that starts
  with `/` is a Minecraft command run as you: `/give @s diamond 3`, another mod's command, or your project's own
  (`/welcome:gift Dev 3`); its answer shows in the console.
  `Alt+Shift+E` runs the selected lines there.
- Errors show the file and line; `Ctrl+click` the line in the console to jump there.
- **Validate** (`Ctrl+Enter`) checks a file without running it; the event tracer and handler profiler are in **Tools**.
- **Breakpoints** (`Ctrl+F8`, a red dot): when that line runs - at Run, in an event, a timer or a window - the **Debug**
  window (`Alt+5`) shows the variables and the call stack of that moment. The game does not pause (a paused Python would
  freeze the server); each line records its first 20 hits until you press Clear.
- The checks while you type (Problems, `Alt+6`): typos in names, imports nobody uses, imports the sandbox blocks (`os`,
  `socket`...), event names that do not exist (`@on.block_brake`), variables never used, `== None`, list/dict defaults,
  bare `except:`, code after `return`, a dict key written twice, `is "text"`, f-strings without `{}`.

## Tests

**What it is:** small functions that check your code still does what you think.

```python
# code/test_rewards.py
from rewards import reward_for

def test_diamonds_give_ten():
    assert reward_for("diamond") == 10

def test_unknown_gives_nothing():
    assert reward_for("dirt") == 0
```

- Files are `code/test_<something>.py`; functions whose name starts with `test` are the tests.
- **Run tests**: `Ctrl+Shift+F10` (or the project menu on the toolbar > **Run tests**, or Find action "Run tests of this file"). Failures come
  first with the line of the `assert`; a click opens it.
- Each test runs on its own: one failing test does not stop the others.
- Tests import your other files (`from rewards import ...`), not `main.py`: put the logic you test in its own file.
- What a test declares or schedules is thrown away after the run; the loaded project is not touched. Calls to the
  world or players do reach the running game.

## Other mods

**What it is:** use the content of every installed mod as if it were vanilla.

```python
shapeless("create:wrench", "iron_ingot", "#c:ingots/copper")
Item("staff").extends(Attributes("irons_spellbooks:spell_power", 0.2, operation="multiply_base"))
recipe("create:mixing", ingredients=[{"item": "minecraft:sugar"}], results=[{"id": "candy"}])
raw_json("tags/item/magic.json", {"values": ["diamond"]}, namespace="irons_spellbooks")
```

- Completion inside strings lists the ids of every installed mod (items, blocks, effects, attributes, sounds...);
  hover an id to see which mod it comes from.
- The list comes from a catalog the game writes when it starts, so start the game once after adding a mod.

## Java bridge

**What it is:** call Java classes of the game and mods directly. See the **Java bridge** document in Docs.

## Sounds, particles, translations, data

```python
sound("ruby.chime", sounds=["ruby_rush:chime"])                 # a sound event from assets/sounds/
lang("item.ruby_rush.ruby.lore", "Shiny")                       # extra translation keys
advancement("first_ruby", criteria={...})
raw_json("worldgen/configured_feature/ruby.json", {...})          # any data file of your own
```

## Limits

- Blocks and items are slots registered when the game starts (a pool per kind), so content appears without a restart.
- Mobs and effects are slots too: 32 passive mobs, 32 hostile mobs and 32 effects at a time. Natural spawns of a new
  or changed mob start the next time the world is opened.
- Python runs in a sandbox: no files, network or OS access. Handlers get about 50 ms.
- Client-side code cannot change the world; keys cannot be rebound in the controls screen (the code chooses them).
- The sandbox also refuses the insides of the interpreter: attributes such as `__globals__`, `__subclasses__`,
  `__code__`, `f_back`, `gi_frame` and modules such as `inspect` and `gc`. The editor marks them as errors while you
  type. Everything the API offers (classes, `__init__`, `__repr__`, `super()`, properties, `getattr` on your own names)
  works as usual. The worker may use 1 GB (`pythonMemoryMb` in the config) and cannot write files or open connections.

## Older syntax

Projects written for the first API keep working. After a run the console lists the old names a project used and what
replaces them (`[api] recipe_smelting() is the old API ...`); see [API versions](#api-versions).

```python
@block("ruby_ore", name="Ruby Ore", texture="minecraft:deepslate_gold_ore", hardness=4.0)
class RubyOre:
    pass

recipe_shaped("ruby_sword", [" R ", " R ", " S "], {"R": {"item": "ruby_rush:ruby"}, "S": {"item": "minecraft:stick"}})
recipe_smelting("ruby_rush:ruby", {"item": "ruby_rush:ruby_ore"}, xp=0.5)
loot_table("ruby_ore", [loot_entry("ruby_rush:ruby", count=2), loot_self()])
```

Also available: `item`, `recipe_shapeless`, `recipe_blasting`, `recipe_smoking`, `recipe_campfire`,
`recipe_stonecutting`, `recipe_smithing`, `tex()`, `snd()`, `mdl()`, `run_later`, `run_next_tick`, `scheduler`, `cp`.

---

<!-- reference:start (generated by gen_api_doc.py, do not edit) -->

## API versions

This page describes Python API **2.11** (`API_VERSION` in your code). `apiVersion` in `project.json` says which version a project was written for.

- A **minor** version (2.0 to 2.1) only adds things: everything written for 2.0 keeps working.
- A **major** version (1 to 2) may rename things. The old names keep working; after a run the console says `[api] ... is the old API` and what to write instead.
- A project written for a newer API than your CraftPy gets a note to update CraftPy.

### 2.11 (2026-10-23)

- **Added:** `Fluid(id, ...)`: custom liquids that are real NeoForge fluids (flowing, swimmable, buckets, tanks and pipes of any mod). `density`, `viscosity`, `temperature`, `light`, `color` or own `still` / `flowing` textures, `effects` while an entity is in it, `bucket_texture`; `fluid.bucket` is the bucket item. Eight liquid slots by default (`fluids=` in craftpy-slots.properties).
- **Added:** `Block(model=, shape=)`: your own mesh from assets/models/block/ and a hitbox of boxes in sixteenths of a block; without `shape` the hitbox follows the model's boxes when the project runs from its folder.
- **Added:** `Screen(title=)` extension: a Minecraft-style window for machine blocks (two item slots, liquid gauge, energy bar on the right). A bucket in the first slot is emptied into the block's `FluidTank`.
- **Added:** `world.machine_update(x, y, z, energy=, fluid=)`: add or remove energy and remove liquid of a loaded machine block.
- **Added:** Minecraft-style windows: tick `legacy` in the UI maker's window properties, draw panels, item slots, the player inventory, energy bars, liquid gauges, arrows, flames, labels and icons, and name the window with `Screen(layout="my_window")`.

### 2.10 (2026-10-21)

- **Added:** Hub apps: `"apps": [{"id", "name", "description", "window", "icon", "category", "order"}]` and `"icon"` in project.json / module.json put windows of the module into the Hub (Apps tab). Client modules open them on this computer, server modules on the server; only enabled and loaded modules are listed.
- **Added:** `Image` shows files of the module under `assets/media/` (PNG, JPEG, BMP, animated GIF) with `fit`, `crop`, `opacity`, `z`, playback options and an optional control bar.
- **Added:** `Video` (Motion JPEG in an AVI, sound from a separate `audio=` file) and `Audio` (ogg, wav, mp3, flac, aiff, au): `autoplay`, `loop`, `playing`, `speed`, `position`, `volume`, `muted`, `restart`, `controls`.
- **Added:** Media widgets report to `on_end`, `on_loop` and `on_error`, and their position and state arrive in `ctx.values[id]`. `playing`, `position`, `speed`, `volume`, `muted`, `opacity` and `restart` can be bound with `@ui.data`.

### 2.9 (2026-10-19)

- **Added:** `keys.bind(combo, id=..., name=..., category=...)`: stable, remappable module actions on client and server. Assignments survive reloads and restarts. Server callbacks receive the authenticated `ctx.player`.
- **Added:** `@ui.data(window_id)`: viewer-specific display dictionaries for designer JSON `bind` properties. Supports text, numbers, lists, selection, visibility and enabled state; keys never execute expressions.
- **Added:** `NativeMob` and `NativeBlock` reuse original registered mod content. Native variants keep their AI/renderers. Custom mobs support native behavior profiles, inspected permission-gated goals, named GeckoLib clips, owner-scoped entity state and throttled `entity_tick` callbacks.
- **Added:** `Kinetic` adds generator, shaft and two-slot processing blocks using independent native rotation with an optional Create adapter. Separate startup slots retain existing IDs. `world.machine` and throttled `machine_speed`/`machine_state` events expose snapshots. Recipes use `craftpy:kinetic_processing` with ingredient, result, duration and minimum_speed.
- **Added:** `EnergyStorage`, `FluidTank`, `world.resources` and `world.transfer` expose native NeoForge capability storage and automation.

### 2.8 (2026-10-18)

- **Added:** `Canvas(...)`: a window area where each widget sits at `x`, `y` with its own `width` and `height` (the UI maker drags and resizes them). `width=` and `height=` also work on any widget in `VBox`, `HBox` and `Grid`.

### 2.7 (2026-10-17)

- **Added:** `media` (client side): play OGG, WAV, AIFF, MP3 and FLAC files of `pyforge/shared/<folder>` and music sound events; pause, seek, volume, state; `nearby=True` lets players around you hear it. A module reads a shared folder only when it declares `"permissions": {"shared": ["music"]}`.
- **Added:** `Pool(...)` and `Structure(...)`: jigsaw structures from the `.nbt` files of `data/structure/`, generated in new chunks after reopening the world; `world.place_structure(...)` and `world.place_jigsaw(...)`.
- **Added:** A project's `data/` folder goes into the data pack (structures, recipes, tags, worldgen...).

### 2.6 (2026-10-17)

- **Added:** `Mob(...)`: mobs in the game, with a vanilla body or a GeckoLib model, stats, hostile or passive AI, drops, a spawn egg (`/craftpy give <player> <project>:<id> egg`), `/craftpy summon` and natural spawns (`Spawn(...)`). `world.spawn` takes them.
- **Added:** `Effect(...)`: potion effects with name, icon, color, category and attribute modifiers per level; `player.effect(rage)` and `Item(effect=...)` take them; `@on.effect_tick(effect=rage)` runs once a second on whoever has it.
- **Added:** Entities in events and `world.entities` report Python mobs as `<project>:<id>`.

### 2.5 (2026-10-17)

- **Added:** `ctx.player` in a window's `build(self, ctx)`: the player the window is drawn for (before: missing, so `ui.open(event.player, ...)` from an event failed).

### 2.4 (2026-10-16)

- **Added:** `http.get` / `http.post`: web requests through the host, to sites the module declares, the player approves and the server allows; the answer comes to a callback. API keys stay in the server config.
- **Added:** `files`: text files in the project's own folder (pyforge/data/<project>/) and read-only shared folders.
- **Added:** `"permissions"` in project.json / module.json; modules show a forced warning and need approval.

### 2.3 (2026-10-16)

- **Added:** Client-side modules and projects (`side: client`): Python that runs on the player's own computer, in its own sandbox, and never touches the server: `hud` (text, bars, item icons on the screen), `keys.bind(...)`, `client` (the local player, what you look at, local chat, sounds, toasts), client events (`client_tick`, `chat_received`, `screen_open`, `client_join`, `client_leave`) and local windows.
- **Added:** The checks while you type know the client events.

### 2.2 (2026-10-16)

- **Added:** The IDE console runs `/commands` (vanilla, other mods', your project's own) and shows their answer.
- **Added:** `ui.refresh(player, window)` redraws an open window from a timer or an event.
- **Changed:** `run("...")` and `server.run_command(...)` return what the command answered, as text (before: a status object).

### 2.1 (2026-10-16)

- **Added:** Tests: `code/test_*.py` files with `test...` functions, run with `Ctrl+Shift+F10`.
- **Added:** `Item(..., tags=[...])` and `Block(..., tags=[...])`.
- **Added:** `API_VERSION`: the version of the API your code runs on.
- **Changed:** `storage` is saved with the world (before, it was lost when the server stopped).
- **Changed:** `storage.clear()` without a key clears only its `scope` (before, it cleared every scope).

### 2.0

- **Added:** Content as objects: `Block()`, `Item()`, `Food()`.
- **Added:** Short recipes: `shaped()`, `shapeless()`, `smelt()`, `stonecut()`, `recipe()` for any mod's type.
- **Added:** Events with filters (`@on.block_break(block=...)`), `@every`, `after()`, `@command` with typed arguments.
- **Added:** Extensions (`Sword`, `Tool`, `Armor`, `Fuel`, ...) and your own with `@extension`.
- **Added:** `player()`, `world`, `Player` / `Entity` / `Pos` objects, windows, the Java bridge (`java`).
- **Old names (still work):** `@block` / `@item` decorators, `recipe_*()` functions, `tex()` / `snd()` / `mdl()`: they still work; the console says what replaces them.

### 1.0

- **Added:** The first API: `@block` / `@item` decorators, `recipe_*()` functions, `loot_table()`, timers in ticks.

## Reference

Every function, class and method with all its parameters, by topic. Use the search of this page, or hover a name in the editor (`Ctrl+Q`).

### Items and blocks

#### `Item(id, name=None, texture=None, stack=64, glow=False, food=None, fuel=0, effect=None, extensions=(), tags=(), components=None, **extra) -> 'Content'`

Declares an item and returns it; the result can be used anywhere an item id is expected.

- `id`: Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'.
- `name` (default `None`): Display name; default is the id in title case.
- `texture` (default `None`): PNG file name in assets/textures/ ('ruby.png'), a vanilla texture ('minecraft:item/diamond') or tex(...).
- `stack` (default `64`): Largest stack, 1 to 64 (default 64; tools and armor set 1 themselves).
- `glow` (default `False`): True draws the enchantment shimmer.
- `food` (default `None`): A number (hunger points) or Food(...). Makes the item edible.
- `fuel` (default `0`): Furnace burn time in seconds (0 = not a fuel). Same as .extends(Fuel(seconds)).
- `effect` (default `None`): Right click applies an effect: 'speed' or (name, level, seconds), level 1 = normal.
- `extensions` (default `()`): List of extensions (Sword(...), Durability(...), your own); same as .extends(...).
- `tags` (default `()`): Item tags to join: ['minecraft:swords', 'gems'] (a bare name is this project's tag).
- `components` (default `None`): Data components every stack gets, from any mod: {'apotheosis:affixes': {...}}. A listed component is filled in when a stack does not have it yet; later changes (a reforge) are kept.
- `**extra`: Not accepted: any other attribute raises an error that lists the valid ones.

Returns: The item (a string 'project:id' with .extends(...)).

#### `Block(id, name=None, texture=None, hardness=2.0, resistance=None, light=0, tool=None, needs_tool=False, sound=None, drops=None, extensions=(), tags=(), model=None, shape=None, **extra) -> 'Content'`

Declares a block and returns it; usable anywhere a block or item id is expected.

- `id`: Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'.
- `name` (default `None`): Display name; default is the id in title case.
- `texture` (default `None`): PNG file name in assets/textures/, a vanilla texture ('minecraft:block/stone') or tex(...).
- `hardness` (default `2.0`): Mining time factor (stone 1.5, obsidian 50); -1 makes it unbreakable. Default 2.0.
- `resistance` (default `None`): Explosion resistance; default 6.0 (stone).
- `light` (default `0`): Light emitted, 0 to 15.
- `tool` (default `None`): Tool that mines it fastest: 'pickaxe', 'axe', 'shovel', 'hoe' or 'sword'. See MineableBy.
- `needs_tool` (default `False`): True: only drops anything when mined with the right tool (like ores).
- `sound` (default `None`): Sound type: 'stone', 'wood', 'grass', 'glass', 'gravel', 'sand', 'wool', 'metal', 'anvil' or 'snow'.
- `drops` (default `None`): What it drops: an item, a list, (item, count) / (item, count, weight), 'self' or [] for nothing. Default: the block itself.
- `extensions` (default `()`): List of extensions (MineableBy(...), Redstone(...), your own); same as .extends(...).
- `tags` (default `()`): Block tags to join: ['minecraft:mineable/pickaxe'] (a bare name is this project's tag).
- `model` (default `None`): Your own mesh: the name of a file in assets/models/block/ (a Blockbench 'Java Block/Item' export), such as 'pedestal'.
- `shape` (default `None`): Hitbox (outline and collision): one box (x1, y1, z1, x2, y2, z2) or a list of up to 32, in sixteenths of a block (0..16). Default: the boxes of the model, or a full cube without one.
- `**extra`: Not accepted: any other attribute raises an error that lists the valid ones.

Returns: The block.

#### `NativeBlock(id, *, base, name=None) -> 'Content'`

References an original installed block/item, retaining its native machine behavior and renderer. This is an alias, not a new registered block type; it cannot have independent extensions.

- `id`: Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'.
- `base`: Complete registered block ID, such as 'create:shaft'.
- `name` (default `None`): Name of the Python alias; the original block keeps its original display name.

Returns: A native block reference.

#### `Food(hunger=4, saturation=0.6, always_eat=False, fast=False)`

Food values for Item(food=...).

- `hunger` (default `4`): Hunger points restored (each is half a drumstick).
- `saturation` (default `0.6`): Saturation modifier; 0.6 is bread, 1.2 cooked meat.
- `always_eat` (default `False`): True lets players eat it with a full hunger bar.
- `fast` (default `False`): True eats in half the time (like dried kelp).

Returns: A dict to pass as food=.

#### `ItemType()`

Class form of Item(...): attributes id, name, texture, stack, glow, food, fuel, effect and extensions are the keyword arguments; methods named like hooks run directly.

#### `BlockType()`

Class form of Block(...): attributes id, name, texture, hardness, resistance, light, tool, needs_tool, sound, drops and extensions are the keyword arguments.

### Mobs, effects and liquids

#### `Mob(id, name=None, texture=None, model='humanoid', animation=None, health=10, speed=0.25, damage=2, armor=0, hostile=False, width=0.6, height=1.8, scale=1.0, follow_range=16, egg=None, spawns=(), drops=None, behavior=None, goals=(), animations=None, **extra) -> 'Content'`

Declares a mob and returns it: world.spawn(mob, x, y, z), /craftpy summon, a spawn egg (/craftpy give <player> <project>:<id> egg) or natural spawns.

- `id`: Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'.
- `name` (default `None`): Display name; default is the id in title case.
- `texture` (default `None`): PNG in assets/textures/ ('goblin.png') or a vanilla one ('minecraft:entity/pig/pig'); default: the model's vanilla skin.
- `model` (default `'humanoid'`): Body: 'humanoid', 'player', 'pig', 'cow', 'chicken', 'spider', 'creeper', 'villager', 'slime', or a GeckoLib model 'goblin.geo.json' (assets/models/, needs GeckoLib; without it the mob is humanoid).
- `animation` (default `None`): GeckoLib animation file (assets/animations/); default '<model name>.animation.json'. Animations ending in 'walk' and 'idle' play by themselves.
- `health` (default `10`): Maximum health (a heart is 2).
- `speed` (default `0.25`): Movement speed (zombie 0.23, pig 0.25).
- `damage` (default `2`): Damage of a hit (hostile mobs).
- `armor` (default `0`): Armor points, 0 to 30.
- `hostile` (default `False`): True: attacks players, spawns in the dark, gone in peaceful. False: wanders and flees when hurt.
- `width` (default `0.6`): Hit box width in blocks.
- `height` (default `1.8`): Hit box height in blocks.
- `scale` (default `1.0`): Draws the model bigger or smaller (the hit box is width x height).
- `follow_range` (default `16`): How far it notices players, in blocks.
- `egg` (default `None`): Spawn egg colors: '#3d8f5a' or ('#3d8f5a', '#c9e86b') for base and spots.
- `spawns` (default `()`): Natural spawns: [Spawn('#minecraft:is_forest', weight=8)]. They start the next time the world is opened.
- `drops` (default `None`): What it drops: an item, a list, (item, count) / (item, count, weight).
- `behavior` (default `None`): Profile name or dictionary: profile='melee'|'ranged'|'follow'|'guard', optional target='hostile'|'players'|'none', speed=0.1-4, range/radius=1-64, interval=10-200 ticks. Follow uses world.spawn(owner=player) or entity.set_owner(player).
- `goals` (default `()`): At most eight inspected public native Goal constructors: dictionaries with class, args, priority (0-20), optional target=True and parameters (exact Java type names). Requires approved java permission and allowed bridge packages. Incompatible entity classes or complex arguments need an adapter.
- `animations` (default `None`): Map idle/walk/run/attack/cast/hurt/death to GeckoLib clip names. Triggered events synchronize from the server; entity.animate('cast') triggers one explicitly. Death follows Minecraft's normal removal timing.
- `**extra`: Not accepted: any other attribute raises an error that lists the valid ones.

Returns: The mob (a string 'project:id').

#### `NativeMob(id, *, base, name=None, state=None) -> 'Content'`

Defines a variant that spawns the original registered entity type with its own AI and renderer. Existing entities retain their original type when base changes.

- `id`: Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'.
- `base`: Complete installed living entity ID, such as 'irons_spellbooks:necromancer'.
- `name` (default `None`): Optional custom entity name; omit to keep the native name.
- `state` (default `None`): Initial dictionary in this content owner's persistent entity state (at most 16 KiB).

Returns: The native variant reference.

#### `Spawn(biomes='#minecraft:is_overworld', weight=10, min=1, max=3)`

Where a Mob appears by itself (for Mob(spawns=[...])).

- `biomes` (default `'#minecraft:is_overworld'`): A biome ('minecraft:plains'), a biome tag ('#minecraft:is_overworld') or a list of them.
- `weight` (default `10`): How often, next to other mobs (zombie 100, pig 10).
- `min` (default `1`): Smallest group.
- `max` (default `3`): Largest group.

Returns: A dict for spawns=.

#### `Effect(id, name=None, icon=None, color='#8899ff', category='neutral', attributes=(), **extra) -> 'Content'`

Declares a potion effect and returns it: player.effect(rage), Item(effect=rage), @on.effect_tick(effect=rage).

- `id`: Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'.
- `name` (default `None`): Display name; default is the id in title case.
- `icon` (default `None`): 18x18 PNG in assets/textures/ ('rage.png'); default: CraftPy's flask.
- `color` (default `'#8899ff'`): Particle color, '#rrggbb'.
- `category` (default `'neutral'`): 'beneficial', 'harmful' or 'neutral' (how the game shows it).
- `attributes` (default `()`): Stat changes while active: [Attributes('minecraft:generic.attack_damage', 2)]; the amount is per level.
- `**extra`: Not accepted: any other attribute raises an error that lists the valid ones.

Returns: The effect (a string 'project:id').

#### `Fluid(id, name=None, color='#3fa7ff', density=1000, viscosity=1000, temperature=300, light=0, effects=(), still=None, flowing=None, bucket_texture=None, **extra) -> 'Content'`

Declares a liquid (a real NeoForge fluid with a bucket: Fluid(...).bucket). Flows, can be swum in, fills tanks and pipes.

- `id`: Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'.
- `name` (default `None`): Display name; default is the id in title case.
- `color` (default `'#3fa7ff'`): Tint of the water textures, '#rrggbb'.
- `density` (default `1000`): kg/m3: water 1000, lava 3000; negative rises like a gas.
- `viscosity` (default `1000`): Water 1000, lava 6000: higher slows flowing and swimming.
- `temperature` (default `300`): Kelvin: water 300, lava 1300.
- `light` (default `0`): Light given off, 0 to 15.
- `effects` (default `()`): Applied while an entity is in it: [('poison', 5), (rage, 8, 2)] as (effect, seconds, level).
- `still` (default `None`): Own texture of the still liquid (assets/textures/), instead of tinted water.
- `flowing` (default `None`): Own texture of the flowing liquid.
- `bucket_texture` (default `None`): Texture of the bucket item; default: the water bucket.
- `**extra`: Not accepted: any other attribute raises an error that lists the valid ones.

Returns: The fluid; fluid.bucket is the bucket item.

### Structures

#### `Pool(id, pieces, fallback=None, projection='rigid', processors='minecraft:empty')`

A jigsaw template pool: pieces (.nbt files of data/structure/) a jigsaw block can grow into.

- `id`: Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'.
- `pieces`: ['ruins/hall', ('ruins/tower', 3), (None, 1)]: templates with weights; None is an empty piece.
- `fallback` (default `None`): Pool used when no piece fits (default none).
- `projection` (default `'rigid'`): 'rigid' (as built) or 'terrain_matching' (follows the ground, for paths).
- `processors` (default `'minecraft:empty'`): Processor list id applied to the pieces (default none).

Returns: The pool (a string 'project:id').

#### `Structure(id, start, biomes='#minecraft:is_overworld', size=7, max_distance=80, spacing=32, separation=8, step='surface_structures', terrain='beard_thin', height=0, on_surface=True, start_jigsaw=None)`

A jigsaw structure generated in new chunks, like villages (after reopening the world).

- `id`: Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'.
- `start`: The Pool of the first piece.
- `biomes` (default `'#minecraft:is_overworld'`): Biome, biome tag ('#minecraft:is_forest') or a list.
- `size` (default `7`): How many jigsaw steps it grows, 0 to 20.
- `max_distance` (default `80`): How far pieces may reach, 1 to 128 blocks.
- `spacing` (default `32`): About one every this many chunks.
- `separation` (default `8`): At least this many chunks apart (smaller than spacing).
- `step` (default `'surface_structures'`): Generation step, default 'surface_structures'.
- `terrain` (default `'beard_thin'`): 'beard_thin' (ground fits around it), 'beard_box', 'bury', 'encapsulate' or 'none'.
- `height` (default `0`): Start height; with on_surface it is added to the surface.
- `on_surface` (default `True`): True: starts on the surface.
- `start_jigsaw` (default `None`): Name of the jigsaw block of the first piece to start from.

Returns: The structure (a string 'project:id').

#### `world.place_structure(structure, x, y=None, z=None, rotation=0, mirror=False, entities=True, dimension='minecraft:overworld')`

Places a structure template now (a project's data/structure/<name>.nbt after Run, or the game's).

- `structure`: 'ruins/house' (this project) or 'mod:path'.
- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `rotation` (default `0`): 0, 90, 180 or 270 degrees.
- `mirror` (default `False`): True mirrors it.
- `entities` (default `True`): False leaves out the entities saved in it.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

#### `world.place_jigsaw(pool, x, y=None, z=None, target=None, depth=7, dimension='minecraft:overworld')`

Generates a jigsaw structure from a Pool now (pools exist after the world was opened with the project).

- `pool`: The Pool.
- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `target` (default `None`): Name of the jigsaw block of the first piece to start from.
- `depth` (default `7`): Jigsaw steps, 1 to 20.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

### Extensions

#### `Content.extends(*extensions) -> 'Content'`

Adds behaviour to an item or block; returns the same item or block so calls chain.

- `*extensions`: Extension objects, or lists of them.

Returns: The item or block.

#### `Extension()`

Base class of every extension, built in or your own. Methods named like a hook run for the item or block it is attached to: items on_use, on_hit, on_mine, on_hold_tick; blocks on_interact, on_break, on_place, on_tick. Override configure(self, target) to change the target (target.set('glow', True)). Hooks receive one event; event.cancel() stops block_break, item_use, item_hit and item_mine, event.damage = x changes a hit.

#### `extension(cls)`

Marks your class as an extension: @extension class Vampiric(Extension): ...

- `cls`: The class.

Returns: The class.

#### `Tool(kind, tier='iron', speed=None, damage=None, attack_speed=None)`

Makes an item mine blocks and hit harder like a vanilla tool.

- `kind`: 'pickaxe', 'axe', 'shovel', 'hoe' or 'sword'.
- `tier` (default `'iron'`): 'wood', 'stone', 'iron' (default), 'gold', 'diamond' or 'netherite': mining level, speed, durability and damage defaults.
- `speed` (default `None`): Mining speed; default from the tier (wood 2, stone 4, iron 6, gold 12, diamond 8, netherite 9).
- `damage` (default `None`): Damage per hit as the tooltip shows it (a diamond sword is 7).
- `attack_speed` (default `None`): Hits per second (sword 1.6, axe 1.0, pickaxe 1.2).

#### `Sword(tier='iron', speed=None, damage=None, attack_speed=None)`

A sword (Tool with kind 'sword').

- `tier` (default `'iron'`): Material tier.
- `speed` (default `None`): Mining speed.
- `damage` (default `None`): Damage per hit.
- `attack_speed` (default `None`): Hits per second.

#### `Pickaxe(tier='iron', speed=None, damage=None, attack_speed=None)`

A pickaxe.

- `tier` (default `'iron'`): Material tier.
- `speed` (default `None`): Mining speed.
- `damage` (default `None`): Damage per hit.
- `attack_speed` (default `None`): Hits per second.

#### `Axe(tier='iron', speed=None, damage=None, attack_speed=None)`

An axe.

- `tier` (default `'iron'`): Material tier.
- `speed` (default `None`): Mining speed.
- `damage` (default `None`): Damage per hit.
- `attack_speed` (default `None`): Hits per second.

#### `Shovel(tier='iron', speed=None, damage=None, attack_speed=None)`

A shovel.

- `tier` (default `'iron'`): Material tier.
- `speed` (default `None`): Mining speed.
- `damage` (default `None`): Damage per hit.
- `attack_speed` (default `None`): Hits per second.

#### `Hoe(tier='iron', speed=None, damage=None, attack_speed=None)`

A hoe.

- `tier` (default `'iron'`): Material tier.
- `speed` (default `None`): Mining speed.
- `damage` (default `None`): Damage per hit.
- `attack_speed` (default `None`): Hits per second.

#### `Durability(uses=250, repair_with=None)`

A durability bar: 1 use is lost per block mined or entity hit; the item breaks at 0.

- `uses` (default `250`): Number of uses, 1 to 32767.
- `repair_with` (default `None`): Item that repairs it in an anvil (optional).

#### `Weapon(damage=4.0, attack_speed=1.6, knockback=0.0)`

Attack values for any item without making it a tool.

- `damage` (default `4.0`): Damage per hit as the tooltip shows it (a fist is 1).
- `attack_speed` (default `1.6`): Hits per second.
- `knockback` (default `0.0`): Extra knockback, 0 to 5.

#### `Fuel(seconds)`

Lets a furnace burn the item.

- `seconds`: Burn time in seconds, 1 to 100000 (200 smelts 20 items).

#### `Armor(slot='chest', defense=3, toughness=0.0, knockback_resistance=0.0)`

Makes an item wearable in an armor slot (no worn model is drawn).

- `slot` (default `'chest'`): 'head', 'chest', 'legs' or 'feet'.
- `defense` (default `3`): Armor points, 0 to 30.
- `toughness` (default `0.0`): Armor toughness, 0 to 20.
- `knockback_resistance` (default `0.0`): 0 to 1.

#### `Enchantable(value=15)`

How good enchantments from an enchanting table are.

- `value` (default `15`): 0 (none) to 50; gold armor is 25.

#### `Cooldown(seconds=1.0)`

The item can only be used again after a pause, shown as a bar.

- `seconds` (default `1.0`): 0.05 to 3600.

#### `Tooltip(*lines, color='gray')`

Extra lines in the item tooltip.

- `*lines`: 1 to 10 texts of at most 120 characters.
- `color` (default `'gray'`): One of the 16 chat colors ('gray', 'gold', 'aqua'...).

#### `MineableBy(tool='pickaxe', tier=None, required=True)`

Which tool mines a block.

- `tool` (default `'pickaxe'`): 'pickaxe', 'axe', 'shovel' or 'hoe'.
- `tier` (default `None`): Lowest tool tier that works ('wood' to 'netherite'; None = any).
- `required` (default `True`): True (default): nothing drops without the right tool.

#### `Drops(*drops)`

What a block drops when mined.

- `*drops`: Items, (item, count) or (item, count, weight) tuples, or 'self'; none = nothing drops.

#### `Redstone(power=15)`

The block emits a redstone signal.

- `power` (default `15`): Signal strength 0 to 15.

#### `Flammable(chance=5, spread=20)`

Fire can burn the block.

- `chance` (default `5`): How easily it catches fire, 0 to 300 (wood 5, leaves 30).
- `spread` (default `20`): How fast fire spreads over it, 0 to 300 (wood 20, leaves 60).

#### `Friction(value=0.98)`

How slippery the block is.

- `value` (default `0.98`): 0.1 to 1.0 (normal 0.6, ice 0.98).

#### `Piston(reaction='normal')`

What pistons do to the block.

- `reaction` (default `'normal'`): 'normal', 'destroy', 'block', 'ignore' or 'push_only'.

#### `Attributes(attribute, amount, operation='add', slot='mainhand')`

Attribute modifiers on an item (any attribute of the game or of a mod): Attributes('irons_spellbooks:fire_spell_power', 0.1, slot='mainhand').

- `attribute`: Attribute id; the IDE completes the ones of the installed mods.
- `amount`: Number added (or the factor, with a multiply operation).
- `operation` (default `'add'`): 'add' (default), 'multiply_base' or 'multiply_total'.
- `slot` (default `'mainhand'`): 'mainhand' (default), 'offhand', 'head', 'chest', 'legs', 'feet', 'hand', 'armor', 'body' or 'any'.

#### `Kinetic(role='shaft', *, axis='y', speed=64, capacity=32, impact=None, rotating_model='craftpy:block/machine_rotor')`

A native CraftPy machine with an optional Create rotation adapter.

Roles: generator supplies speed/capacity, shaft transmits, processor consumes stress and runs
craftpy:kinetic_processing recipes. Stress capacity/impact are per RPM. Axis affects new placements;
The machinery wrench rotates existing machines. Inventory and processing stay native, including on reload.
rotating_model is a baked block model resource ID, aligned along Y before the axis transform.


- `role` (default `'shaft'`): generator, shaft or processor.
- `axis` (default `'y'`): x, y or z.
- `speed` (default `64`): Signed generated RPM, -256 to 256.
- `capacity` (default `32`): Generator stress capacity per RPM, 0 to 65536.
- `impact` (default `None`): Stress consumed per RPM, 0 to 65536 (processor default 8).
- `rotating_model` (default `'craftpy:block/machine_rotor'`): Y-aligned baked block model ID.

#### `EnergyStorage(capacity, max_receive=None, max_extract=None)`

Native NeoForge energy storage in FE. No implicit rotation-to-energy conversion.

- `capacity`: Maximum stored FE (1..1000000000).
- `max_receive` (default `None`): Maximum FE per receive call; default capacity.
- `max_extract` (default `None`): Maximum FE per extraction call; default capacity.

#### `FluidTank(capacity, allowed=None)`

Native NeoForge tank in mB; allowed is an optional list of fluid resource IDs.

- `capacity`: Maximum mB (1..1000000).
- `allowed` (default `None`): Optional list of up to 64 namespaced fluid IDs; None permits all.

#### `Screen(title=None, *, energy=True, fluid=True, slots=True, layout=None)`

A Minecraft-style window for a machine block: right click opens it (sneak-click places a block instead).

It shows the block's two item slots (the input takes a filled bucket or tank item and empties it into the block's
FluidTank, the empty container comes out in the second slot), a fluid gauge when the block has a FluidTank, an energy
bar on the right when it has EnergyStorage, and your inventory. ``title`` is the text at the top.

``layout="my_window"`` uses a window you drew in the UI maker (ui/my_window.json with "Legacy (Minecraft look)" ticked):
panels, slots, the player inventory, bars, arrows, flames, labels and icons at the positions you gave them.

- `title` (default `None`): Text at the top of the window.
- `energy` (default `True`): Show the energy bar (needs EnergyStorage).
- `fluid` (default `True`): Show the fluid gauge (needs FluidTank).
- `slots` (default `True`): Show the two item slots.
- `layout` (default `None`): Name of a Legacy window drawn in the UI maker (ui/<name>.json); replaces the standard layout.

### Recipes

#### `shaped(result, *rows, count=1, **keys)`

Shaped crafting recipe: shaped(sword, ' R ', ' R ', ' S ', R=ruby, S='stick').

- `result`: Item crafted.
- `*rows`: 1 to 3 strings of up to 3 characters; a space is an empty slot.
- `count` (default `1`): How many items the recipe makes.
- `**keys`: One keyword per letter: the ingredient it stands for.

#### `shapeless(result, *ingredients, count=1)`

Shapeless crafting recipe: shapeless(ruby, 'diamond', 'redstone').

- `result`: Item crafted.
- `*ingredients`: Up to 9 ingredients, order does not matter.
- `count` (default `1`): How many items the recipe makes.

#### `smelt(ingredient, result, xp=0.1, seconds=10, using='furnace')`

Furnace-style recipe: smelt(ruby_ore, ruby).

- `ingredient`: Item that is cooked.
- `result`: Item that comes out.
- `xp` (default `0.1`): Experience given (default 0.1).
- `seconds` (default `10`): Cooking time in seconds (default 10).
- `using` (default `'furnace'`): 'furnace' (default), 'blast_furnace', 'smoker' or 'campfire'.

#### `stonecut(ingredient, result, count=1)`

Stonecutter recipe.

- `ingredient`: Item put in.
- `result`: Item produced.
- `count` (default `1`): Items produced per input.

#### `recipe(type, id=None, **fields)`

A recipe of another mod's type: recipe('create:mixing', ingredients=[...], results=[...]).

- `type`: Recipe type id 'mod:type' (the IDE lists the types of the installed mods).
- `id` (default `None`): File name of the recipe (optional).
- `**fields`: The mod's own recipe fields as keywords; the IDE shows the ones it knows.

#### `recipe_shaped(result, pattern, keys, category='misc', **extra)`

Low-level shaped recipe with the raw pattern.

Old API (replaced in 2.0, still works): use shaped(result, " R ", " R ", " S ", R=ingredient, S="stick").

- `result`: Result item id or dict.
- `pattern`: List of up to 3 strings.
- `keys`: Dict letter -> ingredient.
- `category` (default `'misc'`): Recipe book category: 'building', 'redstone', 'equipment' or 'misc'.
- `**extra`: Further fields written into the JSON as they are.

#### `recipe_shapeless(result, ingredients, category='misc', **extra)`

Low-level shapeless recipe.

Old API (replaced in 2.0, still works): use shapeless(result, ingredient, ...).

- `result`: Result item id or dict.
- `ingredients`: List of ingredients.
- `category` (default `'misc'`): Recipe book category.
- `**extra`: Further fields written into the JSON as they are.

#### `recipe_smelting(result, ingredient, xp=0.0, cook_time=200, **extra)`

Furnace recipe.

Old API (replaced in 2.0, still works): use smelt(ingredient, result, xp=..., seconds=...).

- `result`: Result.
- `ingredient`: Input.
- `xp` (default `0.0`): Experience given.
- `cook_time` (default `200`): Ticks to cook (200 = 10 s).
- `**extra`: Further fields written into the JSON as they are.

#### `recipe_blasting(result, ingredient, xp=0.0, cook_time=100, **extra)`

Blast furnace recipe.

Old API (replaced in 2.0, still works): use smelt(ingredient, result, using="blast_furnace").

- `result`: Result.
- `ingredient`: Input.
- `xp` (default `0.0`): Experience given.
- `cook_time` (default `100`): Ticks to cook (100 = 5 s).
- `**extra`: Further fields written into the JSON as they are.

#### `recipe_smoking(result, ingredient, xp=0.0, cook_time=100, **extra)`

Smoker recipe.

Old API (replaced in 2.0, still works): use smelt(ingredient, result, using="smoker").

- `result`: Result.
- `ingredient`: Input.
- `xp` (default `0.0`): Experience given.
- `cook_time` (default `100`): Ticks to cook (100 = 5 s).
- `**extra`: Further fields written into the JSON as they are.

#### `recipe_campfire(result, ingredient, cook_time=600, **extra)`

Campfire recipe.

Old API (replaced in 2.0, still works): use smelt(ingredient, result, using="campfire").

- `result`: Result.
- `ingredient`: Input.
- `cook_time` (default `600`): Ticks to cook (600 = 30 s).
- `**extra`: Further fields written into the JSON as they are.

#### `recipe_stonecutting(result, ingredient, count=1, **extra)`

Stonecutter recipe.

Old API (replaced in 2.0, still works): use stonecut(ingredient, result, count=...).

- `result`: Result.
- `ingredient`: Input.
- `count` (default `1`): Items produced.
- `**extra`: Further fields written into the JSON as they are.

#### `recipe_smithing(result, template, base, addition, **extra)`

Smithing table recipe.

- `result`: Result.
- `template`: Smithing template item.
- `base`: Item that is upgraded.
- `addition`: Material added.
- `**extra`: Further fields written into the JSON as they are.

### Tags, names, loot and data

#### `tag(ident, values, replace=False, type='item')`

Declares a tag, a named group of items or blocks.

- `ident`: Tag id without namespace ('ruby_gems' becomes <project>:ruby_gems).
- `values`: Items/blocks or '#other_tag' entries to include.
- `replace` (default `False`): True drops the entries of a tag with the same id from other packs.
- `type` (default `'item'`): 'item' (default), 'block', 'entity_type', 'fluid' or 'game_event'.

#### `lang(key, value)`

Adds a translation: lang('itemGroup.mypack', 'My Pack').

- `key`: Translation key.
- `value`: English text.

#### `loot_table(ident, entries, rolls=1, **extra)`

Declares a loot table (what chests, blocks or mobs drop).

- `ident`: Table id; a block's own id replaces what that block drops.
- `entries`: List of loot_entry(...) or 'self'.
- `rolls` (default `1`): How many entries are picked (default 1).
- `**extra`: Further fields written into the JSON as they are.

#### `loot_entry(item, count=1, weight=1, **kwargs)`

One possible drop of a loot table.

- `item`: Item to drop.
- `count` (default `1`): Stack size dropped (default 1).
- `weight` (default `1`): Relative chance against the other entries (default 1).
- `**kwargs`: Further fields written into the JSON as they are.

Returns: A dict for loot_table.

#### `loot_self()`

Marks a loot entry that drops the block itself.

#### `sound(event, **kwargs)`

Declares a sound event (sounds.json).

- `event`: Sound event id ('ruby_chime').
- `**kwargs`: Further fields written into the JSON as they are.

#### `particle(ident, **kwargs)`

Declares a particle definition.

- `ident`: Particle id.
- `**kwargs`: Further fields written into the JSON as they are.

#### `advancement(ident, **kwargs)`

Declares an advancement; keywords are the fields of the advancement JSON.

- `ident`: Advancement id.
- `**kwargs`: Further fields written into the JSON as they are.

#### `raw_json(path, data, namespace=None)`

Writes any data pack JSON file as is (escape hatch; version specific).

- `path`: Path inside the data pack, for example 'tags/item/foo.json'.
- `data`: A dict or list to write as JSON.
- `namespace` (default `None`): Write to data/<namespace>/ instead of your project's own folder (for example 'minecraft').

#### `tex(ref)`

Reference to a texture by name.

Old API (replaced in 2.0, still works): use the file name as text, like texture="ruby.png".

- `ref`: Texture name or id.

Returns: A texture reference.

#### `snd(ref)`

Reference to a sound by name.

Old API (replaced in 2.0, still works): use the sound id as text.

- `ref`: Sound event id.

Returns: A sound reference.

#### `mdl(ref)`

Reference to a model by name.

Old API (replaced in 2.0, still works): use the model name as text.

- `ref`: Model id.

Returns: A model reference.

### Events, commands and timers

#### `on(event, every=None, priority='normal', throttle_ticks=0, **filters)`

Runs a function when something happens: @on('block_break'), @on.block_break(block=ruby_ore).

- `event`: Event name: block_break, block_place, block_interact, block_tick, item_use, item_hit, item_mine, item_hold_tick, item_craft, item_pickup, player_join, player_leave, player_respawn, player_death, entity_death, entity_hurt, entity_tick, chat, or a command_<name> event. entity_tick is for CraftPy-owned entities, at most once a second, capped at 64 snapshots per server tick.
- `every` (default `None`): Run at most every this many ticks (optional).
- `priority` (default `'normal'`): 'normal' (default); reserved for ordering.
- `throttle_ticks` (default `0`): Minimum native ticks between calls for each entity/player (0-12000). Filtering happens first.
- `**filters`: block=, item= or entity= : only for that block/item/entity (ids or Block/Item objects).

#### `every(ticks=None, seconds=None)`

Runs a function again and again: @every(seconds=5) or @every(100).

- `ticks` (default `None`): Interval in ticks (20 = one second).
- `seconds` (default `None`): Interval in seconds (wins over ticks).

#### `after(seconds, fn=None)`

Runs a function once after a delay: after(3, fn) or @after(3).

- `seconds`: Delay in seconds.
- `fn` (default `None`): Function to run (omit to use as a decorator).

Returns: A Task that can be cancelled.

#### `run_later(ticks, fn)`

Runs a function after a number of ticks.

- `ticks`: Delay in ticks.
- `fn`: Function to run.

Returns: A Task.

#### `run_every(ticks, fn)`

Runs a function every N ticks until cancelled.

- `ticks`: Interval in ticks.
- `fn`: Function; receives the tick count.

Returns: A Task (task.cancel() stops it).

#### `run_next_tick(fn)`

Runs a function on the next game tick.

- `fn`: Function to run.

Returns: A Task.

#### `command(name, **params)`

Registers /<project>:<name>. Arguments come from the function signature (int, float, bool, str, Player).

- `name`: Command name.
- `**params`: Explicit argument types instead of annotations (optional).

#### `on_load(fn)`

Runs the decorated function when the project or module is loaded.

- `fn`: The function.

#### `on_unload(fn)`

Runs the decorated function when the project or module is unloaded.

- `fn`: The function.

#### `requires_restart(flag=True)`

Declares that loading this module needs a full game restart.

- `flag` (default `True`): True (default) or False.

#### `core()`

Marks this module as 'core': it may need a full game restart.

#### `has(capability)`

True when the game supports a capability: has('ui.window').

- `capability`: Capability name.

### Chat and server

#### `say(*message)`

Broadcasts a chat message to everyone.

- `*message`: Texts; non-text values are converted and joined with spaces.

#### `run(command_text)`

Runs a Minecraft command as the server: run('time set day').

- `command_text`: Command without the leading slash.

Returns: what the command answered, as text (lines joined with a newline)

#### `player(name) -> 'Player'`

The online player with this name, or None.

- `name`: Player name.

Returns: A Player or None.

#### `server.broadcast(message)`

Chat message to everyone.

- `message`: Text.

#### `server.run_command(command)`

Runs a command at permission level 2, like a command block. Needs "permissions": {"commands": true} in project.json / module.json (modules: approved on the Modules page).

- `command`: Without the leading slash.

Returns: what the command answered, as text

#### `server.mods()`

The ids of the loaded mods, e.g. 'create' in server.mods(). Needs no permission.

Returns: a list of mod ids

#### `server.features() -> dict`

Live installed mods and verified adapter capabilities; never cached catalog discovery.

Returns: dict

#### `server.info() -> dict`

Native player counts, persistent game_time ticks and average server tick milliseconds.

Returns: dict

#### `server.log(message, level='info')`

Writes to the server log and the IDE console.

- `message`: Text.
- `level` (default `'info'`): 'info' (default), 'warn' or 'error'.

#### `players.all()`

Names of every online player.

Returns: list of str

#### `players.online() -> 'list[Player]'`

Every online player.

Returns: list of Player

#### `players.get(name)`

Snapshot of an online player.

- `name`: Player name.

Returns: A Player or None.

#### `players.give(name, item, count=1)`

Gives items to a player by name.

- `name`: Player name.
- `item`: Item or id.
- `count` (default `1`): How many.

#### `players.send_message(name, message)`

Chat message to one player by name.

- `name`: Player name.
- `message`: Text.

#### `players.teleport(name, x, y, z, dimension='minecraft:overworld')`

Teleports a player by name.

- `name`: Player name.
- `x`: X.
- `y`: Y.
- `z`: Z.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

### Player

#### `Player.say(*message)`

Sends a chat message to this player only.

- `*message`: Texts, joined with spaces.

#### `Player.send_message(*message)`

Same as say.

- `*message`: Texts, joined with spaces.

#### `Player.actionbar(text)`

Shows text above the hotbar.

- `text`: The text.

#### `Player.title(text, subtitle=None, seconds=3)`

Shows a big title in the middle of the screen.

- `text`: Title.
- `subtitle` (default `None`): Smaller line under it (optional).
- `seconds` (default `3`): How long it stays (default 3).

#### `Player.give(item, count=1)`

Gives items; what does not fit is dropped.

- `item`: Item or id.
- `count` (default `1`): How many (default 1).

#### `Player.take(item, count=1)`

Removes items from the inventory.

- `item`: Item or id.
- `count` (default `1`): How many (default 1).

Returns: How many were taken.

#### `Player.has(item, count=1)`

True when the inventory holds enough of an item.

- `item`: Item or id.
- `count` (default `1`): At least this many.

Returns: bool

#### `Player.inventory()`

The inventory contents.

Returns: A list of stacks.

#### `Player.heal(amount=None)`

Restores health.

- `amount` (default `None`): Health points (2 = one heart); omit for full health.

#### `Player.set_health(health)`

Sets health.

- `health`: Health points, 20 is full.

#### `Player.feed(food=20, saturation=1.0)`

Restores hunger.

- `food` (default `20`): Hunger points (default 20 = full).
- `saturation` (default `1.0`): Saturation (default 1.0).

#### `Player.effect(name, seconds=10, level=1, particles=True)`

Applies a potion effect: player.effect('speed', seconds=30, level=2).

- `name`: Effect id ('speed', 'minecraft:regeneration').
- `seconds` (default `10`): Duration (default 10).
- `level` (default `1`): 1 = normal, 2 = II ... (default 1).
- `particles` (default `True`): False hides the swirl.

#### `Player.clear_effects()`

Removes every effect.

#### `Player.add_xp(levels=0, points=0)`

Gives experience.

- `levels` (default `0`): Whole levels.
- `points` (default `0`): Experience points.

#### `Player.teleport(x, y=None, z=None, dimension=None, *, safe=False)`

Moves the player.

- `x`: X (or a Pos).
- `y` (default `None`): Y.
- `z` (default `None`): Z.
- `dimension` (default `None`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').
- `safe` (default `False`): Require loaded, unobstructed destination and solid ground.

#### `Player.play_sound(sound, volume=1.0, pitch=1.0)`

Plays a sound only this player hears.

- `sound`: Sound event id ('minecraft:entity.blaze.shoot').
- `volume` (default `1.0`): 1.0 = normal.
- `pitch` (default `1.0`): 1.0 = normal.

#### `Player.set_gamemode(mode)`

Sets the game mode.

- `mode`: 'survival', 'creative', 'adventure' or 'spectator'.

#### `Player.set_fire(seconds=3)`

Sets the player on fire.

- `seconds` (default `3`): Burn time (default 3).

#### `Player.kill()`

Kills the player.

#### `Player.refresh() -> 'Player'`

A fresh snapshot of this player (position, health...).

Returns: A Player.

#### `Player.named(name) -> 'Player'`

The online player with this name, or None.

- `name`: Player name.

Returns: A Player or None.

### Entity

#### `Entity.kill()`

Kills the entity.

#### `Entity.teleport(x, y=None, z=None)`

Moves the entity.

- `x`: X (or a Pos).
- `y` (default `None`): Y.
- `z` (default `None`): Z.

#### `Entity.set_name(name)`

Sets its custom name.

- `name`: The name.

#### `Entity.refresh() -> 'Entity'`

A fresh snapshot (None if it is gone).

Returns: An Entity or None.

#### `Entity.state(key=None, default=None)`

Reads persistent state scoped to the calling owner; other owners' values are not exposed.

- `key` (default `None`): Dictionary key, or None for the whole dictionary.
- `default` (default `None`): Value returned for a missing key.

Returns: The stored value.

#### `Entity.set_state(key, value)`

Writes this owner's persistent state (at most 16 KiB per entity).

- `key`: Key, 1-128 characters.
- `value`: JSON-compatible value.

Returns: The value.

#### `Entity.set_owner(player=None)`

Assigns the player followed by a custom mob. Only its content owner may assign it.

- `player` (default `None`): Player/name, or None to clear.

#### `Entity.animate(name)`

Triggers a configured GeckoLib animation from the server. Only the content owner may trigger it.

- `name`: attack, cast, hurt or death.

### Position

#### `Pos(x, y, z)`

An (x, y, z) position; supports pos + (0, 1, 0), pos.up(2).

- `x`: X.
- `y`: Y.
- `z`: Z.

#### `Pos.up(n=1) -> 'Pos'`

The position n blocks higher.

- `n` (default `1`): Blocks (default 1).

Returns: A Pos.

#### `Pos.down(n=1) -> 'Pos'`

The position n blocks lower.

- `n` (default `1`): Blocks (default 1).

Returns: A Pos.

#### `Pos.block() -> 'Pos'`

Integer block coordinates.

Returns: A Pos with whole numbers.

### World

#### `world.get_block(x, y=None, z=None, dimension='minecraft:overworld')`

The block id at a position.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: str, for example 'minecraft:stone'

#### `world.set_block(x, y, z=None, block=None, dimension='minecraft:overworld', replace=True)`

Places a block: world.set_block(x, y, z, 'stone') or world.set_block(pos, 'stone').

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y`: Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `block` (default `None`): Block id or Block object.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').
- `replace` (default `True`): False only places into air.

#### `world.machine(x, y=None, z=None, dimension='minecraft:overworld') -> dict`

Read a loaded kinetic machine without loading chunks. Snapshot includes speed, theoretical_speed, overstressed, processing, progress, recipe and input/output stacks.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: dict

#### `world.machine_update(x, y=None, z=None, *, energy=0, fluid=0, dimension='minecraft:overworld') -> dict`

Add or remove energy (FE) and remove fluid (mB, negative number) of a loaded machine block; returns its new snapshot. The block's capacity and contents limit it.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `energy` (default `0`): FE to add (negative removes).
- `fluid` (default `0`): mB to remove, 0 or negative.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: dict

#### `world.resources(x, y=None, z=None, dimension='minecraft:overworld', *, side=None) -> dict`

Bounded native NeoForge item/FE/fluid snapshot from a loaded block face.

- `x`: Block X, tuple or Pos.
- `y` (default `None`): Block Y.
- `z` (default `None`): Block Z.
- `dimension` (default `'minecraft:overworld'`): Dimension resource ID.
- `side` (default `None`): Capability face or None for unsided access.

Returns: dict

#### `world.transfer(source, target, kind='item', amount=1, *, source_side=None, target_side=None, slot=0, simulate=False, dimension='minecraft:overworld') -> int`

Move items (max 64), fluid mB or energy FE through native NeoForge capabilities.

simulate=True reports the possible transfer without changing either endpoint. No chunks are loaded.


- `source`: Three source block coordinates.
- `target`: Three destination coordinates in the same dimension.
- `kind` (default `'item'`): item, fluid or energy.
- `amount` (default `1`): Requested count, mB or FE (1..1000000; items capped to 64).
- `source_side` (default `None`): Source capability face or None.
- `target_side` (default `None`): Destination capability face or None.
- `slot` (default `0`): Source item slot; default 0.
- `simulate` (default `False`): True checks without changing contents.
- `dimension` (default `'minecraft:overworld'`): Dimension resource ID.

Returns: int

#### `world.fill(start, end, block, dimension='minecraft:overworld')`

Fills the box between two corners (at most 32768 blocks).

- `start`: First corner (Pos or tuple).
- `end`: Opposite corner.
- `block`: Block id.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: Blocks changed.

#### `world.blocks_in_box(x, y, z, radius=0, dimension='minecraft:overworld')`

Blocks around a position.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y`: Y coordinate (height).
- `z`: Z coordinate.
- `radius` (default `0`): Distance in blocks in each direction.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: list

#### `world.get_block_entities(x, y, z, radius=0, dimension='minecraft:overworld')`

Block entities around a position.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y`: Y coordinate (height).
- `z`: Z coordinate.
- `radius` (default `0`): Distance in blocks.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: list

#### `world.entities(x, y=None, z=None, radius=8, type=None, dimension='minecraft:overworld') -> 'list[Entity]'`

Entities near a position.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `radius` (default `8`): Distance in blocks (default 8).
- `type` (default `None`): Only this entity type ('minecraft:zombie').
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: list of Entity

#### `world.entities_in_box(x, y, z, radius=8, dimension='minecraft:overworld')`

Entities in a cube around a position.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y`: Y coordinate (height).
- `z`: Z coordinate.
- `radius` (default `8`): Half the cube size.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: list

#### `world.spawn(entity, x, y=None, z=None, dimension='minecraft:overworld', *, owner=None) -> 'Entity'`

Spawns an entity ('zombie', 'minecraft:pig') or NativeMob variant.

- `entity`: Entity type id.
- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').
- `owner` (default `None`): Optional player who controls this owner's custom mob (follow profile).

Returns: The Entity.

#### `world.drop(item, x, y=None, z=None, count=1, dimension='minecraft:overworld')`

Drops an item stack on the ground.

- `item`: Item or id.
- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `count` (default `1`): Stack size.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

#### `world.explode(x, y=None, z=None, power=3, fire=False, break_blocks=True, dimension='minecraft:overworld')`

Creates an explosion.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `power` (default `3`): Strength (TNT is 4, default 3).
- `fire` (default `False`): True sets fires.
- `break_blocks` (default `True`): False only damages.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

#### `world.lightning(x, y=None, z=None, dimension='minecraft:overworld')`

Strikes lightning.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

#### `world.particle(particle, x, y=None, z=None, count=1, dimension='minecraft:overworld')`

Spawns particles.

- `particle`: Particle id ('minecraft:flame').
- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `count` (default `1`): How many.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

#### `world.play_sound(sound, x, y=None, z=None, volume=1.0, pitch=1.0, dimension='minecraft:overworld')`

Plays a sound at a position.

- `sound`: Sound event id.
- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `volume` (default `1.0`): 1.0 = normal.
- `pitch` (default `1.0`): 1.0 = normal.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

#### `world.biome(x, y=None, z=None, dimension='minecraft:overworld')`

Biome id at a position.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: str

#### `world.highest_y(x, z, dimension='minecraft:overworld')`

Height of the highest solid block in a column.

- `x`: X.
- `z`: Z.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: int

#### `world.info(x, y=None, z=None, dimension='minecraft:overworld')`

Biome, highest_y, light, is_day, raining, thundering and time at a position.

- `x`: X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).
- `y` (default `None`): Y coordinate (height).
- `z` (default `None`): Z coordinate.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: dict

#### `world.time(dimension='minecraft:overworld')`

Time of day in ticks (0 to 24000).

- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: int

#### `world.set_time(ticks, dimension='minecraft:overworld')`

Sets the time of day.

- `ticks`: 0 = sunrise, 6000 = noon, 13000 = night.
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

#### `world.is_day(dimension='minecraft:overworld')`

True during the day.

- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: bool

#### `world.weather(weather='clear', duration=6000, dimension='minecraft:overworld')`

Sets the weather.

- `weather` (default `'clear'`): 'clear', 'rain' or 'thunder'.
- `duration` (default `6000`): Ticks (default 6000).
- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

### Storage and scheduler

#### `storage.get(key, default=None, scope='world')`

Reads a saved value (saved with the world, shared by all handlers).

- `key`: Name.
- `default` (default `None`): Returned when missing.
- `scope` (default `'world'`): A name that keeps groups of values apart (default 'world').

Returns: the value

#### `storage.set(key, value, scope='world')`

Saves a value. storage['key'] = value does the same in the default scope.

- `key`: Name.
- `value`: Number, text, bool, list or dict.
- `scope` (default `'world'`): Group name (default 'world').

Returns: the value

#### `storage.clear(key=None, scope='world')`

Deletes one value, or everything of the scope.

- `key` (default `None`): Name; omit to clear the whole scope.
- `scope` (default `'world'`): Group name (default 'world').

#### `scheduler.after(seconds, fn)`

Runs fn once after a delay.

- `seconds`: Delay.
- `fn`: Function.

Returns: A Task.

#### `scheduler.every_seconds(seconds, fn)`

Runs fn repeatedly.

- `seconds`: Interval.
- `fn`: Function.

Returns: A Task.

#### `scheduler.run_every(ticks, fn)`

Runs fn every N ticks.

- `ticks`: Interval.
- `fn`: Function.

Returns: A Task.

#### `scheduler.run_later(ticks, fn)`

Runs fn after N ticks.

- `ticks`: Delay.
- `fn`: Function.

Returns: A Task.

#### `scheduler.run_next_tick(fn)`

Runs fn on the next tick.

- `fn`: Function.

Returns: A Task.

### Windows

#### `window(ident, title=None, size=None, resizable=False)`

Registers a Window subclass: @window('counter', title='Counter', size=(260, 170)).

- `ident`: Window id used by ui.open.
- `title` (default `None`): Title bar text.
- `size` (default `None`): (width, height) in pixels.
- `resizable` (default `False`): True lets the player resize it.

#### `action(name)`

Names a function that buttons of ui/*.json windows can call: @action('save').

- `name`: Action name used in on_click.

#### `Window.build(ctx)`

Returns the widgets of the window. Called again after every callback.

- `ctx`: Window context: ctx.player, ctx.values, ctx.close.

Returns: A widget.

#### `Window.state(default)`

A value remembered between redraws.

- `default`: Initial value.

Returns: A holder with .value.

#### `ui.open(player, window_id)`

Opens a window for a player.

- `player`: Player or name.
- `window_id`: Id of a @window class or ui/<id>.json.

#### `ui.data(window_id)`

Decorates a display data provider. It receives ctx.player and ctx.window and returns a dictionary. Widgets bind properties to dictionary keys, for example bind={'text': 'title'}. Called on open and refresh; named actions still validate every operation.

- `window_id`: Id of the JSON or Python window.

Returns: A decorator; its function returns a display dictionary.

#### `ui.close(player, window_id=None)`

Closes the window.

- `player`: Player or name.
- `window_id` (default `None`): Which one; omit to close any.

#### `ui.windows()`

Ids of the registered windows.

Returns: list of str

#### `ui.refresh(player, window_id)`

Redraws a window that is open, from a timer or an event (callbacks redraw by themselves); it keeps its state.

- `player`: Player or name.
- `window_id`: Which window.

Returns: False when the player has closed it

#### `Label(text, **p)`

Text.

- `text`: The text.
- `**p`: style='title'|'headline'|'text', color='#rrggbb', id.

Returns: A widget.

#### `Button(text, on_click=None, **p)`

A button.

- `text`: Caption.
- `on_click` (default `None`): An @action name or a function (called as fn(event)).
- `**p`: style='text' for a flat button, id.

Returns: A widget.

#### `TextInput(value='', **p)`

One-line text field.

- `value` (default `''`): Initial text.
- `**p`: id (read it with ctx.values[id]), hint, on_change, on_submit.

Returns: A widget.

#### `TextArea(value='', **p)`

Multi-line text field.

- `value` (default `''`): Initial text.
- `**p`: id, hint, on_change.

Returns: A widget.

#### `Checkbox(label='', checked=False, **p)`

A check box.

- `label` (default `''`): Caption.
- `checked` (default `False`): Initial state.
- `**p`: id, on_change.

Returns: A widget.

#### `Slider(value=0, min=0, max=100, **p)`

A slider.

- `value` (default `0`): Initial value.
- `min` (default `0`): Lowest value.
- `max` (default `100`): Highest value.
- `**p`: id, on_change.

Returns: A widget.

#### `Dropdown(options=None, selected=0, **p)`

A drop-down list.

- `options` (default `None`): List of texts.
- `selected` (default `0`): Index of the chosen one.
- `**p`: id, on_select.

Returns: A widget.

#### `ListView(items=None, **p)`

A list of texts.

- `items` (default `None`): List of texts.
- `**p`: id, on_select.

Returns: A widget.

#### `ProgressBar(value=0, **p)`

A progress bar.

- `value` (default `0`): Current value.
- `**p`: max (default 100), id.

Returns: A widget.

#### `Image(source='', **p)`

A picture. A source such as 'media/logo.png' or 'media/loop.gif' is a file of the module (PNG, JPEG, GIF, BMP; a GIF animates) and takes the media options; any other source is a texture of the game.

- `source` (default `''`): Texture id, or a file under media/ of the module (assets/media/).
- `**p`: id, width, height, size, fit='contain'|'cover'|'stretch', crop=[x, y, width, height] in picture pixels, opacity (0-1), z (draw order in a Canvas), autoplay, loop, speed, position (seconds), controls=True for a bar, on_end, on_loop, on_error.

Returns: A widget.

#### `ItemIcon(item='', **p)`

The icon of an item.

- `item` (default `''`): Item id.
- `**p`: id.

Returns: A widget.

#### `Video(source='', **p)`

A video: Motion JPEG in an AVI file of the module (assets/media/clip.avi). Sound is a separate file named by audio=. Needs no controls: start it with playing=True or autoplay=True.

- `source` (default `''`): A file under media/ ending in .avi.
- `**p`: id, width, height, audio='media/clip.ogg', autoplay (default False), loop (default False), playing, speed (0.25-4), position (seconds), volume (0-1), muted, restart, fit, crop, opacity, z, controls=True for a play/seek/speed/volume bar, on_end, on_loop, on_error.

Returns: A widget.

#### `Audio(source='', **p)`

A sound for the window: it plays through Minecraft (Records volume), has nothing to see unless controls=True. Use it for effects and background sound.

- `source` (default `''`): A file under media/ (ogg, wav, mp3, flac, aiff, au).
- `**p`: id, autoplay, loop, playing, position (seconds), volume (0-1), muted, restart (set a new number to play again), controls=True, on_end, on_loop, on_error.

Returns: A widget.

#### `VBox(*children, **p)`

Stacks children top to bottom.

- `*children`: Widgets.
- `**p`: spacing (pixels between), padding.

Returns: A widget.

#### `HBox(*children, **p)`

Places children side by side.

- `*children`: Widgets.
- `**p`: spacing, padding.

Returns: A widget.

#### `Grid(*children, **p)`

Places children in a grid.

- `*children`: Widgets.
- `**p`: columns, spacing, padding.

Returns: A widget.

#### `TabView(*children, **p)`

Tabs; each child is one tab.

- `*children`: Widgets.
- `**p`: selected (index).

Returns: A widget.

#### `ScrollView(*children, **p)`

A scrolling area.

- `*children`: Widgets.
- `**p`: padding.

Returns: A widget.

#### `Canvas(*children, **p)`

Places each child freely: x and y (from the top left) and width and height, all in pixels; what the UI maker makes when you drag and resize widgets.

- `*children`: Widgets with x=, y=, width=, height=.
- `**p`: width, height, padding.

Returns: A widget.

### Client side

#### `hud.text(id, text, anchor='top_left', x=0, y=0, color='white', scale=1.0, shadow=True)`

Shows text on your screen (client-side code only). Calling it again with the same id updates it.

- `id`: Name of this element (letters, digits, _ . -); the same id replaces it.
- `text`: The text (at most 256 characters).
- `anchor` (default `'top_left'`): Where on the screen: top_left, top_center, top_right, center_left, center, center_right, bottom_left, bottom_center, bottom_right.
- `x` (default `0`): Pixels to the right of the anchor (negative = left).
- `y` (default `0`): Pixels below the anchor (negative = above).
- `color` (default `'white'`): One of the 16 chat colors ('gold', 'aqua'...) or '#rrggbb'.
- `scale` (default `1.0`): Size, 0.25 to 8.
- `shadow` (default `True`): Draw a shadow behind the text.

#### `hud.bar(id, value, max=100, anchor='top_left', x=0, y=0, width=80, height=6, color='green', background='dark_gray', label=None)`

Shows a bar (health, mana, progress) on your screen.

- `id`: Name of this element.
- `value`: Current value.
- `max` (default `100`): Value when the bar is full.
- `anchor` (default `'top_left'`): Where on the screen (see hud.text).
- `x` (default `0`): Pixels right of the anchor.
- `y` (default `0`): Pixels below the anchor.
- `width` (default `80`): Width in pixels, 1 to 600.
- `height` (default `6`): Height in pixels, 1 to 60.
- `color` (default `'green'`): Color of the filled part.
- `background` (default `'dark_gray'`): Color of the empty part.
- `label` (default `None`): Text drawn on the bar (at most 64 characters), or None.

#### `hud.item(id, item, anchor='top_left', x=0, y=0, count=None, scale=1.0)`

Shows an item icon on your screen.

- `id`: Name of this element.
- `item`: Item id such as 'minecraft:diamond' or 'welcome:gem'.
- `anchor` (default `'top_left'`): Where on the screen (see hud.text).
- `x` (default `0`): Pixels right of the anchor.
- `y` (default `0`): Pixels below the anchor.
- `count` (default `None`): A number drawn in the corner, or None.
- `scale` (default `1.0`): Size, 0.25 to 8.

#### `hud.remove(id)`

Takes one element off the screen.

- `id`: Name of the element.

#### `hud.clear()`

Takes every element of this module off the screen.

#### `keys.bind(combo, fn=None, *, id=None, name=None, category='CraftPy')`

Runs a function when you press a key (while no screen is open). Use it as @keys.bind('ctrl+k').

- `combo`: One key with optional ctrl / shift / alt: 'r', 'f6', 'ctrl+shift+k'. Letters, digits, f1-f12, space, enter, tab, arrows, home, end, pageup, pagedown, insert, delete, backspace, minus, equals, comma, period, slash, semicolon, grave, bracket_left, bracket_right.
- `fn` (default `None`): The function; leave out to use as a decorator. Server callbacks get ctx.player from the server.
- `id` (default `None`): Stable action ID within this owner. Omit for the legacy combo ID. Keep this unchanged when changing the default key.
- `name` (default `None`): Display name in Controls and Module Keybinds (defaults to the ID).
- `category` (default `'CraftPy'`): Controls category (default CraftPy).

Returns: the function

#### `client.player()`

The local player as the client knows it (read only).

Returns: an object with name, uuid, health, max_health, food, level, x, y, z, pos, dimension, gamemode, held_item, held_count, on_ground

#### `client.looking_at()`

The block you are looking at.

Returns: {'block': 'minecraft:stone', 'pos': [x, y, z]}, or None

#### `client.fps()`

Frames per second right now.

Returns: number

#### `client.chat(text)`

Writes a line in your own chat; nobody else sees it and the server is not told.

- `text`: The text (at most 512 characters).

#### `client.sound(sound, volume=1.0, pitch=1.0)`

Plays a sound only you hear.

- `sound`: Sound event id.
- `volume` (default `1.0`): 1.0 = normal.
- `pitch` (default `1.0`): 1.0 = normal.

#### `client.toast(title, message='')`

Shows a small pop-up in the corner of the screen.

- `title`: Title (at most 64 characters).
- `message` (default `''`): Second line (at most 128 characters).

#### `client.open_window(window_id)`

Opens one of your windows (see window) on this computer.

- `window_id`: Id of a @window class or ui/<id>.json.

#### `client.close_window(window_id=None)`

Closes a window of yours.

- `window_id` (default `None`): Which one; omit to close any.

### Media (client side)

#### `media.files(folder='music')`

Client side: the audio files of pyforge/shared/<folder> (declare "shared": ["music"] in permissions). Call again to refresh.

- `folder` (default `'music'`): Folder of pyforge/shared (default 'music').

Returns: list of str

#### `media.play(file, folder='music', seconds=0, volume=1.0, nearby=False)`

Client side: plays a file of the shared folder; it keeps playing when the window closes.

- `file`: Name from media.files().
- `folder` (default `'music'`): Folder of pyforge/shared (default 'music').
- `seconds` (default `0`): Where to start.
- `volume` (default `1.0`): 0.0 to 1.0.
- `nearby` (default `False`): True: players around you hear it too (if the server allows it, up to media.maxShareMb).

#### `media.play_sound(sound, volume=1.0)`

Client side: plays a sound event (music disc, music of a resource pack or mod).

- `sound`: Id such as 'minecraft:music_disc.cat'.
- `volume` (default `1.0`): 0.0 to 1.0.

#### `media.sounds()`

Client side: music sound events the game, packs and mods have (ids with 'music' or 'record').

Returns: list of str

#### `media.pause()`

Client side: pauses the track.

#### `media.resume()`

Client side: plays on from where it paused.

#### `media.stop()`

Client side: stops the track.

#### `media.seek(seconds)`

Client side: jumps to a position (files only).

- `seconds`: Seconds from the start.

#### `media.volume(volume)`

Client side: sets the volume.

- `volume`: 0.0 to 1.0, on top of the Jukebox/Note Blocks slider.

#### `media.state() -> dict`

Client side: what plays now.

Returns: dict: state ('playing', 'paused', 'stopped', 'ended'), name, kind, position, duration (-1 unknown), volume, shared, error

### Web and files

#### `http.get(url, on_done=None, headers=None, key=None, timeout=10)`

Fetches a web page or API (https only). The answer comes later to on_done(response).

- `url`: https:// address of a site the module declares in permissions.net and the server allows.
- `on_done` (default `None`): Function called with a Response (ok, status, text, headers, error, json()).
- `headers` (default `None`): Extra request headers (dict).
- `key` (default `None`): Name of an API key in the server config; the key itself stays there.
- `timeout` (default `10`): Seconds before giving up.

Returns: the request id

#### `http.post(url, on_done=None, body=None, json=None, headers=None, key=None, timeout=10)`

Sends data to a web API (https only); the answer comes to on_done(response).

- `url`: https:// address.
- `on_done` (default `None`): Function called with a Response.
- `body` (default `None`): Text to send.
- `json` (default `None`): A dict or list sent as JSON instead of body.
- `headers` (default `None`): Extra request headers (dict).
- `key` (default `None`): Name of an API key in the server config.
- `timeout` (default `10`): Seconds before giving up.

Returns: the request id

#### `http.request(method, url, on_done=None, headers=None, body=None, json_body=None, key=None, timeout=10)`

Any of GET, POST, PUT, DELETE; see http.get.

- `method`: GET, POST, PUT or DELETE.
- `url`: https:// address.
- `on_done` (default `None`): Function called with a Response.
- `headers` (default `None`): Extra headers.
- `body` (default `None`): Text to send.
- `json_body` (default `None`): A dict or list sent as JSON.
- `key` (default `None`): API key name in the server config.
- `timeout` (default `10`): Seconds.

Returns: the request id

#### `files.read(name, folder=None, default=None)`

Reads a text file of this project's data folder (or of a shared folder).

- `name`: File name, folders with /: 'notes/day1.txt'.
- `folder` (default `None`): Name of a shared folder from the server config, or None for your own.
- `default` (default `None`): Returned when the file does not exist (instead of an error).

Returns: the text

#### `files.write(name, text)`

Writes a text file into this project's data folder.

- `name`: File name.
- `text`: The text.

#### `files.delete(name)`

Deletes a file of the data folder.

- `name`: File name.

Returns: True when it was there

#### `files.exists(name, folder=None)`

True when the file exists.

- `name`: File name.
- `folder` (default `None`): Shared folder name or None.

Returns: bool

#### `files.list(folder=None)`

Names of the files of the data folder (or of a shared folder).

- `folder` (default `None`): Shared folder name or None.

Returns: list of str

#### `files.read_json(name, default=None)`

Reads a JSON file.

- `name`: File name.
- `default` (default `None`): Returned when the file does not exist.

Returns: the value

#### `files.write_json(name, value)`

Writes a value as a JSON file.

- `name`: File name.
- `value`: Dict, list, number, text...

### Java bridge

#### `java.available()`

True when the host allows the bridge (config javaBridge.mode is not OFF).

Returns: bool

#### `java.cls(name)`

The Java class with this full name.

- `name`: Full class name, for example 'net.minecraft.world.level.block.Blocks'.

Returns: A JavaClass: .FIELD, .method(...), Class(...) builds an object.

#### `java.new(name, *args)`

Builds a Java object.

- `name`: Full class name.
- `*args`: Constructor arguments.

Returns: A JavaObject.

#### `java.on(event_class, priority='normal', receive_cancelled=False)`

Handles any NeoForge event: @java.on('net.neoforged...LivingIncomingDamageEvent').

- `event_class`: Event class name or JavaClass.
- `priority` (default `'normal'`): 'highest', 'high', 'normal', 'low' or 'lowest'.
- `receive_cancelled` (default `False`): True also receives events other handlers cancelled.

#### `java.items(obj)`

A Java list, set, map (as pairs), array or stream as a Python list.

- `obj`: The Java object.

Returns: list

#### `java.members(obj)`

Public fields and methods of a Java object or class.

- `obj`: The Java object or class.

Returns: dict

#### `java.mods()`

The loaded mods.

Returns: list of {id, name, version}

#### `java.server()`

The MinecraftServer.

Returns: A JavaObject.

#### `java.level(dimension='minecraft:overworld')`

A ServerLevel.

- `dimension` (default `'minecraft:overworld'`): Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end').

Returns: A JavaObject.

#### `java.entity(uuid)`

The live entity or player with this uuid.

- `uuid`: The uuid text.

Returns: A JavaObject.

<!-- reference:end -->
