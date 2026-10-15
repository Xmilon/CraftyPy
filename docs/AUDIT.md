# CraftPy UI Architecture (Modern UI)

Status as of 2026-10-14. Supersedes the earlier "UI Architecture Audit & Rewrite Plan", which
mapped CraftPy onto Android classes (RecyclerView, CardView, AppCompat, ViewPager2...) that
Modern UI does not ship.

## 1. Screens

All CraftPy screens are one Modern UI screen with a navigation rail, modelled on Modern UI's
own center screen (`icyllis.modernui.mc.ui.CenterFragment2` + `PreferencesFragment`).

| Page | Class | Contents |
|------|-------|----------|
| Root | `client/mui/CraftPyFragment` | Nav rail (Home / Modules / Editor / Docs), page container, `ScreenCallback` (Esc-only back key, no pause, no blur), shared helpers (`card`, `button`, `label`, `prompt`, `hoverBackground`, `store`, `color`), handles `/craftpy ide project <id>` |
| Main menu | `client/mui/HomeFragment` | Create project, project list (Open + More menu: Run, Stop, Rename, Duplicate, Import texture, Delete) |
| Modules menu | `client/mui/ModulesFragment` | Searchable module list with enable `Switch`es, Enable/Disable all, details pane, README via Markflow, Reload / Open folder / Delete |
| Editor menu | `client/mui/EditorFragment` | Toolbar (Save, Validate, Assets, Build, Stop, Run), file explorer with context menus, tabs, highlighted `EditText` + line-number gutter, Console (REPL) / Problems panel |
| Docs | `client/mui/DocsFragment` | `assets/craftpy/docs/api.md` rendered with Markflow |

Entry points (`CraftPyClient`):
- Computer block / `/craftpy ide`: `ComputerMenu` is registered with
  `icyllis.modernui.mc.neoforge.MenuScreenFactory` -> `new CraftPyFragment()`.
- Keybind `I` (operators): `CraftPyFragment.createScreen(PAGE_HOME)`.
- File drop on a CraftPy screen: uploads to the open project (`AssetUploader.uploadAll`).

Still vanilla (`client/screen/`, opened from the Editor and returning to a fresh Editor page):
`AssetFileScreen` (texture/sound viewer), `TextureEditorScreen`, `AssetPickerScreen`
(+ `InspectorScreen`, `SoundPlayerScreen`, `ModelViewerScreen`). These were not part of the
rewrite.

## 2. Separation of UI and logic

- `ClientProjectStore` (client thread) owns every piece of project/module/console state and all
  network requests. The fragments contain no scripting logic.
- `client/Syntax` is the pure tokenizer used for highlighting (unit-tested in `SyntaxTest`).

## 3. Threading rule

Modern UI views run on Modern UI's **UI thread**; `ClientProjectStore` belongs to the
**Minecraft client thread**.

- UI -> store: always through `CraftPyFragment.store(s -> ...)` (`Minecraft.execute`).
- store -> UI: each page registers a store listener. The listener runs on the client thread,
  copies what it needs into immutable values (`List.copyOf`, the editor's `State` record) and
  `View.post`s a render to the UI thread. Views are never touched from the client thread.

Editor text sync: typing is debounced (150 ms), then the text is pushed with `markDirty` and
re-highlighted. A server-side reload only replaces the editor text when the user has no unsynced
edits (`synced` comparison), so typing is never overwritten.

## 4. Build / runtime

- `build.gradle`: Modern UI is `compileOnly` and non-transitive (`ModernUI-NeoForge`,
  `modernui-core`, `arc3d-core`, `ModernUI-Markflow`, `commonmark`). Transitive resolution failed
  because Modern UI's icu4j/fastutil versions clash with Minecraft's strict pins.
- Runtime: the Modern UI mod provides the classes. `neoforge.mods.toml` declares
  `modernui` as required, `side="CLIENT"`, so dedicated servers do not need it.
- Dev client: `run/mods/ModernUI-NeoForge-1.21.1-3.13.0.1-universal.jar`.

## 5. Removed

Old vanilla UI, deleted in the rewrite: `ComputerScreen`, `ProjectChooserScreen`,
`ModuleManagerScreen`, `CodeEditor`, `client/ide/*` (CodeEditorWidget, EditorTabs, IconButton,
IdeColors, ScrollView, MarkdownRenderer), `ApiDocs`, `ClientSettings`, and the unused custom toolkit
`client/ui/*` + `SlottedScreen` (nothing referenced them). Tests moved/removed accordingly
(`CodeEditorTokenizerTest` -> `SyntaxTest`; `MarkdownRendererTest` and `ApiDocsTest` deleted
with their classes).

## 6. Editor internals (M8)

- `client/mui/CodeView` (EditText subclass) paints current line, ruler, word and find matches under the text.
- `client/TextOps` holds every text command as pure functions (unit-tested in `TextOpsTest`).
- `client/EditorSettings` persists theme, zoom, wrap, pins, bookmarks, recent files, find history and panel
  sizes in `config/craftpy-editor.json`; `client/EditorTheme` defines the six code themes.
- `client/mui/Splitter` resizes the explorer and the bottom panel.

## 7. Project tools (M9)

- `analysis/` (pure Java, shared by server and client, unit-tested): `PyLex`, `PyOutline`, `PyImports`,
  `PyDocstring`, `ProjectScan`, `ModuleGraph`. Nothing in it touches Minecraft or Modern UI types.
- `server/ProjectTools` answers `ProjectAction.TOOL` requests (`symbols`, `usage`, `rename`, `explain`, `decls`,
  `profile`, `trace`). Why not on the client: it holds only open files and packet strings are capped at 32767
  characters; replies are trimmed to 30000 and say how many rows were cut (`<key>Cut`).
- `ProjectManager.snapshot` (paths + text of text files, capped 256 KB per file / 6 MB total) and `applyRename`
  (write texts, then move files) run on the per-project executor.
- `client/mui/EditorTools` is the single owner of these features on the UI side; it reaches the editor through
  the small `EditorTools.Host` interface. `client/mui/ListDialog` is the reusable popup; `ContentPreviewScreen` is the
  one new vanilla screen (3D item model, same technique as `ModelViewerScreen`).
- Runtime tools: the worker keeps `_PROFILE` per owner; `EventTrace` (server JVM, static) is fed from
  `EventBridge.fire`. Both are read-only views, safe to leave on.
- Threading is unchanged: tool callbacks arrive on the client thread and are `post`ed to the UI thread
  (`EditorTools.postUi` drops replies that arrive after the editor page was closed).
- The code area is now a `FrameLayout` (scroll view + sticky-header overlay); `CodeView` also reports caret moves
  (`setOnCaret`, used for breadcrumbs, status bar, sticky headers and focus mode) and paints the focus dimming.
- `EditorTools` caches the parsed outline per `textVersion` (a counter the fragment bumps on every text change).

## 7b. M11 changes (completion, hover docs, asset viewer, lint)

- Completion and hover are answered by the Python worker (`complete` / `hover`, via `TOOL` ops with a `seq`), so they
  use the real API objects: members after a dot, signatures and docstrings. Replies for text that has changed are
  dropped by `seq`. The `complete` / `hover` callbacks stay registered (`ClientProjectStore`), because several are in
  flight while typing; their failures are not written to the console. Files over 20000 chars are sent as a window of
  whole lines around the caret. Both ops need the IDE permission like every `TOOL` op; `line`/`column` are validated.
- `EditorFragment.renderTabs` / `renderProblems` / `renderConsole` now skip the rebuild when nothing changed. Before,
  every store snapshot rebuilt the tab chips: a click whose press and release straddled a snapshot was lost, and a
  tooltip could be left on a removed view. Toolbar buttons hide their tooltip while the menu they open is up.
- `ListDialog` clamps its height to the window (the footer used to fall off screen, so the dialog could not be closed),
  has a header close button and closes on Escape.
- `AssetViewer` (Modern UI popup covering the window) replaces `AssetFileScreen`, which is deleted. The pixel logic is
  `PixelBuffer` (unit tested). `TextureEditorScreen`, `SoundPlayerScreen` and `AssetPickerScreen` are still the old
  vanilla screens; the picker still uses them.
- The worker `validate` also lints (undefined names, unused imports, redefined top-level functions) as warnings.
  It is flow-insensitive, so a name that is only bound later is not reported. A `from x import *` other than craftpy
  switches the undefined-name check off for that file.
- `ChangeLog` (`system/logs/changes-<id>.log`, last 2000 lines) records saves, creates, renames, deletes, uploads.

## 7c. M12 changes (overlays, tree, definitions, multi-file, bridge)

- `Overlays` replaces `PopupWindow` everywhere in `client/mui` (grep: no `PopupWindow` import left in `EditorFragment`, `CodeAssist`, `ListDialog`, `UiDesigner`, `AssetViewer`, `CraftPyFragment`). Esc handling lives in `Overlays.Host.dispatchKeyEvent` (before the focused view) and `EditorFragment.onBack`.
- Threading unchanged: UI views on the UI thread, store calls through `store(...)`, assets through the client thread (`AssetPicker`).
- Worker: `ProjectFinder`, transactional `run_main`, `revert_owner`, `definition` / `stub`, `java` bridge proxies, `call_fn`.
- Host: `bridge.*`, `SlotRegistry.applyAll`, `SlotPlan`, `SlotCounts`, `ProjectController.scheduleReload`.
- Tests added: `FileTreeTest`, `SlotPlanTest`, `JavaBridgeTest`, `PackBuilderAssetsTest`, Python `test_multi_file_projects_and_failed_reload`, `test_definition_and_stub`, `test_java_bridge_proxies`, `test_java_event_handlers`; `ExamplesAnalysisTest` now reads the split example.

## 8. Known gaps vs. the old IDE

Still not carried over: smart-fill ghost text, go to definition,
command palette, console meta-commands, Markdown preview tab, JSON format action, inline `tex()` thumbnails.
UI strings are English literals, not lang keys.
