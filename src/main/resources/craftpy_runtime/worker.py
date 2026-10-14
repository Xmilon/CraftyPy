#!/usr/bin/env python3
"""
CraftPy worker - runs as a sandboxed external subprocess.

Communicates with the Java host over newline-delimited JSON-RPC on stdin/stdout:

    -> {"id": 1, "method": "ping"}
    <- {"id": 1, "result": {"version": "3.13", ...}}
    -> {"id": 2, "method": "validate", "params": {"files": [...], "code": "..."}}
    <- {"id": 2, "result": {"problems": [...]}}

The worker also speaks the *reverse* direction: while project code runs it may
issue host calls

    <- {"host_call": 7, "method": "world.get_block", "params": {...}}
    -> {"host_response": 7, "result": {...}}

which the Java host executes on the server thread and answers. This is what lets
Python reach into the live world (`world`, `players`, `storage`, ...).

SECURITY: This is BEST-EFFORT sandboxing, NOT a hard sandbox. A determined
attacker can escape. Do not run untrusted projects with full OS privileges.
Host hardening suggestions: run with a low-privilege OS account, job objects
(Windows), seccomp/namespaces (Linux).
"""

import json
import sys
import traceback

# Import everything the *worker internals* need before the sandbox finder is
# installed, so the finder (which blocks e.g. threading for project code) does
# not prevent the runtime itself from using them.
import threading
import queue
import time
import math
import random

# ---------------------------------------------------------------------------
# Import policy. This is a *denylist*: everything ships with the sandbox except
# modules that touch the OS, filesystem, network or processes. That keeps normal
# Python available (collections, statistics, hashlib, csv, ...) while making it
# impossible to read/write files or open sockets from project code.
# ---------------------------------------------------------------------------
_BLOCKED = {
    # interpreter internals / escape hatches
    "sys", "builtins", "__builtin__", "importlib", "_imp",
    # OS / filesystem / processes
    "os", "posix", "nt", "ntpath", "posixpath",
    "subprocess", "_posixsubprocess", "_winapi", "shutil", "pathlib", "glob",
    "tempfile",
    "signal", "_signal", "threading", "_thread", "_threading_local",
    "multiprocessing", "concurrent", "asyncio", "select", "selectors",
    "mmap", "fcntl", "termios", "pty", "resource", "msvcrt",
    "pwd", "grp", "spwd", "crypt", "getpass",
    # network
    "socket", "_socket", "ssl", "_ssl", "http", "urllib", "ftplib", "smtplib",
    "poplib", "imaplib", "telnetlib", "nntplib", "xmlrpc", "webbrowser",
    "socketserver", "wsgiref",
    # native / dynamic code
    "ctypes", "_ctypes", "cffi",
    # serialization that can execute code
    "pickle", "_pickle", "shelve", "marshal", "dbm", "sqlite3", "_sqlite3",
    "winreg", "sysconfig",
}


class SandboxFinder:
    """MetaPathFinder that refuses to import anything on the blocklist."""

    def find_spec(self, fullname, path=None, target=None):
        top = fullname.split(".")[0]
        if top in _BLOCKED:
            raise ImportError(
                "Import '%s' is blocked by the CraftPy sandbox" % fullname)
        return None  # fall through to the real finders


def _install_finder():
    sys.meta_path.insert(0, SandboxFinder())


_real_import = __import__


def _sandboxed_import(name, globals=None, locals=None, fromlist=(), level=0):
    top = name.split(".")[0]
    if top in _BLOCKED:
        raise ImportError(
            "Import '%s' is blocked by the CraftPy sandbox" % name)
    return _real_import(name, globals, locals, fromlist, level)


def _denied(*args, **kwargs):
    raise RuntimeError("I/O and process calls are blocked in the CraftPy sandbox")


def restricted_builtins(log):
    b = {
        "abs": abs, "all": all, "any": any, "ascii": ascii, "bin": bin, "bool": bool,
        "bytearray": bytearray, "bytes": bytes, "callable": callable, "chr": chr,
        "classmethod": classmethod, "complex": complex,
        "dict": dict, "divmod": divmod, "enumerate": enumerate,
        "filter": filter, "float": float, "format": format, "frozenset": frozenset,
        "getattr": getattr, "hasattr": hasattr, "hash": hash, "hex": hex, "id": id,
        "int": int, "isinstance": isinstance, "issubclass": issubclass,
        "iter": iter, "len": len, "list": list,
        "map": map, "max": max, "memoryview": memoryview, "min": min, "next": next,
        "object": object, "oct": oct, "ord": ord, "pow": pow, "property": property,
        "range": range, "repr": repr, "reversed": reversed, "round": round,
        "set": set, "setattr": setattr, "slice": slice, "sorted": sorted,
        "staticmethod": staticmethod, "str": str, "sum": sum, "super": super,
        "tuple": tuple, "type": type, "zip": zip,
        "print": lambda *a, **k: log.append(" ".join(str(x) for x in a)),
        "Exception": Exception, "BaseException": BaseException,
        "ValueError": ValueError, "TypeError": TypeError,
        "RuntimeError": RuntimeError, "KeyError": KeyError, "IndexError": IndexError,
        "StopIteration": StopIteration, "NotImplementedError": NotImplementedError,
        "NotImplemented": NotImplemented, "AssertionError": AssertionError,
        "NameError": NameError, "AttributeError": AttributeError,
        "ArithmeticError": ArithmeticError, "ZeroDivisionError": ZeroDivisionError,
        "OverflowError": OverflowError, "ImportError": ImportError,
        "__import__": _sandboxed_import,
        # Deliberately still blocked: filesystem, processes, dynamic exec.
        "open": _denied, "eval": _denied, "exec": _denied,
        "compile": _denied, "input": _denied, "__build_class__": __build_class__,
    }
    return b


# ---------------------------------------------------------------------------
# Host bridge. Project code (and the API facade) can call back into the Java
# host; the response is delivered by the stdin reader thread while the request
# is still executing, so calls are synchronous from Python's point of view.
# ---------------------------------------------------------------------------

_HOST_LOCK = threading.Lock()
_HOST_NEXT = [1]
_HOST_WAITERS = {}
_IO_LOCK = threading.Lock()
_REQUEST_QUEUE = queue.Queue()
_HOST_ENABLED = [True]


class HostError(RuntimeError):
    """Raised when a host call fails or the host does not answer."""


def _host_call(method, params=None, timeout=10.0):
    if not _HOST_ENABLED[0]:
        raise HostError("host bridge is disabled")
    with _HOST_LOCK:
        call_id = _HOST_NEXT[0]
        _HOST_NEXT[0] += 1
        slot = {"event": threading.Event(), "result": None, "error": None}
        _HOST_WAITERS[call_id] = slot
    payload = json.dumps({"host_call": call_id, "method": method, "params": params or {}})
    with _IO_LOCK:
        sys.stdout.write(payload + "\n")
        sys.stdout.flush()
    if not slot["event"].wait(timeout):
        with _HOST_LOCK:
            _HOST_WAITERS.pop(call_id, None)
        raise HostError("host call timed out: %s" % method)
    with _HOST_LOCK:
        _HOST_WAITERS.pop(call_id, None)
    if slot["error"] is not None:
        raise HostError(str(slot["error"]))
    return slot["result"]


def _try_host(method, params=None, timeout=10.0, default=None):
    """Convenience wrapper: returns ``default`` instead of raising."""
    try:
        return _host_call(method, params, timeout)
    except HostError:
        return default


# ---------------------------------------------------------------------------
# Declarative API
# ---------------------------------------------------------------------------

_DECL_KEYS = ("blocks", "items", "entities", "effects", "handlers", "recipes",
               "loot_tables", "tags", "lang", "commands", "advancements",
               "sounds", "particles", "enchantments", "raw", "meta")
_DECLS = {k: [] for k in _DECL_KEYS}
_DECLS["lang"] = {}
_HANDLERS = {}
_WINDOWS = {}
_SCHEDULE = []
_STORAGE = {}
_TICK = [0]
_CAPABILITIES = {
    "content.block", "content.item", "content.effect", "content.entity",
    "recipe.all", "loot.table", "tags", "lang", "events", "commands",
    "ui.window", "storage.kv", "scheduler", "world.read", "world.write",
}


def _register(kind):
    def deco(ident, **kwargs):
        def wrap(cls):
            _DECLS[kind].append({"id": ident, "definition": _fields(cls) | kwargs})
            return cls
        return wrap
    return deco


def _register_fn(kind):
    def deco(ident, **kwargs):
        def wrap(fn):
            _DECLS[kind].append({"id": ident, "definition": kwargs})
            return fn
        return wrap
    return deco


def _fields(cls):
    out = {}
    for key in dir(cls):
        if key.startswith("_"):
            continue
        value = getattr(cls, key)
        if callable(value):
            continue
        out[key] = value
        if len(out) > 64:
            break
    return out


block = _register("blocks")
item = _register("items")
entity = _register("entities")
effect = _register("effects")
enchantment = _register_fn("enchantments")


def potion(ident, **kwargs):
    _DECLS["items"].append({"id": ident, "definition": dict(kwargs, kind="potion")})


def tex(ref):  # asset reference: location name
    return {"ref": "texture", "value": ref}


def snd(ref):
    return {"ref": "sound", "value": ref}


def mdl(ref):
    return {"ref": "model", "value": ref}


def loot_self():
    return {"ref": "loot", "value": "self"}


def lang(key, value):
    _DECLS["lang"][key] = value


def sound(event, **kwargs):
    _DECLS["sounds"].append({"id": event, "definition": kwargs})


def particle(ident, **kwargs):
    _DECLS["particles"].append({"id": ident, "definition": kwargs})


def advancement(ident, **kwargs):
    _DECLS["advancements"].append({"id": ident, "definition": kwargs})


def raw_json(path, data):
    """Escape hatch: write a raw datapack JSON file (flagged version-specific)."""
    _DECLS["raw"].append({"id": path, "definition": data})


def tag(ident, values, replace=False, type="item"):
    _DECLS["tags"].append({"id": ident, "values": list(values),
                           "replace": replace, "type": type})


# -- recipes -----------------------------------------------------------------

def _recipe(kind, **payload):
    _DECLS["recipes"].append(dict(payload, type=kind))


def recipe_shaped(result, pattern, keys, category="misc", **extra):
    _recipe("shaped", result=result, pattern=list(pattern), keys=dict(keys),
            category=category, **extra)


def recipe_shapeless(result, ingredients, category="misc", **extra):
    _recipe("shapeless", result=result, ingredients=list(ingredients),
            category=category, **extra)


def recipe_smelting(result, ingredient, xp=0.0, cook_time=200, **extra):
    _recipe("smelting", result=result, ingredient=ingredient, xp=xp,
            cook_time=cook_time, **extra)


def recipe_blasting(result, ingredient, xp=0.0, cook_time=100, **extra):
    _recipe("blasting", result=result, ingredient=ingredient, xp=xp,
            cook_time=cook_time, **extra)


def recipe_smoking(result, ingredient, xp=0.0, cook_time=100, **extra):
    _recipe("smoking", result=result, ingredient=ingredient, xp=xp,
            cook_time=cook_time, **extra)


def recipe_campfire(result, ingredient, cook_time=600, **extra):
    _recipe("campfire", result=result, ingredient=ingredient,
            cook_time=cook_time, **extra)


def recipe_stonecutting(result, ingredient, count=1, **extra):
    _recipe("stonecutting", result=result, ingredient=ingredient,
            count=count, **extra)


def recipe_smithing(result, template, base, addition, **extra):
    _recipe("smithing", result=result, template=template, base=base,
            addition=addition, **extra)


# -- loot --------------------------------------------------------------------

def loot_table(ident, entries, rolls=1, **extra):
    _DECLS["loot_tables"].append({"id": ident, "entries": list(entries),
                                  "rolls": rolls, **extra})


def loot_entry(item, count=1, weight=1, **kwargs):
    return dict(kwargs, item=item, count=count, weight=weight)


# -- events ------------------------------------------------------------------

def on(event, every=None, priority="normal"):
    def deco(fn):
        _DECLS["handlers"].append({"event": event, "every": every, "priority": priority})
        _HANDLERS.setdefault(event, []).append(fn)
        return fn
    return deco


def command(name, **kwargs):
    def deco(fn):
        _DECLS["commands"].append({"name": name, "params": kwargs})
        _HANDLERS.setdefault("command_" + name, []).append(fn)
        return fn
    return deco


def block_logic(ident):
    def deco(cls):
        # Block behaviour lives in @on handlers; kept for source compatibility.
        return cls
    return deco


def every(ticks):
    """Decorator: run the handler every ``ticks`` server ticks."""
    return on("tick", every=ticks)


def on_load(fn):
    _HANDLERS.setdefault("load", []).append(fn)
    return fn


def on_unload(fn):
    _HANDLERS.setdefault("unload", []).append(fn)
    return fn


# ---------------------------------------------------------------------------
# Scheduler (tick driven) and storage
# ---------------------------------------------------------------------------

class Task:
    """Handle returned by the scheduler; call ``cancel()`` to stop it."""

    def __init__(self, interval, fn, repeat):
        self.interval = interval
        self.fn = fn
        self.repeat = repeat
        self.cancelled = False

    def cancel(self):
        self.cancelled = True


def run_later(ticks, fn):
    return _schedule(ticks, fn, repeat=False)


def run_every(ticks, fn):
    return _schedule(ticks, fn, repeat=True)


def run_next_tick(fn):
    return _schedule(1, fn, repeat=False)


def _schedule(ticks, fn, repeat):
    task = Task(int(ticks), fn, repeat)
    _SCHEDULE.append({"task": task, "next": _TICK[0] + max(1, int(ticks))})
    return task


def _on_tick(params):
    _TICK[0] = int(params.get("tick", _TICK[0] + 1))
    log = []
    for entry in list(_SCHEDULE):
        task = entry["task"]
        if task.cancelled:
            _SCHEDULE.remove(entry)
            continue
        if _TICK[0] >= entry["next"]:
            try:
                task.fn(_TickContext(_TICK[0]))
            except Exception as exc:  # noqa: BLE001
                log.append("scheduler error: %s" % exc)
            if task.repeat and not task.cancelled:
                entry["next"] = _TICK[0] + max(1, task.interval)
            else:
                _SCHEDULE.remove(entry)
    return {"log": log, "scheduled": len(_SCHEDULE)}


class _TickContext:
    def __init__(self, tick):
        self.tick = tick


# Global store fallback used when the host is unavailable.
def storage_get(key, default=None, scope="world"):
    value = _STORAGE.get((scope, key))
    if value is None:
        value = _try_host("storage.get", {"key": key, "scope": scope}, default=None)
    return default if value is None else value


def storage_set(key, value, scope="world"):
    _STORAGE[(scope, key)] = value
    _try_host("storage.set", {"key": key, "value": value, "scope": scope}, default=None)
    return value


def storage_clear(key=None, scope="world"):
    if key is None:
        _STORAGE.clear()
        _try_host("storage.clear", {"scope": scope}, default=None)
    else:
        _STORAGE.pop((scope, key), None)
        _try_host("storage.clear", {"key": key, "scope": scope}, default=None)


# ---------------------------------------------------------------------------
# World / player / server facade (thin, host backed)
# ---------------------------------------------------------------------------

class _World:
    def get_block(self, x, y, z, dimension="minecraft:overworld"):
        return _host_call("world.get_block", {"x": x, "y": y, "z": z, "dimension": dimension})

    def set_block(self, x, y, z, block, dimension="minecraft:overworld", replace=True):
        return _host_call("world.set_block", {"x": x, "y": y, "z": z, "block": block,
                                              "dimension": dimension, "replace": replace})

    def blocks_in_box(self, x, y, z, radius=0, dimension="minecraft:overworld"):
        return _host_call("world.blocks_in_box", {"x": x, "y": y, "z": z, "radius": radius,
                                                  "dimension": dimension})

    def get_block_entities(self, x, y, z, radius=0, dimension="minecraft:overworld"):
        # Legacy alias kept for source compatibility.
        return self.blocks_in_box(x, y, z, radius=radius, dimension=dimension)

    def spawn(self, entity, x, y, z, dimension="minecraft:overworld"):
        return _host_call("world.spawn", {"entity": entity, "x": x, "y": y, "z": z,
                                          "dimension": dimension})

    def play_sound(self, sound, x, y, z, volume=1.0, pitch=1.0, dimension="minecraft:overworld"):
        return _host_call("world.play_sound", {"sound": sound, "x": x, "y": y, "z": z,
                                               "volume": volume, "pitch": pitch,
                                               "dimension": dimension})

    def particle(self, particle, x, y, z, count=1, dimension="minecraft:overworld"):
        return _host_call("world.particle", {"particle": particle, "x": x, "y": y, "z": z,
                                             "count": count, "dimension": dimension})

    def time(self, dimension="minecraft:overworld"):
        return _host_call("world.get_time", {"dimension": dimension})

    def set_time(self, ticks, dimension="minecraft:overworld"):
        return _host_call("world.set_time", {"time": ticks, "dimension": dimension})

    def weather(self, weather="clear", duration=6000, dimension="minecraft:overworld"):
        return _host_call("world.set_weather", {"weather": weather, "duration": duration,
                                                "dimension": dimension})

    def entities_in_box(self, x, y, z, radius=8, dimension="minecraft:overworld"):
        return _host_call("world.entities_in_box", {"x": x, "y": y, "z": z, "radius": radius,
                                                    "dimension": dimension})


class _Players:
    def all(self):
        return _try_host("players.all", {}, default=[]) or []

    def get(self, name):
        return _host_call("players.get", {"name": name})

    def send_message(self, name, message):
        return _host_call("player.send_message", {"player": name, "message": str(message)})

    def teleport(self, name, x, y, z, dimension="minecraft:overworld"):
        return _host_call("player.teleport", {"player": name, "x": x, "y": y, "z": z,
                                              "dimension": dimension})

    def give(self, name, item, count=1):
        return _host_call("player.give", {"player": name, "item": item, "count": count})


class _Server:
    def broadcast(self, message):
        return _host_call("server.broadcast", {"message": str(message)})

    def run_command(self, command):
        return _host_call("server.command", {"command": str(command)})

    def log(self, message, level="info"):
        _try_host("server.log", {"message": str(message), "level": level}, default=None)


class _Storage:
    def get(self, key, default=None, scope="world"):
        return storage_get(key, default, scope)

    def set(self, key, value, scope="world"):
        return storage_set(key, value, scope)

    def clear(self, key=None, scope="world"):
        return storage_clear(key, scope)


class _Scheduler:
    def run_later(self, ticks, fn):
        return run_later(ticks, fn)

    def run_every(self, ticks, fn):
        return run_every(ticks, fn)

    def run_next_tick(self, fn):
        return run_next_tick(fn)

    def after(self, seconds, fn):
        return run_later(int(seconds * 20), fn)

    def every_seconds(self, seconds, fn):
        return run_every(max(1, int(seconds * 20)), fn)


world = _World()
players = _Players()
server = _Server()
storage = _Storage()
scheduler = _Scheduler()


# ---------------------------------------------------------------------------
# UI: server-described widget tree (rendered by the Java client)
# ---------------------------------------------------------------------------

class Widget:
    def __init__(self, kind, **props):
        self.kind = kind
        self.props = props
        self.children = []

    def add(self, *children):
        self.children.extend(children)
        return self

    def to_dict(self):
        data = {"type": self.kind}
        data.update(self.props)
        if self.children:
            data["children"] = [c.to_dict() if isinstance(c, Widget) else c for c in self.children]
        return data


def Label(text, **p):
    return Widget("label", text=text, **p)


def Button(text, on_click=None, **p):
    return Widget("button", text=text, on_click=on_click, **p)


def TextInput(value="", **p):
    return Widget("text_input", value=value, **p)


def TextArea(value="", **p):
    return Widget("text_area", value=value, **p)


def Checkbox(label="", checked=False, **p):
    return Widget("checkbox", label=label, checked=checked, **p)


def Slider(value=0, min=0, max=100, **p):
    return Widget("slider", value=value, min=min, max=max, **p)


def Dropdown(options=None, selected=0, **p):
    return Widget("dropdown", options=options or [], selected=selected, **p)


def ListView(items=None, **p):
    return Widget("list", items=items or [], **p)


def ProgressBar(value=0, **p):
    return Widget("progress", value=value, **p)


def Image(source="", **p):
    return Widget("image", source=source, **p)


def ItemIcon(item="", **p):
    return Widget("item_icon", item=item, **p)


def VBox(*children, **p):
    w = Widget("vbox", **p)
    return w.add(*children)


def HBox(*children, **p):
    w = Widget("hbox", **p)
    return w.add(*children)


def Grid(*children, **p):
    w = Widget("grid", **p)
    return w.add(*children)


def TabView(*children, **p):
    w = Widget("tabs", **p)
    return w.add(*children)


def ScrollView(*children, **p):
    w = Widget("scroll", **p)
    return w.add(*children)


class Window:
    """Base class for Python-defined in-game windows. Subclass and implement build()."""

    id = "unnamed"
    title = "Window"
    size = (320, 200)
    resizable = False

    def state(self, default):
        key = "_state_%d" % len(getattr(self, "_states", []))
        if not hasattr(self, "_states"):
            self._states = []
        self._states.append(default)
        holder = {"value": default}
        self.__dict__[key] = holder
        return holder

    def build(self, ctx):
        raise NotImplementedError

    def to_dict(self):
        self.ctx = _WindowContext(self)
        root = self.build(self.ctx)
        return {
            "id": self.id,
            "title": self.title,
            "size": list(self.size),
            "resizable": bool(self.resizable),
            "root": root.to_dict() if isinstance(root, Widget) else root,
        }


class _WindowContext:
    def __init__(self, window):
        self.window = window
        self.log = lambda message: _try_host("server.log", {"message": str(message)}, default=None)

    def close(self):
        _try_host("ui.close", {"id": self.window.id}, default=None)


def window(ident, title=None, size=None, resizable=False):
    def deco(cls):
        cls.id = ident
        if title is not None:
            cls.title = title
        if size is not None:
            cls.size = size
        cls.resizable = resizable
        _WINDOWS[ident] = cls
        return cls
    return deco


class _Ui:
    def open(self, player, window_id):
        cls = _WINDOWS.get(window_id)
        if cls is None:
            raise KeyError("unknown window: %s" % window_id)
        tree = cls().to_dict()
        return _host_call("ui.open", {"player": _player_name(player), "window": tree})

    def close(self, player, window_id=None):
        return _host_call("ui.close", {"player": _player_name(player), "id": window_id or None})

    def windows(self):
        return sorted(_WINDOWS.keys())


ui = _Ui()


def _player_name(player):
    if isinstance(player, str):
        return player
    if hasattr(player, "name"):
        return player.name
    return str(player)


# ---------------------------------------------------------------------------
# Execution context passed to main(ctx) and event handlers
# ---------------------------------------------------------------------------

class _Context:
    """Execution context passed to a project's main(ctx) function (declarations)."""

    def __init__(self, log, debug=False):
        self.debug = debug
        self._log = log
        self.project = None
        self.global_state = {}

    def log(self, message):
        self._log.append(str(message))

    def info(self, message):
        self._log.append(str(message))

    def warn(self, message):
        self._log.append("WARN: %s" % message)

    def error(self, message):
        self._log.append("ERROR: %s" % message)

    def world(self):
        return world

    def broadcast(self, message):
        return server.broadcast(message)


class _EventContext(_Context):
    """Context handed to @on handlers. All event data is exposed as attributes."""

    pass


def has(capability):
    """Capability check, e.g. ``if cp.has("worldgen.biome_modifiers"):``."""
    return capability in _CAPABILITIES


class _Cp:
    """The ``cp`` namespace: everything a module might reach for."""

    def __init__(self):
        self.world = world
        self.players = players
        self.server = server
        self.storage = storage
        self.scheduler = scheduler
        self.ui = ui
        self.commands = []
        self.tex = staticmethod(tex)
        self.snd = staticmethod(snd)
        self.mdl = staticmethod(mdl)

    def has(self, capability):
        return has(capability)

    def command(self, command):
        return server.run_command(command)

    def log(self, message):
        _try_host("server.log", {"message": str(message)}, default=None)


cp = _Cp()


# ---------------------------------------------------------------------------
# Interactive console (REPL). A persistent namespace so variables, imports and
# definitions survive between console lines - like a Python REPL in an IDE.
# ---------------------------------------------------------------------------

_REPL_SESSIONS = {}


def core():
    """Marks this module as 'core' - it may require a full game restart."""
    _DECLS.setdefault("meta", {})
    _DECLS["meta"].setdefault("core", True)
    _DECLS["meta"]["requiresRestart"] = True


def requires_restart(flag=True):
    """Explicitly declare that this module requires a game restart when loaded/unloaded."""
    _DECLS.setdefault("meta", {})
    _DECLS["meta"]["requiresRestart"] = bool(flag)
    if bool(flag):
        _DECLS["meta"].setdefault("core", True)


def _api_names():
    names = {
        "block": block, "item": item, "entity": entity, "effect": effect,
        "enchantment": enchantment, "potion": potion,
        "on": on, "every": every, "on_load": on_load, "on_unload": on_unload,
        "command": command, "block_logic": block_logic,
        "tex": tex, "snd": snd, "mdl": mdl, "loot_self": loot_self,
        "recipe_shaped": recipe_shaped, "recipe_shapeless": recipe_shapeless,
        "recipe_smelting": recipe_smelting, "recipe_blasting": recipe_blasting,
        "recipe_smoking": recipe_smoking, "recipe_campfire": recipe_campfire,
        "recipe_stonecutting": recipe_stonecutting, "recipe_smithing": recipe_smithing,
        "loot_table": loot_table, "loot_entry": loot_entry,
        "tag": tag, "lang": lang, "sound": sound, "particle": particle,
        "advancement": advancement, "raw_json": raw_json,
        "window": window, "Window": Window, "ui": ui, "cp": cp,
        "world": world, "players": players, "server": server,
        "storage": storage, "scheduler": scheduler,
        "run_later": run_later, "run_every": run_every, "run_next_tick": run_next_tick,
        "Label": Label, "Button": Button, "TextInput": TextInput, "TextArea": TextArea,
        "Checkbox": Checkbox, "Slider": Slider, "Dropdown": Dropdown, "ListView": ListView,
        "ProgressBar": ProgressBar, "Image": Image, "ItemIcon": ItemIcon,
        "VBox": VBox, "HBox": HBox, "Grid": Grid, "TabView": TabView, "ScrollView": ScrollView,
        "has": has, "core": core, "requires_restart": requires_restart,
    }
    return names


def _get_session(session_id):
    if session_id not in _REPL_SESSIONS:
        log = []
        ns = {
            "__name__": "__repl_%s__" % session_id,
            "__builtins__": restricted_builtins(log),
        }
        ns.update(_api_names())
        _REPL_SESSIONS[session_id] = {"ns": ns, "log": log}
    return _REPL_SESSIONS[session_id]


def reset_repl(session_id=None):
    if session_id is None:
        _REPL_SESSIONS.clear()
    else:
        _REPL_SESSIONS.pop(session_id, None)


def _repl_eval(params):
    code = params.get("code", "")
    session_id = params.get("session", "default")
    session = _get_session(session_id)
    ns = session["ns"]
    log = session["log"]

    del log[:]

    import types as _types
    api_module = _types.ModuleType("craftpy")
    api_module.__dict__.update(_api_names())
    sys.modules["craftpy"] = api_module

    value = None
    error = None
    try:
        sys.settrace(_tracer)
        import ast as _ast
        tree = _ast.parse(code, "<console>", mode="exec")
        last = None
        if tree.body and isinstance(tree.body[-1], _ast.Expr):
            last = _ast.Expression(tree.body.pop().value)
        if tree.body:
            exec(compile(tree, "<console>", "exec"), ns, ns)
        if last is not None:
            value = repr(eval(compile(last, "<console>", "eval"), ns, ns))
    except (SyntaxError, KeyboardInterrupt) as exc:
        error = {"message": "%s: %s" % (type(exc).__name__, exc),
                 "line": max(0, (getattr(exc, "lineno", 1) or 1) - 1)}
    except Exception as exc:  # noqa: BLE001
        tb = traceback.extract_tb(sys.exc_info()[2])
        line = tb[-1].lineno if tb else 0
        error = {"message": "%s: %s" % (type(exc).__name__, exc),
                 "line": max(0, line - 1)}
    finally:
        sys.settrace(None)
        sys.modules.pop("craftpy", None)
    return {"output": list(log), "value": value, "error": error, "session": session_id}


# ---------------------------------------------------------------------------
# Execute code
# ---------------------------------------------------------------------------

def _load_module(params):
    import os
    module_id = params.get("id")
    base_dir = params.get("dir")
    main_file = os.path.join(base_dir, "code", "main.py")

    if not os.path.exists(main_file):
        return {"error": "Main file not found: %s" % main_file}

    with open(main_file, "r") as f:
        code = f.read()

    # Modules are loaded into their own namespace and are NOT reset on run_main
    # They behave like permanent additions to the API/registry
    return _run_code(code, main_file, "module_%s" % module_id, persistent=True)


def _run_code(code, filename, module_name, debug=False, persistent=False):
    log = []
    import types as _types
    sandbox_module = _types.ModuleType("craftpy_api")
    sandbox_module.__dict__.update(_api_names())
    globals_ = {
        "__name__": module_name,
        "__builtins__": restricted_builtins(log),
    }
    steps = []

    def _combined_tracer(frame, event, arg):
        if _INTERRUPT:
            raise KeyboardInterrupt("Interrupted by user")
        if debug and event == "line" and frame.f_code.co_filename == filename:
            if len(steps) < 4000:
                steps.append(frame.f_lineno)
        return _combined_tracer

    sys.modules["craftpy"] = sandbox_module
    compiled = compile(code, filename, "exec")
    try:
        sys.settrace(_combined_tracer)
        exec(compiled, globals_, globals_)
        if not persistent:
            main_fn = globals_.get("main")
            if callable(main_fn):
                main_fn(_Context(log, debug))
    except KeyboardInterrupt as exc:
        log.append("STOPPED: %s" % exc)
    finally:
        sys.settrace(None)
        sys.modules.pop("craftpy", None)
    if debug:
        names = sorted(k for k in globals_ if not k.startswith("_"))
        log.append("[debug] traced %d line(s): %s" % (len(steps), steps[:80]))
        log.append("[debug] top-level names: %s" % (", ".join(names) if names else "(none)"))
        log.append("[debug] declarations: %d block(s), %d item(s), %d effect(s), "
                   "%d recipe(s), %d loot table(s), %d tag(s)"
                   % (len(_DECLS["blocks"]), len(_DECLS["items"]), len(_DECLS["effects"]),
                      len(_DECLS["recipes"]), len(_DECLS["loot_tables"]), len(_DECLS["tags"])))
    return {"declarations": _DECLS, "log": log, "scheduled": len(_SCHEDULE)}


# ---------------------------------------------------------------------------
# Handlers
# ---------------------------------------------------------------------------

_INTERRUPT = False


def _tracer(frame, event, arg):
    if _INTERRUPT:
        raise KeyboardInterrupt("Interrupted by user")
    return _tracer


def _handle(method, params):
    global _INTERRUPT
    if method == "ping":
        return {"version": "%d.%d" % sys.version_info[:2],
                "implementation": "CPython", "hello": "craftpy"}
    if method == "interrupt":
        _INTERRUPT = True
        return {"ok": True}
    if method == "capabilities":
        return {"capabilities": sorted(_CAPABILITIES)}
    if method == "validate":
        _INTERRUPT = False
        return _validate(params)
    if method == "complete":
        return _complete(params)
    if method == "load_module":
        return _load_module(params)
    if method == "eval":
        _INTERRUPT = False
        return _repl_eval(params)
    if method == "reset_console":
        reset_repl()
        return {"ok": True}
    if method == "trigger_event":
        return _trigger_event(params)
    if method == "tick":
        if _INTERRUPT:
            return {"error": "interrupted"}
        return _on_tick(params)
    if method == "set_paths":
        _HOST_ENABLED[0] = True
        return {"ok": True}
    if method == "run_main":
        code = params.get("code", "")
        name = params.get("file", "main.py")
        debug = bool(params.get("debug", False))
        try:
            reset()
            result = _run_code(code, name, "__craftpy_main__", debug=debug)
            return result
        except Exception as exc:  # noqa: BLE001
            line = 0
            tb = traceback.extract_tb(sys.exc_info()[2])
            if tb:
                line = tb[-1].lineno
            return {"error": True, "message": str(exc),
                    "line": max(0, line - 1),
                    "problems": [{"file": name, "line": max(0, line - 1),
                                  "column": 0, "message": "%s: %s" % (type(exc).__name__, exc),
                                  "severity": "error"}]}
    raise ValueError("Unknown method: %s" % method)


def reset():
    for key in _DECL_KEYS:
        _DECLS[key] = {} if key == "lang" else []
    _HANDLERS.clear()
    _SCHEDULE[:] = []
    _TICK[0] = 0


def _trigger_event(params):
    event_name = params.get("event")
    ctx_data = params.get("context", {})
    handlers = _HANDLERS.get(event_name, [])
    log = []
    ctx = _EventContext(log)
    ctx.event = event_name
    for key, value in ctx_data.items():
        setattr(ctx, key, _wrap_data(value))

    # Special handling for state: if ctx has a state, we want to capture its mutations
    has_state = hasattr(ctx, "state")
    has_global = hasattr(ctx, "global_state")

    results = []
    for h in handlers:
        try:
            res = h(ctx)
            results.append(res)
        except Exception as exc:
            log.append("ERROR in handler %s: %s" % (event_name, exc))
            traceback.print_exc()

    response = {"log": log, "results": results, "scheduled": len(_SCHEDULE)}
    if has_state:
        response["state"] = _unwrap_data(ctx.state)
    if has_global:
        response["global_state"] = _unwrap_data(ctx.global_state)

    return response


def _unwrap_data(data):
    if hasattr(data, "__dict__"):
        return {k: _unwrap_data(v) for k, v in data.__dict__.items()}
    if isinstance(data, list):
        return [_unwrap_data(x) for x in data]
    return data


def _wrap_data(data):
    if isinstance(data, dict):
        class DataObj:
            def __getattr__(self, name):
                return self.__dict__.get(name)

            def __repr__(self):
                return "DataObj(%r)" % self.__dict__
        obj = DataObj()
        for k, v in data.items():
            setattr(obj, k, _wrap_data(v))
        return obj
    if isinstance(data, list):
        return [_wrap_data(x) for x in data]
    return data


def _complete(params):
    import re
    import ast
    code = params.get("code", "")
    line_idx = params.get("line", 0)
    col = params.get("column", 0)

    lines = code.split("\n")
    prefix = ""
    if line_idx < len(lines):
        text = lines[line_idx][:col]
        match = re.search(r'([A-Za-z_][A-Za-z0-9_]*)$', text)
        if match:
            prefix = match.group(1)

    candidates = set()
    # 1. Builtins
    for b in restricted_builtins([]).keys():
        if b.startswith(prefix):
            candidates.add(b)
    # 2. API names
    for name in _api_names():
        if name.startswith(prefix):
            candidates.add(name)

    # 3. Local names (AST scan)
    try:
        tree = ast.parse(code)
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.ClassDef, ast.AsyncFunctionDef)):
                if node.name.startswith(prefix):
                    candidates.add(node.name)
            elif isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
                if node.id.startswith(prefix):
                    candidates.add(node.id)
            elif isinstance(node, ast.arg):
                if node.arg.startswith(prefix):
                    candidates.add(node.arg)
    except Exception:  # noqa: BLE001
        pass

    return {"suggestions": sorted(list(candidates))}


def _validate(params):
    problems = []
    files = params.get("files", [])
    code = params.get("code", None)
    name = params.get("file", None)
    if code is not None:
        files = [(name or "main.py", code)]
    else:
        files = [(f, "") for f in files]
    for filename, source in files:
        if not source:
            problems.append({"file": filename, "line": 0, "column": 0,
                             "message": "file not supplied for validation",
                             "severity": "info"})
            continue
        try:
            compile(source, filename, "exec")
        except SyntaxError as exc:
            problems.append({"file": filename,
                             "line": max(0, (exc.lineno or 1) - 1),
                             "column": exc.offset or 0,
                             "message": "SyntaxError: %s" % exc.msg,
                             "severity": "error"})
    return {"problems": problems}


# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------

def _dispatch_response(obj):
    """Handles a host_response line received while a request is executing."""
    call_id = obj.get("host_response")
    with _HOST_LOCK:
        slot = _HOST_WAITERS.get(call_id)
    if slot is None:
        return
    slot["result"] = obj.get("result")
    slot["error"] = obj.get("error")
    slot["event"].set()


def _reader_loop():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict) and "host_response" in obj:
            _dispatch_response(obj)
        else:
            _REQUEST_QUEUE.put(obj)


def _process(request):
    req_id = request.get("id")
    method = request.get("method", "")
    params = request.get("params", {}) or {}
    try:
        result = _handle(method, params)
        _write({"id": req_id, "result": result})
    except Exception as exc:  # noqa: BLE001
        _write({"id": req_id, "error": {"message": str(exc)}})


def _write(obj):
    with _IO_LOCK:
        sys.stdout.write(json.dumps(obj) + "\n")
        sys.stdout.flush()


def main():
    _install_finder()
    print("CraftPy worker ready (Python %d.%d)" % sys.version_info[:2],
          file=sys.stderr, flush=True)
    reader = threading.Thread(target=_reader_loop, name="craftpy-stdin", daemon=True)
    reader.start()
    while True:
        request = _REQUEST_QUEUE.get()
        if request is None:
            break
        _process(request)


if __name__ == "__main__":
    main()
