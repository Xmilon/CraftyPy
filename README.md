# CraftPy

**An in-game Python IDE for Minecraft.** CraftPy adds a computer block that opens a full IDE inside the game: write Python to declare blocks, items, recipes, loot tables and tags, run the project, and watch the content appear in your world — no Java, no mod recompilation, no restarts.

*Declarative Python in, live Minecraft content out.*

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [Getting Started](#getting-started)
- [Using the IDE](#using-the-ide)
- [Writing Content in Python](#writing-content-in-python)
- [The `/craftpy` Command](#the-craftpy-command)
- [Project Layout](#project-layout)
- [How It Works](#how-it-works)
- [Configuration](#configuration)
- [Security](#security)
- [Known Limitations](#known-limitations)
- [Development & Testing](#development--testing)
- [License](#license)

---

## Overview

Minecraft's registries are frozen after mod loading, so "add a block at runtime" is normally impossible. CraftPy works around this with a **slot pool**: a set of generic block/item slots pre-registered at startup, whose behavior, appearance and names are driven by *data definitions* produced from your Python scripts. Projects live as plain files in the game instance folder, load and unload at runtime, and ship their assets through dynamic resource/data packs that reload automatically.

The result is a tight authoring loop:

1. Right-click the **CraftPy Computer** (or press `I` as an operator) to open CraftPy.
2. Write or edit `code/main.py` in the Editor page.
3. Press **Run** — the script executes on a sandboxed Python worker.
4. Your blocks and items appear in-game immediately.

## Features

### An IntelliJ-style IDE, in game and on its own (built on Modern UI)
The same IDE runs inside Minecraft and as its own window (`./gradlew runIde`, no game needed). It looks like IntelliJ's
New UI in dark, with CraftPy's green as the accent:
- **Activity bar** (Home, Editor, Modules, Docs) and a **main toolbar**: project widget with recent projects, menus,
  Search Everywhere, and the Run / Reload / Stop widget.
- **Tool windows** on both edges, opened and closed from their stripes or with `Alt+1..9`: Project tree, Bookmarks,
  Python console, Problems, Event tracer, **Building blocks**, Assets, Preview, Texture and Sound editors. All of them are
  resizable; the layout is remembered.
- **Editor**: tabs with file icons and unsaved dots, a **resizable split editor** (`Ctrl+\`), indent guides, bracket pairs,
  minimap with error marks, sticky scroll, focus mode, six color schemes, breadcrumbs, signature help.
- **Smart completion** that infers types without running code (`Item("a").extends(`, `event.player.`, your own classes
  and functions), fuzzy matching (`gb` finds `get_block`), keyword arguments, and ids of every installed mod inside strings.
- **IntelliJ commands**: Find Action (`Ctrl+Shift+A`), Find in Files, TODO list, rename in file (`Shift+F6`), reformat
  (`Ctrl+Alt+L`), comment, duplicate and move lines, live templates (`onj` + `Tab`), go to definition, extract variable /
  function, organise imports, run the selection in the console (`Alt+Shift+E`).
- **Building blocks**: 56 ready-made pieces (windows and other UI elements, items, armor, curios, blocks, events,
  commands, timers, recipes, world actions, web and files, and client-side HUD / keys / windows). The panel only shows the
  ones that work on the project's side. Each one opens its docs, inserts its code at the cursor, or becomes a new file
  that `main.py` already imports. Every block is run and checked by the test suite.
- **Preview drawn by Minecraft**: in game, blocks, items and mobs (vanilla and every mod, GeckoLib models included) are
  rendered live by the game itself inside the Preview tab; drag to turn, wheel to zoom. *Mobs...* lists every mob type.
  GeckoLib `.geo.json` files also preview in the standalone IDE.
- **Recipes & drops** (right stripe, `Ctrl+Shift+R` on an id): every item and mob of the game and its mods; for an item,
  what makes it, what it is used in and what drops it; for a mob, the mob drawn live with everything it drops (chances,
  Looting bonus, player kills). With JEI installed the recipes are JEI's own layouts (Create, Mekanism... exactly as in
  JEI); without it the server's recipes are shown as cards. Drops come from the loot tables, the part JEI does not show.
- **UI maker** (right stripe): the designer of `ui/<id>.json` windows: a palette of widgets, the tree, the properties
  and the window drawn live. Every change goes into the editor tab (undo, save as usual); *Copy Python* gives the
  `@action` functions the window's buttons call.
- **Modules** page: every built `.cpm` with search, enable/disable, reload and the rendered README. **Docs**: the offline
  guide, API reference and Java bridge reference, with a contents list on the left that jumps to each heading.
- Projects work on **client and server** (default), **client only** (Python on each player's computer: HUD, keys,
  windows, plus `assets/` as a resource pack, on any server) or **server only** (code and data; players install nothing).
  Chosen when the project is made (*Client side only* checkbox), stored as `"side"` in `project.json`, switched (and
  back) in *Tools > Project side*. The code checks warn about API names of the other side; *Build module* (toolbar
  hammer) uses the project's side.
- **Using other modules**: `from modules.jukebox import songs` imports what an installed module defines (its `main.py`
  names and its files). *Modules > Use in project* adds it to `"dependencies"` in `project.json`, so it loads first
  and the built module says it needs it.

### Asset management
- **Click-to-view asset files** from the explorer: textures render on a checkerboard backdrop with zoom and a one-click jump into the built-in **pixel editor**; sounds resolve their sound event for playback.
- **Asset picker bank** with search, namespace filter and thumbnails across every loaded pack and mod.
- **Drag & drop** image files onto the window to upload them into the active project.
- Texture editor: pencil, eraser, fill, eyedropper, palette, mirror/flip/rotate, undo, zoom, grid.

### Working with other mods
- **Mod catalog**: ids, tags, events and recipe fields of every installed mod are dumped once and completed inside strings; *Validate* warns about unknown ids.
- `recipe("create:mixing", ...)`, `Attributes(...)` and `raw_json(..., namespace=)` reach into other mods (see the in-game guide).

### Runtime
- Declarative Python API (`@block`, `@item`, recipes, loot tables, tags, events).
- **Mobs** (`Mob(...)`): a vanilla body (humanoid, player, pig, cow, chicken, spider, creeper, villager, slime) or a
  GeckoLib `.geo.json` model with walk / idle animations, stats, hostile or passive AI, drops, a spawn egg
  (`/craftpy give <player> <project>:<id> egg`), `/craftpy summon` and natural spawns (`Spawn(...)`).
- **Effects** (`Effect(...)`): name, icon, color, category and attributes per level; `@on.effect_tick` runs once a
  second on whoever has one.
- Projects load/unload/reload at runtime with automatic resource + data pack regeneration and multiplayer sync.
- Persistent slot mapping (`pyforge/slots.json`) so placed blocks survive reloads and restarts.

### Structures
- `.nbt` templates (what structure blocks save) go in a project's `data/structure/`. `world.place_structure(...)` and
  `/craftpy place <project>:<name> [pos] [rotation]` place one at once (after **Run**).
- `Pool(...)` + `Structure(...)` make jigsaw structures that generate in new chunks like villages. Minecraft reads world
  generation only when a world opens, so a new or changed structure appears after reopening the world;
  `/place structure <project>:<id>`, `/locate structure` and `world.place_jigsaw(pool, ..., target=...)` work then.
- `/craftpy structure import <saved name> <project>` copies what a structure block saved in this world into the project.
  CraftPy blocks in it are kept as content ids (`craftpy_content:<project>/<id>`) and mapped to this game's slots on
  every build, so the template works in any game. Without the project that declares such a block it becomes air.
- A project's whole `data/` folder now goes into the data pack (`data/structure`, `data/worldgen`, `data/recipe`,
  `data/tags`...; a folder named like a namespace, `data/minecraft/...`, stays that namespace).

### Media player and the shared pool
- `pyforge/shared/` holds your own files (`shared/music` is made on the first start). A module reads a folder of it only
  when it declares `"permissions": {"shared": ["music"]}` and you approved it; your own projects are allowed as declared.
- `media` (client side) plays OGG, WAV, AIFF, MP3 (JLayer) and FLAC (jFLAC) through Minecraft's sound engine (the
  *Jukebox/Note Blocks* slider), plus any music sound event of the game, resource packs and mods. A track keeps playing
  when its window closes. Opus, M4A and AAC are not supported.
- `nearby=True` sends the file to the server, which passes it to players within `media.shareRange` blocks (they hear it
  around you, also when they walk up later). Server settings: `media.shareNearby`, `media.maxShareMb`, `media.shareRange`;
  each player can turn other players' media off with `media.hearOthers`.
- The **Assets** tool window has a **Shared** tab: preview audio and **Import** any file into the open project (MP3, FLAC,
  WAV and AIFF become OGG when the converter of the sound editor is installed).
- The `jukebox` example (`examples/jukebox`) is a full media player built on this: press **J**.

### Modules per world
- Which modules are on is saved in the world (`<world>/craftpy/modules.json`). A new world starts with every module off;
  worlds from before keep the modules that were on.
- On a server you join, the server's world decides for its modules. Your own client-side modules get a list per server
  on your computer (`pyforge/system/servers/<address>.json`, all off the first time): `/craftpy_client modules`,
  `/craftpy_client modules enable <id>`.

### Namespace isolation
- Project content is addressed as `<project>:<id>` (e.g. `project_bb:ruby`) — never overlapping with vanilla or mod registry ids.
- Project content is **not** exposed in the creative menu and **cannot** be obtained with vanilla `/give`; it is distributed exclusively through [`/craftpy give`](#the-craftpy-command).

## Requirements

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **Mod loader** | NeoForge 21.1.x |
| **JDK** | 21 or newer |
| **Python** | **Nothing to install**: the mod ships CPython 3.13 for Windows x64, macOS (Intel and Apple silicon) and Linux x64 / ARM64 |
| **Modern UI** | 3.13+ for NeoForge 1.21.1, **client only** (servers do not need it) |

### Python API versions

The Python API has a version (`API_VERSION`, now 2.2; `apiVersion` in `project.json` and `module.json`). A minor
version only adds, so everything written for 2.0 keeps working on 2.1; a major version may rename, and the old names keep
working with an `[api]` note in the console saying what replaces them. A project written for a newer API than the
installed CraftPy gets a note to update. What changed in each version: **API versions** in the API docs (generated from
`API_CHANGES` in `worker.py`, the one place the version is written).

### Two downloads

`./gradlew build` makes both in `build/libs/`: `craftpy-<version>.jar` (about 140 MB, Python inside) and
`craftpy-<version>-no-python.jar` (about 3 MB). For the small one, `install-python.cmd` (Windows: winget or the python.org
installer, for the user only, added to PATH) and `install-python.sh` (macOS / Linux) install Python 3.13; `INSTALL.md`
explains both. They are copied next to the jars from `dist/`.

For Modrinth and CurseForge (100 MB per file) `build` also makes one jar per system with only that system's Python:
`craftpy-<version>-windows.jar` (about 25 MB), `-macos.jar` (Intel + Apple silicon, about 53 MB) and `-linux.jar`
(x64 + ARM64, about 67 MB). On another system such a jar uses the Python on `PATH`. `installerJar` also builds
`craftpy-installer-windows.jar` / `-macos` / `-linux` with the matching jar inside.

### Python is included

You do not install Python. The mod jar carries CPython 3.13 (python-build-standalone, pinned by release and SHA-256) for
Windows x64, macOS Intel / Apple silicon and Linux x64 / ARM64. The first start unpacks the one for your computer to
`pyforge/system/python/` (a few seconds, once; checked against its hash) and every later start uses it. The order is:
`pythonPath` in the config, a Python you put in `pyforge/system/runtime/`, the shipped one, then `python` on `PATH`.
That is why the jar is about 140 MB. Building it: `./gradlew build` downloads the five runtimes once into
`build/python-runtimes/` (`./gradlew downloadPython`); `-Pcraftpy.bundlePython=false` builds a small jar without them.

### Installer

`./gradlew installerJar` builds `build/libs/craftpy-installer.jar` (the mod jar is inside). Double-click it, or run
`java -jar craftpy-installer.jar --cli`. It shows every step before it runs anything: NeoForge 21.1.259 (via its own installer),
Modern UI 3.13.0.1 and CraftPy into `mods/`, all downloads checked against a pinned SHA-256. Python is not a step: it is
inside the mod. Flags: `--dry-run`, `--yes`, `--dir <minecraft folder>`, `--craftpy <jar>`. Start the Minecraft launcher once before the first run so it has a profile.

## Getting Started

### Build and run (development)

```bash
./gradlew build        # compile + run the unit tests
./gradlew runClient    # launch a development client
```

On Windows use `gradlew.bat`. The dev instance lives in `run/`, and everything CraftPy stores is created under `run/pyforge/` on first launch.

Modern UI is a compile-only dependency. Before `runClient`, put `ModernUI-NeoForge-1.21.1-3.13.0.1-universal.jar` (CurseForge/Modrinth) into `run/mods/`.

### In-game first steps

1. Get a **CraftPy Computer** (creative tab *CraftPy*, or craft it — recipe included) and right-click it.
2. Create a project on the **Home** page; it opens in the **Editor**.
3. Edit `code/main.py`, press **Run** (or `Ctrl+R`), and check the console output.
4. Get your content with `/craftpy give <you> <project>:<id> <block|item>` (Tab completes the content list).

### Keyboard shortcuts (code area)

Press `F1` in the editor for the full list, or `Ctrl+Shift+A` to find any command by name. The main ones:

| Shortcut | Action |
|---|---|
| `Ctrl+S` | Save (trims trailing whitespace if enabled) |
| `Ctrl+R` / `F5` | Run the project / run it and reload every module |
| `Ctrl+Enter` | Validate the file on the Python worker |
| `Ctrl+Space`, `Ctrl+Q` | Completion, documentation |
| `Ctrl+click`, `Ctrl+B` | Go to definition (`Alt+Left` back) |
| `Ctrl+F` / `Ctrl+H` / `Ctrl+Shift+F` | Find / replace / find in files |
| `Ctrl+P`, `Ctrl+T`, `Ctrl+G` | Go to file, symbol, line |
| `Ctrl+Shift+A` | Find action |
| `Ctrl+/`, `Ctrl+D`, `Ctrl+Shift+K` | Comment, duplicate, delete line |
| `Alt+Shift+Up` / `Down` | Move lines |
| `Ctrl+Alt+L`, `Shift+F6` | Reformat, rename in file |
| `Ctrl+\` | Split the editor |
| `Alt+1` / `4` / `6` / `8` / `9` | Project / Console / Problems / Building blocks / Assets |
| `Ctrl+Alt+S` | Settings |
| `F11`, `Ctrl+Shift+F12` | Zen mode, hide all tool windows |

### The IDE without the game

One click: `./gradlew ideShortcut` builds `build/ide/` and puts a **CraftPy IDE** shortcut with the logo on the desktop.
`./gradlew ideDist` only builds the folder: double-click `build/ide/CraftPy IDE.bat` (or `craftpy-ide.sh`); the folder can
be moved anywhere. The game folder it works on is `home=` in `build/ide/craftpy-ide.properties` (the dev `run/` folder at
first; remove the line to use your Minecraft folder). From the sources: `./gradlew runIde` (`-PcraftpyHome=<game folder>`). Editing, completion, validation, the console, building modules and the asset browser (vanilla assets) work;
running a project in a world, sounds and the 3D preview need the game.

### Console (bottom panel)

Everything typed in the console is evaluated by the project's Python worker (persistent REPL: variables and imports survive between lines). `Up` / `Down` walk the input history. **Clear** empties the log.

Console tools (only in the console, `tools()` lists them): `me` (you: `me.give("diamond")`), `here()` (position,
biome, block under you), `content()` (what the project declares), `give_all()` (one of every item and block of the
project), `showcase()` (every block of the project placed in a row), `fire("block_break", block="stone")` (runs the
project's handlers now with test data), `timers()` / `stop_timers()`, `slowest()` (handler times), `bench(fn, n)`,
`near(radius)` (entities around you by type), `docs(name)` (signature and description of any API name).

## Writing Content in Python

```python
from craftpy import *

ruby = Item("ruby", texture="ruby.png")                      # assets/textures/ruby.png
ruby_ore = Block("ruby_ore", texture="ruby_ore.png", hardness=3,
                 tool="pickaxe", needs_tool=True, drops=(ruby, 2))

shaped(ruby, "DD", "DD", D="diamond")
smelt(ruby_ore, ruby, xp=0.7)


@on.block_break(block=ruby_ore)
def found_ruby(event):
    event.player.say("You found rubies!")
    event.player.effect("haste", seconds=10)
```

After **Run**, get the content with `/craftpy give @s <project>:ruby_ore block`.

The full reference (blocks, items, recipes, events, players, world, entities, timers, storage,
commands and limits) is the **Docs** page in game, from
[`api.md`](src/main/resources/assets/craftpy/docs/api.md). The older decorator style
(`@block(...) class X: pass`, `recipe_shaped(...)`) still works.
The `welcome` project (installed on first start) is the guided tour; a second example, `ruby_rush`, lives in [`examples/`](examples).

## The `/craftpy` Command

Project content is deliberately *not* registered under vanilla item ids and cannot be obtained with `/give`. Use:

```text
/craftpy give <player> <project>:<id> <block|item> [count]
```

Examples:

```text
/craftpy give Dev project_bb:ruby block
/craftpy give Dev welcome:gem_wand item 3
/craftpy give Dev ruby block          # bare id works when unambiguous
```

- Requires permission level 2 (op).
- **Tab completes** the full list of project content ids, and the `block`/`item` kinds — like vanilla item commands.
- `<project>:<id>` is the id used in `@block`/`@item` (`project_`-prefixed aliases like `project_bb:ruby` for project `bb` are accepted).
- `count` is optional (1–64).
- `egg` instead of `block`/`item` gives a spawn egg of a `Mob(...)`: `/craftpy give Dev zoo:goblin egg`.
- `/craftpy summon <project>:<mob> [pos]` puts a mob in the world (Tab completes the mobs).
- `/craftpy place <template> [pos] [rotation]` places a structure template; `/craftpy structure import <name> <project>`
  copies a structure block save into a project.
- Commands declared with `@command("name")` in a project become `/<project>:<name>` and tab-complete as soon as the project runs.
- `/craftpy module enable|disable|info|reload <module>` complete every discovered module.

## Project Layout

Everything is stored as plain files, so projects can also be edited with any external editor:

```text
<instance>/pyforge/
├── system/python/      # the Python shipped in the mod jar, unpacked on the first start
├── runtime/            # worker.py (a Python you put here yourself is used before the shipped one)
├── logs/
├── modules/            # installed modules (.cpm): drop new ones here, Build module writes here too
├── slots.json          # persistent content-id -> slot mapping
├── build/              # generated resource/data packs (disposable)
└── projects/
    └── <project_id>/
        ├── project.json    # name, version, side, dependencies, permissions, ...
        ├── code/           # main.py + modules
        ├── assets/
        │   ├── textures/   # *.png  -> addressable as "<project>:<name>"
        │   ├── models/     # *.json
        │   ├── sounds/     # *.ogg
        │   ├── lang/ animations/ other/
        ├── data/           # data pack files: structure/*.nbt, worldgen/, recipe/, tags/ ...
        └── build/          # per-project generated output
```

## How It Works

- **Slot pool.** Because registries freeze after startup, CraftPy pre-registers generic `SlottedBlock`/`SlottedItem` slots. Running a project maps each `@block`/`@item` definition onto a slot; the definition drives name, tooltip, texture, hardness, light, food values and more at runtime. The mapping persists across restarts, so placed blocks stay stable.
- **Dynamic packs.** Project assets are copied into a generated resource pack and the datapack contributions (recipes, loot tables, tags) into a generated data pack, registered through pack finders. Loading a project rebuilds the packs and triggers a resource/datapack reload; clients are synced on multiplayer.
- **Python worker.** Scripts run in a sandboxed external CPython subprocess (JSON-RPC over stdio), not in the JVM. Declarative content is collected at load time; behavior runs through whitelisted calls with a per-call timeout budget.

## Configuration

`config/craftpy-common.toml`:

| Key | Default | Description |
|---|---|---|
| `enableScripting` | `true` | Master switch for loading/running projects |
| `permissionLevel` | `2` | Permission level required to use the IDE (singleplayer owner always allowed) |
| `pythonPath` | `""` | Explicit Python executable; empty = the shipped one (then `PATH`) |
| `pythonMemoryMb` | `1024` | Memory the Python worker may use; code that needs more is stopped |
| `debugDefault` | `false` | Start with debug tracing on |
| `hotReloadOnSave` | `false` | Re-run a loaded project 0.7 s after a code/ui/asset file was saved (one run for a burst of saves) |
| `javaBridge.mode` | `RESTRICTED` | `OFF`, `RESTRICTED` (Minecraft, NeoForge, Mojang libs, CraftPy + `javaBridge.extraPackages`) or `FULL` |
| `javaBridge.extraPackages` | `[]` | Package prefixes of other mods Python may use in RESTRICTED mode |
| `eventTimeoutMs` | `50` | Budget per Python event callback before cancellation |
| `maxPacketSizeKb` | `32768` | Cap for project file packets |
| `maxAssetSizeKb` | `8192` | Cap for imported asset files |
| `slotPool.blocks` / `.items` | `256` | Deprecated, ignored. The pool size is `config/craftpy-slots.properties` (default 256, max 4096) |
| `slotPool.entities` / `.effects` / `.sounds` | `64 / 32 / 32` | Ignored: mobs and effects have fixed pools (32 passive + 32 hostile mobs, 32 effects) |

## Security

Every project and module runs in **its own** Python subprocess. The game knows which process is calling, so whatever a script does inside its own interpreter it cannot speak as another module, use another module's approved sites, data folder or saved values, or read another module's memory. Each process:

- starts with an empty environment (no secrets of the game's environment), `python -I`, a memory cap, and on Windows a job that forbids child processes;
- can read only Python itself, `worker.py`, its own code folder and the code of the modules it imports; an audit hook refuses every write, other folders, processes, sockets and native libraries;
- reaches the web, files, commands and the Java bridge only through the game, which checks what the project declared in `"permissions"` and, for modules, what was approved for that exact module file (a changed `.cpm` asks again). `commands` runs at level 2 and `java` is full trust.

Inside the process there is also a denylist of OS, filesystem, network and dynamic-code modules (`os`, `subprocess`, `socket`, `ctypes`, `pickle`, ...), a restricted builtin set (`open`, `eval`, `exec` are blocked) and checks on interpreter internals, plus a per-call timeout.

**The in-process checks are best-effort**: a determined attacker can escape the interpreter sandbox, and the process boundary above is what limits the damage. A native exploit of CPython itself is not covered. Only run projects you trust. For hardening, run the game under a low-privilege OS account and consider OS-level isolation (Windows job objects, Linux namespaces/seccomp). Access to the IDE and project loading is additionally gated by `permissionLevel`, and `enableScripting=false` disables the scripting pipeline entirely.

## Known Limitations

- Mobs and effects use fixed pools: 32 passive mobs, 32 hostile mobs, 32 effects. A mob that switches between
  hostile and passive takes a new slot. Natural spawns (`Spawn(...)`) are read when a world opens, so new ones start
  after the next world load.
- Slot pools are fixed at startup (default 256 blocks / 256 items, at most 4096 each via `config/craftpy-slots.properties`); more at the same time needs a restart.
- Binary asset previews are capped at 288 KB per file (larger files open with a notice).
- Only CPython 3.13+ is supported (no Jython/GraalPy). The shipped Python covers Windows x64, macOS and glibc Linux
  (x64 / ARM64); elsewhere (32-bit Windows, Alpine/musl) install CPython 3.13+ or set `pythonPath`.
- Hot reload of behavior is per-run: reload a project to re-execute its scripts.

## Development & Testing

```bash
./gradlew test          # unit tests (tokenizer, codec, docs, command resolution)
./gradlew build         # full build
./gradlew runGameTestServer
```

The codebase is organized as:

```text
src/main/java/net/xmilon/craftpy/
├── client/        # screens, editor, asset bank, client project store
├── command/       # /craftpy command + /give guard
├── dynamics/      # generated resource/data packs
├── lifecycle/     # project load/unload pipeline
├── network/       # client <-> server payloads
├── python/        # worker discovery + JSON-RPC runtime
├── registry/      # slot pool, items, blocks, creative tabs
└── project/       # on-disk project management
```

## License

CraftPy is free software under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)): you may use, change
and share it, and versions you share must stay under the GPL with their source. Bundled third-party code (CPython,
JLayer, jFLAC) keeps its own license: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The NeoForge MDK template files
are MIT ([TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt)).
