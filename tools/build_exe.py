"""Builds the module checker as one executable for this system: tools/dist/craftpy-check-<system>[.exe].

    python tools/build_exe.py          (needs: python -m pip install pyinstaller)

PyInstaller builds only for the system it runs on; GitHub Actions (.github/workflows/build.yml) builds the Windows,
macOS and Linux ones on every push. The checker carries a copy of the mod's worker.py (its sandbox rules), so build it
again whenever worker.py changes.
"""
import os
import platform
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
WORKER = os.path.join(HERE, "..", "src", "main", "resources", "craftpy_runtime", "worker.py")
SYSTEM = {"Windows": "windows", "Darwin": "macos"}.get(platform.system(), "linux")

subprocess.run([sys.executable, "-m", "PyInstaller", "--onefile", "--clean", "--noconfirm",
                "--name", "craftpy-check-" + SYSTEM,
                "--add-data", WORKER + os.pathsep + ".",
                # worker.py is read at run time, so PyInstaller does not see its imports
                *[arg for name in ("json", "traceback", "threading", "queue", "time", "math", "random", "inspect")
                  for arg in ("--hidden-import", name)],
                "--distpath", os.path.join(HERE, "dist"), "--workpath", os.path.join(HERE, "build"),
                "--specpath", os.path.join(HERE, "build"),
                os.path.join(HERE, "check_module.py")], check=True)
