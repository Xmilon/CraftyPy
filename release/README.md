# CraftPy 1.0.0 (phase 1)

The jars are not in git (the full one is 139 MB, over GitHub's 100 MB limit). Build them yourself:

```
./gradlew --no-daemon build
```

They land in `build/libs/`:

| File | What it is |
|---|---|
| `craftpy-1.0.0.jar` | The mod with Python included (Windows, macOS, Linux). Put it in `mods/`. |
| `craftpy-1.0.0-no-python.jar` | The same mod without Python (3 MB). Run `install-python.cmd` (Windows) or `install-python.sh` (macOS / Linux) once, see `INSTALL.md`. |

Needs Minecraft 1.21.1, NeoForge 21.1 and Modern UI 3.13. JEI, Curios, GeckoLib, Create and the rest are optional.
