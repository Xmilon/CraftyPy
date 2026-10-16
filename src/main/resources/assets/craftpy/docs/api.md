# CraftPy Python API

CraftPy lets you add blocks, items, recipes and game logic to Minecraft with plain Python.
Write your code in `code/main.py`, press **Run**, and it is live in the game.

```python
from craftpy import *

ruby = Item("ruby", texture="ruby.png")
ruby_ore = Block("ruby_ore", texture="ruby_ore.png", hardness=3, tool="pickaxe", drops=ruby)

shaped(ruby, "DD", "DD", D="diamond")


@on.block_break(block=ruby_ore)
def found_ruby(event):
    event.player.say("You found a ruby!")
```

Get your content in game with `/craftpy give @s <project>:<id> block|item`.

---

## 1. Ids and names

- Your content lives in your project's namespace: in project `ruby_rush`, `Item("ruby")` is `ruby_rush:ruby`.
- Ids are lowercase letters, digits and `_` (`ruby_ore`, not `Ruby Ore`).
- Anywhere an item or block is expected you can write:
  - a variable you declared: `ruby`
  - a bare name: `"stick"` means `minecraft:stick`, and `"ruby"` means your own `ruby` if you declared it
  - a full id: `"minecraft:diamond"`, `"ruby_rush:ruby"`
  - a tag (in recipes): `"#minecraft:logs"`

## 2. Blocks

```python
ruby_ore = Block("ruby_ore",
                 name="Ruby Ore",          # optional, defaults to "Ruby Ore" from the id
                 texture="ruby_ore.png",   # assets/textures/ruby_ore.png in your project
                 hardness=3,               # how long it takes to mine (stone = 1.5)
                 resistance=3,             # explosion resistance
                 light=0,                  # 0-15
                 tool="pickaxe",           # pickaxe, axe, shovel, hoe
                 needs_tool=True,          # only drops when mined with the right tool
                 sound="stone",            # stone, wood, metal, glass, grass, sand, wool...
                 drops=ruby)               # what it drops (see below)
```

`drops` options:

- not given: the block drops itself
- `drops=ruby`: one ruby
- `drops=(ruby, 3)`: three rubies
- `drops=[(ruby, 1, 3), ("coal", 2, 1)]`: one of these, picked by weight (item, count, weight)
- `drops=[]`: nothing

## 3. Items

```python
ruby = Item("ruby", texture="ruby.png", glow=True)          # glow = enchanted shimmer
sword = Item("ruby_sword", texture="ruby_sword.png", stack=1)
apple = Item("ruby_apple", texture="ruby_apple.png",
             food=Food(hunger=6, saturation=1.2, always_eat=True, fast=False))
coal_gem = Item("coal_gem", fuel=40)                          # burns 40 seconds in a furnace
wand = Item("wand", effect=("speed", 2, 10))                  # right click: Speed II for 10 s
```

- `stack`: max stack size (1-64)
- `food`: `Food(...)`, or just a number of hunger points: `food=4`
- `effect`: `"speed"` or `(name, level, seconds)`

## 4. Textures

- Put 16x16 PNG files in `assets/textures/` and use the file name: `texture="ruby.png"`.
- Use vanilla textures by name: `texture="minecraft:diamond_ore"` (the `block/` or `item/` folder is found for you).
- Full texture ids work too: `texture="minecraft:block/dirt"`.
- The **Assets** button in the editor browses textures; the picked reference is copied to the clipboard.

## 5. Recipes

```python
# Crafting table: rows of the pattern, then a keyword per letter. Spaces are empty slots.
shaped(sword, " R ", " R ", " S ", R=ruby, S="stick")
shaped(ruby_block, "RRR", "RRR", "RRR", R=ruby)

# Any arrangement.
shapeless(ruby, ruby_block, count=9)
shapeless(apple, "apple", "redstone")

# Furnace and friends. using = "furnace" (default), "blast_furnace", "smoker", "campfire".
smelt(ruby_ore, ruby, xp=0.7, seconds=10)
smelt("raw_iron", "iron_ingot", using="blast_furnace", seconds=5)

# Stonecutter.
stonecut("stone", "stone_bricks", count=1)
```

## 6. Events

Decorate a function with `@on.<event>`. It receives an `event` object.

```python
@on.player_join
def hello(event):
    event.player.say("Welcome,", event.player.name)


@on.block_break(block=ruby_ore)       # filters: block=, item=, entity=
def mined(event):
    event.player.give(ruby)
```

`@on("player_join")` is the same as `@on.player_join`.

Events and what they carry:

- `player_join`, `player_leave`, `player_respawn`: `event.player`
- `chat`: `event.player`, `event.message`
- `block_break`, `block_place`: `event.player`, `event.block`, `event.pos`
- `block_interact` (right click a block): `event.player`, `event.block`, `event.item`, `event.hand`, `event.pos`
- `item_use` (right click with an item): `event.player`, `event.item`, `event.hand`
- `item_craft`, `item_pickup`: `event.player`, `event.item`
- `entity_death`: `event.entity`, `event.cause`, and `event.killer` (a Player) or `event.attacker` (an Entity)
- `player_death`: like `entity_death`, plus `event.player`
- `entity_hurt`: `event.entity`, `event.damage`, `event.cause`, `event.attacker`, `event.attacker_player`, and `event.player` when a player was hurt

`event.block` and `event.item` have `.id` and `.name`; items also `.count`.
Your own content shows up with your ids (`event.block.id == ruby_ore`).

Events are notifications: your code runs right after the game event and cannot cancel it.

## 7. Players

`event.player`, `player("Steve")` and `players.online()` give you `Player` objects.

Data (a snapshot from when the event happened):
`name`, `uuid`, `pos` (and `x`, `y`, `z`), `dimension`, `health`, `max_health`, `food`, `level`, `gamemode`, `held_item`.

Actions:

```python
p.say("hi")                         # chat message to this player
p.title("Big text", subtitle="small", seconds=3)
p.actionbar("text above the hotbar")
p.give(ruby, 5)
p.take("dirt", 10)                  # returns how many were removed
p.count(ruby)                       # how many they carry
p.has(ruby, 3)
p.inventory()                       # list of items (.id, .count, .slot)
p.heal()                            # full health; p.heal(4) = 2 hearts
p.set_health(10)
p.feed(20, saturation=1.0)
p.effect("speed", seconds=30, level=2)
p.clear_effects()
p.teleport(0, 100, 0)               # or p.teleport(pos)
p.set_gamemode("creative")          # survival, creative, adventure, spectator
p.add_xp(levels=5)                  # or points=100
p.play_sound("minecraft:entity.player.levelup")
p.set_fire(3)
p.kill()
p.refresh()                         # a fresh snapshot (position, health...)
```

## 8. World

Positions are `x, y, z` numbers or a `Pos` (event.pos, player.pos, `Pos(0, 64, 0)`, `pos.up(2)`).

```python
world[0, 64, 0]                     # block id at a position: "minecraft:stone"
world[0, 64, 0] = "gold_block"      # set a block (your own blocks work too)
world.fill((0, 64, 0), (4, 68, 4), "glass")      # up to 32768 blocks
world.explode(event.pos, power=3, fire=False, break_blocks=True)
world.lightning(event.pos)
world.drop(ruby, event.pos, count=2)
zombie = world.spawn("zombie", 0, 64, 0)         # returns an Entity
world.particle("minecraft:flame", event.pos, count=10)
world.play_sound("minecraft:block.bell.use", event.pos, volume=1.0, pitch=1.0)
world.entities(event.pos, radius=8, type="zombie")
world.biome(event.pos)              # "minecraft:plains"
world.highest_y(10, 20)
world.is_day()
world.set_time("night")             # day, noon, sunset, night, midnight, sunrise or ticks
world.weather("rain", duration=6000)   # clear, rain, thunder
```

Every world call takes `dimension="minecraft:the_nether"` when you need another dimension.

## 9. Entities

`Entity` objects come from `world.spawn`, `world.entities` and events.

- Data: `type`, `uuid`, `name`, `pos`, `health`, `max_health`
- Actions: `kill()`, `teleport(x, y, z)`, `set_name("Bob")`, `refresh()`

## 10. Timers

```python
@every(seconds=5)
def heartbeat():
    say("five more seconds passed")

after(3, lambda: say("three seconds later"))

task = run_every(20, lambda tick: world.particle("flame", 0, 65, 0))   # every 20 ticks
task.cancel()
```

20 ticks = 1 second. `run_later(ticks, fn)` and `run_next_tick(fn)` exist too.

## 11. Storage

```python
storage["ores_mined"] = storage.get("ores_mined", 0) + 1
```

Values are shared by your handlers while the game runs. They are kept in memory: they reset when the server restarts.

## 12. Commands

```python
@command("gift")
def gift(ctx, target: Player, amount: int):
    target.give(ruby, amount)
```

This adds `/<project>:gift <target> <amount>` with tab completion. Argument types come from the
annotations: `int`, `float`, `bool`, `str`, `Player`. Commands require operator permission.

## 13. Server and chat

```python
say("Hello everyone")               # broadcast
run("time set day")                 # run any Minecraft command as the server
print("debug")                      # shows in the editor console (errors in handlers too)
```

## 14. main(ctx)

If your file defines `main(ctx)`, it runs after everything else on each Run.
`ctx.log("text")` writes to the console.

## 15. Console

The **Console** in the editor runs Python in your project: try `player("Dev").give("diamond", 3)`.
Variables survive between lines.

## 16. Older syntax

Projects written for the first API keep working:

```python
@block("ruby_ore", name="Ruby Ore", texture="minecraft:deepslate_gold_ore", hardness=4.0)
class RubyOre:
    pass

recipe_shaped("ruby_sword", [" R ", " R ", " S "],
              {"R": {"item": "ruby_rush:ruby"}, "S": {"item": "minecraft:stick"}})
recipe_smelting("ruby_rush:ruby", {"item": "ruby_rush:ruby_ore"}, xp=0.5)
loot_table("ruby_ore", [loot_entry("ruby_rush:ruby", count=2), loot_self()])

@on("block_break")
def mined(ctx):
    if ctx.block.id.endswith("ruby_ore"):
        players.send_message(ctx.player.name, "Ruby!")
```

Also still available: `item`, `recipe_shapeless`, `recipe_blasting`, `recipe_smoking`,
`recipe_campfire`, `recipe_stonecutting`, `recipe_smithing`, `tag`, `lang`, `sound`, `particle`,
`advancement`, `raw_json`, `tex()`, `snd()`, `mdl()`, `run_later`, `run_every`, `scheduler`, `cp`.

## 17. Limits

- Only `code/main.py` runs. Other `.py` files in the project are not imported.
- Blocks and items are slots registered at startup (256 of each), so content appears without a restart.
- Entities and custom effects can be declared but are not wired into the game yet.
- Python runs in a separate sandboxed process: no files, network or OS access.
- Handlers run after the game event; they cannot cancel it.
- `storage` is not saved across server restarts.
- Python windows (`@window`) are not available in this build.

## 18. Extensions: behaviour for items and blocks

An item or block starts with its looks (name, texture, stack size, hardness...). **Extensions** add what it does.
Attach them with `.extends(...)` (it returns the same item or block, so you can chain), or with the `extensions=[...]` argument:

```python
ruby_sword = Item("ruby_sword", "Ruby Sword", texture="ruby_sword.png").extends(
    Sword(tier="diamond", damage=8),          # mines cobwebs fast, hits for 8
    Durability(1200, repair_with=ruby),       # a bar that wears down; rubies repair it in an anvil
    Cooldown(2),                              # right click only every 2 seconds
    Tooltip("Forged in the deep", color="gold"))

ruby_ore = Block("ruby_ore", texture="ruby_ore.png", hardness=3, drops=(ruby, 2)).extends(
    MineableBy("pickaxe", tier="iron"),       # an iron pickaxe or better, otherwise nothing drops
    Flammable(chance=5, spread=20))
```

| Extension | On | What it adds |
| --- | --- | --- |
| `Sword`, `Pickaxe`, `Axe`, `Shovel`, `Hoe`, `Tool` | item | Mining speed on the right blocks, attack damage and speed, a durability bar and enchantability from the tier |
| `Durability` | item | A durability bar (and an anvil repair item) |
| `Weapon` | item | Attack damage, speed and knockback for any item |
| `Armor` | item | Armor points in a slot (right click or shift click equips it; no worn model is drawn) |
| `Fuel`, `Enchantable`, `Cooldown`, `Tooltip` | item | Furnace fuel, enchantment value, use cooldown, extra tooltip lines |
| `MineableBy`, `Drops` | block | Which tool (and tier) mines it, what it drops |
| `Redstone`, `Flammable`, `Friction`, `Piston` | block | Signal strength, fire behaviour, slipperiness, piston reaction |

Every parameter is described in the reference at the end of this page. A mistake is reported with the extension and the
parameter named, for example `Tool tier must be one of 'wood', 'stone', ... got 'ruby'`.

### Your own extensions

An extension is a class. Its constructor arguments are its settings, and methods named like a hook run for every item
or block it is attached to:

| Hook | On | When | `event` has |
| --- | --- | --- | --- |
| `on_use(event)` | item | right click with the item | `player`, `item`, `hand` |
| `on_hit(event)` | item | a player hits something holding it | `player`, `entity`, `item`, `damage` |
| `on_mine(event)` | item | a player breaks a block holding it | `player`, `item`, `block`, `pos` |
| `on_hold_tick(event)` | item | once a second while it is in the main hand | `player`, `item` |
| `on_interact(event)` | block | right click on the block | `player`, `block`, `item`, `pos` |
| `on_break(event)` | block | the block is broken | `player`, `block`, `pos` |
| `on_place(event)` | block | the block is placed | `player`, `block`, `pos` |
| `on_tick(event)` | block | every tick | `block`, `pos` |

```python
@extension
class LifeSteal(Extension):
    def __init__(self, hearts=1):
        self.hearts = hearts

    def on_hit(self, event):
        event.player.heal(self.hearts * 2)
        if event.damage > 12:
            event.damage = 12                 # change the damage of the hit
        # event.cancel() would stop the hit

    def configure(self, target):              # optional: change the item/block itself
        target.set("glow", True)

fang = Item("fang").extends(LifeSteal(hearts=2))
```

`event.cancel()` works in `on_hit`, `on_use`, `on_mine` and `on_break`; Python gets about 50 ms to answer, after which the game
carries on unchanged. A method named `on_...` that is not a hook of that kind (a typo, or `on_hit` on a block) is rejected with the list of valid hooks.

### Class form

```python
class RubyBlade(ItemType):
    id = "ruby_blade"
    name = "Ruby Blade"
    texture = "ruby_blade.png"
    extensions = [Sword("iron")]

    def on_mine(self, event):                 # hook methods work directly
        event.player.actionbar("Chip!")

shaped(RubyBlade, "R", "R", R=ruby)           # the class can be used wherever an id is expected
```

`BlockType` works the same for blocks.

<!-- reference:start (generated by gen_api_doc.py, do not edit) -->

# Reference: every function and what each parameter does

## Items and blocks

### `Item(id, name=None, texture=None, stack=64, glow=False, food=None, fuel=0, effect=None, extensions=(), tags=(), **extra) -> 'Content'`

Declares an item and returns it; the result can be used anywhere an item id is expected.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `id` |  | Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'. |
| `name` | `None` | Display name; default is the id in title case. |
| `texture` | `None` | PNG file name in assets/textures/ ('ruby.png'), a vanilla texture ('minecraft:item/diamond') or tex(...). |
| `stack` | `64` | Largest stack, 1 to 64 (default 64; tools and armor set 1 themselves). |
| `glow` | `False` | True draws the enchantment shimmer. |
| `food` | `None` | A number (hunger points) or Food(...). Makes the item edible. |
| `fuel` | `0` | Furnace burn time in seconds (0 = not a fuel). Same as .extends(Fuel(seconds)). |
| `effect` | `None` | Right click applies an effect: 'speed' or (name, level, seconds), level 1 = normal. |
| `extensions` | `()` | List of extensions (Sword(...), Durability(...), your own); same as .extends(...). |
| `tags` | `()` | Item tags to join: ['minecraft:swords', 'gems'] (a bare name is this project's tag). |
| `**extra` |  | Not accepted: any other attribute raises an error that lists the valid ones. |

Returns: The item (a string 'project:id' with .extends(...)).

### `Block(id, name=None, texture=None, hardness=2.0, resistance=None, light=0, tool=None, needs_tool=False, sound=None, drops=None, extensions=(), tags=(), **extra) -> 'Content'`

Declares a block and returns it; usable anywhere a block or item id is expected.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `id` |  | Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'. |
| `name` | `None` | Display name; default is the id in title case. |
| `texture` | `None` | PNG file name in assets/textures/, a vanilla texture ('minecraft:block/stone') or tex(...). |
| `hardness` | `2.0` | Mining time factor (stone 1.5, obsidian 50); -1 makes it unbreakable. Default 2.0. |
| `resistance` | `None` | Explosion resistance; default 6.0 (stone). |
| `light` | `0` | Light emitted, 0 to 15. |
| `tool` | `None` | Tool that mines it fastest: 'pickaxe', 'axe', 'shovel', 'hoe' or 'sword'. See MineableBy. |
| `needs_tool` | `False` | True: only drops anything when mined with the right tool (like ores). |
| `sound` | `None` | Sound type: 'stone', 'wood', 'grass', 'glass', 'gravel', 'sand', 'wool', 'metal', 'anvil' or 'snow'. |
| `drops` | `None` | What it drops: an item, a list, (item, count) / (item, count, weight), 'self' or [] for nothing. Default: the block itself. |
| `extensions` | `()` | List of extensions (MineableBy(...), Redstone(...), your own); same as .extends(...). |
| `tags` | `()` | Block tags to join: ['minecraft:mineable/pickaxe'] (a bare name is this project's tag). |
| `**extra` |  | Not accepted: any other attribute raises an error that lists the valid ones. |

Returns: The block.

### `Food(hunger=4, saturation=0.6, always_eat=False, fast=False)`

Food values for Item(food=...).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `hunger` | `4` | Hunger points restored (each is half a drumstick). |
| `saturation` | `0.6` | Saturation modifier; 0.6 is bread, 1.2 cooked meat. |
| `always_eat` | `False` | True lets players eat it with a full hunger bar. |
| `fast` | `False` | True eats in half the time (like dried kelp). |

Returns: A dict to pass as food=.

### `ItemType()`

Class form of Item(...): attributes id, name, texture, stack, glow, food, fuel, effect and extensions are the keyword arguments; methods named like hooks run directly.

### `BlockType()`

Class form of Block(...): attributes id, name, texture, hardness, resistance, light, tool, needs_tool, sound, drops and extensions are the keyword arguments.

## Extensions

### `Content.extends(*extensions) -> 'Content'`

Adds behaviour to an item or block; returns the same item or block so calls chain.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*extensions` |  | Extension objects, or lists of them. |

Returns: The item or block.

### `Extension()`

Base class of every extension, built in or your own. Methods named like a hook run for the item or block it is attached to: items on_use, on_hit, on_mine, on_hold_tick; blocks on_interact, on_break, on_place, on_tick. Override configure(self, target) to change the target (target.set('glow', True)). Hooks receive one event; event.cancel() stops block_break, item_use, item_hit and item_mine, event.damage = x changes a hit.

### `extension(cls)`

Marks your class as an extension: @extension class Vampiric(Extension): ...

| Parameter | Default | Meaning |
| --- | --- | --- |
| `cls` |  | The class. |

Returns: The class.

### `Tool(kind, tier='iron', speed=None, damage=None, attack_speed=None)`

Makes an item mine blocks and hit harder like a vanilla tool.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `kind` |  | 'pickaxe', 'axe', 'shovel', 'hoe' or 'sword'. |
| `tier` | `'iron'` | 'wood', 'stone', 'iron' (default), 'gold', 'diamond' or 'netherite': mining level, speed, durability and damage defaults. |
| `speed` | `None` | Mining speed; default from the tier (wood 2, stone 4, iron 6, gold 12, diamond 8, netherite 9). |
| `damage` | `None` | Damage per hit as the tooltip shows it (a diamond sword is 7). |
| `attack_speed` | `None` | Hits per second (sword 1.6, axe 1.0, pickaxe 1.2). |

### `Sword(tier='iron', speed=None, damage=None, attack_speed=None)`

A sword (Tool with kind 'sword').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `tier` | `'iron'` | Material tier. |
| `speed` | `None` | Mining speed. |
| `damage` | `None` | Damage per hit. |
| `attack_speed` | `None` | Hits per second. |

### `Pickaxe(tier='iron', speed=None, damage=None, attack_speed=None)`

A pickaxe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `tier` | `'iron'` | Material tier. |
| `speed` | `None` | Mining speed. |
| `damage` | `None` | Damage per hit. |
| `attack_speed` | `None` | Hits per second. |

### `Axe(tier='iron', speed=None, damage=None, attack_speed=None)`

An axe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `tier` | `'iron'` | Material tier. |
| `speed` | `None` | Mining speed. |
| `damage` | `None` | Damage per hit. |
| `attack_speed` | `None` | Hits per second. |

### `Shovel(tier='iron', speed=None, damage=None, attack_speed=None)`

A shovel.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `tier` | `'iron'` | Material tier. |
| `speed` | `None` | Mining speed. |
| `damage` | `None` | Damage per hit. |
| `attack_speed` | `None` | Hits per second. |

### `Hoe(tier='iron', speed=None, damage=None, attack_speed=None)`

A hoe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `tier` | `'iron'` | Material tier. |
| `speed` | `None` | Mining speed. |
| `damage` | `None` | Damage per hit. |
| `attack_speed` | `None` | Hits per second. |

### `Durability(uses=250, repair_with=None)`

A durability bar: 1 use is lost per block mined or entity hit; the item breaks at 0.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `uses` | `250` | Number of uses, 1 to 32767. |
| `repair_with` | `None` | Item that repairs it in an anvil (optional). |

### `Weapon(damage=4.0, attack_speed=1.6, knockback=0.0)`

Attack values for any item without making it a tool.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `damage` | `4.0` | Damage per hit as the tooltip shows it (a fist is 1). |
| `attack_speed` | `1.6` | Hits per second. |
| `knockback` | `0.0` | Extra knockback, 0 to 5. |

### `Fuel(seconds)`

Lets a furnace burn the item.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `seconds` |  | Burn time in seconds, 1 to 100000 (200 smelts 20 items). |

### `Armor(slot='chest', defense=3, toughness=0.0, knockback_resistance=0.0)`

Makes an item wearable in an armor slot (no worn model is drawn).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `slot` | `'chest'` | 'head', 'chest', 'legs' or 'feet'. |
| `defense` | `3` | Armor points, 0 to 30. |
| `toughness` | `0.0` | Armor toughness, 0 to 20. |
| `knockback_resistance` | `0.0` | 0 to 1. |

### `Enchantable(value=15)`

How good enchantments from an enchanting table are.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `value` | `15` | 0 (none) to 50; gold armor is 25. |

### `Cooldown(seconds=1.0)`

The item can only be used again after a pause, shown as a bar.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `seconds` | `1.0` | 0.05 to 3600. |

### `Tooltip(*lines, color='gray')`

Extra lines in the item tooltip.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*lines` |  | 1 to 10 texts of at most 120 characters. |
| `color` | `'gray'` | One of the 16 chat colors ('gray', 'gold', 'aqua'...). |

### `MineableBy(tool='pickaxe', tier=None, required=True)`

Which tool mines a block.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `tool` | `'pickaxe'` | 'pickaxe', 'axe', 'shovel' or 'hoe'. |
| `tier` | `None` | Lowest tool tier that works ('wood' to 'netherite'; None = any). |
| `required` | `True` | True (default): nothing drops without the right tool. |

### `Drops(*drops)`

What a block drops when mined.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*drops` |  | Items, (item, count) or (item, count, weight) tuples, or 'self'; none = nothing drops. |

### `Redstone(power=15)`

The block emits a redstone signal.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `power` | `15` | Signal strength 0 to 15. |

### `Flammable(chance=5, spread=20)`

Fire can burn the block.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `chance` | `5` | How easily it catches fire, 0 to 300 (wood 5, leaves 30). |
| `spread` | `20` | How fast fire spreads over it, 0 to 300 (wood 20, leaves 60). |

### `Friction(value=0.98)`

How slippery the block is.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `value` | `0.98` | 0.1 to 1.0 (normal 0.6, ice 0.98). |

### `Piston(reaction='normal')`

What pistons do to the block.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `reaction` | `'normal'` | 'normal', 'destroy', 'block', 'ignore' or 'push_only'. |

### `Attributes(attribute, amount, operation='add', slot='mainhand')`

Attribute modifiers on an item (any attribute of the game or of a mod): Attributes('irons_spellbooks:fire_spell_power', 0.1, slot='mainhand').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `attribute` |  | Attribute id; the IDE completes the ones of the installed mods. |
| `amount` |  | Number added (or the factor, with a multiply operation). |
| `operation` | `'add'` | 'add' (default), 'multiply_base' or 'multiply_total'. |
| `slot` | `'mainhand'` | 'mainhand' (default), 'offhand', 'head', 'chest', 'legs', 'feet', 'hand', 'armor', 'body' or 'any'. |

## Recipes

### `shaped(result, *rows, count=1, **keys)`

Shaped crafting recipe: shaped(sword, ' R ', ' R ', ' S ', R=ruby, S='stick').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Item crafted. |
| `*rows` |  | 1 to 3 strings of up to 3 characters; a space is an empty slot. |
| `count` | `1` | How many items the recipe makes. |
| `**keys` |  | One keyword per letter: the ingredient it stands for. |

### `shapeless(result, *ingredients, count=1)`

Shapeless crafting recipe: shapeless(ruby, 'diamond', 'redstone').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Item crafted. |
| `*ingredients` |  | Up to 9 ingredients, order does not matter. |
| `count` | `1` | How many items the recipe makes. |

### `smelt(ingredient, result, xp=0.1, seconds=10, using='furnace')`

Furnace-style recipe: smelt(ruby_ore, ruby).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ingredient` |  | Item that is cooked. |
| `result` |  | Item that comes out. |
| `xp` | `0.1` | Experience given (default 0.1). |
| `seconds` | `10` | Cooking time in seconds (default 10). |
| `using` | `'furnace'` | 'furnace' (default), 'blast_furnace', 'smoker' or 'campfire'. |

### `stonecut(ingredient, result, count=1)`

Stonecutter recipe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ingredient` |  | Item put in. |
| `result` |  | Item produced. |
| `count` | `1` | Items produced per input. |

### `recipe(type, id=None, **fields)`

A recipe of another mod's type: recipe('create:mixing', ingredients=[...], results=[...]).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `type` |  | Recipe type id 'mod:type' (the IDE lists the types of the installed mods). |
| `id` | `None` | File name of the recipe (optional). |
| `**fields` |  | The mod's own recipe fields as keywords; the IDE shows the ones it knows. |

### `recipe_shaped(result, pattern, keys, category='misc', **extra)`

Low-level shaped recipe with the raw pattern.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Result item id or dict. |
| `pattern` |  | List of up to 3 strings. |
| `keys` |  | Dict letter -> ingredient. |
| `category` | `'misc'` | Recipe book category: 'building', 'redstone', 'equipment' or 'misc'. |
| `**extra` |  | Further fields written into the JSON as they are. |

### `recipe_shapeless(result, ingredients, category='misc', **extra)`

Low-level shapeless recipe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Result item id or dict. |
| `ingredients` |  | List of ingredients. |
| `category` | `'misc'` | Recipe book category. |
| `**extra` |  | Further fields written into the JSON as they are. |

### `recipe_smelting(result, ingredient, xp=0.0, cook_time=200, **extra)`

Furnace recipe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Result. |
| `ingredient` |  | Input. |
| `xp` | `0.0` | Experience given. |
| `cook_time` | `200` | Ticks to cook (200 = 10 s). |
| `**extra` |  | Further fields written into the JSON as they are. |

### `recipe_blasting(result, ingredient, xp=0.0, cook_time=100, **extra)`

Blast furnace recipe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Result. |
| `ingredient` |  | Input. |
| `xp` | `0.0` | Experience given. |
| `cook_time` | `100` | Ticks to cook (100 = 5 s). |
| `**extra` |  | Further fields written into the JSON as they are. |

### `recipe_smoking(result, ingredient, xp=0.0, cook_time=100, **extra)`

Smoker recipe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Result. |
| `ingredient` |  | Input. |
| `xp` | `0.0` | Experience given. |
| `cook_time` | `100` | Ticks to cook (100 = 5 s). |
| `**extra` |  | Further fields written into the JSON as they are. |

### `recipe_campfire(result, ingredient, cook_time=600, **extra)`

Campfire recipe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Result. |
| `ingredient` |  | Input. |
| `cook_time` | `600` | Ticks to cook (600 = 30 s). |
| `**extra` |  | Further fields written into the JSON as they are. |

### `recipe_stonecutting(result, ingredient, count=1, **extra)`

Stonecutter recipe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Result. |
| `ingredient` |  | Input. |
| `count` | `1` | Items produced. |
| `**extra` |  | Further fields written into the JSON as they are. |

### `recipe_smithing(result, template, base, addition, **extra)`

Smithing table recipe.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `result` |  | Result. |
| `template` |  | Smithing template item. |
| `base` |  | Item that is upgraded. |
| `addition` |  | Material added. |
| `**extra` |  | Further fields written into the JSON as they are. |

## Tags, names, loot and data

### `tag(ident, values, replace=False, type='item')`

Declares a tag, a named group of items or blocks.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ident` |  | Tag id without namespace ('ruby_gems' becomes <project>:ruby_gems). |
| `values` |  | Items/blocks or '#other_tag' entries to include. |
| `replace` | `False` | True drops the entries of a tag with the same id from other packs. |
| `type` | `'item'` | 'item' (default), 'block', 'entity_type', 'fluid' or 'game_event'. |

### `lang(key, value)`

Adds a translation: lang('itemGroup.mypack', 'My Pack').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `key` |  | Translation key. |
| `value` |  | English text. |

### `loot_table(ident, entries, rolls=1, **extra)`

Declares a loot table (what chests, blocks or mobs drop).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ident` |  | Table id; a block's own id replaces what that block drops. |
| `entries` |  | List of loot_entry(...) or 'self'. |
| `rolls` | `1` | How many entries are picked (default 1). |
| `**extra` |  | Further fields written into the JSON as they are. |

### `loot_entry(item, count=1, weight=1, **kwargs)`

One possible drop of a loot table.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `item` |  | Item to drop. |
| `count` | `1` | Stack size dropped (default 1). |
| `weight` | `1` | Relative chance against the other entries (default 1). |
| `**kwargs` |  | Further fields written into the JSON as they are. |

Returns: A dict for loot_table.

### `loot_self()`

Marks a loot entry that drops the block itself.

### `sound(event, **kwargs)`

Declares a sound event (sounds.json).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `event` |  | Sound event id ('ruby_chime'). |
| `**kwargs` |  | Further fields written into the JSON as they are. |

### `particle(ident, **kwargs)`

Declares a particle definition.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ident` |  | Particle id. |
| `**kwargs` |  | Further fields written into the JSON as they are. |

### `advancement(ident, **kwargs)`

Declares an advancement; keywords are the fields of the advancement JSON.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ident` |  | Advancement id. |
| `**kwargs` |  | Further fields written into the JSON as they are. |

### `raw_json(path, data, namespace=None)`

Writes any data pack JSON file as is (escape hatch; version specific).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `path` |  | Path inside the data pack, for example 'tags/item/foo.json'. |
| `data` |  | A dict or list to write as JSON. |
| `namespace` | `None` | Write to data/<namespace>/ instead of your project's own folder (for example 'minecraft'). |

### `tex(ref)`

Reference to a texture by name.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ref` |  | Texture name or id. |

Returns: A texture reference.

### `snd(ref)`

Reference to a sound by name.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ref` |  | Sound event id. |

Returns: A sound reference.

### `mdl(ref)`

Reference to a model by name.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ref` |  | Model id. |

Returns: A model reference.

## Events, commands and timers

### `on(event, every=None, priority='normal', **filters)`

Runs a function when something happens: @on('block_break'), @on.block_break(block=ruby_ore).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `event` |  | Event name: block_break, block_place, block_interact, block_tick, item_use, item_hit, item_mine, item_hold_tick, item_craft, item_pickup, player_join, player_leave, player_respawn, player_death, entity_death, entity_hurt, chat, or a command_<name> event. |
| `every` | `None` | Run at most every this many ticks (optional). |
| `priority` | `'normal'` | 'normal' (default); reserved for ordering. |
| `**filters` |  | block=, item= or entity= : only for that block/item/entity (ids or Block/Item objects). |

### `every(ticks=None, seconds=None)`

Runs a function again and again: @every(seconds=5) or @every(100).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ticks` | `None` | Interval in ticks (20 = one second). |
| `seconds` | `None` | Interval in seconds (wins over ticks). |

### `after(seconds, fn=None)`

Runs a function once after a delay: after(3, fn) or @after(3).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `seconds` |  | Delay in seconds. |
| `fn` | `None` | Function to run (omit to use as a decorator). |

Returns: A Task that can be cancelled.

### `run_later(ticks, fn)`

Runs a function after a number of ticks.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ticks` |  | Delay in ticks. |
| `fn` |  | Function to run. |

Returns: A Task.

### `run_every(ticks, fn)`

Runs a function every N ticks until cancelled.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ticks` |  | Interval in ticks. |
| `fn` |  | Function; receives the tick count. |

Returns: A Task (task.cancel() stops it).

### `run_next_tick(fn)`

Runs a function on the next game tick.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `fn` |  | Function to run. |

Returns: A Task.

### `command(name, **params)`

Registers /<project>:<name>. Arguments come from the function signature (int, float, bool, str, Player).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Command name. |
| `**params` |  | Explicit argument types instead of annotations (optional). |

### `on_load(fn)`

Runs the decorated function when the project or module is loaded.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `fn` |  | The function. |

### `on_unload(fn)`

Runs the decorated function when the project or module is unloaded.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `fn` |  | The function. |

### `requires_restart(flag=True)`

Declares that loading this module needs a full game restart.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `flag` | `True` | True (default) or False. |

### `core()`

Marks this module as 'core': it may need a full game restart.

### `has(capability)`

True when the game supports a capability: has('ui.window').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `capability` |  | Capability name. |

## Chat and server

### `say(*message)`

Broadcasts a chat message to everyone.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*message` |  | Texts; non-text values are converted and joined with spaces. |

### `run(command_text)`

Runs a Minecraft command as the server: run('time set day').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `command_text` |  | Command without the leading slash. |

### `player(name) -> 'Player'`

The online player with this name, or None.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Player name. |

Returns: A Player or None.

### `server.broadcast(message)`

Chat message to everyone.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `message` |  | Text. |

### `server.run_command(command)`

Runs a command as the server.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `command` |  | Without the leading slash. |

### `server.log(message, level='info')`

Writes to the server log and the IDE console.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `message` |  | Text. |
| `level` | `'info'` | 'info' (default), 'warn' or 'error'. |

### `players.all()`

Names of every online player.

Returns: list of str

### `players.online() -> 'list[Player]'`

Every online player.

Returns: list of Player

### `players.get(name)`

Snapshot of an online player.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Player name. |

Returns: A Player or None.

### `players.give(name, item, count=1)`

Gives items to a player by name.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Player name. |
| `item` |  | Item or id. |
| `count` | `1` | How many. |

### `players.send_message(name, message)`

Chat message to one player by name.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Player name. |
| `message` |  | Text. |

### `players.teleport(name, x, y, z, dimension='minecraft:overworld')`

Teleports a player by name.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Player name. |
| `x` |  | X. |
| `y` |  | Y. |
| `z` |  | Z. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

## Player

### `Player.say(*message)`

Sends a chat message to this player only.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*message` |  | Texts, joined with spaces. |

### `Player.send_message(*message)`

Same as say.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*message` |  | Texts, joined with spaces. |

### `Player.actionbar(text)`

Shows text above the hotbar.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `text` |  | The text. |

### `Player.title(text, subtitle=None, seconds=3)`

Shows a big title in the middle of the screen.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `text` |  | Title. |
| `subtitle` | `None` | Smaller line under it (optional). |
| `seconds` | `3` | How long it stays (default 3). |

### `Player.give(item, count=1)`

Gives items; what does not fit is dropped.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `item` |  | Item or id. |
| `count` | `1` | How many (default 1). |

### `Player.take(item, count=1)`

Removes items from the inventory.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `item` |  | Item or id. |
| `count` | `1` | How many (default 1). |

Returns: How many were taken.

### `Player.has(item, count=1)`

True when the inventory holds enough of an item.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `item` |  | Item or id. |
| `count` | `1` | At least this many. |

Returns: bool

### `Player.inventory()`

The inventory contents.

Returns: A list of stacks.

### `Player.heal(amount=None)`

Restores health.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `amount` | `None` | Health points (2 = one heart); omit for full health. |

### `Player.set_health(health)`

Sets health.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `health` |  | Health points, 20 is full. |

### `Player.feed(food=20, saturation=1.0)`

Restores hunger.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `food` | `20` | Hunger points (default 20 = full). |
| `saturation` | `1.0` | Saturation (default 1.0). |

### `Player.effect(name, seconds=10, level=1, particles=True)`

Applies a potion effect: player.effect('speed', seconds=30, level=2).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Effect id ('speed', 'minecraft:regeneration'). |
| `seconds` | `10` | Duration (default 10). |
| `level` | `1` | 1 = normal, 2 = II ... (default 1). |
| `particles` | `True` | False hides the swirl. |

### `Player.clear_effects()`

Removes every effect.

### `Player.add_xp(levels=0, points=0)`

Gives experience.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `levels` | `0` | Whole levels. |
| `points` | `0` | Experience points. |

### `Player.teleport(x, y=None, z=None, dimension=None)`

Moves the player.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X (or a Pos). |
| `y` | `None` | Y. |
| `z` | `None` | Z. |
| `dimension` | `None` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

### `Player.play_sound(sound, volume=1.0, pitch=1.0)`

Plays a sound only this player hears.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `sound` |  | Sound event id ('minecraft:entity.blaze.shoot'). |
| `volume` | `1.0` | 1.0 = normal. |
| `pitch` | `1.0` | 1.0 = normal. |

### `Player.set_gamemode(mode)`

Sets the game mode.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `mode` |  | 'survival', 'creative', 'adventure' or 'spectator'. |

### `Player.set_fire(seconds=3)`

Sets the player on fire.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `seconds` | `3` | Burn time (default 3). |

### `Player.kill()`

Kills the player.

### `Player.refresh() -> 'Player'`

A fresh snapshot of this player (position, health...).

Returns: A Player.

### `Player.named(name) -> 'Player'`

The online player with this name, or None.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Player name. |

Returns: A Player or None.

## Entity

### `Entity.kill()`

Kills the entity.

### `Entity.teleport(x, y=None, z=None)`

Moves the entity.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X (or a Pos). |
| `y` | `None` | Y. |
| `z` | `None` | Z. |

### `Entity.set_name(name)`

Sets its custom name.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | The name. |

### `Entity.refresh() -> 'Entity'`

A fresh snapshot (None if it is gone).

Returns: An Entity or None.

## Position

### `Pos(x, y, z)`

An (x, y, z) position; supports pos + (0, 1, 0), pos.up(2).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X. |
| `y` |  | Y. |
| `z` |  | Z. |

### `Pos.up(n=1) -> 'Pos'`

The position n blocks higher.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `n` | `1` | Blocks (default 1). |

Returns: A Pos.

### `Pos.down(n=1) -> 'Pos'`

The position n blocks lower.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `n` | `1` | Blocks (default 1). |

Returns: A Pos.

### `Pos.block() -> 'Pos'`

Integer block coordinates.

Returns: A Pos with whole numbers.

## World

### `world.get_block(x, y=None, z=None, dimension='minecraft:overworld')`

The block id at a position.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: str, for example 'minecraft:stone'

### `world.set_block(x, y, z=None, block=None, dimension='minecraft:overworld', replace=True)`

Places a block: world.set_block(x, y, z, 'stone') or world.set_block(pos, 'stone').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` |  | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `block` | `None` | Block id or Block object. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |
| `replace` | `True` | False only places into air. |

### `world.fill(start, end, block, dimension='minecraft:overworld')`

Fills the box between two corners (at most 32768 blocks).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `start` |  | First corner (Pos or tuple). |
| `end` |  | Opposite corner. |
| `block` |  | Block id. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: Blocks changed.

### `world.blocks_in_box(x, y, z, radius=0, dimension='minecraft:overworld')`

Blocks around a position.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` |  | Y coordinate (height). |
| `z` |  | Z coordinate. |
| `radius` | `0` | Distance in blocks in each direction. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: list

### `world.get_block_entities(x, y, z, radius=0, dimension='minecraft:overworld')`

Block entities around a position.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` |  | Y coordinate (height). |
| `z` |  | Z coordinate. |
| `radius` | `0` | Distance in blocks. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: list

### `world.entities(x, y=None, z=None, radius=8, type=None, dimension='minecraft:overworld') -> 'list[Entity]'`

Entities near a position.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `radius` | `8` | Distance in blocks (default 8). |
| `type` | `None` | Only this entity type ('minecraft:zombie'). |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: list of Entity

### `world.entities_in_box(x, y, z, radius=8, dimension='minecraft:overworld')`

Entities in a cube around a position.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` |  | Y coordinate (height). |
| `z` |  | Z coordinate. |
| `radius` | `8` | Half the cube size. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: list

### `world.spawn(entity, x, y=None, z=None, dimension='minecraft:overworld') -> 'Entity'`

Spawns an entity ('zombie', 'minecraft:pig').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `entity` |  | Entity type id. |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: The Entity.

### `world.drop(item, x, y=None, z=None, count=1, dimension='minecraft:overworld')`

Drops an item stack on the ground.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `item` |  | Item or id. |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `count` | `1` | Stack size. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

### `world.explode(x, y=None, z=None, power=3, fire=False, break_blocks=True, dimension='minecraft:overworld')`

Creates an explosion.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `power` | `3` | Strength (TNT is 4, default 3). |
| `fire` | `False` | True sets fires. |
| `break_blocks` | `True` | False only damages. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

### `world.lightning(x, y=None, z=None, dimension='minecraft:overworld')`

Strikes lightning.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

### `world.particle(particle, x, y=None, z=None, count=1, dimension='minecraft:overworld')`

Spawns particles.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `particle` |  | Particle id ('minecraft:flame'). |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `count` | `1` | How many. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

### `world.play_sound(sound, x, y=None, z=None, volume=1.0, pitch=1.0, dimension='minecraft:overworld')`

Plays a sound at a position.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `sound` |  | Sound event id. |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `volume` | `1.0` | 1.0 = normal. |
| `pitch` | `1.0` | 1.0 = normal. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

### `world.biome(x, y=None, z=None, dimension='minecraft:overworld')`

Biome id at a position.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: str

### `world.highest_y(x, z, dimension='minecraft:overworld')`

Height of the highest solid block in a column.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X. |
| `z` |  | Z. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: int

### `world.info(x, y=None, z=None, dimension='minecraft:overworld')`

Biome, highest_y, light, is_day, raining, thundering and time at a position.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `x` |  | X coordinate (or a Pos / (x, y, z) tuple when y and z are left out). |
| `y` | `None` | Y coordinate (height). |
| `z` | `None` | Z coordinate. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: dict

### `world.time(dimension='minecraft:overworld')`

Time of day in ticks (0 to 24000).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: int

### `world.set_time(ticks, dimension='minecraft:overworld')`

Sets the time of day.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ticks` |  | 0 = sunrise, 6000 = noon, 13000 = night. |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

### `world.is_day(dimension='minecraft:overworld')`

True during the day.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: bool

### `world.weather(weather='clear', duration=6000, dimension='minecraft:overworld')`

Sets the weather.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `weather` | `'clear'` | 'clear', 'rain' or 'thunder'. |
| `duration` | `6000` | Ticks (default 6000). |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

## Storage and scheduler

### `storage.get(key, default=None, scope='world')`

Reads a saved value (values live while the game runs, shared by all handlers).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `key` |  | Name. |
| `default` | `None` | Returned when missing. |
| `scope` | `'world'` | A name that keeps groups of values apart (default 'world'). |

Returns: the value

### `storage.set(key, value, scope='world')`

Saves a value. storage['key'] = value does the same in the default scope.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `key` |  | Name. |
| `value` |  | Number, text, bool, list or dict. |
| `scope` | `'world'` | Group name (default 'world'). |

Returns: the value

### `storage.clear(key=None, scope='world')`

Deletes one value, or everything of the scope.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `key` | `None` | Name; omit to clear the whole scope. |
| `scope` | `'world'` | Group name (default 'world'). |

### `scheduler.after(seconds, fn)`

Runs fn once after a delay.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `seconds` |  | Delay. |
| `fn` |  | Function. |

Returns: A Task.

### `scheduler.every_seconds(seconds, fn)`

Runs fn repeatedly.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `seconds` |  | Interval. |
| `fn` |  | Function. |

Returns: A Task.

### `scheduler.run_every(ticks, fn)`

Runs fn every N ticks.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ticks` |  | Interval. |
| `fn` |  | Function. |

Returns: A Task.

### `scheduler.run_later(ticks, fn)`

Runs fn after N ticks.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ticks` |  | Delay. |
| `fn` |  | Function. |

Returns: A Task.

### `scheduler.run_next_tick(fn)`

Runs fn on the next tick.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `fn` |  | Function. |

Returns: A Task.

## Windows

### `window(ident, title=None, size=None, resizable=False)`

Registers a Window subclass: @window('counter', title='Counter', size=(260, 170)).

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ident` |  | Window id used by ui.open. |
| `title` | `None` | Title bar text. |
| `size` | `None` | (width, height) in pixels. |
| `resizable` | `False` | True lets the player resize it. |

### `action(name)`

Names a function that buttons of ui/*.json windows can call: @action('save').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Action name used in on_click. |

### `Window.build(ctx)`

Returns the widgets of the window. Called again after every callback.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ctx` |  | Window context: ctx.player, ctx.values, ctx.close. |

Returns: A widget.

### `Window.state(default)`

A value remembered between redraws.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `default` |  | Initial value. |

Returns: A holder with .value.

### `ui.open(player, window_id)`

Opens a window for a player.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `player` |  | Player or name. |
| `window_id` |  | Id of a @window class or ui/<id>.json. |

### `ui.close(player, window_id=None)`

Closes the window.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `player` |  | Player or name. |
| `window_id` | `None` | Which one; omit to close any. |

### `ui.windows()`

Ids of the registered windows.

Returns: list of str

### `Label(text, **p)`

Text.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `text` |  | The text. |
| `**p` |  | style='title'\|'headline'\|'text', color='#rrggbb', id. |

Returns: A widget.

### `Button(text, on_click=None, **p)`

A button.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `text` |  | Caption. |
| `on_click` | `None` | An @action name or a function (called as fn(event)). |
| `**p` |  | style='text' for a flat button, id. |

Returns: A widget.

### `TextInput(value='', **p)`

One-line text field.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `value` | `''` | Initial text. |
| `**p` |  | id (read it with ctx.values[id]), hint, on_change, on_submit. |

Returns: A widget.

### `TextArea(value='', **p)`

Multi-line text field.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `value` | `''` | Initial text. |
| `**p` |  | id, hint, on_change. |

Returns: A widget.

### `Checkbox(label='', checked=False, **p)`

A check box.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `label` | `''` | Caption. |
| `checked` | `False` | Initial state. |
| `**p` |  | id, on_change. |

Returns: A widget.

### `Slider(value=0, min=0, max=100, **p)`

A slider.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `value` | `0` | Initial value. |
| `min` | `0` | Lowest value. |
| `max` | `100` | Highest value. |
| `**p` |  | id, on_change. |

Returns: A widget.

### `Dropdown(options=None, selected=0, **p)`

A drop-down list.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `options` | `None` | List of texts. |
| `selected` | `0` | Index of the chosen one. |
| `**p` |  | id, on_select. |

Returns: A widget.

### `ListView(items=None, **p)`

A list of texts.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `items` | `None` | List of texts. |
| `**p` |  | id, on_select. |

Returns: A widget.

### `ProgressBar(value=0, **p)`

A progress bar.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `value` | `0` | Current value. |
| `**p` |  | max (default 100), id. |

Returns: A widget.

### `Image(source='', **p)`

A picture.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `source` | `''` | Texture id or project asset. |
| `**p` |  | id. |

Returns: A widget.

### `ItemIcon(item='', **p)`

The icon of an item.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `item` | `''` | Item id. |
| `**p` |  | id. |

Returns: A widget.

### `VBox(*children, **p)`

Stacks children top to bottom.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*children` |  | Widgets. |
| `**p` |  | spacing (pixels between), padding. |

Returns: A widget.

### `HBox(*children, **p)`

Places children side by side.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*children` |  | Widgets. |
| `**p` |  | spacing, padding. |

Returns: A widget.

### `Grid(*children, **p)`

Places children in a grid.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*children` |  | Widgets. |
| `**p` |  | columns, spacing, padding. |

Returns: A widget.

### `TabView(*children, **p)`

Tabs; each child is one tab.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*children` |  | Widgets. |
| `**p` |  | selected (index). |

Returns: A widget.

### `ScrollView(*children, **p)`

A scrolling area.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `*children` |  | Widgets. |
| `**p` |  | padding. |

Returns: A widget.

## Java bridge

### `java.available()`

True when the host allows the bridge (config javaBridge.mode is not OFF).

Returns: bool

### `java.cls(name)`

The Java class with this full name.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Full class name, for example 'net.minecraft.world.level.block.Blocks'. |

Returns: A JavaClass: .FIELD, .method(...), Class(...) builds an object.

### `java.new(name, *args)`

Builds a Java object.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `name` |  | Full class name. |
| `*args` |  | Constructor arguments. |

Returns: A JavaObject.

### `java.on(event_class, priority='normal', receive_cancelled=False)`

Handles any NeoForge event: @java.on('net.neoforged...LivingIncomingDamageEvent').

| Parameter | Default | Meaning |
| --- | --- | --- |
| `event_class` |  | Event class name or JavaClass. |
| `priority` | `'normal'` | 'highest', 'high', 'normal', 'low' or 'lowest'. |
| `receive_cancelled` | `False` | True also receives events other handlers cancelled. |

### `java.items(obj)`

A Java list, set, map (as pairs), array or stream as a Python list.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `obj` |  | The Java object. |

Returns: list

### `java.members(obj)`

Public fields and methods of a Java object or class.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `obj` |  | The Java object or class. |

Returns: dict

### `java.mods()`

The loaded mods.

Returns: list of {id, name, version}

### `java.server()`

The MinecraftServer.

Returns: A JavaObject.

### `java.level(dimension='minecraft:overworld')`

A ServerLevel.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `dimension` | `'minecraft:overworld'` | Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end'). |

Returns: A JavaObject.

### `java.entity(uuid)`

The live entity or player with this uuid.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `uuid` |  | The uuid text. |

Returns: A JavaObject.

<!-- reference:end -->
