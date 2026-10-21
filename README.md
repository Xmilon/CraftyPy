# CraftPy

A Python editor inside Minecraft. You place a computer block, open it, write Python, press Run, and your blocks, items,
recipes and mobs show up in the world. No Java, no rebuilding the mod, no restart.

```python
from craftpy import *

ruby = Item("ruby", texture="ruby.png")
ruby_ore = Block("ruby_ore", texture="ruby_ore.png", hardness=3, tool="pickaxe", drops=(ruby, 2))
shaped(ruby, "DD", "DD", D="diamond")

@on.block_break(block=ruby_ore)
def found(event):
    event.player.say("Rubies!")
```

Version 1.1.0. Minecraft 1.21.1, NeoForge 21.1, Java 21. The editor needs [Modern UI](https://modrinth.com/mod/modern-ui) 3.13+ on
the client; servers don't.

## Install

Put one jar in `mods/`:

- `craftpy-<version>.jar` (~140 MB) has Python 3.13 inside for Windows, macOS and Linux. Nothing else to install.
- `craftpy-<version>-windows.jar`, `-macos.jar`, `-linux.jar` carry only that system's Python (for Modrinth and
  CurseForge, which cap file size).
- `craftpy-<version>-no-python.jar` (~3 MB) uses the Python 3.13 on your PATH. `INSTALL.md` explains how to get one.

The first start unpacks Python to `pyforge/system/python/` and checks it against its hash.

## First steps

1. Craft a **CraftPy Computer** and right-click it. Once you have crafted one, ops can also press `I`.
2. Make a project on the Home page and edit `code/main.py`.
3. Press **Run** (`Ctrl+R`). Output goes to the console at the bottom.
4. `/craftpy give <you> <project>:<id> block` gives you what you made.

The **Docs** page in game has the guide and the full API reference ([api.md](src/main/resources/assets/craftpy/docs/api.md)).
**Welcome tour** is a template: choose it in **New project** (Home) and the whole tour is written into a project of your name, with every file commented (items, blocks, recipes, events, timers, commands, two windows, a test). It needs no other mod and nothing exists until you create it. The only other example is `examples/bank`, a plain project (a Hub app: deposit, withdraw and send emeralds); copy it into `pyforge/projects` or build a `.cpm` from it.

## Commands

- `/craftpy give <player> <project>:<id> <block|item|egg> [count]`: Content of a project (vanilla `/give` can't)
- `/craftpy summon <project>:<mob> [pos]`: Spawns a project mob
- `/craftpy place <template> [pos] [rotation]`: Places a structure template
- `/craftpy module list|enable|disable|info|reload <module>`: Manages modules (also from the server console)
- `/craftpy project trust <project>`: Lets a project that came from elsewhere use what it declares

A project's own `@command("name")` becomes `/<project>:<name>`. All of these need op (level 2).

## Projects and modules

Everything lives in `<game>/pyforge/` as plain files:

```text
projects/<id>/project.json    name, version, side, dependencies, permissions
projects/<id>/code/main.py    your code (more .py files next to it)
projects/<id>/assets/         textures, models, sounds
projects/<id>/data/           data pack files (structures, worldgen, tags, ...)
projects/<id>/ui/<id>.json    windows made in the UI maker
modules/*.cpm                 built modules, drop new ones here
```

**Build module** turns a project into a `.cpm` file you can share. On the Modules page you switch modules on per world,
read their README, approve what they ask for, and **Decompile** one back into a project. Rebuild a decompiled project
and the module is replaced and marked *Changed*. Another project imports a module with `from modules.<id> import name`
after listing it under `"dependencies"`. The **Mod map** on the right shows what each mod, module and project does and
how they connect.

Module switches are saved immediately and take effect when you press **RELOAD**. Changed code, windows, permissions
and dependencies restart only the affected owners; unchanged workers keep their state. Asset-only changes refresh the
packs without restarting Python. Leaving Modules with pending changes offers **Reload now**, **Leave for later** or
**Stay**. Revoking a permission takes effect immediately. A reload failure stays pending so you can fix it and retry.

This InDev build uses network protocol **7**; clients and servers must use matching CraftPy builds. The Python API is
**2.10**.

## The Hub (beta)

The key that used to open the IDE (**Open CraftPy Hub** in Controls, default `I`) opens the **Hub**, marked Beta, for every
player. Tabs: **Settings** (yours: editor colours and font, module keybinds; the server's, read-only), **Editor** (opens the full
editor; Esc or the arrow in its rail comes back), **Modules** (*Client* modules you can switch here, *Server* modules read-only),
**Apps** (windows that enabled, loaded modules offer: search, categories, arrow keys, a Hub button in the window) and
**Marketplace** (coming soon). Esc closes a popup, then goes back one level, and closes the Hub only from Apps.

A module adds apps with `"apps"` and `"icon"` in `project.json`, for example `{"id": "journal", "name": "Task Journal",
"window": "journal", "category": "Tools"}`; the Welcome tour and the Bank have a 64 x 64 `assets/icon.png` and list their windows as apps (see the guide).

## Pictures, GIFs, video and sound in windows

Windows made in the UI maker (or with `Image`, `Video` and `Audio`) show your own files from `assets/media/`:
**PNG, JPEG, BMP and animated GIF** pictures (with transparency), **video as Motion JPEG in an AVI** (its sound is a separate file), and
**OGG, WAV, MP3, FLAC, AIFF and AU** sound. They take `fit`, `crop`, `opacity`, layering with `z`, play, pause, loop, seek, speed (not
for sound), volume and mute, an optional control bar, `on_end` / `on_loop` / `on_error`, and can be driven from Python through
`@ui.data` bindings. MP4, WebM, H.264, APNG, WebP, sound inside a video and web addresses are **not** supported. Decoding is
off the screen threads and bounded; see the guide for the limits and the `ffmpeg` commands.

## Independent machinery and Building Blocks

CraftPy includes its own rotation network, generator/shaft/processor roles, two-slot processing, FE batteries and fluid tanks.
These work without Create or another content mod. Create 6.0.10 is an optional rotation backend; unsupported versions use
CraftPy rotation. Other mods connect through standard NeoForge item, fluid and energy capabilities or a specific rotation adapter.
Rotation and electrical energy are separate: a spinning motor does not silently generate FE.

```python
motor = Block("motor", texture="minecraft:block/copper_block").extends(
    Kinetic("generator", axis="x", speed=32, capacity=32))
battery = Block("battery", texture="minecraft:block/redstone_block").extends(EnergyStorage(100000, 1000, 1000))
tank = Block("tank", texture="minecraft:block/glass").extends(FluidTank(16000, ["minecraft:water"]))
```

`world.machine` and `world.resources` inspect loaded machines. `world.transfer` moves resources natively through selected
block faces, with an optional simulation check. Inventories, FE, fluid components and processing persist. The separate
32-slot machine pool keeps existing block IDs intact. Configure rotation rebuild budgets in `craftpy-common.toml`.

Building Blocks contains **120 templates: 96 core and 24 optional integration templates**. Search keywords, filter categories,
preview code, then Insert or New file. Templates with layouts or assets add their companion files without overwriting yours.
Mod-specific entries appear only when the actual execution environment supports them.

No module is installed for you: `pyforge/modules` starts empty. The tests pack the Welcome tour and the Bank into modules on demand
(`packExamples`), and nothing is shipped in the jar or recreated automatically.

The Modules permission toggle remembers approval for the exact file. A changed file asks again; switching access off revokes
it. Server-module acknowledgments can be remembered per server/module/version without granting any local permission.
Network protocol is **6**; updated clients and servers must agree.

## How it works

Registries are frozen once the game has started, so CraftPy registers a pool of generic block, item, mob and effect
slots at startup and maps your definitions onto them at runtime. The mapping is saved, so placed blocks survive
restarts. Assets and recipes go into generated resource and data packs that reload when you press Run. Python runs
outside the JVM in its own process and talks to the game over JSON-RPC.

## Security

Every project and module gets its own Python process with an empty environment and a memory cap. It can only read its
own code folder and the modules it imports. The game knows which process is calling, so one module can't pretend to be
another, read another's data or use its approved sites.

Web access, a data folder, shared folders, server commands and the Java bridge are all off unless the project declares
them in `"permissions"`. A module from someone else also needs your approval, and that approval is tied to the exact
file, so a changed module has to ask again. `commands` run at level 2. `java` means full trust.

On a dedicated server, putting a module in `pyforge/modules/` and enabling it (`/craftpy module enable <id>` works
from the console) counts as the approval. Players who join from another computer see the modules that ask for
permissions, with their READMEs, before anything loads, and choose Continue or Leave. Only the server's textures,
models, sounds and names are sent to them, never code.

The checks inside Python (blocked imports, no `open`/`eval`) are a second layer, not the wall.

**Checking a module before you share it:** `tools/check_module.py <file.cpm or folder>` (or the
`craftpy-check-windows/macos/linux` executable built from it) reads a module without running it and says SAFE, REVIEW
or DANGEROUS, with the reasons and lines. It is strict: SAFE only when the module asks for no permissions and has
nothing hidden or unusual; anything it cannot vouch for goes to REVIEW for a person to read.

**Disclaimer.** CraftPy runs code written by other people. I am not responsible for how anyone uses this mod, or for
anything harmful done through modules or on servers that run them. Be careful with modules you didn't write and with
servers that use modules: read what a module asks for, and install modules or join servers only from people you
trust.

## Config

`config/craftpy-common.toml`: `enableScripting`, `permissionLevel` (who may use the editor), `pythonPath`,
`pythonMemoryMb` (per process, default 512), `javaBridge.mode`, `eventTimeoutMs`, `hotReloadOnSave`.
`config/craftpy-access.toml`: which sites exist at all, API keys, file quotas.

## Building

```bash
./gradlew build       # tests + all jars in build/libs/
./gradlew runClient   # dev client; put the Modern UI jar in run/mods/ first
./gradlew runIde      # the editor without the game
./gradlew uiDesignerSmokeTest  # designer pointer + editor integration checks in a hidden window (needs a GPU)
```

`./gradlew build` downloads the Python runtimes once into `build/python-runtimes/`. Add
`-Pcraftpy.bundlePython=false` for the small jar only.

## Known limits

- 32 passive mobs, 32 hostile mobs and 32 effects at a time. Block and item pools default to 256 each, up to 4096 in
  `config/craftpy-slots.properties`, and a bigger pool needs a restart.
- New world generation (structures, natural spawns) applies when a world is opened.
- CPython 3.13+ only. Python is bundled for Windows x64, macOS (Intel and Apple Silicon) and Linux x64/arm64. On
  Windows on ARM, 32-bit Windows or musl Linux (Alpine) there is no bundled build: install Python 3.13 yourself and
  set `pythonPath`.
- Built and tested on Windows; macOS and Linux are untested.

## License

GPL-3.0 ([LICENSE](LICENSE)). CPython, JLayer and jFLAC keep their own licenses
([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)). The NeoForge MDK template files are MIT.
