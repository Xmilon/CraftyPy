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
- **2026-10-16 session**: "do all of the features until they are done, think about what could go wrong, make no mistakes"
  (the user verifies in game later); then **"think about 50 new features (IDE, UI, quality of life, actual coding) and
  implement them, don't ask, then refine the entire project"**; then **"this has to be an SSS tier mod; add resizable editor
  windows like in IntelliJ; it has to look like a clone of IntelliJ but still different"**. Order of work: section 1, M18,
  sections 3-6, the 50 features (section 9), the IntelliJ-style layout (section 10), a refinement pass over everything.
- **Reference material**: the compat mods' sources are in `C:/Users/Xmilon/IdeaProjects/Compatibily Mods` (claudemem there is only
  partly set up because indexing takes too long). Load claudemem for that folder only when really needed (tokens are expensive).
  The mod jars used for testing are in `run/mods` (Create 6.0.10, Curios 9.5.1, GeckoLib 4.9.3, Iron's Spellbooks 3.16.3 + Iron's Lib,
  Mekanism 10.7.19, Player Animation Lib 2.0.4, Modern UI 3.13.0.1).

## 1. Fix first: found in the first in-game session (2026-10-15)

- [x] **Textures are not applied** (2026-10-16): project textures at the root of `textures/` (`welcome:gem`) are not in the
  block atlas, which only stitches `textures/block/` and `textures/item/`. `PackBuilder.blockAtlas` writes
  `assets/minecraft/atlases/blocks.json` with a `single` source per such texture (atlas files of all packs are merged).
  **Check in game.** Known limit: a client on a remote dedicated server only has the pack the server's folder holds when both
  share the game folder (dev setup); sending the pack to remote clients is not done.
- [x] **Complex syntax is not completed** (2026-10-16): the worker infers types statically (`_infer`, `_members`): chained
  calls, `event.` in `@on`/`@command`/`@every`/`@action` handlers, locals, `for` loops, `self.` in classes of the file, values
  returned by defs, keyword arguments of the call being typed (any nesting). API classes carry annotations for it. **Check in game.**
- [x] `block_tick` / `block_interact` failed on every tick with `property 'pos' of '_EventContext' object has no setter`
  (`SlottedBlockEntity` sent `pos` as a string). Now sends the shape of every other block event (x/y/z, dimension, block, player).
  Test: `test_block_tick_and_interact_context_from_a_block_entity`. **Needs one look in game** (a block with `@on.block_tick`).
- [x] `/craftpy give` printed `Gave 0` although the item arrived (`Inventory.add` uses the stack up). Message fixed.
- [ ] Check in game and report: PC block opens the IDE; Ctrl+C/Ctrl+V in code and texture editor; F1 Help; asset upload/drop;
  the Leave dialog; editor settings saving; completion inside strings (`player.give("`); Validate warns on an unknown id.
- [ ] Dev machine notes: the client died once with `insufficient memory` (client `-Xmx8G` + server `-Xmx4G` + other apps on a
  16 GB PC; close big apps or lower `-Xmx` for the session). Client and server both use `run/`, so they fight over
  `run/logs/latest.log` (harmless). Stdin is not forwarded to `runServer`, so the console cannot be typed into; `run/server.properties`
  had `enable-rcon=true` with a random password during the session; it is back to `enable-rcon=false` (set it to true and a password to use RCON again). To op yourself:
  start the server, then send `op <name>` over RCON (port 25575), or write `run/ops.json` before starting.

## 2. M18: IDE without launching Minecraft (in progress)

Done (2026-10-16): `gradlew runIde` opens the IDE in its own window on `run/` (or `-PcraftpyHome=<game folder>`);
`gradlew ideSmokeTest` starts it headless. How: the game's classes stay on the classpath but are never bootstrapped (the unit
tests already ran that way), so the payload records, `Config` (`Config.get` falls back to defaults) and the handler are reused
as they are. `ProjectRequestHandler.Caller.LOCAL` has no server: load/unload/modules answer "Needs the game". `IdeMain`,
`LocalHost` (requests in-process, Modern UI thread, system clipboard), `WindowChrome` (title, logo icon, dark Windows caption).
Remaining:
- [ ] Assets dock, 3D preview and sound preview read the game's resource manager: in the standalone window they must read the
  bundled vanilla assets jar instead, or say "needs the game" (check every `client/assets/*` entry point).
- [ ] `ideJar`: one runnable jar with Modern UI (today `runIde` needs the Gradle classpath).
- [ ] Optional later: the decoupling steps of the old plan (plain `Response` record, `HostBridge`/`SlotRegistry` interfaces)
  only matter for an IDE without the game jar on the classpath.

## 3. M16 gaps (mod catalog / completion)

- [x] Silent `minecraft:` prefix: `IdChecker` already warns about a bare name no installed mod has ("a bare name means
  minecraft:x"), so a typo is reported.
- [x] Extension registry: `@extension` classes of a module (or project) can be used by name in every project.
- [x] Effect helpers with catalog ids: `Item(effect="mod:effect")` was broken (read as id `mod`); fixed on both sides.
- [x] Field check of `recipe("mod:type", ...)` against the fields the dump saw (warning).
- [x] Completion cap raised to 250 and ranked (prefix, then initials).
- [x] Hover and completion of names imported from the project's other files (the assist request carries the .py files).
- [ ] Verify the catalog dump on a server with Create, Mekanism, Iron's Spells, Curios (needs the game).

## 4. M17 gaps (installer)

- [ ] Run a real install on a clean profile (NeoForge installer, Modern UI download, copy). Not run: it changes the user's
  `.minecraft`; do it together with the user.
- [x] Linux and macOS commands (`apt`, `dnf`, `pacman`, `brew`) and the Swing window were already there (this item was stale).
- [ ] Embedded Python download (only package managers are supported now).

## 5. Earlier plan, batch 5 (mod metadata and clean push)

- [x] `neoforge.mods.toml`: logo (`craftpy_logo.png`, 256 px, made from `CraftPyLogo.png`), `displayURL`,
  `issueTrackerURL`, `credits`, description. Window icons in `assets/craftpy/icon/`.
- [x] README: the new IDE, standalone IDE, shortcuts.
- [ ] The clean push: decide whether `docs/`, `src/test`, `logs/` stay public. Never force-push; review `git status` /
  `git diff --stat` first. **Nothing is pushed yet** (branch `Updates`).

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
reset `run/pyforge` for the dev client (move it out of the repo, backup in the scratchpad, never a hard delete; re-seed
`welcome` from the manifest; the game writes the marker on next start).
- Done 2026-10-16 after M19: `welcome/code/main.py` describes the new layout; `run/pyforge` moved to the session
  scratchpad (`pyforge-backup-2026-10-16`). Placed CraftPy blocks in `run/saves` may show other content until the projects
  are loaded again (the slot map was in that folder).

## 8. Known limits (from the last "what is missing" list)

- Java bridge: no registry entries after startup, no subclassing from Python, public members only; the deny list is best effort.
- Files are always read and written as UTF-8 (no charset conversion).
- `storage` is not persisted across restarts.
- Recipe, loot and content previews need the project to be running.
- Rename refuses on unsaved text.
- The missing-imports helper only knows about 20 standard-library modules.
- Handlers can cancel only `block_break`, `item_use`, `item_hit` and `item_mine`.
