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
