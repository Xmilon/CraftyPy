# Backpack

Minecraft-style windows that belong to a player or to an entity instead of a block.

- Module id: `backpack`
- Works on: the server (the windows are drawn by every player's game; the server keeps the items)
- CraftPy Python API: 2.11 (`ui.open_screen`, `ui.screen_set`, `entity_interact`, value bars)
- Needs: nothing but CraftPy; asks for no permissions

## How to use it

- `/backpack:open` opens **your backpack**: nine slots that stay with you, whichever world or server you are in on this
  server. The window also shows how many times you opened it, as a label and a bar.
- **Shift + right click a villager** opens its **pockets**: six slots that stay with that villager (a normal right click
  still trades).

## How it is made

| Part | Where | What it shows |
| --- | --- | --- |
| The windows | `ui/backpack.json`, `ui/pockets.json` | drawn in the UI maker with *legacy* ticked: panel, slot grid, labels, a value bar, your inventory |
| Opening one | `ui.open_screen(player, "backpack", inventory=Inventory(storage=9), values={...})` in `code/main.py` | the slots belong to the entity named with `entity=` (default: the player) |
| Values | `{opened}` label and the `value:opened` bar (`max` 50) | numbers Python sets; `ui.screen_set` changes them while the window is open |
| The villager | `@on.entity_interact` | the event fires when a player right clicks an entity; `ctx.cancel()` stops its own reaction |

The items are saved in the entity's data under `backpack_<window name>`, so another project cannot read or change them.
