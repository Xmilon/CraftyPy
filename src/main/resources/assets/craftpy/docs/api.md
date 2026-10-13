# CraftPy Python API Reference

CraftPy turns a Minecraft **1.21.1 / NeoForge** server into a Python
runtime. You write Python modules that declare content (blocks, items,
recipes, ...), react to game events, use the world through safe helpers,
and even build in-game windows. This page is the complete reference for
that API. It is the same document the **API Reference** editor tab shows.

> Tip: the IDE renders this file as real Markdown - headings, **bold**,
> *italic*, `code`, lists, tables and fenced code blocks all render.

---

## 1. How a module works

A project is a folder with a `code/main.py` entry file. At the top level
you run *declarations* (things that exist before the world loads) and
register *handlers* (things that run later). You may also define a
`main(ctx)` function for startup logic.

```python
from craftpy import *

@block("ruby_block", name="Ruby Block", texture=tex("ruby_block"), light=6)
class RubyBlock:
    hardness = 3.0
    tool = "pickaxe"

@on("block_break")
def on_break(ctx):
    ctx.log(f"{ctx.player['name']} broke a block at {ctx.x}, {ctx.y}, {ctx.z}")
```

The worker imports the API for you, but `from craftpy import *` is the
idiomatic way to pull every name into scope. `craftpy` is the canonical
module name.

### Declarations versus handlers

- **Declarations** are collected when the project runs and are frozen into
  the world's slot pool and datapack. They are: `@block`, `@item`,
  `@entity`, `@effect`, `@enchantment`, `potion`, `recipe_*`,
  `loot_table`, `loot_entry`, `tag`, `advancement`, `sound`, `particle`,
  `lang`, `raw_json`, `window`.
- **Handlers** run at runtime: everything registered with `@on`, `@command`,
  `on_load`, `on_unload`, and the scheduler.

---

## 2. Content declarations

### @block(id, **fields)

Declares a block. The decorated class body may hold any of these fields as
class attributes (they are also accepted as decorator keyword arguments):

| Field | Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `name` | str | id | Display name (tooltip / inventory). |
| `texture` | ref | placeholder | Texture reference, e.g. `tex("ruby_block")`. |
| `hardness` | float | 1.5 | Mining time; higher is slower (stone = 1.5). |
| `resistance` | float | 6.0 | Explosion resistance (stone = 6). |
| `light` | int | 0 | Light emitted, 0-15 (glowstone = 15). |
| `tool` | str | none | Preferred tool: `pickaxe`, `axe`, `shovel`, `hoe`, `sword`. |
| `needs_tool` | bool | false | True = only drops with the correct tool. |
| `sound` | str | `stone` | Step sound set: `stone`, `wood`, `grass`, `metal`, ... |

```python
@block("ruby_block", name="Ruby Block", light=6)
class RubyBlock:
    hardness = 3.0
    tool = "pickaxe"
    needs_tool = True
    loot = [loot_self()]        # optional custom drop
```

### @item(id, **fields)

Declares an item.

| Field | Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `name` | str | id | Display name. |
| `texture` | ref | placeholder | Texture reference, e.g. `tex("item/ruby")`. |
| `max_stack` | int | 64 | Stack size, 1-64. |
| `glow` | bool | false | Enchantment glint overlay. |
| `food` | dict | none | `{"hunger": 6, "saturation": 0.6}` makes it edible. |
| `burn_time` | int | 0 | Furnace fuel value in ticks. |
| `use_effect` | str | none | On right-click, `"speed:2:10"` = effect:amplifier:seconds. |

```python
@item("ruby", name="Ruby", texture=tex("item/ruby"))
class Ruby:
    max_stack = 64
    glow = True

@item("ruby_apple", name="Ruby Apple", food={"hunger": 6, "saturation": 0.6})
class RubyApple:
    pass
```

### @entity(id, **fields)

Declares a reskinned mob built on a vanilla base.

| Field | Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `name` | str | id | Display name. |
| `texture` | ref | base skin | Entity skin texture. |
| `base` | str | `mob_passive` | Base AI preset (`mob_passive`, `mob_hostile`). |
| `health` | float | 10 | Max health (20 = full bar). |
| `speed` | float | 0.25 | Movement speed. |
| `damage` | float | 0 | Attack damage (hostile bases). |
| `width` | float | 0.6 | Hitbox width. |
| `height` | float | 1.8 | Hitbox height. |
| `follow_range` | float | 16 | Target detection range in blocks. |

```python
@entity("ruby_golem", name="Ruby Golem", base="mob_hostile", health=40, damage=7)
class RubyGolem:
    width = 0.9
    height = 1.9
```

### @effect(id, **fields)

Declares a mob effect.

| Field | Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `name` | str | id | Display name. |
| `icon` | ref | none | Status icon texture. |
| `category` | str | `neutral` | `good`, `neutral` or `bad` (color/tint). |
| `color` | str | `#8899ff` | Particle/liquid color, `#RRGGBB`. |

```python
@effect("ruby_glow", name="Ruby Glow", category="good", color="#ff3355")
def ruby_glow():
    pass
```

### @enchantment(id, **fields)

Declares a data-driven enchantment. Accepts arbitrary keyword fields that
are written to the enchantment JSON (for example `max_level`, `anvil_cost`,
`slots`, `weight`, `effects`).

```python
@enchantment("ruby_sharpness", max_level=5, anvil_cost=4, weight=2)
def ruby_sharpness():
    pass
```

### potion(id, **fields)

Declares a potion variant (stored as an item entry).

```python
potion("ruby_potion", base="minecraft:water", effects=["ruby_glow:1:200"])
```

### Asset references

These helpers describe where an asset comes from. They are plain data and
are resolved by the pack builder.

- `tex(ref)` - texture asset.
- `snd(ref)` - sound event.
- `mdl(ref)` - model.
- `loot_self()` - drop the block/item itself.

```python
@block("gem_ore", texture=tex("gem_ore"))
class GemOre:
    pass
```

---

## 3. Recipes

Every vanilla recipe type is available. Results and ingredients accept a
`project:<content_id>` reference or a plain `minecraft:<id>`. Prefix an
ingredient with `#` to reference a tag.

| Function | Signature |
| :--- | :--- |
| `recipe_shaped` | `recipe_shaped(result, pattern, keys, category="misc")` |
| `recipe_shapeless` | `recipe_shapeless(result, ingredients, category="misc")` |
| `recipe_smelting` | `recipe_smelting(result, ingredient, xp=0.0, cook_time=200)` |
| `recipe_blasting` | `recipe_blasting(result, ingredient, xp=0.0, cook_time=100)` |
| `recipe_smoking` | `recipe_smoking(result, ingredient, xp=0.0, cook_time=100)` |
| `recipe_campfire` | `recipe_campfire(result, ingredient, cook_time=600)` |
| `recipe_stonecutting` | `recipe_stonecutting(result, ingredient, count=1)` |
| `recipe_smithing` | `recipe_smithing(result, template, base, addition)` |

```python
recipe_shaped("project:ruby_block", ["RRR", "RRR", "RRR"],
              {"R": {"item": "project:ruby"}})

recipe_shapeless("project:ruby", ["project:ruby_block"])

recipe_smelting("project:ruby", "project:ruby_ore", xp=0.6, cook_time=180)
recipe_blasting("project:ruby", "project:ruby_ore", xp=0.6, cook_time=90)
recipe_stonecutting("project:ruby_slab", "project:ruby_block", count=2)
recipe_smithing("project:ruby_sword", "minecraft:netherite_upgrade_smithing_template",
                "minecraft:diamond_sword", "project:ruby")
```

---

## 4. Loot tables

```python
def loot_table(ident, entries, rolls=1, **extra)
def loot_entry(item, count=1, weight=1, **kwargs)
```

`entries` is a list of item ids or `loot_entry(...)` specs. The table id
matching a block's content id overrides that block's default self-drop.

```python
loot_table("project:ruby_ore", [
    loot_entry("project:ruby", count=2, weight=3),
    loot_entry("minecraft:diamond", count=1, weight=1),
], rolls=1)
```

---

## 5. Tags

```python
def tag(ident, values, replace=False, type="item")
```

`type` selects the registry folder: `item`, `block`, `entity_type`,
`fluid`, `game_event` or `function`. Values may be strings (ids or
`#other_tag`) or dicts like `{"id": "...", "required": False}`.

```python
tag("minecraft:mineable/pickaxe", ["project:ruby_block"], type="block")
tag("project:gems", ["project:ruby", "#minecraft:diamonds"], type="item")
```

---

## 6. Advancements, sounds, particles, raw JSON

### advancement(id, **fields)

Writes a data-driven advancement.

```python
advancement("project:get_ruby", parent="minecraft:story/root",
            display={"title": "Shiny!", "description": "Get a ruby"})
```

### sound(event, **fields)

Adds an entry to the project's `sounds.json` (references `.ogg` files you
ship in the project `assets/`).

```python
sound("project:ruby_chime", sounds=[{"name": "project:ruby/chime"}])
```

### particle(id, **fields)

Writes `assets/<project>/particles/<id>.json`.

```python
particle("project:ruby_spark", textures=["project:particle/ruby"])
```

### raw_json(path, data)

Escape hatch for version-specific datapack JSON. The linter flags it. The
path is written under `data/<project>/`; `.json` is appended if missing.

```python
raw_json("worldgen/configured_feature/ruby", {"type": "minecraft:ore", ...})
```

### lang(key, value)

Adds a translation to `en_us.json`.

```python
lang("item.project.ruby", "Ruby")
```

---

## 7. Events

```python
def on(event, every=None, priority="normal")
```

`@on` registers a handler. `priority` is one of `lowest`, `low`, `normal`,
`high`, `highest`. `every=N` runs the handler every N server ticks instead
of on a discrete event (this implicitly targets the `tick` event).

The handler receives a context object. Every field supplied by the event is
exposed as an attribute, and the cancel/query helpers below are always
available.

```python
@on("block_break", priority="high")
def on_break(ctx):
    ctx.log(f"broken at {ctx.x},{ctx.y},{ctx.z} in {ctx.dimension}")
```

### Event list

| Event | Fires when | Useful context fields |
| :--- | :--- | :--- |
| `block_break` | a block is broken | `player`, `block`, `x`, `y`, `z`, `dimension` |
| `block_interact` | a block is right-clicked | `player`, `block`, `hand`, `x`, `y`, `z`, `dimension` |
| `player_join` | a player joins | `player` |
| `player_leave` | a player leaves | `player` |
| `tick` | server tick (with `every=`) | `tick` |
| `load` | project loaded (`on_load`) | - |
| `unload` | project unloaded (`on_unload`) | - |
| `command_<name>` | a registered command runs | `player`, `args` |

### Context object

| Member | Meaning |
| :--- | :--- |
| `ctx.log(message)` | Write to the project console. |
| `ctx.info/warn/error(message)` | Same, with a level prefix. |
| `ctx.world` | The `world` facade (see below). |
| `ctx.broadcast(message)` | Message every player. |
| `ctx.global_state` | A dict persisted per world level. |
| `ctx.cancel()` | Cancel a cancellable event (where supported). |

### Convenience decorators

- `@every(ticks)` - same as `@on("tick", every=ticks)`.
- `@on_load` / `@on_unload` - run when the project loads/unloads.

---

## 8. Commands

```python
@command("givegem", permission=2)
def give_gem(ctx):
    name = ctx.args[0] if ctx.args else ctx.player["name"]
    players.give(name, "project:ruby", 1)
```

Register a server command with `/craftpy` subcommand semantics. The handler
is invoked as an event named `command_<name>`.

---

## 9. World helpers

The `world` facade performs safe, server-threaded world access. Every call
returns a dict; a failure carries `{"ok": False, "error": ...}`.

| Call | Description |
| :--- | :--- |
| `world.get_block(x, y, z, dimension=...)` | Block id at a position. |
| `world.set_block(x, y, z, block, dimension=..., replace=True)` | Place a block. |
| `world.blocks_in_box(x, y, z, radius=0, dimension=...)` | List blocks in a cube. |
| `world.entities_in_box(x, y, z, radius=8, dimension=...)` | List entities in a box. |
| `world.spawn(entity, x, y, z, dimension=...)` | Spawn an entity. |
| `world.play_sound(sound, x, y, z, volume=1.0, pitch=1.0, dimension=...)` | Play a sound. |
| `world.particle(particle, x, y, z, count=1, dimension=...)` | Spawn particles (simple types). |
| `world.time(dimension=...)` | Current day time in ticks. |
| `world.set_time(ticks, dimension=...)` | Set the day time. |
| `world.weather(weather="clear", duration=6000, dimension=...)` | `clear`, `rain` or `thunder`. |

```python
@on("block_interact")
def rubify(ctx):
    world.set_block(ctx.x, ctx.y + 1, ctx.z, "project:ruby_block")
    world.play_sound("project:ruby_chime", ctx.x, ctx.y, ctx.z)
```

> `dimension` defaults to `minecraft:overworld`. Writing calls are queued
> onto the server thread and block the Python call until applied.

---

## 10. Players, server, storage

### players

| Call | Description |
| :--- | :--- |
| `players.all()` | List of online player names. |
| `players.get(name)` | Player info dict (name, uuid, position, health, gamemode). |
| `players.send_message(name, message)` | Send a chat message to one player. |
| `players.teleport(name, x, y, z, dimension=...)` | Teleport a player. |
| `players.give(name, item, count=1)` | Give an item. |

### server

| Call | Description |
| :--- | :--- |
| `server.broadcast(message)` | Message every player. |
| `server.run_command(command)` | Run a server command (no leading slash needed). |
| `server.log(message, level="info")` | Write to the server log. |

### storage (persistent key-value)

| Call | Description |
| :--- | :--- |
| `storage.get(key, default=None, scope="world")` | Read a value. |
| `storage.set(key, value, scope="world")` | Write a value. |
| `storage.clear(key=None, scope="world")` | Remove one key, or all in scope. |

`scope` is a string namespace (for example `world`, `global`, or a player
uuid) so modules can keep per-world and per-player data apart.

```python
visits = storage.get("visits", 0)
storage.set("visits", visits + 1)
```

---

## 11. Scheduler

| Call | Description |
| :--- | :--- |
| `run_later(ticks, fn)` | Run `fn` once after N ticks. |
| `run_every(ticks, fn)` | Run `fn` every N ticks. |
| `run_next_tick(fn)` | Run `fn` on the next tick. |
| `scheduler.after(seconds, fn)` | Time-based one-shot. |
| `scheduler.every_seconds(seconds, fn)` | Time-based repeating. |

Each call returns a `Task` handle with `.cancel()`.

```python
def burn():
    world.particle("minecraft:flame", 0, 64, 0, count=5)

handle = run_every(20, burn)     # every second
run_later(200, handle.cancel)    # stop after 10 seconds
```

The scheduler is driven by the server tick. The worker only receives ticks
while at least one task is pending, so idle modules cost nothing.

---

## 12. Capabilities

Query whether an optional feature exists before using it:

```python
if has("worldgen.biome_modifiers"):
    ...
```

Known capabilities: `content.block`, `content.item`, `content.effect`,
`content.entity`, `recipe.all`, `loot.table`, `tags`, `lang`, `events`,
`commands`, `ui.window`, `storage.kv`, `scheduler`, `world.read`,
`world.write`.

---

## 13. In-game windows

Python describes a widget tree; the Java client renders it. Register a
window with the `@window` decorator, build a tree in `build(ctx)`, and open
it for a player with `ui.open`.

```python
from craftpy import *

@window("counter", title="Counter", size=(240, 150))
class Counter(Window):
    def build(self, ctx):
        self.count = self.state(0)
        return VBox(
            Label("Clicks: 0"),
            Button("Click me", on_click=self.bump),
        )

    def bump(self, event):
        self.count["value"] += 1
```

### Widgets

Layout: `VBox`, `HBox`, `Grid`, `TabView`, `ScrollView`.
Controls: `Label`, `Button`, `TextInput`, `TextArea`, `Checkbox`, `Slider`,
`Dropdown`, `ListView`, `ProgressBar`, `Image`, `ItemIcon`.

### ui facade

| Call | Description |
| :--- | :--- |
| `ui.open(player, window_id)` | Open a window for a player. |
| `ui.close(player, window_id=None)` | Close a window. |
| `ui.windows()` | List registered window ids. |

> Window rendering is server-described: the client never runs module code.
> Round-trip latency and bandwidth budgets apply; this feature is marked
> experimental and in-client verification is pending.

---

## 14. Console and REPL

The IDE console is a persistent Python REPL. Names from this API are
pre-imported, so you can experiment interactively: variables, imports and
definitions survive between lines. `craftpy` is also importable inside the
console.

---

## 15. Limits and possibilities

| Area | Status | Notes |
| :--- | :--- | :--- |
| Blocks, items, effects, entities | Supported | Slot-pool backed; persistent across unload. |
| All recipe types | Supported | Shaped, shapeless, smelting, blasting, smoking, campfire, stonecutting, smithing. |
| Loot tables, tags, advancements | Supported | Rewritten to allocated slot ids. |
| Sounds, particles, lang, raw JSON | Partial | Written to the pack; in-client verification pending. |
| Events, world helpers, scheduler, storage | Supported | Server-threaded host bridge. |
| Commands | Supported | Registered under `/craftpy`. |
| In-game windows | Experimental | Widget tree is described; rendering pending in-client verification. |
| Arbitrary Java interop | Not possible | Sandbox blocks it by design. |
| Custom shaders / render code | Not possible | Out of scope. |
| New entity AI beyond presets | Not possible | Use `base` presets only. |
| Runtime registry additions | Not possible | Content is allocated through the slot pool. |
| Non-simple particle types | Partial | `world.particle` supports simple particle types only. |

### Security notes

- Module code runs in a sandboxed CPython subprocess with an import
  allowlist and restricted builtins.
- `open()` and OS/network/threading modules are blocked in project code.
- Every host call is validated and executed on the server thread.

---

## 16. Quick examples

### A pile of rubies that glows

```python
from craftpy import *

@item("ruby", name="Ruby", texture=tex("item/ruby"), glow=True)
class Ruby:
    pass

@block("ruby_block", name="Ruby Block", texture=tex("ruby_block"), light=6)
class RubyBlock:
    hardness = 3.0
    tool = "pickaxe"
```

### Welcome message

```python
@on("player_join")
def welcome(ctx):
    players.send_message(ctx.player["name"], "Welcome to the server!")
    server.broadcast(f"{ctx.player['name']} joined!")
```

### Ore that turns to a block when clicked

```python
@on("block_interact")
def convert(ctx):
    if ctx.block["id"] == "project:ruby_ore":
        world.set_block(ctx.x, ctx.y, ctx.z, "project:ruby_block")
        world.particle("minecraft:flame", ctx.x, ctx.y, ctx.z, count=8)
```
