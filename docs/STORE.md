# CraftPy

**Write Minecraft content in Python, inside Minecraft.** Items, blocks, recipes, loot, events, commands and windows are
a few lines of Python. Press Run and they exist in your world: no Java, no restart, no installing Python.

```python
from craftpy import *

ruby = Item("ruby", name="Ruby", texture="ruby.png", glow=True)
ruby_ore = Block("ruby_ore", texture="ruby_ore.png", hardness=3, drops=(ruby, 2))
shaped(Item("ruby_sword", texture="ruby_sword.png").extends(Sword(tier="diamond")),
       " R ", " R ", " S ", R=ruby, S="stick")

@on.block_break(block=ruby_ore)
def mined(event):
    event.player.actionbar("You found a ruby!")
```

## What you get

- **A real IDE in the game** (and as its own window): completion for your code and for other mods' ids, errors while
  you type, search, refactoring, undo, breakpoints, tests, a built-in texture and sound editor.
- **Works with other mods**: Create, Mekanism, Iron's Spells and more. Recipes of any mod's machine, their attributes,
  tags and registries are offered in completion from the mods you have installed.
- **Content without restarts**: blocks and items appear while you play; everything is reloaded when you press Run.
- **Windows and HUD in Python**: buttons, lists, sliders, progress bars; a music player is about 100 lines.
- **Modules**: pack a project into a `.cpm`, share it, and others install it by dropping the file into a folder. A module
  can work on the client, the server, or both. A module can also be opened again as a project to learn from it.
- **Python included**: nothing to install. Windows, macOS and Linux.
- **Made to be safe**: Python runs in a separate sandboxed process with no file or network access and a memory limit.

## Requirements

Minecraft 1.21.1 · NeoForge 21.1 · [Modern UI](https://modrinth.com/mod/modern-ui) (client only; servers do not need it)

## Getting started

1. Put CraftPy and Modern UI in `mods/`.
2. Craft or take the **Python Computer** from the creative tab and right click it (or press `I`).
3. Open the **Welcome** project, read it top to bottom, press **Run** (`Ctrl+R`).

Docs for everything you can write are inside the game (**Docs**), written to be quick to scan.

## Notes

Code from other people is code: a module you did not write can give items, run commands as the server and use the Java
bridge as far as the server config allows. It cannot touch your files, start programs or use the network. Read a module
(Modules page: README, Open as project) before you run it.
