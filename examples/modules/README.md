# Ready-built modules

Copy a `.cpm` to `pyforge/modules` and enable it in Modules. These same files are embedded in CraftPy and installed once,
disabled. Existing files and deleted bundles are never replaced automatically. Decompile to edit a module.

| Module | Controls / purpose | Requires |
|---|---|---|
| HUD Pack | F7 visibility, Ctrl+H settings; remappable | CraftPy |
| Jukebox | J; local music and playback settings | CraftPy; approve shared music folder |
| Waypoints | Ctrl+Shift+W; save/select/remove destinations; ten-second cooldown | CraftPy |
| Death Compass | Ctrl+Shift+D; last death position/direction/distance | CraftPy |
| Task Journal | Ctrl+Shift+J; personal tasks, completion and reminder preference | CraftPy |
| Server Toolkit | Ctrl+Shift+I status; operator-only `/server_toolkit:day`, `clear`, `announce "text"` | CraftPy |
| Activity Stats | Ctrl+Shift+S; mined/crafted/defeated counters | CraftPy |
| Workshop | Craft a motor, shaft, mill, battery and tank; `/workshop:inspect x y z` | CraftPy |
| Create Bridge | Craft the bridge motor/mill and connect through Create shafts/gears | Create 6.0.10 adapter |
| Curios Charms | Craft and wear luck/health rings | Curios |

Welcome, Ruby Rush and Sky Islands are also included. Their source READMEs describe their gameplay.
All existing script commands keep their operator requirement. Personal shortcuts and named window actions use the actual
server player and do not require editor access. Change shortcuts in Module Keybinds or Controls.

Workshop's motor supplies rotation, not electrical energy. Batteries expose native FE storage, tanks accept buckets,
and the mill processes cobblestone into gravel when driven. Use the Machinery Wrench to change rotation axes.
Place machines yourself; no module builds an arena or changes terrain merely by being enabled.
