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

1. Right-click the **CraftPy Computer** to open the IDE.
2. Write or edit `code/main.py` (with completion, docs and snippets).
3. Press **Run** — the script executes on a sandboxed Python worker.
4. Your blocks and items appear in-game immediately.

## Features

### In-game IDE
- **Project explorer** with create / rename / delete and an IntelliJ-style file flow: clicking an asset previews it, text files open in the editor.
- **Tabbed editor** with per-tab close buttons, unsaved markers (`*`), and buffer preservation between tabs.
- **Python editor**: syntax highlighting, line numbers, auto-indent, bracket matching, undo/redo, find, duplicate line, comment toggle, mouse selection.
- **Go to definition** via shift-click, including cross-file lookup.
- **Bottom panel**: console (with a Python REPL and commands), problems, asset overview, and an offline API reference.

### Code completion
- Context-aware suggestions for the CraftPy API, Python keywords, builtins and identifiers in your file.
- **Documentation panel** on every suggestion — a signature plus a one-line description of what it does.
- **Field completion inside declarations**: typing inside `@block(...)` / `@item(...)` offers exactly the fields that definition supports (with descriptions).
- **Snippet templates**: accepting `block` or `item` at a statement start inserts the full declaration with its fields pre-filled (ghost previewed on screen; **Tab** applies it, typing continues normally).
- The popup dismisses itself the moment your typing stops matching — never stale.

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

## Getting Started

### Build and run (development)

```bash
./gradlew build        # compile + run the unit tests
./gradlew runClient    # launch a development client
```

On Windows use `gradlew.bat`. The dev instance lives in `run/`, and everything CraftPy stores is created under `run/pyforge/` on first launch.

### In-game first steps

1. Get a **CraftPy Computer** (creative tab *CraftPy*, or craft it — recipe included) and right-click it.
2. Create a project in the project chooser, then open it in the IDE.
3. Edit `code/main.py`, press **Run** (or `Ctrl+Shift+R`), and check the console output.
4. Get your content with `/craftpy give <you> <project>:<id> <block|item>` (Tab completes the content list).

### Keyboard shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+S` | Save the active file |
| `Ctrl+Enter` | Validate the project on the Python worker |
| `Ctrl+Shift+R` | Save and run the project |
| `Ctrl+F` | Find in file |
| `Ctrl+W` | Close the active tab |
| `Ctrl+Space` | Open the asset picker (on an asset reference) or completion |
| `Ctrl+D` | Duplicate line / selection |
| `Ctrl+/` | Toggle comment |
| `Ctrl+Z` / `Ctrl+Y` | Undo / redo |
| `Shift`+click | Go to definition |
| `Tab` / `Shift+Tab` | Accept suggestion / (un)indent |
| Right- or middle-click a tab | Close the tab |

### Console commands (bottom panel)

| Command | Action |
|---|---|
| `help` | List console commands |
| `run` | Save and run the project |
| `validate` | Check the active file |
| `open <file>` | Open a file in the editor |
| `files` | List project files |
| `api` | Show the API reference tab |
| `debug` | Toggle debug tracing |
| `clear` / `reset` | Clear console / reset console state |

Anything else typed in the console is evaluated by the project's Python worker (persistent REPL — variables and imports survive between lines).

## Writing Content in Python

```python
from craftpy import *

@block(
    "ruby_block",
    name="Ruby Block",
    texture="my_project:ruby_block",  # <project_id>:<file> from assets/textures/
    hardness=3.0,
    resistance=15.0,
    light=7,
    tool="pickaxe",
    sound="stone",
    needs_tool=True,
)
class RubyBlock:
    pass

@item(
    "ruby_wand",
    name="Ruby Wand",
    texture="my_project:ruby_wand",
    max_stack=1,
    glow=True,
    use_effect="speed:2:10",         # effect[:amplifier:seconds]
)
class RubyWand:
    pass

def main(ctx):
    ctx.log("project loaded")
```

> Replace `my_project` with your own project id — project assets are addressed as `<project_id>:<file name>`, e.g. `assets/textures/ruby_block.png` becomes `my_project:ruby_block`. After **Run**, get the content with `/craftpy give <you> my_project:ruby_block block`. A project block behaves like a normal block: it drops itself when mined, unless you declare `loot_table("ruby_block", entries=[...])` with the same id.

### API reference

| Construct | Purpose |
|---|---|
| `@block("id", **fields)` | Declare a block |
| `@item("id", **fields)` | Declare an item |
| `@entity("id", **fields)` | Declare a reskinned mob *(declared, not yet active)* |
| `@effect("id", **fields)` | Declare a mob effect *(declared, not yet active)* |
| `tex("ns:path")` / `snd(...)` / `mdl(...)` | Reference a texture / sound event / model |
| `recipe_shaped(result, pattern, keys)` | Shaped crafting recipe |
| `recipe_shapeless(result, ingredients)` | Shapeless crafting recipe |
| `recipe_smelting(result, ingredient, xp, cook_time)` | Furnace recipe |
| `loot_table("id", entries=[...])` | Custom drops (same id as a block → replaces its drop) |
| `loot_self()` | Drop the block/item itself |
| `tag("#id", values=[...])` | Add ids to a data pack tag |
| `lang("key", "value")` | Add a translation entry |
| `on("event", every=20)` | Event handler declaration |
| `main(ctx)` | Optional entry point; `ctx.log()` writes to the console |

**Block fields** — `name`, `texture`, `hardness`, `resistance`, `light` (0–15), `tool`, `sound`, `needs_tool`
**Item fields** — `name`, `texture`, `max_stack`, `glow`, `food` (`{"hunger", "saturation", ...}`), `burn_time`, `use_effect`

Tip: type `block` or `item` at the start of a line and press `Tab` — the editor inserts the full template with all fields, ready to fill in.

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
| `hotReloadOnSave` | `false` | Rebuild/reload the active project when files change |
| `eventTimeoutMs` | `50` | Budget per Python event callback before cancellation |
| `maxPacketSizeKb` | `32768` | Cap for project file packets |
| `maxAssetSizeKb` | `8192` | Cap for imported asset files |
| `slotPool.blocks` / `.items` | `256` | Pre-registered generic slots (hard cap 256 each) |
| `slotPool.entities` / `.effects` / `.sounds` | `64 / 32 / 32` | Reserved pools for later milestones |

## Security

Project Python runs in an external subprocess with a denylist of OS, filesystem, network and dynamic-code modules (`os`, `subprocess`, `socket`, `ctypes`, `pickle`, ...), a restricted builtin set (`open`, `eval`, `exec` are blocked), and a per-call timeout.

**This is best-effort sandboxing, not a hard sandbox** — a determined attacker can escape it. Only run projects you trust. For hardening, run the game under a low-privilege OS account and consider OS-level isolation (Windows job objects, Linux namespaces/seccomp). Access to the IDE and project loading is additionally gated by `permissionLevel`, and `enableScripting=false` disables the scripting pipeline entirely.

## Known Limitations

- **Entities and effects** can be declared but are not yet wired into the game (later milestone).
- Slot pools are capped (256 blocks / 256 items); exceeding them requires a restart-based mode that is not implemented yet.
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
