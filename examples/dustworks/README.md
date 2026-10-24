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
   in the slot below the flame, or pour **lava** into its tank (a bucket in the slot next to the gauge, or any mod's pipe).
   Every 4 seconds a stone becomes two dusts, and a quarter of the times one more; the dusts fill two output slots. A coal
   grinds 7 or 8 stones and a bucket of lava 12.5 times that, like in a furnace. A cable of any energy mod can power it
   instead: it takes up to 400 FE a tick from any side.
3. With JEI or EMI installed, look the Grinder up: its page lists what it grinds, with the energy and time.
4. Four dusts in a square craft the stone back. The recipe takes **any** dust of that stone, from this project or another mod.
5. Craft an **Adventurer's Pack** (string, leather, a chest and wool). Right click with it: 54 slots, two liquid tanks of
   8000 mB and an energy store of 40 000 FE, all kept inside the item. **Shift + right click a block** sets it down as
   itself (right click it there for the same contents; hit it, or shift + right click with an empty hand, to take it back
   with everything in it). **Starbrine** (a bucket, prismarine crystals, an amethyst shard and glowstone dust) is a glowing
   liquid: Night Vision and Slow Falling while you swim in it. Pour it into the pack, press **Distill**: 100 mB becomes 4000 FE
   for the batteries and tools of any mod in the lower right slots.
6. `/dustworks:stats` (every player) tells how much your grinders ground and the whole world. `/dustworks:reset`
   (operators) starts the statistics again.

## How it is made

| Part | Where | What it shows |
| --- | --- | --- |
| The dusts | `Item(..., tags=["c:dusts/diorite", "c:dusts"])` in `code/main.py` | shared `c:` tags: other mods find, accept and craft with these items |
| Recipes | `shaped("minecraft:diorite", "DD", "DD", D="#c:dusts/diorite")`, the grinder's `#c:ingots/iron` | a tag as an ingredient takes the item of every mod in it |
| The Grinder | `Block(...).extends(EnergyStorage(...), FluidTank(4000, allowed=["minecraft:lava"]), Inventory(input=1, fuel=1, output=2, fluid_in=1, accepts=...), Screen(layout="grinder"))` | NeoForge energy, liquid and item storages that cables, pipes and hoppers of every mod use; `accepts` takes `#minecraft:coals`; `fluid_in` pours buckets into the tank |
| What it makes | `machine_recipe(grinder, inputs=[stone], outputs=[(dust, 2), (dust, 1, 0.25)], energy=160, seconds=4)` | real data-pack recipes: JEI and EMI show them on the Grinder's page |
| Its window | `ui/grinder.json`, made with **Tools > New UI window > Minecraft-style window** | slots, the lava gauge, a flame bound to `value:burn`, an arrow bound to `value:progress`, the energy bar, a label `{ground} ground` |
| The logic | `@every(ticks=4)` in `code/main.py`, rules in `code/grinding.py` | Python decides when a machine works; `world.machine_recipe` and `machine_finish` move the items, `machine_update` burns, `machine_set` moves the arrow |
| Tests | `code/test_grinding.py` | the rules are tested apart from the game (**Run tests**, `Ctrl+Shift+F10`) |
| Statistics | `storage` scope `stats`, places in `grinders` | values saved with the world, kept apart per project; `/craftpy backup create dustworks` copies them |
| The pack | `Item(..., model="backpack").extends(Placeable(...))`, `assets/models/item/backpack.json` | an own 3D model (Blockbench "Java Block/Item"), set down as an entity that is its stack |
| Its window | `ui/adventure_pack.json` (248 x 222) | a 9 x 6 grid, two tank gauges bound to `fluid:0` / `fluid:1`, the energy bar, `fluid_in` / `fluid_out` / `energy_in` / `energy_out` slots and a button |
| Opening it | `@on.item_use(item=pack)` with `ui.open_screen(..., hand=)`, `@on.entity_interact(entity=pack)` with `entity=ctx.entity`; Distill is `@on.screen_button` with `ui.screen_update` | the same contents in the hand and on the floor |
| Starbrine | `Fluid("starbrine", light=12, effects=[...], still=, flowing=, bucket_texture=, tags=["c:starbrine"])` | a liquid of its own: textures, glow, effects, a tag other mods read |
| Commands | `@command("stats", level=0)`, `@command("reset", level=2)`, `@command("kit", level=2)` | every command says who may run it |

The grinders' places are kept in `storage` when they are placed and broken, so after a restart the timer finds them again.
A grinder whose chunk is not loaded is skipped until someone comes near.
