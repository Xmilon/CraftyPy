# Ruby Rush

A small mining game that shows most of the CraftPy API: an ore with drops, items, recipes, events, storage, timers and
a command.

- Module id: `ruby_rush`
- Works on: client and server
- CraftPy Python API: 2.9
- Asks for: no permissions

## What it adds

- **Items**: `ruby_rush:ruby`, `ruby_rush:ruby_sword` (diamond-tier sword, right click for Strength II for 8 seconds,
  1200 durability, repaired with rubies) and `ruby_rush:ruby_apple` (always edible).
- **Blocks**: `ruby_rush:ruby_ore` (drops 2 rubies, needs an iron pickaxe or better) and `ruby_rush:ruby_block`
  (light 4, gives redstone power 7 to its neighbours).
- **Recipes**: block from 9 rubies and back, sword, apple, and smelting the ore.
- **Surge**: every 10th ruby ore mined server-wide starts a Ruby Surge. The miner gets a ruby apple, everyone online
  gets Haste II for 30 seconds, and fireworks play around the miner for 5 seconds.
- A zombie killed with the ruby sword drops a ruby one time in four.
- Joining shows a title that says to mine ruby ore.

## How to use it

- `/craftpy give @s ruby_rush:ruby_ore block 16` gives ore to try.
- `/ruby_rush:surge <player>` starts a surge right away.

## Files

- `code/main.py` imports the other files in the order they depend on each other.
- `code/items.py`, `code/blocks.py`, `code/recipes.py` declare the content.
- `code/events.py` and `code/surge.py` hold the mining count and the surge.
- `code/commands.py` holds the command.
- `assets/textures/` holds the PNG textures.
