# HUD Pack

A client-side mod written in Python. It runs only on your computer and never touches the server.

- Module id: `hud_pack`
- Works on: client only
- CraftPy Python API: 2.9
- Asks for: no permissions

## What it does

- Health bar (turns red when low), coordinates, the item in your hand, an FPS counter and the block you look at.
- A toast when a chat line says "welcome".
- Every part can be switched off in the settings window, and the choice is remembered between sessions.

## How to use it

- `F7` hides or shows everything.
- `Ctrl+H` opens the settings window (visible, coordinates, held item, toasts).
- Change both keys in Controls or on the Module Keybinds page. The action ids `f7` and `ctrl+h` are kept so older
  remaps still work.

## How it is made

- `code/main.py` listens to `client_tick` (drawing every fifth tick), `chat_received` and the two keys.
- `code/hud_view.py` draws with `hud.text`, `hud.bar` and `hud.item`.
- `code/settings.py` keeps the saved switches; `@ui.data('hud_settings')` fills the window and the `hud_changed` action
  saves the checkboxes.
- `ui/hud_settings.json` is the window layout. Edit it visually in the UI maker.

Client code only sees what the game already shows you; it cannot change the world.
