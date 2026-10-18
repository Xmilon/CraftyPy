# Welcome

The tour of CraftPy: each file in `code/` shows one thing, with comments, in the order you should read them.

- Module id: `welcome`
- Works on: client and server
- CraftPy Python API: 2.2

## What is inside

- **Items**: `welcome:gem`, `welcome:gem_apple`, `welcome:gem_sword`, `welcome:gem_wand`
- **Blocks**: `welcome:gem_ore`, `welcome:gem_block`
- **Recipes, tags and loot** for all of them
- **Events**: greeting on join, gem apples every 5 ores, the wand, chat `!gems`, zombie drops
- **Timers**, **commands** (`/welcome:gift`, `/welcome:stats`, `/welcome:menu`, `/welcome:settings`, `/welcome:selftest`)
- **Windows**: a counter made in Python and a settings window drawn in the UI designer (`ui/settings.json`)
- **Tests**: `code/test_rewards.py` (Ctrl+Shift+F10)

Start with `code/main.py`, then press Run (Ctrl+R).
