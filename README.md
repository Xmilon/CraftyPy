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

Minecraft 1.21.1, NeoForge 21.1, Java 21. The editor needs [Modern UI](https://modrinth.com/mod/modern-ui) 3.13+ on
the client; servers don't.

## Install

Put one jar in `mods/`:

- `craftpy-<version>.jar` (~140 MB) has Python 3.13 inside for Windows, macOS and Linux. Nothing else to install.
- `craftpy-<version>-windows.jar`, `-macos.jar`, `-linux.jar` carry only that system's Python (for Modrinth and
  CurseForge, which cap file size).
- `craftpy-<version>-no-python.jar` (~3 MB) uses the Python 3.13 on your PATH. `INSTALL.md` explains how to get one.

The first start unpacks Python to `pyforge/system/python/` and checks it against its hash.

## First steps

1. Get a **CraftPy Computer** (creative tab or crafting) and right-click it. Ops can also press `I`.
2. Make a project on the Home page and edit `code/main.py`.
3. Press **Run** (`Ctrl+R`). Output goes to the console at the bottom.
4. `/craftpy give <you> <project>:<id> block` gives you what you made.

The **Docs** page in game has the guide and the full API reference ([api.md](src/main/resources/assets/craftpy/docs/api.md)).
The `welcome` project is a guided tour, and `examples/` has more.

## Commands

| Command | What it does |
|---|---|
| `/craftpy give <player> <project>:<id> <block\|item\|egg> [count]` | Content of a project (vanilla `/give` can't) |
| `/craftpy summon <project>:<mob> [pos]` | Spawns a project mob |
| `/craftpy place <template> [pos] [rotation]` | Places a structure template |
| `/craftpy module enable\|disable\|info\|reload <module>` | Manages modules |
| `/craftpy project trust <project>` | Lets a project that came from elsewhere use what it declares |

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

The checks inside Python (blocked imports, no `open`/`eval`) are a second layer, not the wall. Only run modules you
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
```

`./gradlew build` downloads the Python runtimes once into `build/python-runtimes/`. Add
`-Pcraftpy.bundlePython=false` for the small jar only.

## Known limits

- 32 passive mobs, 32 hostile mobs and 32 effects at a time. Block and item pools default to 256 each, up to 4096 in
  `config/craftpy-slots.properties`, and a bigger pool needs a restart.
- New world generation (structures, natural spawns) applies when a world is opened.
- CPython 3.13+ only. On 32-bit Windows or musl Linux, install Python yourself and set `pythonPath`.

## License

GPL-3.0 ([LICENSE](LICENSE)). CPython, JLayer and jFLAC keep their own licenses
([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)). The NeoForge MDK template files are MIT.
