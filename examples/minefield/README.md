# Minefield

Minesweeper in a window. Clear the 8 x 8 field without touching a mine. Hit one and a TNT explosion goes off right next
to you in the world.

- Module id: `minefield`
- Works on: client and server
- CraftPy Python API: 2.9
- Asks for: no permissions

## What it does

- A window drawn in the UI maker with a grid of 64 tiles, a progress bar of the safe tiles cleared and a status line.
- 10 mines. They are laid after your first click, so the first tile and its neighbours are always safe. Numbers show how
  many mines touch a tile; empty areas open on their own.
- **Flag mode** turns your clicks into flags, so you can mark a tile you do not trust.
- **Hitting a mine**: TNT explodes two blocks beside you (power 2: it hurts, it should not kill, and no blocks are
  broken). The other mines then blow up one after another in the window, starting next to the one you hit, and the
  status line flashes BOOM.
- **Close to winning**: with 3 safe tiles left the status line blinks to warn you. Clearing the field plays fireworks
  and a sound.

## How to use it

- `/minefield:open` or `Ctrl+Shift+M` opens the window. Change the key in Controls or on the Module Keybinds page.
- **New game** starts again; **Close** closes the window.

## Files

- `ui/minefield.json`: the window.
- `code/game.py`: the rules, with no game calls in it.
- `code/main.py`: the window's data, the buttons (`@action`), the explosion and the animation (`@every` redraws the
  window every 4 ticks while something is happening).
