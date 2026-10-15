# CraftPy to-do (single list, kept up to date)

Last updated 2026-10-15 (after M18 step 1 and the first in-game session of M13-M17).
Sources merged here: the two plan files (`extensions / dock / clean push` and `mod-aware IDE M15-M18`), the roadmap in
`docs/REPORT.md` (Batches A-E), the audit gaps (`docs/AUDIT.md`), and what was found while playing.
Rules that apply to every item: Gradle with `--no-daemon`, only the 1.21.1 version, Modern UI widgets only, no Python logic in
UI code, update REPORT/AUDIT/README/guide after each batch, never plant bugs. See also `REQUIREMENTS.local.md` (not committed).

## 0. Your requests, as you gave them (so nothing is lost)

- **Before the first crash, about the asset dock (15:14)** - verify each in game (M13/M14 were built, never clicked):
  widgets such as the View menu must close when the cursor leaves them or their children; the text cursor must not show as
  "type mode" over the Assets tab and other non-text areas; sounds, textures and models can be inspected by clicking them;
  dragging an asset adds it to the project in the specified folder, or inserts a function/call that uses it; modified assets go
  to the module's resource area, which is different from the plain texture-pack overwrite.
- **The big request (18:37), now stages M15-M18** (plan file `~/.claude/plans/u-can-fix-the-binary-rocket.md`):
  1. fix the gaps first (M15, done);
  2. a full smart completion system that knows the extensions, classes, recipe types and more of every installed mod, plus a
     silent integration for attributes, effects and such, cached per mod so entries hide when the mod is removed and come back
     when it is added again (M16, done, needs in-game verification; gaps in section 3);
  3. an easy way to install the mod with its dependencies (M17, installer built, section 4);
  4. a "fake instance" of Minecraft: an IDE that compiles and works without opening the game (M18, section 2);
  5. **"once done, commit and push"**: commits are made per stage (M15, M16, M17, M18 step 1+) but **nothing is pushed yet**.
     Push only when M18 is finished (or when you say so) and after the clean-push decision in section 5.
- **Reference material**: the compat mods' sources are in `C:/Users/Xmilon/IdeaProjects/Compatibily Mods` (claudemem there is only
  partly set up because indexing takes too long). Load claudemem for that folder only when really needed (tokens are expensive).
  The mod jars used for testing are in `run/mods` (Create 6.0.10, Curios 9.5.1, GeckoLib 4.9.3, Iron's Spellbooks 3.16.3 + Iron's Lib,
  Mekanism 10.7.19, Player Animation Lib 2.0.4, Modern UI 3.13.0.1).

## 1. Fix first: found in the first in-game session (2026-10-15)

- [ ] **Textures are not applied** to CraftPy items/blocks in game. Not investigated yet. Start with: does the dynamic pack
  contain the textures (`run/pyforge/system/...` and the rebuilt `craftpy:dynamic` pack), does the client get the pack after
  `Module welcome synced to clients` (`ClientPackSync`), do the slot models point at `<namespace>:...` after the M12 texture-pack
  namespace change in `PackBuilder`, and does the client reload resources after the sync.
- [ ] **Complex syntax is not completed** in the editor. Simple names and keywords work; to check and fix: chained calls
  (`Item(...).extends(` / `.extends(Sword(`), attributes of values returned by calls, `event.` inside `@on` handlers
  (`event.player.`, `event.block.`), attributes of instances and of class members, arguments of nested calls, completion inside
  lists/dicts passed to calls. Code: worker `_complete`, `server/CompletionEngine`, `analysis/StringContext`, `client/mui/CodeAssist`.
- [x] `block_tick` / `block_interact` failed on every tick with `property 'pos' of '_EventContext' object has no setter`
  (`SlottedBlockEntity` sent `pos` as a string). Now sends the shape of every other block event (x/y/z, dimension, block, player).
  Test: `test_block_tick_and_interact_context_from_a_block_entity`. **Needs one look in game** (a block with `@on.block_tick`).
- [x] `/craftpy give` printed `Gave 0` although the item arrived (`Inventory.add` uses the stack up). Message fixed.
- [ ] Check in game and report: PC block opens the IDE; Ctrl+C/Ctrl+V in code and texture editor; F1 Help; asset upload/drop;
  the Leave dialog; editor settings saving; completion inside strings (`player.give("`); Validate warns on an unknown id.
- [ ] Dev machine notes: the client died once with `insufficient memory` (client `-Xmx8G` + server `-Xmx4G` + other apps on a
  16 GB PC; close big apps or lower `-Xmx` for the session). Client and server both use `run/`, so they fight over
  `run/logs/latest.log` (harmless). Stdin is not forwarded to `runServer`, so the console cannot be typed into; `run/server.properties`
  now has `enable-rcon=true` with a random password (local dev only, set `enable-rcon=false` to turn it off). To op yourself:
  start the server, then send `op <name>` over RCON (port 25575), or write `run/ops.json` before starting.

## 2. M18: IDE without launching Minecraft (in progress)

Done: spike (Modern UI runs on a plain JVM), step 1 (`IdeHost`/`McHost`, `CraftPyLog`). Remaining, in this order, each step
compiled and tested alone (details and error counts in REPORT M18):
- [ ] `RequestPayload` / `ResponsePayload`: a plain `Response` record behind the packets (store and handler use them as data).
- [ ] `Config.safe(...)` (defaults when the NeoForge config is not loaded); `PyForgePaths` honouring `-Dcraftpy.home`.
- [ ] `python/HostBridge` and `registry/SlotRegistry` (via `ProjectTools.ownIds`, `Declarations`) behind small interfaces.
- [ ] `ProjectService` taking a `Caller` (permission, upload key, reload/refresh callbacks); `ProjectRequestHandler` becomes the
  player adapter (window events stay there).
- [ ] `client/assets/*` (AssetIndex, AssetInfo, AssetPreview, SoundPreview) read from the catalog / files instead of Minecraft
  registries when there is no game; 3D preview, sound preview and Run-in-world disabled through capabilities.
- [ ] Copy the few `ThemeControl` / `ClampingScrollView` members used (or find MC-free equivalents).
- [ ] `ide/IdeMain` (+ `LocalBackend`), `runIde` and `ideJar` Gradle tasks, smoke test printing `CraftPy IDE ready`.
  Classpath that worked in the spike is in REPORT M18. Limit: with no game ever started the catalog is only the seeds + jar scan.

## 3. M16 gaps (mod catalog / completion), from the plan but not confirmed done

- [ ] Replace the silent `minecraft:` prefix in `_ref` by a lint warning.
- [ ] Extension registry that modules can append to (`_EXTENSION_NAMES` is still a fixed dict; user `@extension` classes are completed).
- [ ] Effect helpers using catalog ids.
- [ ] Field check of `recipe("mod:type", ...)` against the recipe-type schema (warning).
- [ ] Project context for `hover` (own content and imports), cap on completion items raised, filtering in Java.
- [ ] Verify the catalog dump on a server with Create, Mekanism, Iron's Spells, Curios (first run wrote
  `create, curios, flywheel, geckolib, irons_lib, irons_spellbooks, mekanism, minecraft, neoforge, playeranimator, ponder` files; contents not inspected).

## 4. M17 gaps (installer)

- [ ] Run a real install on a clean profile (NeoForge installer, Modern UI download, copy). Only the plan, the hash check and
  `--dry-run` were run.
- [ ] Linux and macOS commands (`apt`, `dnf`, `pacman`, `brew`), the Swing window.
- [ ] Embedded Python download (only package managers are supported now).

## 5. Earlier plan, batch 5 (mod metadata and clean push) - still open

Batches 1-4 of that plan (extensions, build overwrite/new, docs, asset dock with texture/sound editors, editor font) are in
REPORT M8-M15. Open:
- [ ] `neoforge.mods.toml`: `logoFile`, `displayURL`, `issueTrackerURL`, `authors`, `credits` are still the template placeholders;
  `mod_description` in `gradle.properties`; copy `CraftPyLogo.png` into `src/main/resources/` (the root copy is untracked/unused).
- [ ] Rewrite `README.md` as a project page (what it is, features, quick start, examples, extension syntax, config, licence).
- [ ] The clean push: that plan wanted `src/test`, `logs`, `.claudemem`, `run`, `bin`, `docs`, `AUDIT.md`,
  `instructions.txt`, `TEMPLATE_LICENSE.txt` untracked and ignored for the public push. Today `docs/`, `src/test` and `logs/` are
  tracked and the branch is **8+ commits ahead of `origin/Updates`, not pushed**. Decide before pushing: keep tests/docs public or
  strip them as planned. Never force-push; review `git status` / `git diff --stat` first.

## 6. Editor roadmap that was never started (from REPORT)

- Batch D (needs a custom text view instead of `EditText`): 2 minimap (a simple one exists), 4 Alt+drag box selection,
  15 smooth scroll / cursor blink settings, 16 split editor; then 31 move function/class to another file, 37 `test_*.py` runner
  (needs the multi-file import design), 41 Python environment selector, 55 theme editor.
- Batch E (design first): 34-35 pyright / mypy / ruff in the worker, 38-39 debugger + call stack, 51 shared sessions with file locks.
- More than 4096 slots / no restart: data-component items + variant blocks (design in REPORT M12 "Not done").
- Model viewer, content preview and inspector are still vanilla screens (`ContentPreviewScreen`); drag is only from the Assets tab.
- Audit gaps: 8 (every asset save rebuilds the pack and reloads clients), 29 (*New module* builds keep the old namespace), a sound
  copied from a multi-variant event is a random variant.
- Old unchecked items of the REPORT checklist (texture editor hex input / palette, generated-content summary in the preview,
  underline problems, resizable panels, export to a chosen folder, better tutorials, polish) - most were redone in the Modern UI
  rewrite; re-check before doing any.

## 7. End-of-batch routine (standing request)

At the end of a batch: remake the shipped example (`src/main/resources/craftpy_default/welcome`, listed in `files.txt`) and
reset `run/pyforge` for the dev client (move it out of the repo, backup in the scratchpad, never a hard delete; re-seed `welcome`
from the manifest; the game writes the marker on next start). **Not done for M16-M18**; the session built and installed the
`welcome` module into `run/pyforge/modules/` while testing.

## 8. Known limits (from the last "what is missing" list)

- Java bridge: no registry entries after startup, no subclassing from Python, public members only; the deny list is best effort.
- Files are always read and written as UTF-8 (no charset conversion).
- `storage` is not persisted across restarts.
- Recipe, loot and content previews need the project to be running.
- Rename refuses on unsaved text.
- The missing-imports helper only knows about 20 standard-library modules.
- Handlers can cancel only `block_break`, `item_use`, `item_hit` and `item_mine`.
