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
          "recipes": [], "loot_tables": [], "tags": [], "lang": {}}


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


def on(event, every=None):
    def deco(fn):
        _DECLS["handlers"].append({"event": event, "every": every})
        return fn
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
        "tex": tex, "snd": snd, "mdl": mdl, "loot_self": loot_self,
        "recipe_shaped": recipe_shaped, "recipe_shapeless": recipe_shapeless,
        "recipe_smelting": recipe_smelting, "loot_table": loot_table,
        "tag": tag, "lang": lang,
    }


# ---------------------------------------------------------------------------
# Interactive console (REPL). A persistent namespace so variables, imports and
# definitions survive between console lines - like a Python REPL in an IDE.
# ---------------------------------------------------------------------------

_REPL_NS = {}
_REPL_LOG = []
_REPL_READY = False


def _repl_namespace():
    global _REPL_READY
    if not _REPL_READY:
        _REPL_NS.clear()
        _REPL_NS["__name__"] = "__craftpy_console__"
        _REPL_NS["__builtins__"] = restricted_builtins(_REPL_LOG)
        _REPL_NS.update(_api_names())
        _REPL_READY = True
    return _REPL_NS


def reset_repl():
    global _REPL_READY
    _REPL_NS.clear()
    del _REPL_LOG[:]
    _REPL_READY = False


def _repl_eval(params):
    code = params.get("code", "")
    ns = _repl_namespace()
    del _REPL_LOG[:]
    import types as _types
    api_module = _types.ModuleType("craftpy")
    api_module.__dict__.update(_api_names())
    sys.modules["craftpy"] = api_module
    value = None
    error = None
    try:
        import ast as _ast
        tree = _ast.parse(code, "<console>", mode="exec")
        last = None
        if tree.body and isinstance(tree.body[-1], _ast.Expr):
            last = _ast.Expression(tree.body.pop().value)
        if tree.body:
            exec(compile(tree, "<console>", "exec"), ns, ns)
        if last is not None:
            value = repr(eval(compile(last, "<console>", "eval"), ns, ns))
    except SyntaxError as exc:
        error = {"message": "SyntaxError: %s" % exc.msg,
                 "line": max(0, (exc.lineno or 1) - 1)}
    except Exception as exc:  # noqa: BLE001
        tb = traceback.extract_tb(sys.exc_info()[2])
        line = tb[-1].lineno if tb else 0
        error = {"message": "%s: %s" % (type(exc).__name__, exc),
                 "line": max(0, line - 1)}
    finally:
        sys.modules.pop("craftpy", None)
    return {"output": list(_REPL_LOG), "value": value, "error": error}


# ---------------------------------------------------------------------------
# Execute code
# ---------------------------------------------------------------------------

def _run_code(code, filename, module_name, debug=False):
    log = []
    import types as _types
    sandbox_module = _types.ModuleType("craftpy_api")
    sandbox_module.__dict__.update(_api_names())
    globals_ = {
        "__name__": module_name,
        "__builtins__": restricted_builtins(log),
    }
    steps = []

    def _tracer(frame, event, arg):
        if event == "line" and frame.f_code.co_filename == filename:
            if len(steps) < 4000:
                steps.append(frame.f_lineno)
        return _tracer

    sys.modules["craftpy"] = sandbox_module
    compiled = compile(code, filename, "exec")
    try:
        if debug:
            sys.settrace(_tracer)
        exec(compiled, globals_, globals_)
        if debug:
            sys.settrace(None)
        main_fn = globals_.get("main")
        if callable(main_fn):
            main_fn(_Context(log, debug))
    finally:
        if debug:
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

def _handle(method, params):
    if method == "ping":
        return {"version": "%d.%d" % sys.version_info[:2],
                "implementation": "CPython", "hello": "craftpy"}
    if method == "validate":
        return _validate(params)
    if method == "eval":
        return _repl_eval(params)
    if method == "reset_console":
        reset_repl()
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
    _DECLS.clear()
    _DECLS.update({"blocks": [], "items": [], "effects": [], "handlers": [],
                   "recipes": [], "loot_tables": [], "tags": [], "lang": {}})


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