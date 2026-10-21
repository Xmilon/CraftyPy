# Welcome

The tour of CraftPy: each file in `code/` shows one thing, with comments, in the order you should read them.

- Module id: `{{id}}`
- Works on: client and server
- Needs: nothing but CraftPy (no other mod)
- In the Hub: *Click Counter* and *Welcome Settings* (listed under `apps` in `project.json`)
- CraftPy Python API: 2.10

## What is inside

- **Items**: `{{id}}:gem`, `{{id}}:gem_apple`, `{{id}}:gem_sword`, `{{id}}:gem_wand`
- **Blocks**: `{{id}}:gem_ore`, `{{id}}:gem_block`
- **Recipes, tags and loot** for all of them
- **Events**: greeting on join, gem apples every 5 ores, the wand, chat `!gems`, zombie drops
- **Timers**, **commands** (`/{{id}}:gift`, `/{{id}}:stats`, `/{{id}}:menu`, `/{{id}}:settings`, `/{{id}}:selftest`)
- **Windows**: designer-authored counter/settings windows (`ui/*.json`), Python display bindings, named actions and per-player saved state
- **Tests**: `code/test_rewards.py` (Ctrl+Shift+F10)

Start with `code/main.py`, then press Run (Ctrl+R).
