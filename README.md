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

### In-game UI (built on Modern UI)
One screen with a navigation rail, styled like Modern UI's own settings center:
- **Home**: create projects; open, run, stop, rename, duplicate or delete them; recent files.
- **Modules**: every built `.cpm`, with search, enable/disable switches, Reload all, details and the rendered README.
- **Editor**:
  - explorer (preview on click, keep open on double click; right click for new / rename / delete), pinned and preview tabs, a tab overflow menu, quick open (`Ctrl+P`)
  - code area with themes (Dark, Light, Monokai, Dracula, Solarized Dark, High Contrast), zoom, per-file word wrap, current-line and same-word highlight, a ruler, line numbers with bookmarks
  - editing helpers: auto-closing brackets and quotes, surround selection, `else:`/`except:` dedent, smart Home, sort / dedupe / join / split lines, trim trailing whitespace on save
  - find and replace with history and "in selection"; Console (Python REPL), Problems and Bookmarks panels; resizable explorer and bottom panel; zen mode
  - toolbar: Edit, View, Save, Validate, Assets, Build, Stop, **Reload** (re-run the project and reload every module), Run
- **Docs**: the offline API reference, rendered as Markdown.

### Asset management
- **Click-to-view asset files** from the explorer: textures render on a checkerboard backdrop with zoom and a one-click jump into the built-in **pixel editor**; sounds resolve their sound event for playback.
- **Asset picker bank** with search, namespace filter and thumbnails across every loaded pack and mod.
- **Drag & drop** image files onto the window to upload them into the active project.
- Texture editor: pencil, eraser, fill, eyedropper, palette, mirror/flip/rotate, undo, zoom, grid.

### Runtime
- Declarative Python API (`@block`, `@item`, recipes, loot tables, tags, events).
- Projects load/unload/reload at runtime with automatic resource + data pack regeneration and multiplayer sync.
- Persistent slot mapping (`pyforge/slots.json`) so placed blocks survive reloads and restarts.

### Namespace isolation
- Project content is addressed as `<project>:<id>` (e.g. `project_bb:ruby`) — never overlapping with vanilla or mod registry ids.
- Project content is **not** exposed in the creative menu and **cannot** be obtained with vanilla `/give`; it is distributed exclusively through [`/craftpy give`](#the-craftpy-command).

## Requirements

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **Mod loader** | NeoForge 21.1.x |
| **JDK** | 21 or newer |
| **Python** | CPython **3.13+** on `PATH` (or set `pythonPath` in config; a runtime placed in `pyforge/runtime/` also works) |
| **Modern UI** | 3.13+ for NeoForge 1.21.1, **client only** (servers do not need it) |

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

Press `F1` (or `Ctrl+/`) in the editor for the full list. The main ones:

| Shortcut | Action |
|---|---|
| `Ctrl+S` | Save (trims trailing whitespace if enabled) |
| `Ctrl+R` / `F5` | Run the project / run it and reload every module |
| `Ctrl+Enter` | Validate the file on the Python worker |
| `Ctrl+F` / `Ctrl+H`, `F3` | Find / replace, next match |
| `Ctrl+P` | Quick open |
| `Ctrl+W` | Close tab |
| `Ctrl+J`, `Ctrl+Shift+Enter` | Join lines, split line |
| `Ctrl+F2`, `F2` / `Shift+F2` | Toggle bookmark, next / previous |
| `Ctrl+=` / `Ctrl+-` / `Ctrl+0` | Zoom (also `Ctrl` + mouse wheel) |
| `F11` | Zen mode |
| `Esc` | Close the screen (unsaved edits stay in memory until you save or close the tab) |

### Console (bottom panel)

Everything typed in the console is evaluated by the project's Python worker (persistent REPL: variables and imports survive between lines). `Up` / `Down` walk the input history. **Clear** empties the log.

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
Two example projects live in [`examples/`](examples): `tutorial` (start here) and `ruby_rush`.

## The `/craftpy` Command

Project content is deliberately *not* registered under vanilla item ids and cannot be obtained with `/give`. Use:

```text
/craftpy give <player> <project>:<id> <block|item> [count]
```

Examples:

```text
/craftpy give Dev project_bb:ruby block
/craftpy give Dev tutorial:ruby_wand item 3
/craftpy give Dev ruby block          # bare id works when unambiguous
```

- Requires permission level 2 (op).
- **Tab completes** the full list of project content ids, and the `block`/`item` kinds — like vanilla item commands.
- `<project>:<id>` is the id used in `@block`/`@item` (`project_`-prefixed aliases like `project_bb:ruby` for project `bb` are accepted).
- `count` is optional (1–64).
- Commands declared with `@command("name")` in a project become `/<project>:<name>` and tab-complete as soon as the project runs.
- `/craftpy module enable|disable|info|reload <module>` complete every discovered module.

## Project Layout

Everything is stored as plain files, so projects can also be edited with any external editor:

```text
<instance>/pyforge/
├── runtime/            # worker.py and an optional bundled Python
├── logs/
├── slots.json          # persistent content-id -> slot mapping
├── build/              # generated resource/data packs (disposable)
└── projects/
    └── <project_id>/
        ├── project.json    # name, version, author, description, ...
        ├── code/           # main.py + modules
        ├── assets/
        │   ├── textures/   # *.png  -> addressable as "<project>:<name>"
        │   ├── models/     # *.json
        │   ├── sounds/     # *.ogg
        │   ├── lang/ animations/ other/
        ├── data/           # data pack JSON (recipes, loot, tags)
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
| `pythonPath` | `""` | Explicit Python executable; empty = auto-detect |
| `autoDownloadPython` | `true` | Allow falling back to a runtime in `pyforge/runtime/` *(auto-download is not bundled in this build)* |
| `debugDefault` | `false` | Start with debug tracing on |
| `hotReloadOnSave` | `false` | Re-run a loaded project 0.7 s after a code/ui/asset file was saved (one run for a burst of saves) |
| `javaBridge.mode` | `RESTRICTED` | `OFF`, `RESTRICTED` (Minecraft, NeoForge, Mojang libs, CraftPy + `javaBridge.extraPackages`) or `FULL` |
| `javaBridge.extraPackages` | `[]` | Package prefixes of other mods Python may use in RESTRICTED mode |
| `eventTimeoutMs` | `50` | Budget per Python event callback before cancellation |
| `maxPacketSizeKb` | `32768` | Cap for project file packets |
| `maxAssetSizeKb` | `8192` | Cap for imported asset files |
| `slotPool.blocks` / `.items` | `256` | Deprecated, ignored. The pool size is `config/craftpy-slots.properties` (default 256, max 4096) |
| `slotPool.entities` / `.effects` / `.sounds` | `64 / 32 / 32` | Reserved pools for later milestones |

## Security

Project Python runs in an external subprocess with a denylist of OS, filesystem, network and dynamic-code modules (`os`, `subprocess`, `socket`, `ctypes`, `pickle`, ...), a restricted builtin set (`open`, `eval`, `exec` are blocked), and a per-call timeout.

**This is best-effort sandboxing, not a hard sandbox** — a determined attacker can escape it. Only run projects you trust. For hardening, run the game under a low-privilege OS account and consider OS-level isolation (Windows job objects, Linux namespaces/seccomp). Access to the IDE and project loading is additionally gated by `permissionLevel`, and `enableScripting=false` disables the scripting pipeline entirely.

## Known Limitations

- **Entities and effects** can be declared but are not yet wired into the game (later milestone).
- Slot pools are fixed at startup (default 256 blocks / 256 items, at most 4096 each via `config/craftpy-slots.properties`); more at the same time needs a restart.
- Binary asset previews are capped at 24 KB per file (larger files open with a notice).
- Only CPython 3.13+ is supported (no Jython/GraalPy).
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

MIT — see [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt).
