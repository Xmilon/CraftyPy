# Death Compass

Remembers where you died last and tells you the way back. It does not recover items or teleport you.

- Module id: `death_compass`
- Works on: client and server
- CraftPy Python API: 2.9
- Asks for: no permissions

## What it does

- Saves your coordinates and dimension each time you die, per world and per player. A new death replaces the old one.
- Reports the coordinates and the dimension of the last death.
- In the same dimension it also gives the horizontal distance and a direction (East or West, South or North).

## How to use it

- `/death_compass:where` prints the report in chat.
- `Ctrl+Shift+D` does the same. Change the key in Controls or on the Module Keybinds page.
- Before any death it says that none is recorded.

## How it is made

`code/main.py`: a `player_death` handler writes `death:<uuid>` to `storage`; the `where` command reads it and works out
the direction from the difference in x and z.
