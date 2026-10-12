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
