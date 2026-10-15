# CraftPy IDE guide

CraftPy lets you make Minecraft content with Python **without recompiling the game**. A *project* is a folder of
Python files, textures and data. Press **Run** and the blocks, items, recipes, events, commands and windows exist in
the running world; change a file, press **Run** again, and they change.

## How it works

* The game keeps a pool of empty *slots* (blocks and items that were registered once at startup). When your script
  declares `Block("ruby_ore", ...)`, a slot is given to `ruby_ore` and filled with your settings. The mapping is
  saved, so blocks you placed keep working after a reload or restart.
* A separate Python process (the *worker*) runs your code inside a sandbox: no files, no processes, no network.
  Your code reaches the game only through the CraftPy API (`world`, `players`, `ui`, ...) or, when you need
  something the API does not wrap, the **Java bridge** (`java.cls(...)`, see *Java bridge*).
* Textures and models are generated into a resource pack that is sent to the players, so nothing needs a restart.
  Registry-level changes (a new *kind* of thing) say so in the console: "full game restart required".
* Everything is yours to test: the project is a normal folder, `pyforge/projects/<id>/`.

## The project folder

```
code/main.py          entry point; every file it imports is run too
code/items.py ...     any other .py file: `import items`, `from blocks.ore import ruby_ore`
code/blocks/__init__.py   a folder with __init__.py is a package
assets/textures/      PNG textures, used by file name: texture="ruby.png"
assets/<namespace>/   a full resource pack layout, see "Texture packs"
ui/*.json             windows drawn with the UI designer
data/                 raw data pack files
project.json          name, version, author
```

A mistake in one file never breaks a running project: if the new version fails to run, the previous version keeps
its blocks, items and event handlers, and the error is shown with its file and line.

## The editor

| Part | What it does |
| --- | --- |
| **Explorer** (left) | A folder tree like VS Code. Click a folder to open or close it; click a file to preview it, double click to keep it open. Textures and sounds: click selects, double click opens the viewer. Right click for *New file / folder, Rename, Delete, Copy path*. Arrow keys move, Enter opens, F2 renames, Delete deletes. `+` makes a new file in the selected folder, the other two buttons collapse and expand everything. |
| **Tabs** | Preview tabs (italic) are replaced by the next click. Right click a tab for pin / close others. The arrow at the right lists every open tab. |
| **Breadcrumb** | Where the cursor is (file > class > function). Click it for the outline of the file. |
| **Code area** | Syntax colours, line numbers, bookmarks, current-line and word highlights, a minimap at the right edge. |
| **Console / Problems / Bookmarks** (bottom) | Output of runs and events, errors and warnings with their line, your bookmarks. Click a line to jump. |
| **Dock** (right) | Four tabs: *Preview* (3D model or picture), *Texture* editor, *Sound* editor and *Assets*. *Assets* (toolbar button) lists every texture, item, block, model and sound the game has: type to search (`#tag`, `@namespace`). Click one to open it in the dock. Drag it onto the code to insert it (the id as a string, a sound as `event.player.play_sound("...")`) or onto a folder of the explorer to copy the file there (an existing file is never overwritten). Pressing Save on a texture or sound taken from the game writes your copy to `assets/<project>/textures/...` or `sounds/...`, the resources of the project and of the module built from it; the original stays untouched. |

### Esc

`Esc` always steps back one level: it closes the open dialog, then the completion list or hint, then the find bar,
then the selection in the explorer, then a text selection, then zen mode, and finally leaves the screen.
Clicking outside a dialog also closes it.

### Writing code

* **Completion** opens while you type and with `Ctrl+Space`: API names, your own functions and variables, keywords,
  and members after a dot (`world.` shows everything the world can do). `Up`/`Down` choose, `Enter` or `Tab` accept,
  `Esc` closes.
* **Documentation**: rest the mouse on a name, or press `Ctrl+Q`, to see its signature and description. If the line
  has a problem, the message is shown there too.
* **Go to definition**: `Ctrl+click` a name (or `Ctrl+B`, `F12`). Your own functions open where they are defined,
  also in other files. API names (`world.set_block`, `Item`, `java.cls`) open the generated, read-only
  **library file** `library/craftpy_api.py` at that name: every class, method and its documentation.
  `Alt+Left` / `Alt+Right` go back and forward.
* **Problems**: syntax errors, undefined names, unused imports and redefined functions are marked as you type and
  after *Validate*.
* **Refactoring**: *Tools > Rename id everywhere* renames a block or item in every file and its textures;
  *Organise imports*, *Add missing imports*, *Generate docstring*, *Extract variable / function*.
* **Search**: `Ctrl+F` find, `Ctrl+H` replace (with preview), `Ctrl+P` go to file, `Ctrl+T` go to symbol anywhere in
  the project, `Ctrl+E` recent locations.

### Run, Reload, Stop

* **Run** (`Ctrl+R`) saves and runs this project. **Reload** (`F5`) also reloads every module.
* **Stop** interrupts Python code that is still running.
* If the server config `hotReloadOnSave` is on, saving a code, ui or asset file re-runs the project after a short
  pause (one run for a burst of saves).

### The asset dock

*View > Asset dock* (or double click a texture or sound in the explorer) opens a panel at the right of the editor with
four tabs (Preview, Texture, Sound, Assets). Drag its edge to resize it; `x` hides it, your edits stay.

* **Preview** draws a model, block state or item file (`assets/**/models`, `blockstates`, `items`) as a 3D picture you can turn
  with the mouse (wheel zooms). The buttons above it show the model the way the game does in the inventory, on the ground, in
  item frames, in hands and on the head. While the Preview tab is showing, it follows the file you are typing in. Textures and
  parents are read from the game's resources, so run the project once to put your own assets into the pack. The picture is an
  approximation: no lighting, tint colours or animation.
* **Texture** is a pixel editor. Tools: pencil, eraser, fill (with tolerance), colour picker, line, rectangle, filled box,
  ellipse, filled ellipse, lighten, darken and select. Brush size `[` `]`, mirror drawing, a 3x3 tile preview to check seams,
  RGBA sliders and hex input, a palette, recent colours, copy / paste of the palette, select + copy / cut / paste, flips, rotate,
  wrap-shift, resize, undo / redo (`Ctrl+Z` / `Ctrl+Y`) and Save (`Ctrl+S`) into the project. Files up to 288 KB open.
  *Tools > New texture* starts a blank one. Keys work while the picture has the focus (click it).
* **Sound** shows the waveform of an `.ogg` or `.wav` file. Drag to select, click to place the cursor, wheel to zoom. Play the
  sound, the selection or a loop (`Space`); trim, cut (`Delete`), silence, fade in / out, gain, normalize, reverse, faster /
  slower, mono and insert silence, all undoable. Minecraft plays Ogg Vorbis only, so *Save* writes an `.ogg`: Python with the
  `soundfile` package encodes it (if it is missing the editor offers *Install encoder*, which runs `pip install --user soundfile`,
  or *Save as .wav*, a file Minecraft cannot play).

* **Assets** (also the toolbar button) is described above: click opens, drag inserts or copies.

Menus close when you click outside, press Esc or move the pointer away from them. In a menu the arrow keys, Home, End and Enter work (Right opens a sub-menu, Left closes it, Esc closes the menu).

### Windows

*Tools > New UI window* creates `ui/<id>.json`; *Tools > UI designer* opens the visual editor. Buttons name an
`@action("...")` function in Python. Windows can also be written in code (`@window` classes, see the API).

## Extensions

Behaviour of items and blocks is added with extensions: `Item("ruby_sword").extends(Sword(tier="diamond"), Durability(1200))`.
There are built-in ones (tools, durability, armor, fuel, cooldown, tooltips, redstone, fire, friction, pistons...) and you can
write your own with `@extension` (hooks such as `on_hit` or `on_use`). They are described, with every parameter, in the Python
API topic.

## Building modules

*Build* packs the project into a `.cpm` module. In the dialog choose **Update** (the installed module of this project is replaced
in place, so there is never a duplicate) or **New module** (its own id and display name; its blocks and items then use that id as
namespace). With *Install* ticked the module is loaded right away; the file is always also written to `exports/`.

## Editor font

The code area, line numbers, console and completion list use JetBrains Mono (SIL Open Font License), shipped with the mod. The rest
of the IDE keeps the game's UI font.

## Texture packs

A project can ship a full resource pack. Put files in `assets/<namespace>/...` exactly as in a normal mod or
resource pack and they are added to the generated pack:

```
assets/textures/ruby.png                        the simple way: file name only
assets/ruby_rush/textures/block/ruby_ore.png    your namespace
assets/minecraft/textures/block/stone.png       replaces a vanilla texture for everyone who joins
assets/minecraft/lang/en_us.json               extra translations
assets/ruby_rush/sounds.json, sounds/*.ogg      sounds
assets/ruby_rush/models/..., blockstates/...    hand written models (they win over generated ones)
pack.mcmeta                                     optional; a default one is written
```

To replace a game texture for everyone instead of adding your own copy, save it as `assets/minecraft/textures/...` (type that path in the name box of the texture editor). The default save of a texture taken from the Assets tab is the module resource folder `assets/<project>/...`, never an override.

## Java bridge

See the *Java bridge* topic: `java.cls("net.minecraft...")`, `java.new(...)`, `@java.on(EventClass)`.

## Keyboard shortcuts

| Key | Action |
| --- | --- |
| `Esc` | Step back: dialog, completion, find bar, selection, zen mode |
| `Ctrl+S` | Save (trims trailing spaces if enabled) |
| `Ctrl+R` / `F5` | Run / reload project and modules |
| `Ctrl+Enter` | Validate |
| `Ctrl+F` / `Ctrl+H` | Find / replace |
| `F3` / `Shift+F3` | Next / previous match |
| `Ctrl+P` | Go to file |
| `Ctrl+Space` | Completion list |
| `Ctrl+Q` | Documentation of the name at the cursor |
| `Ctrl+click`, `Ctrl+B`, `F12` | Go to definition |
| `Alt+Left` / `Alt+Right` | Back / forward through jumps |
| `Ctrl+W` | Close tab |
| `Ctrl+T` | Go to symbol |
| `Ctrl+E` | Recent locations |
| `Ctrl+Alt+O` / `Ctrl+Alt+I` | Organise / add missing imports |
| `Ctrl+Shift+D` | Generate a docstring |
| `Ctrl+Alt+V` / `Ctrl+Alt+M` | Extract variable / function |
| `Ctrl+J` | Join lines |
| `Ctrl+Shift+Enter` | Split line |
| `Ctrl+F2` | Toggle bookmark |
| `F2` / `Shift+F2` | Next / previous bookmark (in the explorer, `F2` renames) |
| `Tab` / `Shift+Tab` | Indent / unindent |
| `Ctrl+=` / `Ctrl+-` / `Ctrl+0` | Zoom in / out / reset (also `Ctrl` + mouse wheel) |
| `F11` | Zen mode |
| `Ctrl+Z` / `Ctrl+Y` | Undo / redo |
| `F1` | This help |
