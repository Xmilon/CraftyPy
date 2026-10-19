# HUD Pack

A client-side mod written in Python: it runs only on your computer and never touches the server.

- Module id: `hud_pack`
- Works on: client only (Python that runs on the player's computer)
- CraftPy Python API: 2.9

## What it does

- Health bar (turns red when low), coordinates, the item in your hand and an FPS counter on the screen
- The block you are looking at, at the top
- `F7` hides or shows everything; `Ctrl+H` opens a settings window (what to show), remembered between sessions
- A toast when a chat line says "welcome"

## How it is made

`code/main.py` listens to `client_tick`, `chat_received` and two keys; `code/hud_view.py` draws with `hud.text`, `hud.bar`
and `hud.item`; `code/settings.py` is a local window. Nothing here can change the world: client code only sees what
the game already knows.

The window layouts are in `ui/*.json`: edit them in Design. Python `@ui.data` providers supply live values and named `@action` handlers apply changes. Module shortcuts can be remapped in Controls or Module Keybinds.
