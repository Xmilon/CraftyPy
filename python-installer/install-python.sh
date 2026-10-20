#!/bin/sh
# Installs Python 3.13 for the "no-python" download of CraftPy on macOS or Linux.
# (The normal download has Python inside: you do not need this then.)
set -e

ok() {
    "$1" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 13) else 1)' 2>/dev/null
}

for candidate in python3.13 python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 && ok "$candidate"; then
        echo "Python is ready: $("$candidate" --version). Start Minecraft."
        exit 0
    fi
done

echo "Installing Python 3.13 ..."
if command -v brew >/dev/null 2>&1; then
    brew install python@3.13
elif command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update && sudo apt-get install -y python3.13
elif command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y python3.13
elif command -v pacman >/dev/null 2>&1; then
    sudo pacman -S --noconfirm python
elif command -v zypper >/dev/null 2>&1; then
    sudo zypper install -y python313
else
    echo "No package manager found. Install Python 3.13 from https://www.python.org/downloads/"
    echo "or set pythonPath in CraftPy's config to a python3 executable."
    exit 1
fi
echo "Done. Start Minecraft again."
