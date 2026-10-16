# Ruby Rush

Rubies as a full little mod: an ore that drops them, a ruby block, a sword, a ruby apple, recipes for all of them, and a
"surge" event that rewards players who keep mining.

| | |
|---|---|
| Module id | `ruby_rush` |
| Works on | client and server |
| CraftPy Python API | 2.2 |

## What is inside

- **Items**: `ruby_rush:ruby`, `ruby_rush:ruby_sword`, `ruby_rush:ruby_apple`
- **Blocks**: `ruby_rush:ruby_ore`, `ruby_rush:ruby_block`
- **Recipes**: crafting, smelting and block packing for every ruby item
- **Events**: mining ruby ore counts towards a surge; a surge plays particles around the miner for a while
- **Commands**: `/ruby_rush:surge <player>` starts a surge right away

## Files

| File | What it does |
|---|---|
| `code/main.py` | imports the other files in the order they depend on each other |
| `code/items.py`, `code/blocks.py` | the content |
| `code/recipes.py` | every recipe |
| `code/events.py`, `code/surge.py` | mining rewards and the surge timer |
| `code/commands.py` | the commands |
| `assets/textures/` | the PNG textures |
