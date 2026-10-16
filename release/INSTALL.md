# Installing CraftPy

There are two downloads. Pick one.

| File | Size | Python |
|---|---|---|
| `craftpy-<version>.jar` | about 140 MB | **inside**: nothing else to install (Windows, macOS, Linux) |
| `craftpy-<version>-no-python.jar` | about 3 MB | you install Python 3.13 once |

Both need Minecraft 1.21.1, NeoForge 21.1 and [Modern UI](https://modrinth.com/mod/modern-ui) (Modern UI only on the client).

## With Python inside

Put the jar and Modern UI into `mods/` and start the game. The first start unpacks Python (a few seconds, once).

## Without Python

1. Put `craftpy-<version>-no-python.jar` and Modern UI into `mods/`.
2. Install Python 3.13 or newer:
   - **Windows**: double-click `install-python.cmd`. It uses winget, or downloads the official installer from python.org,
     installs for your user only (no administrator rights) and adds Python to PATH.
   - **macOS / Linux**: run `sh install-python.sh` in a terminal (Homebrew, apt, dnf, pacman or zypper).
   - Or by hand from <https://www.python.org/downloads/>; on Windows tick **Add python.exe to PATH**.
3. Start Minecraft (a fresh start, so it sees the new PATH).

CraftPy looks for Python in this order: `pythonPath` in `config/craftpy-common.toml`, a Python in
`pyforge/system/runtime/`, the one inside the jar (only the big download), then `python` on PATH. If it finds none, the IDE
says so and tells you what to do.
