# Sky Islands

Islands rise into the sky above you, a few blocks at a time: grass and rock hanging in ragged points, groves,
waterfalls pouring into little basins, broken arches, flower gardens and crystal fields, all joined by rope bridges
with lanterns. The hub carries a spire you can see from every island. Each of the other islands hides a glowing
**Sky Crystal**. Break them all to finish, and beat your best time.

## Play

1. Craft the **Skyward Compass**: a compass with a feather on each side.
2. Right click it, pick a size (5, 8 or 12 islands), a theme and maybe a seed, and press **Raise the islands**.
3. Wait for the islands to rise; you land on the hub. Find the crystals. If you fall, the wind carries you back.
4. **Take them down** removes everything that was built.

Themes: verdant, frost, blossom, ember, void. Sky Shards from the crystals craft into an elytra (7 shards and a
phantom membrane).

Commands: `/sky_islands:open`, `/sky_islands:raise <small|medium|large> <theme>`, `/sky_islands:home`,
`/sky_islands:collapse`.

## How it is made

| File | What it does |
|---|---|
| `code/islands.py` | The plan: where islands float and which bridges join them. Plain Python, tested in `test_islands.py`. |
| `code/themes.py` | The blocks of each theme. Copy one to make your own. |
| `code/builder.py` | Plan to blocks, a few pieces per tick. It remembers every box it touched so it can take them down. |
| `code/expedition.py` | One player's run: raising, the crystal hunt, records, the safety net. |
| `ui/archipelago.json` | The settings window, made in the UI maker. Its buttons call `@action` functions in `main.py`. |

The status window (`sky_status`) is written in Python instead, because it changes while you play.

It asks for no permissions: it only places blocks.
