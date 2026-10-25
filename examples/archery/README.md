# Archery

A charge bar for every bow, like the one in Minecraft Dungeons: three segments beside the crosshair that fill as you draw, a
tick at every third and a chime when the draw is full.

- Module id: `archery`
- Works on: your own computer only (`"side": "client"`): any server, even one without CraftPy, and nothing runs there
- CraftPy Python API: 2.12 (`client.player().using / use_ticks / use_full`, `hud.bar(vertical=True, frame=)`)
- Needs: nothing but CraftPy; asks for no permissions

## How to use it

Draw any bow: the vanilla bow, a crossbow, a trident, or the Warbow, Twinfang and Artemis' Bow of the Armory. A bar of three
segments fills beside the crosshair, grey, then yellow, then gold, and flashes green and white when the draw is full. It ticks
at a third and two thirds and chimes when ready. **F8** moves the bar to the other side of the crosshair (change the key in the
game's controls).

## How it is made

| Part | Where | What it shows |
| --- | --- | --- |
| What you are drawing | `client.player()` in `code/main.py`: `using`, `use_ticks`, `use_full` | the item in use and how long it takes to draw |
| The bar | `hud.bar(..., vertical=True, frame="black")` once per segment | a vertical bar that fills from the bottom, with an outline |
| The rules | `code/rules.py`, `code/test_rules.py` | segment fills, colours and ticks, tested apart from the game |
| Sounds | `assets/sounds/*.ogg`, `client.sound("archery:ready")` | sounds of a client module play on your own computer |
| The key | `@keys.bind("f8", ...)` | a rebindable key that flips the side, remembered in `storage` |
