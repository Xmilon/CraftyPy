# Activity Stats

Counts what each player mines, crafts and defeats, per world.

- Module id: `activity_stats`
- Works on: client and server
- CraftPy Python API: 2.9
- Asks for: no permissions

## What it does

- **Mined**: every block you break.
- **Crafted**: every item you craft, by the number of items made.
- **Defeated**: every entity you are the killer of.
- Counters belong to the current world and to your player; another world starts at zero.
- It reacts to events only, nothing runs every tick.

## How to use it

- `/activity_stats:show` prints your three counters in chat.
- `Ctrl+Shift+S` does the same. Change the key in Controls or on the Module Keybinds page.

## How it is made

`code/main.py` has three event handlers (`block_break`, `item_craft`, `entity_death`) that add to one `storage` entry
named `stats:<player uuid>`, plus the `show` command and the key. To count something new, add another handler and
another category name in `show`.
