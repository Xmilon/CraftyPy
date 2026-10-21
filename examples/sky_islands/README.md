# Sky Islands

Islands rise into the sky above you, a few blocks at a time: grass and rock hanging in ragged points, groves,
waterfalls pouring into little basins, broken arches, flower gardens and crystal fields, all joined by rope bridges
with lanterns. The hub carries a spire you can see from every island. Each of the other islands hides a glowing
**Sky Crystal**. Break them all to finish, and beat your best time.

- Module id: `sky_islands`
- Works on: client and server
- CraftPy Python API: 2.10
- In the Hub: Sky Islands and Expedition in the Apps tab (declared in `project.json`; the icon is `assets/icon.png`)
- Asks for: no permissions; it only places blocks

## How to play

1. Craft the **Skyward Compass**: a compass with a feather above, below and on each side.
2. Right click it, or press `Ctrl+G`, pick a size (5, 8 or 12 islands), a theme and maybe a seed, and press
   **Raise the islands**.
3. Wait for the islands to rise; you land on the hub. Find the crystals. If you fall, the wind carries you back.
4. **Take them down** removes everything that was built.

Themes: verdant, frost, blossom, ember, void. Sky Shards from the crystals craft into an elytra (7 shards and a
phantom membrane). A status window shows your progress, the time and your records.

## Commands

- `/sky_islands:open`
- `/sky_islands:raise <small|medium|large> <theme>`
- `/sky_islands:home`
- `/sky_islands:collapse`

Change the `Ctrl+G` key in Controls or on the Module Keybinds page.

## How it is made

- `code/islands.py`: the plan, which islands float and which bridges join them. Plain Python, tested in
  `code/test_islands.py`.
- `code/themes.py`: the blocks of each theme. Copy one to make your own.
- `code/builder.py`: plan to blocks, a few pieces per tick. It remembers every box it touched so it can take them down.
- `code/expedition.py`: one player's run, the crystal hunt, records and the safety net.
- `code/main.py`: the compass, the commands, the key and the `@action` functions the windows call.
- `ui/archipelago.json` is the settings window and `ui/sky_status.json` the status window. Edit both visually in the UI
  maker.
- `assets/textures/` holds the PNG textures.
