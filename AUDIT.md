# CraftPy Project Audit - October 2026

## Repository Cleanup
- Removed stray `net/` folder from root.
- Logs are now correctly managed.
- Build is GREEN (all datagen and tokenizer tests passing).

## Implementation Status
- File/Folder creation: Functional.
- Item Texture rendering: Fixed.
- Markdown/JSON support: Implemented.
- API Tab: Implemented with live Markdown.
- Event System: Scaffolding complete and wired.
- Datagen: Verified and fixed broken tests.

## Key Fixes
- `SlotRegistry` now supports kind-prefixed keys to avoid name collisions.
- `PackBuilder` correctly handles namespaces in filenames and model mapping.
- `PyForgePaths` and `SlotRegistry` updated for better test environment compatibility.

## Python -> Java Host Bridge (October 2026)
- New `python/HostBridge.java`: project Python can now call back into Java synchronously.
  World mutations are scheduled on the server thread (`MinecraftServer.execute` +
  `CompletableFuture`, 8s timeout); unknown methods return `{ok:false}` rather than throwing.
- Implemented: `world.*` (get/set block, blocks/entities in box, spawn, sound, particle [simple
  types], time, weather), `players.*` (all/get, send_message, teleport, give), `server.*`
  (broadcast, command, log) and `storage.*` (get/set/clear).
- `RpcClient` gained a host-call dispatch path on its own `craftpy-host-call` thread, shut down
  cleanly in `stopInternal`; `PyRuntime` registers the bridge and tracks pending scheduler tasks.
- `EventBridge` delivers `ServerTickEvent.Post` ticks only while tasks are pending, and passes
  block position/dimension and richer player context.

## API Surface + Datagen Coverage (October 2026)
- All eight vanilla recipe types are generated (shaped, shapeless, smelting, blasting, smoking,
  campfire, stonecutting, smithing) with `#tag` ingredient support.
- Tags now target correct vanilla folders (`tags/blocks`, `tags/items`, `tags/entity_types`, ...).
- Added datapack/resource writers: advancements, enchantments, `raw_json` (path-traversal guarded),
  `sounds.json`, per-project particles, and merged `lang`.
- `Declarations` parses the new worker keys; `assets/craftpy/docs/api.md` is a complete reference
  rendered through the new `MarkdownRenderer` (`ScrollView` spans/styles).

## Verification Status
- `.\gradlew.bat --no-daemon test --console=plain` -> BUILD SUCCESSFUL (63 tests).
- `worker.py` passes `py_compile`.
- In-client behaviour of the host bridge, scheduler and window rendering is NOT yet verified;
  all host-visible calls and the pack outputs are covered by unit tests only.

## UI Rewrite on Modern UI (October 2026)
- All CraftPy screens are now one Modern UI screen (`client/mui/`): Home, Modules, Editor, Docs.
  Architecture, threading rule and the list of removed vanilla screens: `docs/AUDIT.md`.
- Modern UI is a required **client-side** dependency; compile-only in Gradle.
- `.\gradlew.bat --no-daemon test` -> BUILD SUCCESSFUL (56 tests). `runClient` starts cleanly;
  the pages have not been clicked through in-game yet.
- Seen in the dev log, not related to the UI: `Failed to apply module ruby_rush declarations`
  (`JsonNull` at `Declarations.parse`, line 86).
