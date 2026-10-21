# Waypoints

Save places and teleport back to them, with a short cooldown.

- Module id: `waypoints`
- Works on: client and server
- CraftPy Python API: 2.10
- In the Hub: Waypoints in the Apps tab (declared in `project.json`; the icon is `assets/icon.png`)
- Asks for: no permissions

## What it does

- Up to 20 waypoints per player and world; names are cut to 32 characters. Saving an existing name overwrites it.
- Each waypoint remembers its dimension.
- Teleporting asks for a safe spot and then waits ten seconds before the next one.

## How to use it

- `Ctrl+Shift+W` opens the window: type a name and save, pick one from the list to go there or remove it.
  Change the key in Controls or on the Module Keybinds page.
- Commands: `/waypoints:save <name>`, `/waypoints:go <name>`, `/waypoints:list`, `/waypoints:remove <name>`.

## How it is made

- `ui/waypoints.json` is the window layout.
- `code/main.py`: `@ui.data('waypoints')` supplies the list; the actions `waypoint_save`, `waypoint_go` and
  `waypoint_remove` call the same functions as the commands. Waypoints and the cooldown are in `storage`.
