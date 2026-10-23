# Dust Works

Stone dusts that every mod understands, and a Grinder that makes them.

- Module id: `dustworks`
- Works on: client and server (textures and the window need the client; the grinding runs on the server)
- CraftPy Python API: 2.12
- Needs: nothing but CraftPy; asks for no permissions

## How to use it

1. Craft a **Grinder**: iron ingots around a furnace, stone below (any mod's iron works: the recipe asks for `#c:ingots/iron`).
   Operators can get one to try with `/dustworks:kit <player>`.
2. Place it and right click it. Put **diorite, granite or andesite** (polished too) in the top slot and **coal or charcoal**
   in the slot below the flame. Every 4 seconds a stone becomes two dusts. A cable of any energy mod can power it instead
   of coal: it takes up to 400 FE a tick from any side.
3. Four dusts in a square craft the stone back. The recipe takes **any** dust of that stone, from this project or another mod.
4. `/dustworks:stats` (every player) tells how much your grinders ground and the whole world. `/dustworks:reset`
   (operators) starts the statistics again.

## How it is made

| Part | Where | What it shows |
| --- | --- | --- |
| The dusts | `Item(..., tags=["c:dusts/diorite", "c:dusts"])` in `code/main.py` | shared `c:` tags: other mods find, accept and craft with these items |
| Recipes | `shaped("minecraft:diorite", "DD", "DD", D="#c:dusts/diorite")`, the grinder's `#c:ingots/iron` | a tag as an ingredient takes the item of every mod in it |
| The Grinder | `Block(...).extends(EnergyStorage(...), Inventory(input=1, fuel=1, output=1, accepts=...), Screen(layout="grinder"))` | NeoForge energy and item storages that cables, pipes and hoppers of every mod use; `accepts` takes `#minecraft:coals` |
| Its window | `ui/grinder.json`, made with **Tools > New UI window > Minecraft-style window** | slots, a flame bound to `value:burn`, an arrow bound to `value:progress`, the energy bar, a label `{ground} ground` |
| The logic | `@every(seconds=1)` in `code/main.py`, rules in `code/grinding.py` | Python decides what a machine does; `world.machine`, `machine_slot`, `machine_update`, `machine_set` |
| Tests | `code/test_grinding.py` | the rules are tested apart from the game (**Run tests**, `Ctrl+Shift+F10`) |
| Statistics | `storage` scope `stats`, places in `grinders` | values saved with the world, kept apart per project; `/craftpy backup create dustworks` copies them |
| Commands | `@command("stats", level=0)`, `@command("reset", level=2)`, `@command("kit", level=2)` | every command says who may run it |

The grinders' places are kept in `storage` when they are placed and broken, so after a restart the timer finds them again.
A grinder whose chunk is not loaded is skipped until someone comes near.
