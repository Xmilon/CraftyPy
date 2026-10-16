# CraftPy Implementation Report

## Checklist

| Item | Status | Files Changed | Purpose |
| :--- | :--- | :--- | :--- |
| Milestone M1: Threading fixes | Completed | `CraftPyExecutors.java`, `ProjectManager.java`, `PyRuntime.java`, ... | separate pools, per-project serialization, exception logging |
| Milestone M2: ui-toolkit core | Completed | `net.xmilon.craftpy.client.ui.*`, `ComputerScreen.java` | retained-mode widget library, IDE shell port |
| Standalone checklist file: docs/REPORT.md | Completed | `docs/REPORT.md` | Tracking every requested item, status, files changed, purpose |
| File/folder creation inside the IDE | Completed | `src/main/java/net/xmilon/craftpy/client/screen/ComputerScreen.java` | explorer context menu, modal prompts, server action, ProjectManager |
| Fix generated item texture rendering | Completed | `src/main/java/net/xmilon/craftpy/dynamics/PackBuilder.java` | created items show their texture in-world |
| Add .md support inside the IDE | Completed | `CodeEditor.java`, `ComputerScreen.java` | open/edit/preview rendered markdown |
| Add .json support inside the IDE | Completed | `CodeEditor.java`, `ComputerScreen.java` | view/edit/format |
| API tab: render a full Markdown doc | Completed | `api.md`, `ComputerScreen.java` | examples, definitions, tips, limits |
| Event system scaffolding | Completed | `worker.py`, `PyRuntime.java`, `EventBridge.java`, `CraftPy.java` | Python API + worker + Java wiring |
| Item crafting + loot drops | Completed | `PackBuilder.java`, `SlotRegistry.java`, `PackBuilderRecipesTest.java` | verify existing datagen, add coverage/tests, wire missing pieces |
| Milestone M3: ComputerScreen on official GUI system | Completed (compile + tests); in-client verification pending | `client/ide/*`, `client/screen/ComputerScreen.java` | rewrite the IDE shell on Mojang's layout/widget system for a professional, maintainable IDE |
| Milestone M4: IDE UX pass (focus, console, tabs, theme) | Completed (compile + tests); in-client verification pending | `client/ide/IdeColors.java`, `client/ide/EditorTabs.java`, `client/screen/ComputerScreen.java` | fix prompt typing, embed console input, Window menu + closeable bottom tabs, OLED palette, tab-title clipping |
| Milestone M5: ModuleManagerScreen enhancements | Completed (compile + tests) | `client/screen/ModuleManagerScreen.java`, `client/ui/Theme.java` | right-click context menu per module, theme-aware colors, improved hover tooltips, modernized styling |
| Milestone M6: ModernUI integration | Completed | `build.gradle`, `ModuleManagerScreen.java`, `ProjectChooserScreen.java` | added ModernUI 3.13.0 dependency, theme-aware color system |
| Milestone M8: bug fixes, simple Python API, editor Batch A, editors, docs | Completed (compile + 74 JUnit tests incl. 10 Python worker tests + client starts); in-game pass pending | `python/*`, `lifecycle/EventBridge.java`, `dynamics/PackBuilder.java`, `project/ModuleLoader.java`, `command/*`, `client/mui/*`, `client/{Syntax,TextOps,EditorSettings,EditorTheme}.java`, `client/screen/{TextureEditor,SoundPlayer,ModelViewer,AssetPicker}Screen.java`, `worker.py`, `api.md`, `examples/` | see the M8 section |
| Milestone M7: UI rewrite on Modern UI (Home / Modules / Editor / Docs) | Completed (compile + 56 tests + client starts); in-game click-through pending | `client/mui/*`, `client/Syntax.java`, `CraftPyClient.java`, `build.gradle`, `neoforge.mods.toml` | replace the vanilla screens with one Modern UI screen styled like Modern UI's settings center |
| Real Markdown rendering (headings/bold/lists/code/tables) | Completed | `client/ide/MarkdownRenderer.java`, `client/ide/ScrollView.java`, `client/screen/ComputerScreen.java`, `MarkdownRendererTest.java` | render the Preview panel and API tab as styled Markdown instead of plain text |
| Full API documentation | Completed | `assets/craftpy/docs/api.md`, `client/screen/ApiDocs.java`, `client/screen/CodeEditor.java` | complete reference for every declaration, event, helper, scheduler, storage, commands and windows; richer completion docs |
| Python -> Java host bridge (world/player/server/storage) | Completed (compile + tests); in-client verification pending | `python/HostBridge.java`, `python/RpcClient.java`, `python/PyRuntime.java`, `lifecycle/EventBridge.java`, `worker.py` | synchronous, server-threaded reverse calls so Python can read/write the world, players, server and storage |
| Scheduler wiring (tick-driven `run_later`/`run_every`) | Completed (compile + tests); in-client verification pending | `worker.py`, `python/PyRuntime.java`, `lifecycle/EventBridge.java` | gated server-tick delivery so scheduled tasks run without per-tick cost when idle |
| Tier 1 datagen coverage (all recipe types, tags, advancements, sounds, particles, raw, lang) | Completed | `dynamics/PackBuilder.java`, `python/Declarations.java`, `PackBuilderDatagenTest.java`, `DeclarationsTest.java` | generate every vanilla recipe type plus the remaining datapack/resource kinds |

## Progress Details

### Milestone M1: Threading Fixes
- Reworked `CraftPyExecutors` to use three specialized thread pools:
    - **IO**: For file operations and pack building. Serialized per project using `SerialExecutor`.
    - **RPC**: For communication with the Python worker.
    - **UI**: For background IDE tasks like indexing and previews.
- Implemented `SerialExecutor` to ensure only one build/save operation happens per project at a time.
- Added exception wrapping to all async tasks to ensure they are logged instead of being swallowed by `CompletableFuture`.
- Updated all call sites in `ProjectManager`, `PyRuntime`, `ProjectController`, etc., to use the appropriate pools.
- Fixed pool lifecycle issues by using lazy synchronized initialization, allowing the mod to work across multiple world loads in the same JVM session.

### Milestone M2: UI Toolkit Core
- Created a new retained-mode UI toolkit in `net.xmilon.craftpy.client.ui`.
- Implemented base `Widget` and `Container` logic with event bubbling.
- Added `Theme` system with design tokens for colors and dimensions.
- Created basic widgets: `Panel`, `Label`, `Button`, `Spacer`, `ScrollView`, `VBox`, `HBox`.
- Implemented wrappers for existing components: `EditorWidget` and `EditBoxWidget`.
- Ported the `ComputerScreen` shell to use the new toolkit, significantly cleaning up the `render` method and improving layout management.
- Fixed P2 (Editor losing state on resize) by preserving the `CodeEditor` instance.

### File/Folder Creation
- Aligned `ComputerScreen` with `ProjectAction` enum (`CREATE_ENTRY`, `DELETE_ENTRY`, `RENAME_ENTRY`).
- Fixed payloads for file/folder creation to match server expectations.
- Removed stray `net` folder from repo root.

### Item Texture Rendering
- Fixed `PackBuilder` to use `mapRef` for generated models, correctly namespacing textures to the project ID.

### Markdown and JSON Support
- Added language detection to `CodeEditor` based on file extension.
- Implemented syntax highlighting for Markdown and JSON.
- Added a "PREVIEW" tab in the bottom panel for Markdown files.
- Added "Format Document" action for JSON files.

### API Tab
- Created a comprehensive `api.md` document in resources.
- Updated the API tab to render this document using the Markdown renderer.
- Removed legacy `API_ROWS` static data.

### Event System Scaffolding
- Updated `worker.py` to support persistent event handlers and `trigger_event` RPC.
- Added `triggerEvent` to `PyRuntime.java`.
- Created `EventBridge.java` to forward NeoForge events (break block, player join) to Python.
- Registered `EventBridge` in `CraftPy.java`.

### Item Crafting and Loot Drops
- Fixed `PackBuilder` to correctly handle namespaces in filenames and `mapRef`.
- Added support for inline loot definitions in `@block` decorators.
- Fixed a bug in `SlotRegistry` where blocks and items with the same name would collide.
- Ensured `writeJson` creates parent directories.
- Updated `PackBuilderRecipesTest` to be self-contained and pass with the new registry logic.
- Verified all datagen tests pass.

### Milestone M3: ComputerScreen on the Official GUI System
- Rewrote `ComputerScreen` (`net.xmilon.craftpy.client.screen`) on top of Mojang's official 1.21.1 GUI
    classes (`AbstractWidget`, `AbstractContainerEventHandler`, `AbstractScrollWidget`, `LinearLayout`,
    `LayoutElement`, `SpacerElement`) instead of the custom retained-mode toolkit.
- Added `net.xmilon.craftpy.client.ide` with reusable widgets: `IdeColors` (dark palette),
    `IconButton`, `ScrollView`, `EditorTabs`, `CodeEditorWidget`.
- Preserved all prior IDE behavior: File/Edit/Run/Assets/Help menus, Build/Run/Stop/Assets toolbar,
    explorer tree + context menu, console REPL, problems/preview/settings bottom tabs, find & replace,
    prompt/confirm dialogs, command palette, definition jump, API virtual file, single-click asset
    picker, Import-from-PC, drop handling, and Ctrl shortcuts.
- Fixed overlay z-order and input priority by adding overlay widgets last and dispatching clicks/scroll
    through a single reversed-order handler (`dispatchClick`/`dispatchScroll`).
- Kept the `net.xmilon.craftpy.client.ui` toolkit on disk because `SlottedScreen` still depends on
    `UIContext`/`Widget`.
- Verified `.\gradlew.bat compileJava` is `BUILD SUCCESSFUL` and all `net.xmilon.craftpy.command.*`
    tests pass. Runtime (in-client) verification is still pending.

### Milestone M4: IDE UX Pass
- Fixed the modal prompt/new-file text box not accepting typing: `dispatchClick` was calling
    `Screen.setFocused(overlay)` after a menu item ran, stealing focus from the `EditBox` that the
    action had just focused (`EditBox.charTyped` requires `isFocused()`). Focus is now left alone
    while a prompt/confirm/command-palette modal is open.
- Integrated the console input into the bottom console panel (IntelliJ style): the input is now
    borderless (`EditBox.setBordered(false)`), positioned on the panel's last line behind a `>>>`
    prompt, with a trailing spacer row so it no longer hides the newest log line.
- Added a **Window** menu that lists the bottom tabs (Console/Problems/Preview/Settings) with
    checkmarks and toggles them; bottom tabs are now closeable via an `x` on the active tab. Closing
    the last tab hides the bottom panel and lets the editor/explorer reclaim the space.
- Updated `IdeColors` to a true-black OLED palette with IntelliJ's new-UI accent blue.
- Clipped editor tab titles (`EditorTabs`) so long file names no longer overlap the dirty/close
    markers or the next tab.
- **Fixed the file explorer showing no project files.** `ExplorerPanel.addPath` built ancestor
    directory nodes for a path but never inserted the top-level one into `roots`; since
    `ProjectManager.listFiles` returns only files (never `code/`, `assets/`, ...), every file in a
    subdirectory hung off a directory node that was never walked. The tree therefore only ever
    showed root-level files. `addPath` now registers each ancestor as a node and inserts the
    top-most directory into `roots` (`ComputerScreen.java`).
- Verified `.\gradlew.bat compileJava` is `BUILD SUCCESSFUL` and all `net.xmilon.craftpy.command.*`
    tests pass. Runtime (in-client) verification is still pending.

### Markdown Rendering + Full API Docs
- Added `client/ide/MarkdownRenderer.java`: a pure-data renderer that turns Markdown into styled
    `ScrollView.Row`s - ATX headings (#..####), fenced code blocks (with a language tag row),
    horizontal rules, blockquotes, bullet/ordered lists, tables (inline-parsed cells with aligned
    columns) and inline **bold**, *italic*, `code` and [links](url).
- Extended `ScrollView` rows with `Span`s, a `Style` enum and per-row heights so code blocks,
    quotes, headings, rules and tables render with distinct backgrounds/bars.
- `ComputerScreen.markdownRows()` now renders `.md`/`.markdown` through the renderer (plain rows
    otherwise) and auto-opens the Preview tab for a freshly opened Markdown file.
- Rewrote `assets/craftpy/docs/api.md` into a complete reference (16 sections) covering content
    declarations, all recipe types, loot, tags, advancements/sounds/particles/raw/lang, events,
    commands, world/players/server/storage helpers, the scheduler, capabilities, windows, the
    console, a Limits & possibilities table and worked examples.
- Enriched `ApiDocs` (completion popup) and `CodeEditor.CRAFTPY_API` with the real, full API
    surface so suggestions carry signatures + descriptions and insert correct call templates.
- Verified with `MarkdownRendererTest` (8 tests) and `ApiDocsTest`; all pass.

### Python -> Java Host Bridge
- Added `python/HostBridge.java`: executes reverse calls issued by project Python. Every world
    mutation runs on the server thread (`MinecraftServer.execute` + `CompletableFuture` with an
    8s timeout); unknown methods return `{ok:false}` instead of throwing.
- Implemented and wired: `world.get_block`, `world.set_block`, `world.blocks_in_box`,
    `world.entities_in_box`, `world.spawn`, `world.play_sound`, `world.particle` (simple types),
    `world.get_time`, `world.set_time`, `world.set_weather`; `players.all/get`,
    `player.send_message/teleport/give`; `server.broadcast/command/log`; `storage.get/set/clear`.
- `RpcClient` gained `setHostHandler`, a dedicated `craftpy-host-call` thread and `host_call`
    dispatch (cleanly shut down in `stopInternal`). `PyRuntime` registers `HostBridge::handle`
    before the worker starts and tracks the pending-task count returned by the worker.
- `EventBridge` now subscribes to `ServerTickEvent.Post` and delivers ticks only while
    `PyRuntime.hasScheduledTasks()`, plus it passes positions/dimensions on block events and
    richer player data.
- `worker.py` gained the player facade methods and a correctly-named `world.blocks_in_box`
    (kept `get_block_entities` as an alias). Verified the worker compiles (`py_compile`).
- In-client verification is still pending (needs a running server).

### Tier 1 Datagen Coverage
- `PackBuilder.writeRecipe` now handles `blasting`, `smoking`, `campfire`, `stonecutting` and
  `smithing` in addition to shaped/shapeless/smelting, with `#tag` ingredient support and a
  `count` field on results.
- `writeTag` now maps registry types to the correct vanilla folders (`tags/blocks`, `tags/items`,
  `tags/entity_types`, `tags/fluids`, ...) and accepts object values (`{"id", "required"}`).
- Loot entries accept `loot_entry`-style objects with `count`/`weight` (emitted as a
  `minecraft:set_count` function / `weight`).
- Added datapack writers for `advancement`, `enchantment` and `raw_json` (with path-traversal
  guard), plus resource writers for `sounds.json`, per-project `particles/` and merged `lang`.
- `Declarations` parses the new worker keys (`entities`, `advancements`, `sounds`, `particles`,
  `enchantments`, `raw`) and merges project `lang`.
- Verified with `PackBuilderDatagenTest` (7 tests) and `DeclarationsTest` (2 tests); full suite
    (63 tests) is green.

### Milestone M5: ModuleManagerScreen Enhancements
- **Theme-aware colors**: Migrated all hardcoded color constants to use `Theme.current().color(Theme.TOKEN)` for dynamic theming support (dark, light, high-contrast, etc.)
- **Right-click context menu**: Added `ContextPopup` widget for per-module actions (Enable/Disable, Open Folder, Reload, Delete)
- **Right-click on module row**: Opens context menu at cursor position; left-click still selects
- **Improved hover tooltips**: Now use theme-aware colors; shows module name, id, version, author, source, path, size, status, duplicate/error badges
- **Keyboard navigation**: ESC closes context menu; F5 refreshes module list
- **Confirm deletion**: Double-press Delete required to delete a module
- **Modernized styling**: Uses Theme tokens (bg, panel, panel_alt, accent, text, muted, border, selected, error, success, warning) consistently

### Milestone M6: ModernUI Integration
> Correction (M7): the dependency set below did not compile once used (icu4j/fastutil clash with
> Minecraft's strict pins) and no screen actually used Modern UI. Replaced in M7.
- **Added ModernUI dependency**: `dev.icyllis:modernui-core:3.13.0` and `icyllis.modernui:ModernUI-Markflow:3.12.0` via maven.izzel.io/releases
- **Transitive dependencies**: Explicitly declared icu4j, fastutil, slf4j-api, arc3d-* to ensure resolution
- **Theme system**: Integrated ModernUI-compatible `Theme` class with tokens (bg, panel, panel_alt, accent, text, muted, border, selected, error, success, warning) and preset themes (defaultDark, light, highContrast, solarizedDark, monokai, dracula, colorblindSafe)
- **Removed hardcoded colors**: Replaced all static color constants with `Theme.current().color(Theme.TOKEN)` calls throughout ModuleManagerScreen
- **Ready for MuiScreen migration**: Foundation laid for future `MuiScreen`-based screens (ProjectChooserMuiScreen, ModuleManagerMuiScreen, etc.)

### Editor feature roadmap (numbers = the user's feature list, October 2026)
- Batch A - DONE (M8): 3, 5, 6, 7, 8, 9, 10, 13, 14, 17, 18, 19, 20, 23, 25, 27, 28, 54.
- Batch B - DONE (M9): 21 symbol search (Ctrl+T), 22 breadcrumbs, 24 jump history list, 29 replace
  preview with per-match accept/skip, 32 organise imports, 33 change a block id everywhere (code, assets,
  recipes), 36 docstring generator, 42 import helper, 48 asset usage report, 50 templates gallery for new
  projects (starter, block, item, mob, command, window app, HUD, empty), 53 copy diagnostics button.
- Batch C - DONE (M9): 40 handler profiler, 43 block/item live preview, 44 event tracer,
  45 module dependency graph, 46 recipe preview, 47 loot preview, 49 "why isn't my block showing" explainer.
- Batch D (needs a custom text view instead of EditText): 2 minimap, 4 Alt+drag box selection,
  15 smooth scroll / cursor blink settings, 16 split editor; then 31 move function/class to file,
  37 test_*.py runner, 41 Python environment selector, 55 theme editor.
  Already done in M9 because they did not need the custom view: 1 sticky scroll, 26 focus mode,
  30 extract variable / function, and the unbatched 11 line-ending switcher + 12 encoding display.
- Batch E (design first): 34-35 pyright/mypy/ruff in the worker, 38-39 debugger + call stack, 51 shared
  sessions with file locks, 52 project change log.
- Unbatched quick ones: 11 LF/CRLF switcher in a status bar, 12 encoding display/conversion.

### Milestone M19 (2026-10-16): fixes, standalone IDE, IntelliJ-style shell, building blocks, 50 features

`gradlew --no-daemon test` green after every step (Java + `test_worker.py`); `gradlew ideSmokeTest` green; the standalone
window was opened several times without errors. **Nothing was clicked in game**; everything below needs the in-game check.
- Textures: the block atlas only stitches `textures/block` and `textures/item`; project textures elsewhere (`welcome:gem`)
  were the missing texture. `PackBuilder.blockAtlas` adds `single` sources in `assets/minecraft/atlases/blocks.json`.
- Completion: static inference in the worker (`_infer`, `_members`, `_scope_for`, `_parse_lenient`): chained calls,
  `event.` in `@on`/`@command`/`@every`/`@action` handlers, locals, loops, `self.`, returned values, keyword arguments of
  any call. API classes carry annotations (`player: "Player"`, `-> "Content"`). Fuzzy ranking (word initials), signature
  help on `(`/`,`, hover on ids in strings (`CompletionEngine.hover`). The Java `params` round trip was removed.
- Standalone IDE (M18): `gradlew runIde` / `ideSmokeTest`; `IdeMain`, `LocalHost`, `WindowChrome` (logo icon, dark DWM
  caption). The game's classes stay on the classpath but are never bootstrapped. `ProjectRequestHandler.Caller.LOCAL`
  (no server: world/module actions say "Needs the game"), `Config.get` (defaults without a loaded config),
  `-Dcraftpy.home`. `GameResources`: asset browser and previews read the vanilla assets jar through a
  `MultiPackResourceManager`; registries come from the mod catalog; sound playback and the 3D screen need the game.
- Look: `IdeColors` (IntelliJ New UI dark with CraftPy green) answers every `color()` lookup, so game and standalone look
  the same; `IdeIcon` painted icons; VS Code activity bar; main toolbar (project widget, search, run widget); tool window
  stripes (`ToolStripe`, Alt+1..9); underlined tabs with file icons and unsaved dots; resizable split editor
  (`SplitEditor`, never two editable views of one file); fading hover states; animated tool windows; the console is a
  read-only code view (scrolls sideways, no jitter while selecting, colored lines).
- Building blocks (the user's idea): `craftpy_blocks/` (30 templates, `#:` header), `BlocksPanel` (Alt+8): Docs (section
  of api.md), Insert, New file (`code/<name>.py` + `import` in main.py, on disk via tool `add_part` or in the open buffer).
- Editing (`LineEdits`, tested): comment, duplicate, delete, move lines, reformat, live templates, smart backspace, rename in
  file; Find Action, Find in Files (`grep` tool), TODO list, go to line, reopen tab, hide all tool windows, execute
  selection, copy reference; indent guides, bracket pairs, caret line number, status bar counts, minimap marks; settings
  dialog; remembered folders and tabs; recent projects; tips; in-game action-bar notices; the IDE key opens the editor.
- Fixed: a save reply cleared the unsaved mark of whatever tab was active (edits typed during a save were shown as saved);
  `__pycache__` was tracked and shipped in the jar; warnings were drawn in the success green.
- Later in M19: recipe field check (`IdChecker.recipeFields`); `@extension` classes shared by name across projects;
  `Item(effect="mod:effect")` fixed (it was read as the id `mod`); Home is a welcome screen (search, New Project dialog);
  mod metadata and README; the standalone window closes properly (Modern UI's own `stop()`; the GLFW flag alone never
  ended the loop, so *Leave* did nothing there); `runIde -Pcraftpy.ide.page=... -Pcraftpy.ide.exitAfter=...` opened and
  closed every page without errors; `run/pyforge` reset (backup in the scratchpad).
- Decisions: delete line is Ctrl+Shift+K because Ctrl+Y is the editor's redo; balloons were not built (Modern UI toasts
  already look the same in game and standalone); the old M18 plan steps (plain Response record, interfaces for HostBridge
  and SlotRegistry) were not needed because the game jar stays on the classpath.

### In-game session 2026-10-15 (dev server + client, M13-M18 step 1)

First time the mod was run with Create, Mekanism, Iron's Spells, Curios and Modern UI. The PC block opened, `welcome` was built and loaded on the server ("Applied module welcome declarations: 2 block(s), 4 item(s)"), the catalog dump wrote one file per mod. Found:
- Fixed: `SlottedBlockEntity` sent `pos` as a string, so every `block_tick` / `block_interact` failed in the worker (`property 'pos' ... has no setter`) and handlers with a `block=` filter never matched. It now sends x/y/z + dimension + `block` + `player` like the other block events (`EventBridge.addPosition/wrapBlock` are public). Test in `test_worker.py`.
- Fixed: `/craftpy give` reported `Gave 0` (the count was read after `Inventory.add`).
- Open: textures are not applied to CraftPy content; complex syntax is not completed in the editor (both in `docs/TODO.md` section 1).
- Environment: one client crash was the JVM running out of memory (not CraftPy); server console input needs RCON (`enable-rcon=true` was set in `run/server.properties`).
- The full to-do list (this session, the two plans and the roadmap) is `docs/TODO.md`.

### Milestone M18 (in progress): standalone IDE, step 1 of the decoupling

`gradlew --no-daemon test` green. Nothing was clicked in game; the edited call sites are mechanical (same calls, now behind `IdeHost`).
- Spike (scratch `main`, not in the repo): `new ModernUI().run(fragment)` opens a Modern UI window on a plain JVM, `Core.initialize()` works outside the game. Classpath that was enough: `modernui-core 3.13.0`, `arc3d-core/sketch/compiler/engine/granite/opengl/vulkan 2026.2.0`, LWJGL 3.3.3 (core, glfw, opengl, stb, freetype, jemalloc + natives), fastutil, icu4j 73.2, joml 1.10.5, slf4j-api, log4j-api/core.
- `client/IdeHost` (execute on the client thread, `send` a request, clipboard, config dir, bundled docs, close screen). `McHost` is the game implementation, installed in `CraftPyClient.onClientSetup`; `IdeHost.PLAIN` is the default (inline execution, in-memory clipboard, `-Dcraftpy.home`) so unit tests and the future IDE run without the game. `client/mui/*`, `ClientProjectStore` and `EditorSettings` no longer touch `Minecraft`, `PacketDistributor` or `FMLPaths` for these (`IdeHostTest`).
- `CraftPyLog` holds `MODID` and `LOGGER`; `CraftPy.LOGGER/MODID` still exist as aliases. Every other class uses `CraftPyLog`, so logging no longer loads the mod class and, through it, all registries, commands and packets.
- Measured with javac on the store, `ProjectTools`, `CompletionEngine`, `ProjectManager` and `PyRuntime` with no Minecraft on the classpath (compile errors from classes that need Minecraft): 785 before this step, 403 after. What still blocks, in order: `SlotRegistry` and the `block/*` classes (reached through `ProjectTools.ownIds` and `Declarations`), `python/HostBridge` (called from `PyRuntime`), `Config` (NeoForge `ModConfigSpec`), `network/RequestPayload` and `ResponsePayload` (packets, used as plain data by the store and the handler), `Defs`, `NbtJson`, `CraftPyExecutors`, `PyForgePaths` (`FMLPaths`), `ClientPackSync`, the two screen fragments (`CraftPyFragment`, `WindowFragment`).
- Next steps, each compiled and tested on its own: plain `Response` record behind the packets; `Config.safe(...)`; `PyForgePaths` honouring `-Dcraftpy.home`; `HostBridge` and `SlotRegistry` behind small interfaces; `ProjectService` taking a `Caller` (permission, upload key, reload/refresh callbacks) with `ProjectRequestHandler` as the player adapter; then `ide/IdeMain`, `LocalBackend`, `runIde`/`ideJar`.

### Milestone M17: installer

`gradlew --no-daemon test` green. A real install was not run.
- `installer/` source set (plain Java 21, no dependencies, `installerJar` -> `craftpy-installer.jar`, the mod jar bundled as `craftpy.jar`). `InstallPlan.build` (pure, tested) turns `Env` (OS, Minecraft folder, Java, Python, package manager, NeoForge present, mod files) + `requirements.properties` into steps: download NeoForge installer and run `--installClient`, download Modern UI, copy CraftPy, and Python/pip only with `--install-python`. Missing Java or an unknown package manager become manual notes, never commands.
- `Installer` downloads to `.part`, checks SHA-256 and only then moves; a mismatch deletes the file. Everything is printed before it runs (`--dry-run`, `--yes`, `--cli`, `--dir`, `--craftpy`); with a display and no `--cli` a small Swing window shows the plan and an Install button.
- Pinned: NeoForge 21.1.259 (maven.neoforged.net), Modern UI 3.13.0.1 (Modrinth CDN). Decision: `.properties` instead of JSON so the installer has no dependency.
- Removed the dead `autoDownloadPython` config option and its lang/README entries; the "no Python" error now points to the installer.
- Not done: an embedded Python download (package managers only), Linux distros without `python3.13` packages get the python.org note.
  Done in M19: the mod jar ships CPython 3.13 for five platforms; the installer no longer installs Python.

### Milestone M16: mod catalog, id completion and checks, other mods' recipes and attributes

`gradlew --no-daemon test` green. Nothing was clicked in game; the catalog dump on a real server with real mods is the unverified part.
- `catalog/*`: `CatalogDump.onServerStarted` writes `system/catalog/<mod>.json` (items, blocks, entities, effects, attributes, sounds, particles, enchantments, tags, registries, recipe types with fields, events, classes) only for mods whose jar or version changed; `ModCatalog.view()` is what the IDE reads. `craftpy_integrations/*.json` (Create, Mekanism, Iron's Spells, Curios) ship recipe layouts and datapack folders a dump cannot tell (`CatalogSeeds`), visible only while the mod is installed.
- Completion: `CompletionEngine` answers inside strings from the catalog (ids, `#tags`, `java.cls(`, `@on(` events) without Python; `StringContext` finds the call and keyword; typing a keyword in a call adds `name=` items from the worker (`params` RPC, `PyRuntime.params`). `@extension` classes of the project are completed (`_USER_EXTENSIONS`, dropped with the owner). `on.` completes from `__dir__`. `CodeAssist` triggers on `:` and `/` inside strings.
- Validate: `IdChecker` warns about ids no installed mod provides and recipe types of missing mods; silent while the catalog is empty.
- API: `recipe("mod:type", ...)` writes another mod's recipe as given, with a `neoforge:mod_loaded` condition (`PackBuilder.modRecipe`); an unknown built-in recipe type is now skipped with a warning instead of becoming shapeless; `Attributes(...)` extension applies any attribute while held or worn (`ItemAttributes`, `Defs.AttributeSpec`); `raw_json(..., namespace=)` writes to `data/<namespace>/`.
- Fixed while resuming after a crash: `_drop_owner` had its docstring pushed below new code.
- Tests: `CatalogTest`, `IdCheckerTest`, `CompletionEngineTest`, `PackBuilder*Test`, `test_worker.py` (recipes/attributes/params/extensions).

### Milestone M15: remaining gaps from the M14 audit

`gradlew --no-daemon test` green (186 tests). Nothing was clicked in game.
- 11 The mod no longer replaces GLFW's drop callback for every screen: on screens other than CraftPy's the previous callback is called (vanilla pack drag and drop works again).
- 17 `PackBuilder.mergeSounds`: sounds declared from Python are added to the project's own `sounds.json` instead of replacing it.
- 22 `AssetIndex.isFailed()`: a failed index build is reported in the Assets tab instead of polling forever. The IO and UI pools now run a task on the submitting thread when their queue is full (`CallerRunsPolicy`) instead of silently dropping the oldest (31: a big drop of files no longer loses uploads); only the RPC pool still drops its oldest.
- 23 Copying an asset into a folder says "Copied" only after the server wrote it, and brings the `.png.mcmeta` of animated textures (`.mcmeta` is an accepted upload type).
- 26 Sound undo history is also capped at 96 MB.
- 27 `AssetDrop.validTarget` (tested) is the one rule for names typed into the texture and sound editors: under `assets/`, lower case, no empty or dot segments.
- 28 New projects no longer get empty `assets/animations` and `assets/other` folders (they would ship as namespaces).
- 30 Dead code removed: `ModelViewerScreen`, `InspectorScreen`, `SoundPreview.playLooping/tick/setPitchVolume`.
Closed in M19: 8 (one debounced rebuild, `util/Debouncer`), 29 (`ModuleBuilder` renames the namespace), the multi-variant sound copy (fixed variant).

### Milestone M14: OGG export, menu keys, upload hardening

`gradlew --no-daemon test` is green (new: `OggEncoderTest`, `MenuNavTest`, `UploadRulesTest`, `ProjectWritesTest`). Nothing was clicked in game. The OGG round trip was run for real after `pip install --user soundfile` on this PC.

**New**
- Sound editor saves **Ogg Vorbis**: `client/assets/OggEncoder` runs the player's Python (`PythonDiscovery`) with `soundfile` in a short-lived process on the client, so dedicated servers need nothing. A missing package shows *Install encoder* (`pip install --user soundfile`, only on a click) and *Save as .wav*. Default name is `.ogg`.
- Menus take **Up / Down / Home / End / Enter / Right / Left**; the highlight is shared with the mouse (`MenuNav`, `Overlays.MenuPopup.onKey`, `Host.dispatchKeyEvent`).

**Fixed (from the gap audit)**
1. Dock could not open assets over 24 KB (every normal `.ogg` / saved `.wav`): `BINARY_READ_LIMIT` is 288 KB.
2. Leaving the editor with Esc while the dock holds unsaved texture / sound edits asks first (`EditorFragment.confirmLeave`). Switching page with the left rail still drops them.
3. "Saved" appeared before the server accepted the file: the texture and sound editors now wait for the upload result (`ClientProjectStore.uploadAsset(..., done)`); a refused file stays marked unsaved.
4. Two quick asset opens swapped their bytes (single callback): `ClientProjectStore` keeps a queue matched by path.
5. Uploads: chunk count, size (`maxAssetSizeKb`, which was never read) and a 60 s expiry for unfinished uploads (`UploadRules`, `ProjectRequestHandler`).
6. Uploads only into `assets/`; `:` refused in project paths (`ProjectManager.resolveSafe`).
7. A save no longer recreates a deleted project; binary files are written through a temp file (`ProjectManager`).
9. Dropping on a root-level or `code/` row copied the texture there; now it goes to the asset's usual `assets/<kind>/` folder.
10. `.wav` dropped / imported lands in `assets/sounds/`; the file dialog lists `.wav`; opening a `.jpg` / `.mp3` says what to convert.
12. ▶ in the Assets tab plays sounds that only a `sounds.json` defines, and says so when the id is invalid.
13. Blocks with only a block state can be inspected (`AssetFetch.blockState`).
14. Missing models are no longer cached as empty; the cache is cleared on every resource reload.
15. Sounds stop on the client thread; zen mode stops them too.
16. A model that cannot be drawn shows the error instead of "Loading...".
18. A menu no longer closes if the pointer moves before its first layout (real card bounds).
19. `Overlays.contextMenuListener` exposes the right-click listener so it can be combined with drag handling later.
20. Closing a menu removes its cards.
21. Esc cancels an asset drag.
24. Assets dropped on a non-Python file are refused with a message.
25. The project file list is copied on the UI thread before the client thread uses it.

**Located, not fixed**
8 every asset save rebuilds the pack and reloads all clients (`ProjectRequestHandler.refreshPacksAfterAssetChange`, `ClientPackSync.reload`); 11 the mod replaces GLFW's drop callback (`CraftPyClient.onClientSetup`); 17 `PackBuilder.writeProjectResourceExtras` overwrites a project's own `sounds.json`; 22 the index build can poll forever if it fails (`AssetIndex.ensureBuilt`, `AssetBrowserPanel.refresh`); 23 `EditorFragment.copyAssetInto` reports "Copied" before the server answers, skips animation `.mcmeta` and copies a random sound variant; 26 sound undo history can reach ~250 MB (`SoundEditorPanel.HISTORY`); 27 `TextureEditorPanel.validTarget` is loose and untested; 28 `assets/animations` and `assets/other` are seeded as folders but ship as namespaces (`ProjectManager`); 29 *New module* builds keep the old namespace for `assets/<project>/...` (`ModuleBuilder`); 30 dead code (`SoundPreview.playLooping/tick/setPitchVolume`, `ModelViewerScreen`, `InspectorScreen`); 31 many dropped files can overflow the IO queue (`AssetUploader.uploadAll`).
Earlier gaps stay: slots over 4096, `ContentPreviewScreen` still vanilla, Batch D / E, drag only from the Assets tab, Java bridge limits.
**Unverified in game:** menu keys and placement, drag and drop, the leave confirmation, the install button, cursor icons.

### Milestone M13: asset dock, asset browser, overlay menus, cursor fix

Compiles and `gradlew --no-daemon test` is green (new: `AssetDropTest`, 7 tests); none of it was clicked in game.

**Fixed**
- *Text cursor over dialogs.* Modern UI answers the pointer icon with the first child that has one, siblings underneath included, so the code area behind a dialog showed the I-beam over the whole dialog. `Overlays.Layer.onResolvePointerIcon`: a dialog owns the pointer everywhere, menus and floating cards where they cover something.
- *Menus stayed open.* Modern UI's `PopupMenu` opens a separate popup window. All menus of the editor, Home and the UI designer (Edit, View, Tools, New, tab list, find history, status bar, node menu) and the right click menus (explorer, tab chips, bookmarks) are now `Overlays.MenuPopup` / `Overlays.contextMenu`: drawn in the screen, close on a click outside, Esc, after an item ran, and when the pointer leaves the menu, its open sub-menus and the view it hangs from (6 dp slack). Sub-menus open on hover. `WindowFragment` (Python windows, no overlay host) keeps `PopupMenu` for its dropdown.
- *Sound editor saves were rejected.* The server only accepted `.png .jpg .ogg .json` uploads, so Save in the sound editor (which then wrote `.wav`) never reached the project. `.wav` is allowed now.

**New**
- Dock tab *Assets* (`AssetBrowserPanel`, replaces the `AssetPicker` dialog): textures, items, blocks, models, sounds of the game, search with `#tag` and `@namespace`, sound play button.
  - Click: `AssetDock.inspect` opens a texture in the texture editor, a sound in the sound editor, a model / block / item as the 3D picture (`AssetFetch` reads the bytes from the resource manager on the client thread).
  - Drag: a chip follows the pointer; release over the code inserts `"id"` (a sound: `event.player.play_sound("id")`) at the pointer; over the explorer copies the file into the folder under the pointer (`AssetDrop.intoFolder`, never overwrites, items and blocks are not files). Naming rules are in `AssetDrop` (pure, tested).
  - Modified assets: Save of a texture / sound taken from the game writes to the module resources `assets/<project>/textures|sounds/...` (`AssetDrop.projectPath`; other mods' ids keep their namespace as a folder; an id in the project's own namespace keeps using the simple `assets/textures/` file when it exists). Replacing the original for everyone is a separate, explicit act: type `assets/minecraft/textures/...` in the name box.
- `welcome` example: header documents the asset workflow. `run/pyforge` was reset (old copy kept outside the repo) and `welcome` re-seeded from the jar resources.

**Not done / limits**
- (Sound saves became `.ogg` in M14.)
- Drag only starts from the Assets tab (not from the explorer or the preview); keyboard navigation of the new menus is not implemented (Esc only).
- Pointer leave is judged on hover events, so a menu stays until the pointer moves.
- Unverified in game: drag and drop positions, menu placement near window edges, cursor icon in every dialog.

### Milestone M12: overlays, explorer tree, go to definition, multi-file projects, Java bridge

Everything below compiles and is covered by unit tests (`gradlew --no-daemon test`: Java 120+ tests, 20 Python tests); none of it has been clicked through in game.

**Editor and bug fixes**
- `client/mui/Overlays.java` (new): every dialog, prompt, quick open, asset viewer, UI designer and the completion / hover cards are plain views in one `Host` over the page instead of `PopupWindow`s. Why: popups that opened off screen or were never dismissed kept swallowing input (left rail dead, "text box at the bottom"). Rules: one title bar with one `x`, footers only hold actions, a click on the dim area closes, `Esc` closes the top dialog and then runs the page handlers (completion list, find bar, explorer selection, text selection, zen) before the screen goes back, focus returns to the editor, nothing outlives its screen. `ListDialog`, `CraftPyFragment.prompt`, `AssetViewer` (unsaved-changes guard), `UiDesigner`, `CodeAssist`, quick open and help were ported.
- Left rail buttons also have an explicit click listener (`navigate(id)`).
- `client/FileTree.java` (new, tested) + explorer rewrite: VS Code style tree (folders first, chevrons, indent, file badges, expand state kept per project, auto reveal of the open file, collapse / expand all, keyboard navigation, F2 / Delete, context menu with New / Rename / Delete / Copy path). Empty folders are listed by the server as `dir/` entries.
- Go to definition: `Ctrl+click`, `Ctrl+B`, `F12` (link underline on Ctrl+hover). Worker methods `definition` and `stub`: own code, other project files (`FIND_DEFINITION`), and the generated read-only library file `library/craftpy_api.py` with every API name, signature and doc.
- Help: `HelpPanel` (IDE guide `docs/guide.md`, Python API, Java bridge `docs/java.md`) as a dialog (Help button, F1) and as the Docs page. The old shortcut list lives in the guide.
- `AssetPicker` (Modern UI) replaces `AssetPickerScreen`; `TextureEditorScreen` and `SoundPlayerScreen` were deleted (the M11 `AssetViewer` does both).
- Response packets: `ResponsePayload.json` accepted at most 32767 characters (a larger file or the API stub would have disconnected the client); now 400000.

**Projects and reloads (from the review)**
- Multi-file: the worker installs a `ProjectFinder` import hook; `ProjectManager.readSources` sends `code/**.py`; modules read their own folder. Examples and the default project were split into several files; `PyImports.organise` puts craftpy before the project's own modules.
- `run_main` is transactional: the previous owner state is snapshotted and put back when the new run raises; `revert_owner` puts it back when the host cannot apply the result (slot pool full, bad definition). Errors name the file and line of the file that failed (`Declarations` used to show the text "true" as the message).
- `SlotRegistry.releaseProject` removed the block AND item definition at an index (pools overlap): now only the slot's own kind. `SlotRegistry.applyAll` + `SlotPlan` (pure, tested): all definitions parsed and all slots found before anything changes; content the project no longer declares is detached; a failed apply changes nothing. `ModuleLoader` uses it too.
- `ProjectController`: the in-flight marker was never cleared after a successful load (later reloads of the same project were silently ignored); now cleared in `finishLoad`, saves during a run are folded into one more run, `scheduleReload` debounces (0.7 s).
- `hotReloadOnSave` is implemented (`ProjectRequestHandler.WRITE_FILE` -> `ProjectController.scheduleReload`; only loaded projects; only `code/*.py`, `ui/*.json`, `assets/**`).

**Texture packs, slots, Java bridge**
- `PackBuilder.assetNamespace`: `assets/<resource folder>/` stays in the project namespace; any other folder under `assets/` is its own namespace (`assets/minecraft/...`).
- Slot pool size: `config/craftpy-slots.properties` (default 256, max 4096), read before the config system loads because registries freeze at startup. The old hard cap was the constant 256 in `ModBlocks` / `ModItems` / `PackBuilder`.
- `bridge/JavaBridge`, `JavaCodec`, `JavaAccess`, `JavaEvents` + worker `java` API: handles to Java objects, field / method / constructor access by name with overload scoring, argument conversion (enums, ResourceLocation, Component, registry ids, BlockPos / Vec3 from lists, arrays, lists, maps, Python functions as interfaces), any NeoForge event via `@java.on`, `java.server()/level()/entity()`, `player.java`. Access modes OFF / RESTRICTED / FULL plus a hard deny list.

**Not done / honest limits**
- Slots beyond 4096 or without a restart: the next step is data-component backed items (one registered item, `custom_model_data` + `minecraft:custom_data` per stack, generated model overrides) and `IntegerProperty` variant blocks (16 variants per registered block). Not started; it changes rendering and item identity and needs in-game testing.
- Java bridge: no new registry entries after startup, no subclassing from Python, public members only; the sandbox deny list is best effort (see README Security).
- The model viewer, content preview and inspector screens are still vanilla screens.
- Nothing here was exercised in a running game: overlay positions, Esc routing and the tree interactions are the first things to check.

### Milestone M11: assist, asset viewer, lint, minimap, change log
**Fixed** (all from the first in-game test, so reasoned, not reproduced)
- *Left tabs / tab chips did nothing*: the tab bar, the problems list and the console were rebuilt on every store
  snapshot, so a click straddling a snapshot was lost. They now rebuild only when their content changed.
- *Tooltip stays until the submenu closes*: toolbar buttons hide their tooltip on click.
- *Recipes dialog could not be closed*: the dialog was taller than the window, so its Close button was off screen.
  Height is clamped, there is a header close button and Escape closes.
- *Click on an asset opened it*: now one click selects, a double click opens (text files keep preview / pin).

**New**
- Completion (typing, Ctrl+Space) and hover documentation (mouse rest, Ctrl+Q) in the code area, answered by the worker
  from the real API objects. Hover also shows the problem message of the line.
- `AssetViewer`: pixel texture editor (pencil, eraser, fill, picker, line, rectangle, box, colour mixer, palette,
  recent colours, undo / redo, flips, rotate, wrap shift, zoom, grid, save / save as) and a sound player (play, loop,
  volume, pitch). Tools > New texture 16 / 32 / 64. `AssetFileScreen` removed.
- Lint warnings in Validate, minimap (View > Minimap), change log (Tools).

**Not done**: model viewer remake (skipped on request), `AssetPickerScreen` / `TextureEditorScreen` /
`SoundPlayerScreen` (the picker still opens them), Batch D items that need a custom text view (box selection, split
editor), 15, 31, 37, 41, 55, and Batch E except lint and the change log (debugger, shared sessions, pyright/mypy).
Nothing in M11 was clicked in game.

### Milestone M10: bug fixes, in-game windows, UI designer, folder layout, default project
**Fixed**
- *Stuck popup that survived into the main menu.* `ClientPackSync.reload()` called `reloadResourcePacks()` for every
  build, often several in a row; each call replaces vanilla's loading overlay and orphans the previous one. It also
  re-opened the saved IDE screen over the main menu afterwards. Reloads are now coalesced (one at a time, one repeat if
  more were requested) and the screen is only restored when nothing else is showing. **Not clicked in game**: I could not
  reproduce it, this is the cause the code points to.
- *`/give` listing `craftpy:pcblock_N` / `pcitem_N`.* Vanilla suggests every registry entry and the slot pool must be
  registered at startup, so the entries cannot disappear; `ItemSuggestionMixin` / `BlockSuggestionMixin` filter them out of
  the suggestions of every item / block argument (`/give`, `/setblock`, `/fill`...). Typing one still works. Both mixins
  were applied by a real dedicated server start (log: "Mixing ItemSuggestionMixin ...", no error).
- `/craftpy give <player> <id> [count]`: block or item is detected (block first, then item); `block` / `item` still force
  a kind. Ambiguity errors are unchanged.

**Folder layout** (`PyForgePaths`): `projects/ modules/ imports/ exports/ system/`. Everything CraftPy manages (runtime,
logs, build, cache, `slots.json`, `modules.json`) lives in `system/`. The old flat layout is moved in on the first path
lookup, before anything can create a file at the new place (a real server start showed `slots.json` being written first
while the old one stayed behind - fixed). Modules are scanned **only** in `modules/` (before: the whole `pyforge/` tree,
so the `.cpm` in `exports/` and `imports/` showed up as duplicates). `imports/` is a drop folder: files are moved to
`modules/` and never overwrite an existing name. I deleted the byte-identical duplicate `run/pyforge/imports/ruby_rush.cpm`.

**Default project.** `welcome` (`src/main/resources/craftpy_default/`, manifest `files.txt`) is installed once per
instance (marker `system/default_project_installed`, so deleting it keeps it deleted). It covers items, food, blocks,
drops, all recipe kinds, tags, lang, loot tables, events, timers, commands, storage, a Python window, a drawn window and
`/welcome:selftest`. It replaces `examples/tutorial` (removed). The project description is now shown on the Home rows.

**In-game windows** (they did not exist: `ui.open` answered "not wired yet").
- Python: callbacks become tokens (`on_click=self.method`) or `@action` names (strings); `ui.open/close`,
  `ctx.player / value / values / close() / open()`; one instance per player and window, redrawn after every callback;
  optional `on_close()`. The worker only runs tokens it issued for that player's open window.
- Java: `ProjectAction.WINDOW` (server to client) and `WINDOW_EVENT` (client to server, no IDE permission needed);
  `WindowFragment` draws the JSON tree with Modern UI widgets (label, button, text input / area, checkbox, slider,
  dropdown, list, progress, grid, tabs, scroll, boxes). Inputs keep what the player typed across redraws unless the
  server changed the value.
- **UI designer** (`UiDesigner`, Tools menu): widget tree, live preview (same builder as the game), property editor;
  it edits the JSON of the open `ui/<id>.json` tab, `Tools > New UI window` creates the file. `ui/` is part of projects
  and of built modules.
- Limits: images / item icons are placeholders; no drag and drop; the whole tree must fit one packet string (32k chars).

**Not done**: remakes of the texture, model and sound editors on Modern UI (they are still the old vanilla screens), and
the Batch D / E items. **Not verified by clicking in game**: `WindowFragment`, `UiDesigner`, the reload fix. Verified:
Python tests (windows, token security, welcome project), Java tests (manifest, placeholder filter), full
`gradlew --no-daemon test`, a dedicated server start (layout migration, project seeding, both mixins).

### Milestone M9: Editor Batches B and C
Everything below is **unit-tested but not yet clicked through in game** (see "Not verified").

**Architecture.** Project-wide features cannot run on the client: it only holds the files that are open, and a
packet string is capped at 32767 characters. So there is one new action, `ProjectAction.TOOL`
(`server/ProjectTools`), whose request is `{"op": ..., args}` and whose reply echoes `op`; the client calls it with
`ClientProjectStore.tool(op, args, callback)`. Replies are trimmed to 30000 characters (`<array>Cut` says how many
rows were dropped). The analysis itself is pure Java in the new `analysis/` package so it is testable:
`PyLex` (code / string / comment spans), `PyOutline` (classes, defs, handlers, commands, `Block`/`Item`
declarations, constants), `PyImports`, `PyDocstring`, `ProjectScan` (asset usage, rename plan), `ModuleGraph`.
`client/mui/EditorTools` is the glue that owns every feature below; `EditorFragment` only forwards keys/menus.
`client/mui/ListDialog` is the shared popup (title, filter, rows, footer buttons, custom row views).

**Batch B**
- 21 *Go to symbol* (Ctrl+T, Tools menu): every def/class/method/handler/command/content/constant of the project.
  Unsaved open files are merged in from the editor, so new code is found before saving.
- 22 *Breadcrumbs* under the tabs (`file > Class > def`), updated on caret moves (`CodeView.setOnCaret`); click for
  the outline of the file.
- 24 *Jump history*: bookmarks, problems and symbol jumps are remembered (`client/JumpHistory`); Alt+Left /
  Alt+Right, Ctrl+E lists them. Explorer clicks are not jumps.
- 29 *Replace with preview*: the find bar's Preview button lists every match as before/after with a toggle each;
  Apply replaces only the accepted ones (`TextOps.previewReplace` / `replaceMatches`).
- 32 *Organise imports* (Ctrl+Alt+O): groups `__future__` / standard library / other / craftpy, merges `from x import`
  lines, removes unused names (an f-string use counts as a use; `import *` is kept). An import with a trailing
  comment is left verbatim.
- 42 *Add missing imports* (Ctrl+Alt+I): adds `import random` etc. for `random.` / `math.` ... used but not imported
  or defined (fixed list of safe modules).
- 33 *Rename block / item id everywhere* (Tools menu): preview first, nothing is written until Apply. It rewrites
  whole-string references only (`"ruby"`, `"demo:ruby"`, `"block/ruby.png"`, lang keys `block.demo.ruby`), renames the
  Python variable of the same name (not keyword arguments, not text inside ordinary strings, f-string braces
  included) and moves `assets/**/ruby.*` / `data/**/ruby.*` files. Refuses when the new id exists or when files
  have unsaved edits. Other projects/modules are not touched.
- 36 *Docstring generator* (Ctrl+Shift+D): Google style from the signature (Args with annotations, Returns / Yields /
  Raises found in the body), summary pre-selected.
- 48 *Asset usage report*: missing references first, then unused assets, then used ones with where they are used.
- 50 *Templates gallery*: Home page chips (Starter, Block, Item, Mob, Command, Window app, HUD, Empty). Scripts live in
  `resources/craftpy_templates/<id>.py`; `ProjectTemplates` serves them; `CREATE` accepts `{"name","template"}`
  (a plain name still works). The Window app template documents the API but windows still are not wired in.
- 53 *Copy* button in the bottom panel: console, problems (`file:line:col: error: message`) or bookmarks.

**Batch C**
- 49 *Why isn't my block showing?* (Tools menu): checks, in order: declared in code, declared in `code/main.py`
  (only that file runs), project running, registered by the last Run, slot assigned, texture file exists (typo
  suggestions); each as a green / amber / red line.
- 43 *Block / item preview*: lists what the last Run declared; click opens `ContentPreviewScreen`, the real in-game
  model of the slot (drag to rotate, wheel to zoom) plus the declared properties. A purple cube = missing texture.
- 46 *Recipes*: every recipe of the last Run drawn as a 3x3 grid / ingredient row -> result (names, not icons).
- 47 *Loot tables*: items, counts and per-roll chance (weight / total).
- 40 *Handler profiler*: the worker times every handler and timer (`_PROFILE`; calls, total, average, slowest,
  errors). Tools menu > Handler profiler, with Refresh / Reset. Rows of a stopped project are dropped.
- 44 *Event tracer*: bottom panel "Events". `EventBridge.fire` records every event the game fires (also the ones no
  handler wants, marked so), who matched and how long Python took (`EventTrace`, 300 entries). Start / Stop / Clear;
  it polls once per 800 ms while visible. Only events CraftPy forwards are traced (not every Forge event).
- 45 *Module dependency graph*: Modules page > Dependencies; tree with missing / disabled / circular marked and a
  problem summary (`ModuleGraph`, uses the `dependencies` of `module.json`).

**Extras from the old Batch D / unbatched list** (done in M9 without the custom text view)
- 1 *Sticky scroll*: the signatures of the scopes scrolled out above the first visible line are pinned at the top of the
  code area (max 3, overlay in a `FrameLayout`). View menu toggle, saved as `stickyScroll`.
- 26 *Focus mode*: everything outside the function/class around the cursor is dimmed (`CodeView.setFocusRange`).
  View menu toggle, saved as `focusMode`. Nothing is dimmed when the cursor is outside any def/class.
- 30 *Extract variable* (Ctrl+Alt+V) and *Extract function* (Ctrl+Alt+M), `analysis/PyRefactor`. Variable: the
  line selection becomes a name, `name = expr` is inserted above the statement (continuation lines handled; refuses
  inside strings/comments or with unbalanced brackets). Function: whole lines go to a new top-level `def` placed above the
  enclosing function; parameters are the enclosing function's parameters / earlier assignments that the lines use, return
  values are names assigned in the lines and used afterwards; refuses `return`/`yield`/`await` and a loose
  `break`/`continue`. These are heuristics - the result toast says to review the parameters.
- 11 / 12 *Status bar* under the bottom panel: `Ln, Col`, selection size, language, line ending (click: convert to LF /
  CRLF) and encoding (`TextFormat`: UTF-8, BOM, count of invalid bytes shown as U+FFFD; click: remove BOM). The
  files are still always read and written as UTF-8 - converting to another charset is not possible with the current
  file protocol.
**Not done and why**: 2 minimap, 4 box selection, 16 split editor (need a custom text view - the actual Batch D
refactor); 15 (Modern UI exposes no cursor blink); 31 move to file, 37 test runner (only `code/main.py` runs, so a
test runner needs the multi-file import design first), 41 environment selector, 55 theme editor, and Batch E.

**Tests added** (all green, `gradlew --no-daemon test`): `AnalysisTest` (lexer, outline, imports, docstring, asset
usage, rename, module graph), `EditorToolsTest` (jump history, replace preview, recipe/loot views), `RefactorTest` (extract variable / function,
line endings / BOM), `ExamplesAnalysisTest` (outline, imports, docstring and a rename run on the real ruby_rush example),
`ServerToolsTest` (packet trimming, typo suggestion, tracer, templates, a real rename on a temp project incl. file
moves), and in `test_worker.py` the profiler and all 8 templates running.
**Not verified**: any of the UI by clicking (popups, the Events panel polling, ContentPreviewScreen rendering,
Home chips); `runClient` was not started after this milestone.
**Known limits**: the static tools (symbols, usage, rename, explain) read the files from disk, so symbols merge your
unsaved text but usage / explain / rename do not (rename refuses; the others say so). Recipe/loot/content previews
show what the *last Run* declared and need the project running. Recipe previews show item names, not icons.
Imports: relative `from . import x` and imports with trailing comments are left alone; "missing imports" only knows
~20 safe standard modules.

### Milestone M8: Bug Fixes, Simple Python API, Editor Batch A
**Bugs found and fixed (why module/project content did not show up):**
- `Declarations.parse` crashed on `"every": null` (every `@on("...")` handler) -> the whole module failed silently.
- The worker kept ONE global set of declarations/handlers/timers: running any project wiped every module's
  event handlers. Now per owner (`<project>` / `module:<id>`); stopping a project or disabling a module drops
  only its own handlers and timers (`unload_owner` RPC).
- Module declarations were applied off the server thread and parse failures were dropped; now applied on the
  server thread, errors logged and sent to the console, module `@command`s registered.
- Shaped recipes were written with `"keys"` (1.21 needs `"key"`); object ingredients (`{"item": ..}`) fell back
  to iron ore or crashed shapeless recipes; `loot_self()` crashed loot generation; block loot tables were never
  written (slot keys are `BLOCK:project:id`); `PackBuilder` took `BLOCK` as the project id for textures;
  `texture="minecraft:x"` lacked the `block/`/`item/` folder. All fixed, with regression tests.
- Events: fired on the client copy too (handlers ran twice in singleplayer), reported project blocks as
  `craftpy:pcblock_N`, and sent an RPC even with no handler. `@every` never ran. `print()` inside handlers and
  timers was lost. All fixed; handler/timer output and errors now reach the editor console.
- `HostBridge` resolved ids through the vanilla registry only (`set_block` of project content placed air).
- `tool=` / `needs_tool=` had no effect on slot blocks; now tool tier speed + `match_tool` loot condition.
- `/craftpy give` suggested invalid ids (`BLOCK:p:id`) and could report false ambiguity; project commands were
  never re-sent to clients (no tab completion); module commands now cover enable/disable for every module.
- Switching projects kept the old project's tabs (a Save then wrote into the new project); the file list refresh
  jumped back to `main.py`.
- Texture editor: only the first stroke was undoable; fast strokes skipped pixels.
- Dynamic packs were deleted and rewritten in place; at world start several rebuilds overlapped a client reload
  ("missing model for variant" for pcblock_75..255 in the log = purple blocks). Packs are now built in
  `pyforge/build/staging` and swapped in with renames.
**Python API ("easier, more Pythonic")**: `Block(...)`, `Item(...)`, `Food(...)` return their ids;
`drops=`; `shaped(result, "RR", R=ruby)`, `shapeless`, `smelt`, `stonecut`; bare names (`"stick"`, own content);
`@on.block_break(block=ruby_ore)`; `Player`/`Entity`/`Pos` objects (`say`, `give`, `take`, `effect`, `heal`,
`teleport`, `title`, `actionbar`, ...); `world[x, y, z]`, `fill`, `explode`, `lightning`, `drop`, `spawn` ->
Entity, `entities`, `biome`, `highest_y`; `@every(seconds=)`, `after()`; `storage["k"]`; `@command` arguments
from type annotations; `say()`, `run()`. New events: leave, respawn, chat, block_place, item_craft, item_pickup,
entity_death, player_death, entity_hurt. Old syntax still works (tested).
**Docs/examples**: `api.md` rewritten (no tables: Markflow cannot render them); `examples/tutorial` and
`examples/ruby_rush` rewritten with new textures; new-project template uses the new API; computer block has a
screen front, faces the player, drops itself.
**Editor (Batch A)**: word wrap per file, auto-surround, auto-pairs, `else:`/`except:` dedent, smart Home,
sort/dedupe/join/split lines, trim + final newline on save, current-line and same-word highlight, ruler, pinned
tabs, preview tabs, tab overflow menu, recent files (Home + quick open), bookmarks (gutter, panel, F2), zen mode,
find history, find in selection, replace / replace all, shortcut cheat sheet (F1). Plus: 6 editor themes, zoom
(keys + Ctrl+wheel), draggable splitters (saved), Reload button (project + all modules).
Settings persist in `config/craftpy-editor.json`.
**Asset tools**: asset picker starts on the project's own namespace; texture editor gets line/rect tools, hex
input, recent colors, keyboard shortcuts, cursor pixel info; sound player shows a waveform (stb_vorbis) and
"Copy as code" (re-encoding .ogg is not possible: Minecraft ships no encoder); model viewer renders a rotatable
3D preview when a block/item uses the model.
**Verified**: `gradlew --no-daemon test` green (Java tests + `src/test/python/test_worker.py`, which also runs
both examples and the template); `runClient` starts without resource errors. **Not verified**: any of it clicked
in-game.
**Known limits**: bookmarks shift with line edits but not with external file changes; `storage` is not persisted
across restarts (fixed in M19: saved with the world); only `code/main.py` runs; handlers cannot cancel events.

### Milestone M7: UI Rewrite on Modern UI
- **One screen, four pages** (`client/mui/`): `CraftPyFragment` (nav rail + page container, modelled on
  Modern UI's `CenterFragment2`), `HomeFragment` (main menu), `ModulesFragment` (modules menu),
  `EditorFragment` (editor menu), `DocsFragment` (API reference via Markflow). Cards, dividers,
  buttons, switches and text fields are Modern UI widgets with Material 3 theme attributes.
- **Entry points**: the computer block's `ComputerMenu` uses `MenuScreenFactory`; the `I` keybind uses
  `MuiModApi.createScreen`. The back key is Escape only (menu screens default to E as well, which would
  close the editor while typing).
- **Logic stays in `ClientProjectStore`**. Pages snapshot store state on the client thread and render on
  the UI thread; clicks call the store through `CraftPyFragment.store(...)` on the client thread.
- **Editor**: `EditText` with `ForegroundColorSpan` highlighting from the new pure `client/Syntax`
  tokenizer (moved out of `CodeEditor`; fixed an out-of-range substring on a JSON string ending in `\`),
  line-number gutter, Tab = 4 spaces, auto-indent, Ctrl+S / Ctrl+R / Ctrl+Enter, closable tabs with
  unsaved markers and discard confirmation, explorer context menus (new file/folder, rename, delete),
  Console REPL with history, Problems list (click jumps to the line). Asset files open the existing viewer.
- **Build**: Modern UI deps are `compileOnly` + non-transitive; `modernui` is a required **client-side**
  dependency in `neoforge.mods.toml`.
- **Removed**: `ComputerScreen`, `ProjectChooserScreen`, `ModuleManagerScreen`, `CodeEditor`, `client/ide/*`,
  `ApiDocs`, `ClientSettings`, and the unreferenced `client/ui/*` toolkit + `SlottedScreen`.
- **Not carried over** (regressions vs. the old vanilla IDE): completion popup + smart fill, hover docs,
  find/replace, go to definition, command palette, console meta-commands, Markdown preview tab, JSON
  format, inline asset thumbnails; the asset picker copies `tex("...")` to the clipboard instead of
  inserting it. UI text is not localized yet.
- **Verified**: `gradlew --no-daemon test` green (56 tests, incl. new `SyntaxTest`); `runClient` starts
  with Modern UI 3.13.0.1 and CraftPy loaded, no errors. **Not verified**: clicking through the pages
  in-game.

- [x] Prompt / new-file / new-folder text box accepts typing (focus theft fixed in `dispatchClick`)
- [x] Console input embedded inside the console panel (`>>>` prompt, borderless)
- [x] Top **Window** menu + closeable bottom tabs
- [x] Icon/text overlap: long editor-tab titles clip instead of overlapping markers
- [x] Hover tooltips on icon-only buttons (`IconButton` uses `setTooltip`)
- [x] IntelliJ-style OLED palette (`IdeColors`)  — needs in-client confirmation on exact hues/font
- [x] Explorer reliably lists project files — bug: `ExplorerPanel.addPath` never registered top-level directories as tree roots, so files under `code/`, `assets/`, `data/` were attached to an unreachable node (only root-level files showed)
- [x] ModuleManagerScreen: right-click context menu per module (Enable/Disable, Open Folder, Reload, Delete)
- [x] ModuleManagerScreen: theme-aware colors via Theme system (bg, panel, accent, text, muted, border, selected, error, success, warning)
- [x] ModuleManagerScreen: improved hover tooltips with theme-aware colors
- [x] ModernUI 3.13.0 dependency added with Theme system and preset themes
- [ ] Texture editor: hex `#RRGGBB` input, larger controls, real colour palette (currently gray)
- [ ] Preview panel: show a generated-content summary (generated item/block/effect/recipe X) instead of raw text
- [x] Expand API docs + render Markdown properly (headings/bold/lists/code) — full 16-section `api.md`, `MarkdownRenderer` + `ScrollView` spans/styles, auto-preview; in-client look pending confirmation
- [ ] Underline code problems: red for errors, yellow for warnings (in `CodeEditor`/`CodeProblems`)
- [ ] Resizable panels (explorer divider, bottom-panel divider) — assess feasibility first
- [ ] Export/save the built module to a user-chosen location
- [ ] Better tutorials: an easy one, a complex multi-file one, and a Python-only one
- [ ] Overall professional polish pass

### M19 (continued, 2026-10-16): storage, audit gaps, tests, bundled Python, API versions

- `storage` saved with the world (`lifecycle/StorageData`); `storage.clear()` keeps other scopes.
- Audit gaps 8, 29 and the multi-variant sound copy closed (`util/Debouncer`, `ModuleBuilder.entryName/content`).
- Test runner: worker `run_tests`, tool `run_tests`, `Ctrl+Shift+F10`, results dialog with failures first.
- CPython 3.13.15 (python-build-standalone 20260929) for five platforms inside the jar, unpacked by `BundledPython` /
  `TarGz`; installer without Python. Verified here: Windows runtime unpacked from the real jar and the worker tests pass
  on it; the Linux archive unpacks. **Not verified**: macOS and Linux machines, in-game.
- Python API 2.1 with a generated changelog and deprecation notes (`PyApi`, `API_CHANGES`, `_DEPRECATED`).

