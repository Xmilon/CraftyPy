# CraftyPy

Python-based editor and runtime environment for Minecraft modding, asset management, and namespace isolation.

---

## Technical Specifications & Features

### 1. Workspace & Asset Management
* **Asset Inspector:** Direct previewing of `.png` textures, `.json` models/blockstates, and `.nbt` files upon selection in the file explorer.
* **Tabbed Editor Interface:** Multi-file tab manager with explicit close controls (`X`), buffer preservation, and shortcut bindings (`Ctrl+W` / `Cmd+W`).
* **Interactive File Explorer:** Tree-view navigation filtering project assets versus active python source files.

### 2. Autocomplete Engine
* **Context-Aware Completion:** Inline suggestions for python methods, block properties, and item IDs.
* **Documentation Tooltips:** Hover signatures and brief parameter descriptions for registered API functions.
* **Typing Debounce & Auto-Dismiss:** Popup overlays automatically detach on unmatched typing input.
* **Inline Key Insertion:** Press `Tab` to accept suggestions and inject boilerplate arguments (e.g., auto-filling block parameters).

### 3. Namespace & Registry Architecture
* **Isolated Registries:** Custom assets and blocks are scoped exclusively within defined namespaces (e.g., `project_bb`).
* **Command Interception:** `/give` commands are constrained to base/vanilla assets; custom blocks are strictly accessible via the dedicated command pipeline:
  ```text
  /craftpy give <player> <namespace>:<item_id> [count]
  ```

---

## Requirements

* **Python:** 3.10+
* **Java Development Kit (JDK):** 17 or higher
* **Dependencies:**
    * `PyQt6` / `CustomTkinter` (UI framework)
    * `Pillow` (Image preview generation)
    * `watchdog` (File system observer)

---

## Project Structure

```text
CraftyPy/
├── assets/             # Raw textures, models, and JSON definitions
├── src/
│   ├── core/           # Runtime hooks and namespace registry logic
│   ├── editor/         # Autocomplete engine, tab manager, keybindings
│   └── ui/             # Main window, file tree, asset preview panel
├── tests/              # Unit tests for registry isolation and parser
├── main.py             # Application entry point
└── requirements.txt    # Python dependencies
```

---

## Installation & Local Setup

1. **Clone the Repository**
   ```bash
   git clone https://github.com/Xmilon/CraftyPy.git
   cd CraftyPy
   ```

2. **Initialize Environment**
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   ```

3. **Install Packages**
   ```bash
   pip install -r requirements.txt
   ```

4. **Run Application**
   ```bash
   python main.py
   ```

---

## Command Syntax & Usage

To grant a custom block without polluting vanilla registries:

```text
/craftpy give Dev project_bb:ruby_block 64
```

---

## License

[MIT License](LICENSE)