#!/usr/bin/env python3
"""
CraftPy worker - runs as a sandboxed external subprocess.

Communicates with the Java host over newline-delimited JSON-RPC on stdin/stdout:

    -> {"id": 1, "method": "ping"}
    <- {"id": 1, "result": {"version": "3.14", ...}}
    -> {"id": 2, "method": "validate", "params": {"files": [...], "code": "..."}}
    <- {"id": 2, "result": {"problems": [...]}}

SECURITY: This is BEST-EFFORT sandboxing, NOT a hard sandbox. A determined
attacker can escape. Do not run untrusted projects with full OS privileges.
Host hardening suggestions: run with a low-privilege OS account, job objects
(Windows), seccomp/namespaces (Linux).
"""

import json
import sys
import traceback

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
# Declarative API (mirrors python-side "craftpy" user module)
# ---------------------------------------------------------------------------

_DECLS = {"blocks": [], "items": [], "effects": [], "handlers": [],
          "recipes": [], "loot_tables": [], "tags": [], "lang": {}, "commands": []}
_HANDLERS = {}


def _register(kind):
    def deco(ident, **kwargs):
        def wrap(cls):
            _DECLS[kind].append({"id": ident, "definition": _fields(cls) | kwargs})
            return cls
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
        if len(out) > 40:
            break
    return out


block = _register("blocks")
item = _register("items")
effect = _register("effects")


def command(name, **kwargs):
    def deco(fn):
        _DECLS["commands"].append({"name": name, "params": kwargs})
        _HANDLERS.setdefault("command_" + name, []).append(fn)
        return fn
    return deco

def on(event, every=None):
    def deco(fn):
        _DECLS["handlers"].append({"event": event, "every": every})
        _HANDLERS.setdefault(event, []).append(fn)
        return fn
    return deco

def block_logic(id):
    def deco(cls):
        # TODO: register block-specific logic class
        return cls
    return deco


def tex(ref):  # asset reference: location name
    return {"ref": "texture", "value": ref}


def snd(ref):
    return {"ref": "sound", "value": ref}


def mdl(ref):
    return {"ref": "model", "value": ref}


def loot_self():
    return {"ref": "loot", "value": "self"}


def recipe_shaped(result, pattern, keys, category="minecraft"):
    _DECLS["recipes"].append({"type": "shaped", "result": result, "pattern": pattern, "keys": keys})


def recipe_shapeless(result, ingredients, category="minecraft"):
    _DECLS["recipes"].append({"type": "shapeless", "result": result, "ingredients": ingredients})


def recipe_smelting(result, ingredient, xp=0.0, cook_time=200):
    _DECLS["recipes"].append({"type": "smelting", "result": result, "ingredient": ingredient, "xp": xp, "cook_time": cook_time})


def loot_table(id, entries):
    _DECLS["loot_tables"].append({"id": id, "entries": entries})


def tag(id, values, replace=False):
    _DECLS["tags"].append({"id": id, "values": values, "replace": replace})


def lang(key, value):
    _DECLS["lang"][key] = value


def _api_names():
    return {
        "block": block, "item": item, "effect": effect, "on": on,
        "command": command,
        "tex": tex, "snd": snd, "mdl": mdl, "loot_self": loot_self,
        "recipe_shaped": recipe_shaped, "recipe_shapeless": recipe_shapeless,
        "recipe_smelting": recipe_smelting, "loot_table": loot_table,
        "tag": tag, "lang": lang, "ui": _ui_module(),
    }

def _ui_module():
    import types as _types
    m = _types.ModuleType("ui")
    def label(text, x=0, y=0, color="text"):
        return {"type": "label", "text": text, "x": x, "y": y, "color": color}
    def button(text, x=0, y=0, w=100, h=20, on_click=None):
        return {"type": "button", "text": text, "x": x, "y": y, "w": w, "h": h, "on_click": on_click}
    def panel(x=0, y=0, w=200, h=150, children=None):
        return {"type": "panel", "x": x, "y": y, "w": w, "h": h, "children": children or []}
    
    m.label = label
    m.button = button
    m.panel = panel
    return m


# ---------------------------------------------------------------------------
# Interactive console (REPL). A persistent namespace so variables, imports and
# definitions survive between console lines - like a Python REPL in an IDE.
# ---------------------------------------------------------------------------



_REPL_SESSIONS = {}


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
    return {"declarations": _DECLS, "log": log}


class _Context:
    """Minimal execution context passed to a project's main(ctx) function."""

    def __init__(self, log, debug=False):
        self.debug = debug
        self._log = log
        self.global_state = {}

    def log(self, message):
        self._log.append(str(message))

    def info(self, message):
        self._log.append(str(message))

    def warn(self, message):
        self._log.append("WARN: %s" % message)

    def error(self, message):
        self._log.append("ERROR: %s" % message)


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
    _DECLS.clear()
    _DECLS.update({"blocks": [], "items": [], "effects": [], "handlers": [],
                   "recipes": [], "loot_tables": [], "tags": [], "lang": {}})
    _HANDLERS.clear()


def _trigger_event(params):
    event_name = params.get("event")
    ctx_data = params.get("context", {})
    handlers = _HANDLERS.get(event_name, [])
    log = []
    ctx = _Context(log)
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
            
    response = {"log": log, "results": results}
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
            
    # 2. Local names (AST scan)
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
    except: # noqa: E722
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
        if not source and params.get("base_dir"):
            import pathlib as _pathlib
            # pathlib import removed from allowlist; this branch stays closed.
            source = ""
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

def main():
    _install_finder()
    print("CraftPy worker ready (Python %d.%d)" % sys.version_info[:2],
          file=sys.stderr, flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError as exc:
            print(json.dumps({"error": {"message": "bad json: %s" % exc}}))
            continue
        req_id = request.get("id")
        method = request.get("method", "")
        params = request.get("params", {}) or {}
        try:
            result = _handle(method, params)
            print(json.dumps({"id": req_id, "result": result}), flush=True)
        except Exception as exc:  # noqa: BLE001
            print(json.dumps({"id": req_id, "error": {"message": str(exc)}}), flush=True)


if __name__ == "__main__":
    main()