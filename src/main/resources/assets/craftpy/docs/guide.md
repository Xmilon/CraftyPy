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

The layout follows IntelliJ's New UI. The **main toolbar** has the project widget (switch project, run, build, settings),
the menus, *Search Everywhere* and the Run / Reload / Stop buttons. The narrow **stripes** on the left and right edges open
and close the tool windows (also `Alt+1`, `Alt+2`, `Alt+4`, `Alt+5`, `Alt+6`, `Alt+7`, `Alt+8`, `Alt+9`); drag the thin bars between
the panels to resize them. `Ctrl+\` splits the editor in two, `Ctrl+Shift+F12` hides every tool window, and
`Ctrl+Alt+S` opens the settings. The same IDE runs without the game: `gradlew runIde`.

- **Project** (left, `Alt+1`): A folder tree like VS Code. Click a folder to open or close it; click a file to preview it, double click to keep it open. Textures and sounds: click selects, double click opens the viewer. Right click for *New file / folder, Rename, Delete, Copy path*. Arrow keys move, Enter opens, F2 renames, Delete deletes. `+` makes a new file in the selected folder, the other two buttons collapse and expand everything.
- **Tabs**: Preview tabs (italic) are replaced by the next click. A dot means unsaved changes. Right click a tab for pin, split right, close others / all / to the right, copy path; middle click closes it; double click hides the tool windows. The arrow at the right lists every open tab.
- **Breadcrumb**: Where the cursor is (file > class > function). Click it for the outline of the file.
- **Code area**: Syntax colours, line numbers, bookmarks, indent guides, bracket pairs, current-line and word highlights, a minimap with error marks at the right edge.
- **Console / Problems / Bookmarks / Events** (bottom): Output of runs and events (Ctrl+click a traceback line to open the file there), errors and warnings with their line, your bookmarks, the event tracer. Click a line to jump.
- **Building blocks** (right, `Alt+8`): Ready-made pieces by category: Docs, Insert at the cursor, or New file imported by `main.py`.
- **Assets, Preview, Texture, Sound** (right, `Alt+9`): *Preview* (3D model or picture), *Texture* editor, *Sound* editor and *Assets*. *Assets* lists every texture, item, block, model and sound the game has: type to search (`#tag`, `@namespace`). Click one to open it in the dock. Drag it onto the code to insert it (the id as a string, a sound as `event.player.play_sound("...")`) or onto a folder of the explorer to copy the file there (an existing file is never overwritten). Pressing Save on a texture or sound taken from the game writes your copy to `assets/<project>/textures/...` or `sounds/...`, the resources of the project and of the module built from it; the original stays untouched.

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
  `soundfile` package encodes it (if it is missing the editor tells you to run `python -m pip install soundfile` yourself once, because the mod never
  downloads anything, or *Save as .wav*, a file Minecraft cannot play).

* **Assets** (`Alt+9`, right edge) is described above: click opens, drag inserts or copies.

Menus close when you click outside, press Esc or move the pointer away from them. In a menu the arrow keys, Home, End and Enter work (Right opens a sub-menu, Left closes it, Esc closes the menu).

### Windows

*Tools > New UI window* creates `ui/<id>.json`; *Tools > UI designer* opens the visual editor. Buttons name an
`@action("...")` function in Python. Windows can also be written in code (`@window` classes, see the API).

## Tutorials

Each one is a whole `main.py` you can paste into a new project and press F5 on. Texture names are files in the project's
`assets/textures/`; use the Assets window (Alt+9) to add them, or write a vanilla one such as `minecraft:block/stone`.

### 1. A block that reacts when you right click it

```python
from craftpy import *

lamp = Block("party_lamp", name="Party Lamp", texture="minecraft:block/glowstone", light=15, hardness=1)

@on.block_interact(block=lamp)
def toggled(ctx):
    ctx.player.actionbar("The lamp hums")
```

`Block(...)` declares the block; `@on.block_interact(block=lamp)` runs when someone right clicks that block (the filter keeps
it from running for every other block). `light=15` is the light it gives off.

### 2. A block with its own mesh and hitbox

1. Model the block in Blockbench and export it as **Java Block/Item**. Save it as `assets/models/block/pedestal.json`, and
   its textures as PNG files in the same project (the model refers to them as `yourproject:block/name`).
2. Declare the block with the model. The hitbox (outline and collision) is taken from the model's boxes, so you
   normally write nothing more. To override it, give `shape`: boxes in sixteenths of a block (0 to 16).

```python
from craftpy import *

Block("pedestal", name="Pedestal", model="pedestal")

# the same block with a hitbox of your own (here a simple base and column)
Block("pedestal2", name="Pedestal 2", model="pedestal",
      shape=[(2, 0, 2, 14, 3, 14), (5, 3, 5, 11, 12, 11)])
```

Without `model` and `shape` the block is a cube with `texture`. The hitbox follows the model's boxes as they are drawn; a rotation set on a box in Blockbench is not applied to it (use `shape` then).
A model that does not fill the cube can show the sky or a cave behind it from the side (the game hides the faces of
blocks next to it as if it were a full block): keep the model's footprint close to the whole cube where that matters.

### 3. A block that stores energy and fluid

```python
from craftpy import *

Block("battery", name="Battery", texture="minecraft:block/iron_block").extends(
    EnergyStorage(capacity=100000, max_receive=500, max_extract=500))

Block("tank", name="Tank", texture="minecraft:block/glass").extends(
    FluidTank(capacity=16000, allowed=["minecraft:water"]))
```

They are real NeoForge energy (FE) and fluid (mB) storages, so pipes and cables of other mods connect to them. To move
things yourself, `world.transfer(source, target, kind, amount)` takes block coordinates and a kind of `"energy"`,
`"fluid"` or `"item"` (the Python API topic lists every parameter).

### 4. A liquid, a window and energy for cables

The **Power Cell** example (`examples/powercell`) is a whole project that does this; here are its parts.

A liquid is a real game fluid, with a bucket:

```python
from craftpy import *

sap = Fluid("volt_sap", name="Volt Sap", color="#5CE03C",
            density=1400, viscosity=2600, temperature=340, light=7,
            effects=[("glowing", 15), ("poison", 3)],    # (effect, seconds, level) while you stand in it
            bucket_texture="volt_sap_bucket.png")
```

A machine block gets a tank, an energy store and a window, and its own mesh:

```python
generator = Block("sap_generator", name="Sap Generator", model="generator", texture="generator.png",
                  hardness=3, tool="pickaxe").extends(
    FluidTank(capacity=8000, allowed=[sap]),
    EnergyStorage(capacity=400_000, max_receive=0, max_extract=2000),  # gives energy out, takes none in
    Screen(title="Sap Generator"),
)
```

Right click it with a bucket of `sap` to fill it; right click it with an empty hand to open the window (a bucket slot, the
liquid gauge and the energy bar on the right). Python turns liquid into energy, because it decides what the machine does:

```python
@on.machine_state(block=generator)
def burn(ctx):
    machine = ctx.machine
    mb = min(machine.fluid_amount, 100, (machine.energy_capacity - machine.energy) // 80)
    if mb > 0:
        world.machine_update(ctx.x, ctx.y, ctx.z, energy=mb * 80, fluid=-mb, dimension=ctx.dimension)
```

`machine_state` fires when the block's contents change (a bucket, a cable taking energy), so the generator burns more as
room appears. Energy and liquid are the game's standard storages, so a cable of Mekanism (or any other mod) that takes
energy from a neighbour takes it from the generator, and any pipe can fill the tank. Nothing in the project names those mods.

### 5. A command with arguments

```python
from craftpy import *

@command("heal")
def heal(ctx, amount: int = 4):
    ctx.player.actionbar("Healed %d" % amount)
```

The parameter types come from the annotations (`int`, `float`, `bool`, `str`, `Player`), and the IDE completes them in game.

### 6. A window with a button

Draw the window in the UI maker (`ui/hello.json`), give the button an action name, then answer it in Python:

```python
from craftpy import *

@action("hello_click")
def hello_click(ctx):
    ctx.player.actionbar("Hello from a window")

@command("hello")
def hello(ctx):
    ui.open(ctx.player, "hello")
```

The Bank example in the examples folder is a complete version with fields, bound values and checks done on the server.

### 7. Where to go next

* *Working with other mods* (below) for ids of other mods and their recipe types.
* *Building modules* to share it.
* The Welcome template (New project > Welcome) is a small project to read.

## Extensions

Behaviour of items and blocks is added with extensions: `Item("ruby_sword").extends(Sword(tier="diamond"), Durability(1200))`.
There are built-in ones (tools, durability, armor, fuel, cooldown, tooltips, redstone, fire, friction, pistons...) and you can
write your own with `@extension` (hooks such as `on_hit` or `on_use`). They are described, with every parameter, in the Python
API topic.

## Working with other mods

When the game starts, CraftPy quietly writes a *catalog* of every installed mod to `pyforge/system/catalog/<mod>.json`: its items,
blocks, entities, mob effects, attributes, sounds, particles, enchantments, tags, custom registries, recipe types (with the fields
their recipes use), event classes and class names. It is made again only for mods whose jar or version changed. If you remove a
mod its entries disappear from the IDE (the file stays; add the same mod back and it is used again without a new dump).

What the IDE does with it:

* **Completion inside strings.** `player.give("`, `shaped("`, `effect("`, `Attributes("`, `recipe("` and keywords like
  `on.block_break(block="` offer the ids of the installed mods, vanilla first, with the mod shown next to each. `#` completes
  tags, `java.cls("` completes class names, `@on("` event names. Typing a keyword inside a call offers `name=` for the
  arguments of the CraftPy function.
* **Checks.** *Validate* warns about an id no installed mod provides (a typo, or content of a mod that is not installed) and about
  recipe types of missing mods. Nothing is reported while the catalog is empty (before the game ran once).
* **Recipes of other mods.** `recipe("create:mixing", ingredients=[...], results=[...], heat_requirement="heated")` writes the
  recipe as you give it (the fields are the mod's own JSON; your items and blocks may be named as usual) and adds a
  `mod_loaded` condition, so the pack still loads without that mod. An unknown recipe type of CraftPy's own is skipped with a
  warning instead of becoming a shapeless recipe.
* **Attributes.** `Item("ring").extends(Attributes("irons_spellbooks:fire_spell_power", 0.1, slot="offhand"))` adds any attribute
  of the game or of a mod while the item is held or worn. An attribute whose mod is missing is not applied.
* **Other namespaces.** `raw_json("tags/item/ingots.json", {...}, namespace="minecraft")` writes to `data/minecraft/...`.
* Your own `@extension` classes are completed too.

CraftPy ships what a dump cannot tell: the JSON layout of the recipe types of Create, Mekanism and Iron's Spells 'n Spellbooks, and
the datapack folders of Curios (`craftpy_integrations/*.json`). They show only while that mod is installed.

## Building modules

*Build* packs the project into a `.cpm` module. In the dialog choose **Update** (the installed module of this project is replaced
in place, so there is never a duplicate) or **New module** (its own id and display name; its blocks and items then use that id as
namespace). With *Install* ticked the module is loaded right away; the file is always also written to `exports/`.

**Works on** picks where the module does its job:

- Client and server (default) (what goes in: everything; where it works: code, recipes and loot on the server; textures, models, sounds, names for the players)
- Client only (what goes in: only `assets/`; where it works: each player's own CraftPy applies it as a resource pack, on any server (even without CraftPy). No code runs)
- Server only (what goes in: code, `data/`, `ui/`; where it works: the server; players need nothing. Blocks and items would show without textures)

A client-only module is read from the `pyforge/` folder of the computer it is installed on; enable or disable it on the
Modules page and the resources reload. `/craftpy module info <id>` shows a module's side.

### Decompiling a module

Select a module on the Modules page and press **Decompile**. You get a project with its code, assets and windows, plus
its side, permissions and dependencies. The module is turned off so the two don't run at the same time. **Build module**
in that project replaces the original module, even if the project got another name (`foo_2`). The module then shows a
**Changed** tag, and **Re-approve** if you had approved it before. What the project asks for (web, files...) stays off
until an op runs `/craftpy project trust <project>`: it is someone else's code until you say otherwise.

### The mod map

The Mod map (right edge) reads top to bottom:

- **Problems** of the whole setup: a dependency that is not installed, mods that don't work together, modules waiting
  for approval or failing to load, events that three or more modules handle. Click one to look at what it is about.
- **The one you look at:** what it is, what it does, what it adds, where it runs, what it may do and whether that was
  approved.
- **How it is tied to the rest**, one row per link: *Needs*, *Can work with*, *Doesn't work with*, *Imports code
  from*, *Uses blocks and items of*, and the other way round (*Needed by*, *Its code is imported by* ...). Click a
  row to look at that one.
- Its commands and the events it reacts to, then everything installed, searchable.

## The UI maker

Open a window in `ui/*.json` to use its **Design** and **Source** tabs in the main editor. The UI maker on the right
lists the project's windows and Python `@window` classes; the designer itself uses the main editing area.

- **Toolbox (left):** search controls and containers. Drag one into the window, or click a component then click the
  window to place it. An outline previews its position in a free layout; a blue line shows its place in a box.
  **Select** or Esc returns to selection mode. Scroll vertically to reach more components.
- **Design surface (centre):** use the zoom controls (50–200%) and **Grid** to inspect the window. Zoom changes only
  the editor view. New windows use a free layout; existing rows, columns, grids and other containers keep their layout.
- **In the window preview:** click a widget to select it. Drag it to move it: anywhere inside a **canvas** (snapped to
  4 pixels), or to another place or container (a blue line shows where). Drag the edge or corner squares to resize it, and the
  square at the window's bottom-right corner to resize the window. New windows start as a canvas.
- **Component hierarchy (left):** drag a widget onto the top or bottom of a row to put it above or below, or onto the middle of a box
  to put it inside.
- **Properties & events (right):** select a component on the surface or in the hierarchy. Edit its identity, layout,
  appearance and values in groups; **Events** names its Python `@action` functions. **Window** selects the root and shows
  the window title and dimensions. Drag the pane dividers to resize the toolbox and property inspector.
- **Editing:** arrows move the selected component in a free layout by 4 pixels (Shift moves by 20); Delete removes it.
  **Duplicate** or Ctrl+D copies it, with new input ids and the same event bindings. **Up** and **Down** change sibling order.
- Design and Source edit the same document: **Undo**, **Redo**, **Save**, Ctrl+Z and Ctrl+S work in either view.
  Every completed drag is one undo step in the editor tab; Esc during a gesture restores the
  previous layout. In Python a canvas is `Canvas(Button("Go", x=8, y=8, width=96,
  height=24), height=160)`; `width=` and `height=` work on any widget.
- **Windows in code:** the list also shows the `@window` classes of your Python files. Click one to make a
  `ui/<id>.json` from it. This works when `build()` returns the layout written out (`VBox(Label(...), Button(...))`).
  Values computed in Python, f-strings and loops are listed in the console. Buttons then call actions by name: add
  `@action("name")` functions (Copy Python writes them). Your Python file is not changed.
- If a `ui/*.json` is not valid JSON, the designer shows the error and does not touch the file until you fix it.

### Pictures, GIFs, video and sound in windows

Windows can show your own media next to buttons, sliders and lists. Put the files in the project's `assets/media/` folder (they
go into the module and, for server windows, are sent to the players with the resource pack) and name them in `source`, written
`media/<file>`, or `othermodule:media/<file>`. Only files of the module are used: web addresses are refused, so media never
need a permission, and nothing is downloaded while the game runs.

| Widget | What it shows | Formats |
|---|---|---|
| `Image("media/logo.png")` | a picture; a GIF animates | PNG, JPEG, BMP, GIF (a GIF can have transparency and any frame timing) |
| `Video("media/clip.avi")` | a video | **Motion JPEG in an AVI** (a video file has no sound of its own: name it in `audio=`) |
| `Audio("media/hit.wav")` | a sound (nothing to see unless `controls=True`) | OGG, WAV, MP3, FLAC, AIFF, AU |

An `Image` whose source does not start with `media/` is still a texture or item of the game, exactly as before.

Not supported: MP4, WebM, H.264 and other video codecs (Java cannot decode them without a native library, and CraftPy adds none),
APNG and WebP pictures, sound inside a video file, streaming, and web addresses. Make a video the supported way:

```
ffmpeg -i in.mp4 -an -c:v mjpeg -q:v 5 -vf scale=480:-2,fps=15 clip.avi
ffmpeg -i in.mp4 -vn -ac 1 -ar 22050 clip.wav
```

Limits (a file over a limit shows its reason in the widget and calls `on_error`): pictures up to 2048 x 2048; a GIF up to
512 frames, 1024 x 1024 and 96 MiB decoded; a video file up to 48 MiB, 1280 x 720 and 36 000 frames (frames stay compressed and are
decoded a few ahead, so a long clip costs about its file size); a sound file up to 32 MiB. Decoding and file reading never run
on the screen's threads; a window that closes, or a module that unloads, releases the pictures, the video and the sound.

Options (also in the **Media** group of the property inspector): `fit` (`contain`, `cover` or `stretch`), `crop=[x, y, width,
height]` in picture pixels, `opacity` (0 to 1), `z` (drawing order in a free layout: a lower number is behind), `autoplay`,
`loop`, `speed` (0.25 to 4), `position` (seconds), `volume` (0 to 1), `muted` and `controls=True` for a bar with play and pause,
seek, speed (pictures and video), mute and volume. A GIF plays and loops by itself; a video or a sound starts when told to
(`autoplay=True`, or `playing` bound to a value). The sound engine plays at its own rate, so speed does not change sound: a video
with another speed than 1 is silent. Pictures drawn by Modern UI sit in the layer order of the window: a GIF with `z=-1` is a
background behind everything.

Control them from Python without a player of your own: bind `playing`, `position`, `speed`, `volume`, `muted`, `opacity` or
`restart` to a key of `@ui.data` (`"bind": {"restart": "click_n"}`); a value is acted on when it changes since the last
version of the window, so a refresh does not restart a clip. A new `restart` number plays the widget again from the start
(sound effects). React with `on_end` (a clip that does not loop finished), `on_loop` and `on_error` (the message is
`ctx.value`); `ctx.values[id]` holds `position`, `duration`, `playing`, `ended` and `error` of each media widget.

In the UI maker preview pictures, GIFs and video play (read straight from the project, before it is loaded into the world); sounds
stay silent and show a note. Media of a server window need the module's resource pack: after adding a file, run the project or
RELOAD the module so the pack is rebuilt. A folder named `media` directly under `assets/` is this folder, not a namespace.

## The Hub

The Hub is the front door of CraftPy: press its key (**Open CraftPy Hub** in Controls, default `I`). It is marked **Beta**. Esc closes a popup first, then goes back one level (any tab goes back to **Apps**; a selected
module is a level of its own), and closes the Hub only from Apps. The close button always closes it. Pending module changes are
asked about before leaving the Modules tab by any route.

- **Apps:** what enabled, loaded modules offer. Search by name, description or module, filter by category, move with the arrow
  keys (Home and End jump, Enter opens). An app that cannot open is dimmed and says why (not loaded yet: RELOAD; a missing mod;
  not running on the server); clicking it opens Modules. A badge marks modules that wait for a permission. A window opened from
  an app has a **Hub** button and Esc returns to the Hub.
- **Modules:** *Client* modules live on your computer: switch them and their permission toggle here, then RELOAD. *Server* modules
  are shown read-only (details, README, status). Administration (Delete, Decompile, Enable all, servers) is in the editor.
- **Editor:** opens the full editor (needs permission and the CraftPy Computer, as before). Its rail has an arrow back to the Hub.
- **Settings:** yours (editor colour scheme and font, module keybinds, client modules, music of others) and the server's
  (read-only; values are shown only in your own world).
- **Marketplace:** a placeholder (coming soon).

### Adding your module to the Hub

List apps in `project.json` (a built module carries them in `module.json`): each opens one of your windows.

```json
"icon": "icon.png",
"apps": [
  {"id": "journal", "name": "Task Journal", "description": "Your checklist.",
   "window": "journal", "icon": "minecraft:book", "category": "Tools", "order": 10}
]
```

`window` is the id of a `ui/*.json` or `@window`. A module with side `client` opens it on the player's computer; `both` and
`server` modules ask the server, which checks that the app is declared and running. `icon` is a 64 x 64 PNG under `assets/`
(PNG only, 256 KiB and 256 x 256 at most) or an item id; without one the Hub draws coloured letters. `category` is one of Tools,
Games, Server, Media, Machinery. Limits: 32 apps per module, names of 32 and descriptions of 120 characters; an id that
is declared twice keeps the first (the Modules page reports the rest). The Hub does not run a module to list its apps.

## Permissions

A project only gets what its `project.json` asks for under `"permissions"`: `"net"` (sites), `"files"` (its data
folder), `"shared"` (folders of `pyforge/shared`), `"commands"` (`server.run_command`, at level 2) and `"java"` (the
Java bridge: full trust). Modules from others also need **Approve access** on the Modules page, for that exact file.
`server.mods()` lists the loaded mods without any permission.

## Playing on a server

On a dedicated server the server owner approves a module by putting it in `pyforge/modules/` and enabling it;
`/craftpy module list|enable|disable <id>` also works from the server console (`⚠` marks modules that ask for
permissions). Nothing is asked in game there.

When you join from another computer, CraftPy shows the modules that ask for permissions before the world loads: what
each one can do and its README (click it). **Leave** disconnects; **Continue** joins, and "Don't show again" skips the
screen on that server until its modules change. `/craftpy_client server_modules` shows the list again. Then the server
sends its resource pack (textures, models, sounds and names of its projects and modules, never code); you keep it in
`pyforge/cache/serverpacks/`, so the next visit only downloads what changed. Singleplayer and the LAN host skip all
of this.

Be careful with modules you didn't write and with servers that use them: trust only people you trust. The CraftPy
author is not responsible for what anyone does through modules.

## The Python Wrench

Right-click a block or mob with the **Python Wrench** (iron, redstone, iron in a V): chat shows its id, its block
state, the project or module that adds it, and a **[copy Python]** line (`world.set_block(...)` / `world.spawn(...)`).
Sneak + right-click content of one of your projects to open that project in the IDE.

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

The Hub key (`I`, in Controls: **Open CraftPy Hub**) opens the Hub for everyone; its Editor tab works once you have crafted a CraftPy Computer; until then open the computer block.

- `Esc`: Step back: dialog, completion, find bar, selection, zen mode
- `Ctrl+S`: Save (trims trailing spaces if enabled)
- `Ctrl+R` / `F5`: Run / reload project and modules
- `Ctrl+Enter`: Validate
- `Ctrl+F` / `Ctrl+H`: Find / replace
- `F3` / `Shift+F3`: Next / previous match
- `Ctrl+P`: Go to file
- `Ctrl+Space`: Completion list
- `Ctrl+Q`: Documentation of the name at the cursor
- `Ctrl+click`, `Ctrl+B`, `F12`: Go to definition
- `Alt+Left` / `Alt+Right`: Back / forward through jumps
- `Ctrl+W`: Close tab
- `Ctrl+T`: Go to symbol
- `Ctrl+E`: Recent locations
- `Ctrl+Alt+O` / `Ctrl+Alt+I`: Organise / add missing imports
- `Ctrl+Shift+D`: Generate a docstring
- `Ctrl+Alt+V` / `Ctrl+Alt+M`: Extract variable / function
- `Ctrl+J`: Join lines
- `Ctrl+Shift+Enter`: Split line
- `Ctrl+F2`: Toggle bookmark
- `Shift+F9`: Debug: run with breakpoints on and open the Debug panel (a plain Run, `Ctrl+R`, ignores breakpoints)
- `Ctrl+Shift+R`: Recipes & loot of the item at the cursor: what makes it, what uses it, what drops it
- `Ctrl+F8`: Toggle breakpoint (red dot): each time the line runs, the Debug window (`Alt+5`) gets the call stack and the variables; the game never pauses
- `F2` / `Shift+F2`: Next / previous bookmark (in the explorer, `F2` renames)
- `Tab` / `Shift+Tab`: Indent / unindent
- `Ctrl+=` / `Ctrl+-` / `Ctrl+0`: Zoom in / out / reset (also `Ctrl` + mouse wheel)
- `F11`: Zen mode
- `Ctrl+Z` / `Ctrl+Y` (or `Ctrl+Shift+Z`): Undo / redo. Every file keeps its own history while the IDE is open; a word you type is one step, like in IntelliJ
- `Ctrl+Z` / `Ctrl+Y` in the Project tree: Undo / redo a file operation: new file or folder, rename, delete (deleted files wait in `system/trash` until undone), a building block's new file (with its `import` line)
- `Ctrl+Shift+A`: Find action: every command by name
- `F6`: Move the function / class at the cursor to another file: imports are fixed in every file, a preview shows what changes
- `Ctrl+Shift+F10`: Run tests: the `test*` functions of `code/test_*.py`, failures first
- `Alt+0`: Mod catalog: every id, attribute, tag, recipe type and class of the installed mods, searchable; it follows the cursor (inside `Attributes("` it shows attributes) and a click puts the id in the code
- `Ctrl+Shift+F`: Find in files
- `Ctrl+G`: Go to line
- `Ctrl+/`: Comment / uncomment the lines
- `Ctrl+D`: Duplicate the line or selection
- `Ctrl+Shift+K`: Delete the line
- `Alt+Shift+Up` / `Alt+Shift+Down`: Move the line up / down
- `Ctrl+Alt+L`: Reformat the file (whitespace only)
- `Shift+F6`: Rename a name in this file (strings and comments stay)
- `Tab` after `onj`, `onb`, `cmd`, `win`, `item`...: Expand a live template (see below)
- `Ctrl+Shift+T`: Reopen the last closed tab
- `Ctrl+\`: Split the editor right / close the split
- `Alt+1` / `Alt+2` / `Alt+4` / `Alt+5` / `Alt+6` / `Alt+7` / `Alt+8` / `Alt+9`: Project / Bookmarks / Console / Debug / Problems / Structure / Building blocks / Assets
- `Ctrl+Shift+F12`: Hide all tool windows (again: bring them back)
- `F1`: This help

### Live templates

Type the abbreviation alone on a line and press `Tab`: `main`, `item`, `block`, `onj` (player join), `onb` (block break),
`onp` (block place), `oni` (block right click), `onu` (item use), `onc` (chat), `ond` (death), `cmd`, `every`, `after`,
`win` (window), `ext` (extension), `shaped`, `for` (every online player). The cursor lands where you type next.

### Building blocks

The **Building blocks** tool window (right edge, `Alt+8`) has ready-made pieces: windows and other UI elements, items, blocks,
events, commands, timers, recipes and world actions. Each one has **Docs** (its part of the API reference), **Insert** (the
code at the cursor) and **New file** (`code/<name>.py`, already imported by `main.py`).
# API 2.9: live designer layouts and native content

Keep layouts in `ui/*.json` and supply their display values with `@ui.data("window_id")`.
The provider receives the viewer context and returns a dictionary. A widget binding such as
`"bind": {"text": "caption", "enabled": "can_claim"}` looks up those keys; it never evaluates Python.
Use named `@action` handlers for clicks and validate rewards/operations again on the server.
The designer inspector offers binding fields and `preview_data` samples, with static fallback values.
Python `@window` classes remain supported.

Give module shortcuts stable identities, for example
`@keys.bind("ctrl+k", id="open_panel", name="Open panel", category="My module")`.
Remap them in Minecraft Controls or **Modules → Module Keybinds**. The module page supports complete
modifier chords, conflicts, unbinding, reset and inactive assignments. Both interfaces share saved preferences;
changing a module's default key does not erase the player's remap. Server actions receive the authenticated player.

`NativeBlock("shaft", base="create:shaft")` aliases the original registered block and item.
`NativeMob("archivist", base="irons_spellbooks:necromancer")` spawns the original entity type with separate
CraftPy ownership/variant data. These retain native behavior and renderers, and require their original mod.
They do not create an independent Java inheritance family. The catalog inspector distinguishes discovered,
compatible and verified capabilities; inspection grants no Java-bridge permission.

Custom `Mob` definitions accept native melee/ranged/follow/guard profiles, compatible inspected AI goals,
named GeckoLib animation clips, owner-scoped entity state and filtered/throttled callbacks. Navigation,
attack timing and animation playback stay native. Mod AI goals require compatible public constructors and
the existing Java permission/approval checks.

Extend a block with `Kinetic("generator", axis="x", speed=64, capacity=96)`, `Kinetic("shaft", axis="x")`,
or `Kinetic("processor", axis="x", impact=8)`. These work with CraftPy alone. The independent network connects
matching axes, preserves signed speed, sums matching generator capacities, stops conflicting sources and overload,
and rebuilds within a bounded server budget without loading chunks. Create 6.0.10 switches to its native backend
for real Create shafts/gears, stress and wrench interaction. Unsupported Create versions leave core machinery available.

`EnergyStorage(100000, 1000, 1000)` adds native NeoForge FE storage. `FluidTank(16000, ["minecraft:water"])`
adds a native fluid tank in mB. Either works without rotation or other content mods. Rotation never creates FE implicitly.
Compatible automation uses standard NeoForge item/fluid/energy interfaces. Configure the external machine's input/output
faces normally; CraftPy does not bypass them. Use `world.resources(pos, side="up")` for bounded snapshots and
`world.transfer(source, target, "fluid", 1000, source_side="east", target_side="west", simulate=True)` to test a transfer.
Transfers and processing run on the native server thread. Third-party handlers must honor NeoForge simulation semantics.

Processing uses `craftpy:kinetic_processing` with `ingredient`, `result`, `duration` in ticks and `minimum_speed` in RPM.
`world.machine(pos)` and throttled `machine_speed`/`machine_state` callbacks provide live state. Inventory, processing,
energy and fluid survive saves; breaking a machine preserves stored energy/fluid in its block item and drops the two
inventory slots separately. The CraftPy Machinery Wrench rotates machines without requiring Create.

Machines use a separate default pool of 32 slots: configure `kinetics=32` in `config/craftpy-slots.properties` before
startup. Changing pool size requires matching client/server settings. Existing ordinary block/item/entity slots retain
their identities. Changing an existing content ID between native slot families requires an explicit migration.

Building Blocks has 96 core templates and 24 optional templates. Its search, category filter, keyboard focus and code
preview help find examples. Missing integration mods stay hidden; cached discovery never enables a capability.
New file connects Python imports and adds JSON/model/animation companions together, rejecting collisions.
Insert adds the CraftPy import when the target file needs it. Each preview identifies the execution side,
required mods and expected behavior. Core native API examples need no extra requested permission;
the web/file examples include their required `project.json` declarations in the inserted code comments.
Declaring a permission does not approve it: installed modules still use **Allow requested permissions**.

Nothing is seeded: create the **Welcome tour** from the template gallery of **New project**. The only example in the source checkout is `examples/bank`.
`requiredMods` and `requiredCapabilities` in project/module metadata can make optional examples explicitly unavailable.
The permission toggle remembers access only for unchanged module bytes; revocation persists. Remote server
acknowledgments are separately remembered by server, module ID and file hash, and can be changed in the server modules page.
Foundry and Stress Lab are no longer shipped. See the source checkout's `src/nativeTest/README.md` for recorded checks
and the scenarios that still require a real Minecraft client.
