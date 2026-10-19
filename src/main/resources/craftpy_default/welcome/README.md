# Welcome

The tour of CraftPy: each file in `code/` shows one thing, with comments, in the order you should read them.

- Module id: `welcome`
- Works on: client and server
- CraftPy Python API: 2.9

## What is inside

- **Items**: `welcome:gem`, `welcome:gem_apple`, `welcome:gem_sword`, `welcome:gem_wand`
- **Blocks**: `welcome:gem_ore`, `welcome:gem_block`
- **Recipes, tags and loot** for all of them
- **Events**: greeting on join, gem apples every 5 ores, the wand, chat `!gems`, zombie drops
- **Timers**, **commands** (`/welcome:gift`, `/welcome:stats`, `/welcome:menu`, `/welcome:settings`, `/welcome:selftest`)
- **Windows**: designer-authored counter/settings windows (`ui/*.json`), Python display bindings, named actions and per-player saved state
- **Tests**: `code/test_rewards.py` (Ctrl+Shift+F10)

Start with `code/main.py`, then press Run (Ctrl+R).
