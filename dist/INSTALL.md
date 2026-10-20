# Installing CraftPy

CraftPy needs **Python 3.13 or newer** on the computer that runs the game (a server needs it too). The mod does not
contain Python and downloads nothing: you install Python once, then start Minecraft.

Needs Minecraft 1.21.1, NeoForge 21.1 and [Modern UI](https://modrinth.com/mod/modern-ui) (Modern UI only on clients).
Put `craftpy-<version>.jar` into `mods/`.

## Windows

1. Install Python 3.13+ from <https://www.python.org/downloads/>. Tick **Add python.exe to PATH**.
   (Or double-click `install-python.cmd`: it uses winget or the python.org installer, for your user only.)
2. Check in a new terminal: `python --version`
3. Start Minecraft (a fresh start, so it sees the new PATH).

## Linux (desktop)

```bash
python3 --version          # must be 3.13 or newer
sudo apt install python3   # Debian/Ubuntu; dnf, pacman or zypper elsewhere (or: sh install-python.sh)
```

If your distribution only has an older Python, install 3.13 with [uv](https://docs.astral.sh/uv/) or pyenv:

```bash
uv python install 3.13
uv python find 3.13        # prints the path, use it as pythonPath below
```

## Dedicated server (Linux or Windows)

1. Install Python 3.13+ on the server as above. No display is needed.
2. Put the jar into the server's `mods/`. Start the server once.
3. If CraftPy does not find Python, set the exact path in `config/craftpy-common.toml`:

   ```toml
   pythonPath = "/usr/bin/python3.13"
   ```

   On Windows use forward slashes: `"C:/Python313/python.exe"`.
4. Restart. The log says which Python was used; without one it says so and how to fix it.

CraftPy looks for Python in this order: `pythonPath` in `config/craftpy-common.toml`, a Python in
`pyforge/system/runtime/`, then `python` / `python3` on PATH.

## Optional: .ogg export in the sound editor

The sound editor saves Ogg Vorbis through the Python package `soundfile`. The mod never installs it for you:

```bash
python -m pip install soundfile
```

Without it the editor can still save `.wav`.
