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
import inspect

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
    # modules that inspect or rebuild the running interpreter
    "inspect", "gc", "dis", "code", "codeop", "runpy", "pdb", "bdb", "trace", "tracemalloc", "faulthandler",
    "zipimport", "pkgutil", "site", "atexit", "imp", "_io", "_posixshmem", "_winapi", "_testcapi",
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


# ---------------------------------------------------------------------------
# Sandbox, three layers (see docs/AUDIT.md section 10):
#   1. an audit hook (_install_audit_hook) refuses files outside the allowed folders, every write, processes, network,
#      native libraries and similar at the interpreter level, whatever the code does to get there;
#   2. user code is checked before it is compiled (_compile_user): attributes that expose the interpreter's insides are
#      refused, and getattr / hasattr / setattr apply the same rule to names built at run time;
#   3. the worker process is memory limited (_limit_memory) and, on Windows, cannot start other processes.
# ---------------------------------------------------------------------------

# Dunder attributes ordinary code uses. Any other __name__ attribute is refused in user code (defining one with `def`
# is fine: only reading or writing it through a dot is checked).
_SAFE_DUNDERS = frozenset({
    "__init__", "__name__", "__doc__", "__class__", "__module__", "__qualname__", "__slots__", "__repr__", "__str__",
    "__eq__", "__ne__", "__lt__", "__le__", "__gt__", "__ge__", "__hash__", "__len__", "__iter__", "__next__",
    "__getitem__", "__setitem__", "__delitem__", "__contains__", "__call__", "__enter__", "__exit__", "__bool__",
    "__add__", "__sub__", "__mul__", "__truediv__", "__floordiv__", "__mod__", "__neg__", "__abs__", "__radd__",
    "__rsub__", "__rmul__", "__post_init__", "__annotations__", "__wrapped__", "__all__", "__version__",
    "__format__", "__lshift__", "__rshift__", "__and__", "__or__", "__xor__", "__invert__", "__index__",
    "__int__", "__float__", "__round__", "__reversed__", "__missing__", "__set_name__", "__init_subclass__",
})

# Attributes of frames, generators, tracebacks and code objects: they lead out of the program into the interpreter.
_FORBIDDEN_ATTRS = frozenset({
    "f_back", "f_globals", "f_locals", "f_builtins", "f_code", "f_trace", "f_lineno", "gi_frame", "gi_code",
    "gi_yieldfrom", "cr_frame", "cr_code", "cr_await", "ag_frame", "ag_code", "ag_await", "tb_frame", "tb_next",
    "co_code", "co_consts", "co_names", "co_varnames", "co_filename", "co_lines",
})

_FORBIDDEN_NAMES = frozenset({"__builtins__", "__loader__", "__spec__", "__import__"})


def _attribute_allowed(name):
    if not isinstance(name, str):
        return True
    if name.startswith("__") and name.endswith("__"):
        return name in _SAFE_DUNDERS
    return name not in _FORBIDDEN_ATTRS


def _attribute_error(name):
    return "'%s' is not available in CraftPy's sandbox: it reaches into the interpreter, not into the game" % name


_DUNDER_IN_TEXT = None


def _check_user_code(tree, filename):
    """Refuses code that names interpreter internals. Raises SyntaxError with the line, so the editor shows it."""
    import ast
    import re
    global _DUNDER_IN_TEXT
    if _DUNDER_IN_TEXT is None:
        _DUNDER_IN_TEXT = re.compile(r"__[A-Za-z0-9_]+?__")
    for node in ast.walk(tree):
        bad = None
        if isinstance(node, ast.Attribute) and not _attribute_allowed(node.attr):
            bad = node.attr
        elif isinstance(node, ast.Name) and node.id in _FORBIDDEN_NAMES:
            bad = node.id
        elif isinstance(node, ast.Constant) and isinstance(node.value, str) and "__" in node.value:
            # the same names as text: operator.attrgetter('__globals__'), '{0.__globals__}'.format(f), vars(m)[...]
            for found in _DUNDER_IN_TEXT.findall(node.value):
                if found not in _SAFE_DUNDERS and found != "__main__":
                    bad = found
                    break
        if bad is not None:
            raise SyntaxError(_attribute_error(bad), (filename, node.lineno, node.col_offset + 1, None))


def _compile_user(source, filename, mode="exec"):
    """The only way project code is compiled: parse, check, then compile."""
    import ast
    tree = ast.parse(source, filename, mode=mode)
    _check_user_code(tree, filename)
    return compile(tree, filename, mode)


def _safe_getattr(obj, name, *default):
    if not _attribute_allowed(name):
        raise AttributeError(_attribute_error(name))
    return getattr(obj, name, *default)


def _safe_hasattr(obj, name):
    if not _attribute_allowed(name):
        return False
    return hasattr(obj, name)


def _safe_setattr(obj, name, value):
    if not _attribute_allowed(name):
        raise AttributeError(_attribute_error(name))
    setattr(obj, name, value)


def _safe_vars(*obj):
    """vars() without the interpreter's insides: the module of a stdlib import carries the real builtins in its
    namespace (vars(random)['__builtins__'] was a way out). Returns a copy without such names."""
    if obj:
        if len(obj) > 1:
            raise TypeError("vars() takes at most 1 argument")
        found = vars(obj[0])
    else:
        found = sys._getframe(1).f_locals
    return {k: v for k, v in found.items() if not isinstance(k, str) or _attribute_allowed(k)}


_DENIED_EVENTS = frozenset({
    "subprocess.Popen", "os.system", "os.exec", "os.spawn", "os.posix_spawn", "os.fork", "os.forkpty", "os.kill",
    "os.killpg", "os.remove", "os.rename", "os.rmdir", "os.mkdir", "os.chmod", "os.chown", "os.chdir", "os.chroot",
    "os.truncate", "os.symlink", "os.link", "os.mkfifo", "os.mknod", "os.putenv", "os.unsetenv", "os.startfile",
    "os.add_dll_directory", "os.utime", "os.setxattr", "code.__new__", "sqlite3.connect",
})
_DENIED_PREFIXES = ("socket.", "ctypes.", "shutil.", "winreg.", "http.", "urllib.", "ftplib.", "smtplib.",
                    "webbrowser.", "tempfile.", "resource.", "pty.", "msvcrt.", "mmap.")


def _install_audit_hook(read_roots):
    """Refuses, for the whole process, what project code has no business doing. An audit hook cannot be removed from
    Python code. Reads are allowed inside ``read_roots`` (the Python install, this worker, pyforge/), writes never."""
    import os
    roots = tuple(os.path.normcase(os.path.abspath(r)) + os.sep for r in read_roots if r)
    write_flags = os.O_WRONLY | os.O_RDWR | os.O_APPEND | os.O_CREAT | os.O_TRUNC

    def inside(path):
        try:
            text = os.fsdecode(path)
            return (os.path.normcase(os.path.abspath(text)) + os.sep).startswith(roots)
        except (TypeError, ValueError):
            return False

    def hook(event, args):
        if event == "open":
            path = args[0]
            if isinstance(path, int):
                return  # a descriptor that was already open
            mode, flags = args[1], args[2]
            if (isinstance(mode, str) and any(c in mode for c in "wax+")) or (isinstance(flags, int) and flags & write_flags):
                raise PermissionError("CraftPy's sandbox: files cannot be written")
            if not inside(path):
                raise PermissionError("CraftPy's sandbox: files outside the project and the game cannot be read")
        elif event in ("os.listdir", "os.scandir"):
            path = args[0]
            if not isinstance(path, int) and not (path is not None and inside(path)):
                raise PermissionError("CraftPy's sandbox: that folder cannot be listed")
        elif event in _DENIED_EVENTS or event.startswith(_DENIED_PREFIXES):
            raise PermissionError("CraftPy's sandbox: %s is not allowed" % event)

    sys.addaudithook(hook)


def _limit_memory(megabytes):
    """Caps the memory of this process; on Windows the same job also forbids starting other processes."""
    if megabytes <= 0:
        return
    limit = int(megabytes) * 1024 * 1024
    try:
        if sys.platform == "win32":
            import ctypes
            from ctypes import wintypes

            class IoCounters(ctypes.Structure):
                _fields_ = [(n, ctypes.c_ulonglong) for n in (
                    "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                    "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

            class Basic(ctypes.Structure):
                _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong), ("PerJobUserTimeLimit", ctypes.c_longlong),
                            ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                            ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                            ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD),
                            ("SchedulingClass", wintypes.DWORD)]

            class Extended(ctypes.Structure):
                _fields_ = [("BasicLimitInformation", Basic), ("IoInfo", IoCounters), ("ProcessMemoryLimit", ctypes.c_size_t),
                            ("JobMemoryLimit", ctypes.c_size_t), ("PeakProcessMemoryUsed", ctypes.c_size_t),
                            ("PeakJobMemoryUsed", ctypes.c_size_t)]

            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.CreateJobObjectW.restype = wintypes.HANDLE
            kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
            kernel.GetCurrentProcess.restype = wintypes.HANDLE
            kernel.GetCurrentProcess.argtypes = []
            kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
            kernel.SetInformationJobObject.restype = wintypes.BOOL
            kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
            kernel.AssignProcessToJobObject.restype = wintypes.BOOL
            job = kernel.CreateJobObjectW(None, None)
            info = Extended()
            info.BasicLimitInformation.LimitFlags = 0x100 | 0x8  # process memory + active process limit
            info.BasicLimitInformation.ActiveProcessLimit = 1
            info.ProcessMemoryLimit = limit
            if not job or not kernel.SetInformationJobObject(job, 9, ctypes.byref(info), ctypes.sizeof(info)):
                raise OSError("SetInformationJobObject failed")
            if not kernel.AssignProcessToJobObject(job, kernel.GetCurrentProcess()):
                raise OSError("AssignProcessToJobObject failed")
        else:
            import resource
            resource.setrlimit(resource.RLIMIT_AS, (limit, limit))
    except Exception as exc:  # noqa: BLE001  a platform that refuses a limit still gets layers 1 and 2
        print("CraftPy worker: memory limit not applied (%s)" % exc, file=sys.stderr, flush=True)


def _harden():
    """Called once by main() before any project code can run."""
    import os
    try:
        _limit_memory(int(os.environ.get("CRAFTPY_MEMORY_MB", "1024")))
    except ValueError:
        _limit_memory(1024)
    roots = [sys.prefix, sys.base_prefix, sys.exec_prefix, sys.base_exec_prefix, os.path.dirname(os.path.abspath(__file__))]
    roots += [r for r in os.environ.get("CRAFTPY_READ_ROOTS", "").split(os.pathsep) if r]
    _install_audit_hook(roots)


# While an event/timer runs, print() goes to that call's log (shown in the editor console),
# not to the log of the Run that defined the function.
_LOG_TARGET = [None]


def _print_to(log):
    def _print(*args, sep=" ", **_):
        target = _LOG_TARGET[0] if _LOG_TARGET[0] is not None else log
        target.append(sep.join(str(x) for x in args))
    return _print


def restricted_builtins(log):
    b = {
        "abs": abs, "all": all, "any": any, "ascii": ascii, "bin": bin, "bool": bool,
        "bytearray": bytearray, "bytes": bytes, "callable": callable, "chr": chr,
        "classmethod": classmethod, "complex": complex,
        "dict": dict, "divmod": divmod, "enumerate": enumerate,
        "filter": filter, "float": float, "format": format, "frozenset": frozenset,
        "getattr": _safe_getattr, "hasattr": _safe_hasattr, "hash": hash, "hex": hex, "id": id,
        "int": int, "isinstance": isinstance, "issubclass": issubclass,
        "iter": iter, "len": len, "list": list,
        "map": map, "max": max, "memoryview": memoryview, "min": min, "next": next,
        "object": object, "oct": oct, "ord": ord, "pow": pow, "property": property,
        "range": range, "repr": repr, "reversed": reversed, "round": round,
        "set": set, "setattr": _safe_setattr, "slice": slice, "sorted": sorted,
        "staticmethod": staticmethod, "str": str, "sum": sum, "super": super,
        "tuple": tuple, "type": type, "zip": zip,
        "print": _print_to(log), "dir": dir, "vars": _safe_vars, "LookupError": LookupError,
        "Exception": Exception, "BaseException": BaseException,
        "ValueError": ValueError, "TypeError": TypeError,
        "RuntimeError": RuntimeError, "KeyError": KeyError, "IndexError": IndexError,
        "StopIteration": StopIteration, "NotImplementedError": NotImplementedError,
        "NotImplemented": NotImplemented, "AssertionError": AssertionError,
        "NameError": NameError, "AttributeError": AttributeError,
        "ArithmeticError": ArithmeticError, "ZeroDivisionError": ZeroDivisionError,
        "OverflowError": OverflowError, "ImportError": ImportError,
        "PermissionError": PermissionError, "FileNotFoundError": FileNotFoundError, "OSError": OSError,
        "TimeoutError": TimeoutError, "UnicodeError": UnicodeError,
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

_DECL_KEYS = ("blocks", "items", "entities", "effects", "fluids", "handlers", "recipes",
              "loot_tables", "tags", "lang", "commands", "advancements",
              "sounds", "particles", "enchantments", "raw", "meta")


def _new_decls():
    decls = {k: [] for k in _DECL_KEYS}
    decls["lang"] = {}
    decls["meta"] = {}
    return decls


# ---------------------------------------------------------------------------
# Owners. Every project and every module registers into its OWN declarations,
# handlers and timers, so running one project never wipes another module's
# event handlers. The owner is "<project id>" or "module:<module id>".
# ---------------------------------------------------------------------------
_OWNERS = {}
_OWNER = ["__main__"]
_DECLS = _new_decls()
_HANDLERS = {}
_WINDOWS = {}
_SCHEDULE = []
_STORAGE = {}
_TICK = [0]
# Handler profiler: (owner, "event:function") -> [calls, total seconds, slowest seconds, errors].
_PROFILE = {}
_CAPABILITIES = {
    "content.block", "content.item", "content.effect", "content.entity",
    "recipe.all", "loot.table", "tags", "lang", "events", "commands",
    "ui.window", "storage.kv", "scheduler", "world.read", "world.write",
    "world.fill", "world.explode", "player.effects", "player.inventory",
}


def _use_owner(owner):
    """Makes ``owner`` the target of every registration (decorators, Block(), recipes...)."""
    global _DECLS, _HANDLERS
    state = _OWNERS.get(owner)
    if state is None:
        state = _OWNERS[owner] = {"decls": _new_decls(), "handlers": {}}
    _OWNER[0] = owner
    _DECLS = state["decls"]
    _HANDLERS = state["handlers"]


def _profile_add(owner, label, seconds, failed=False):
    row = _PROFILE.setdefault((owner, label), [0, 0.0, 0.0, 0])
    row[0] += 1
    row[1] += seconds
    row[2] = max(row[2], seconds)
    if failed:
        row[3] += 1


def _profile_report():
    rows = [{"owner": owner, "name": name, "calls": r[0], "total_ms": round(r[1] * 1000, 3),
             "avg_ms": round(r[1] * 1000 / r[0], 3), "max_ms": round(r[2] * 1000, 3), "errors": r[3]}
            for (owner, name), r in _PROFILE.items()]
    rows.sort(key=lambda row: -row["total_ms"])
    return {"rows": rows[:200]}


def _drop_owner(owner, host=True):
    """Forgets everything ``owner`` registered: declarations, handlers and timers. ``host``: also clear its HUD and
    keys on the host (not for a dependency's names dropped after a library load: the host would clear the caller's)."""
    for key in [k for k in _KEYS if k[0] == owner]:
        del _KEYS[key]
    for key in [k for k in _EVENT_LAST if k[0] == owner]:
        del _EVENT_LAST[key]
    for key in [k for k in _HUD_LAST if k[0] == owner]:
        del _HUD_LAST[key]
    if host and _MODE[0] == "client" and owner != "__console__":
        try:
            _host_call("hud.clear", {"owner": owner})
        except HostError:
            pass
    for name in [n for n, o in _USER_EXTENSIONS.items() if o[0] == owner]:
        del _USER_EXTENSIONS[name]
    _OWNERS.pop(owner, None)
    if owner.startswith("module:"):
        _MODULE_EXPORTS.pop(owner[len("module:"):], None)
    for key in [k for k in _PROFILE if k[0] == owner]:
        del _PROFILE[key]
    _SCHEDULE[:] = [entry for entry in _SCHEDULE if entry["owner"] != owner]
    for key in [k for k, v in _UI_OPEN.items() if v["owner"] == owner]:
        del _UI_OPEN[key]
    for key in [k for k in _UI_NAMED if k[0] == owner]:
        del _UI_NAMED[key]
    for key in [k for k in _UI_FILES if k[0] == owner]:
        del _UI_FILES[key]
    for key in [k for k in _UI_DATA if k[0] == owner]:
        del _UI_DATA[key]
    for key in [k for k in _WINDOWS if k[0] == owner]:
        del _WINDOWS[key]
    for key in [k for k, v in _FN_TABLE.items() if v[0] == owner]:
        del _FN_TABLE[key]


def _namespace():
    owner = _OWNER[0]
    return owner.split(":", 1)[1] if owner.startswith(("module:", "test:")) else owner


# ---------------------------------------------------------------------------
# Ids. Scripts may write "stick", "minecraft:stick", "#minecraft:logs", or use a
# Block/Item object. A bare name that this project declared means the project's
# content ("ruby" -> "ruby_rush:ruby"); any other bare name is vanilla.
# ---------------------------------------------------------------------------

class Content(str):
    """A declared block or item. It *is* its full id string ("project:id")."""

    kind: str
    local_id: str
    name: str

    def __new__(cls, full_id, kind, local_id, name, definition=None):
        obj = str.__new__(cls, full_id)
        obj.kind = kind
        obj.local_id = local_id
        obj.name = name
        obj._definition = definition if definition is not None else {}
        return obj

    def extends(self, *extensions) -> "Content":
        """Adds behaviour: ``Item("ruby_sword").extends(Sword(tier="diamond"), Durability(1561))``.

        Every extension is an object such as ``Tool(...)``, ``Durability(...)`` or your own
        ``@extension`` class. Returns the same item/block, so calls can be chained."""
        for ext in extensions:
            if isinstance(ext, (list, tuple)):
                self.extends(*ext)
                continue
            if isinstance(ext, type) and issubclass(ext, Extension):
                raise TypeError("extends(): write %s(...) with parentheses - an extension is an object" % ext.__name__)
            if not isinstance(ext, Extension):
                raise TypeError("extends() takes extensions like Sword(...) or Durability(...), not %s"
                                % type(ext).__name__)
            if self.kind not in ext.applies_to:
                raise TypeError("%s can only be used on %s, not on a %s (%s)"
                                % (type(ext).__name__, " or ".join(ext.applies_to), self.kind, self.local_id))
            ext.apply(self)
        return self

    def set(self, key, value):
        """Writes one attribute of the definition (used by extensions that wrap plain attributes)."""
        self._definition[key] = value

    def add_ext(self, entry):
        """Stores the data of a built-in extension; a second one of the same type replaces the first, except Attributes:
        every Attributes(...) is a modifier of its own (an item can raise mana and spell power), the same attribute in the
        same slot replaces the earlier one."""
        found = self._definition.setdefault("ext", [])
        if entry["type"] == "attributes":
            found[:] = [e for e in found if not (e.get("type") == "attributes" and e.get("attribute") == entry["attribute"]
                                                 and e.get("slot") == entry["slot"])]
        else:
            found[:] = [e for e in found if e.get("type") != entry["type"]]
        found.append(entry)

    def has_ext(self, kind):
        return any(e.get("type") == kind for e in self._definition.get("ext", ()))

    @property
    def id(self):
        return str(self)

    def __repr__(self):
        return "%s(%r)" % (self.kind.capitalize(), str(self))


def _declared(local_id, keys=("blocks", "items", "entities")):
    for key in keys:
        for entry in _DECLS[key]:
            if entry.get("id") == local_id:
                return True
    return False


def _ref(value):
    """Normalizes an item/block reference to a full id (or a #tag)."""
    if isinstance(value, Content):
        return str(value)
    if isinstance(value, type) and isinstance(getattr(value, "content", None), Content):
        return str(value.content)  # class form: class RubySword(ItemType)
    if isinstance(value, dict):
        value = value.get("item") or value.get("id") or value.get("value") or ""
    value = str(value).strip()
    if not value or value.startswith("#") or ":" in value:
        return value
    if _declared(value):
        for entry in _DECLS["blocks"]:
            if entry.get("id") == value and entry.get("definition", {}).get("native_base"):
                return entry["definition"]["native_base"]
        return "%s:%s" % (_namespace(), value)
    return "minecraft:" + value


def _check_id(ident):
    if not isinstance(ident, str) or not ident:
        raise ValueError("ids must be non-empty strings, got %r" % (ident,))
    for ch in ident:
        if not (ch.islower() or ch.isdigit() or ch in "_./-"):
            raise ValueError("id %r: use lowercase letters, digits and _ (e.g. 'ruby_ore')" % ident)


def _pretty(ident):
    return ident.replace("_", " ").title()


def _texture(value):
    """Texture argument: 'ruby.png' (project file), 'minecraft:dirt', or tex(...)."""
    if isinstance(value, dict):
        return value
    return str(value)


# ---------------------------------------------------------------------------
# Content: the simple, Pythonic way
#
#   ruby_ore = Block("ruby_ore", texture="ruby_ore.png", hardness=3, tool="pickaxe", drops=ruby)
#   ruby = Item("ruby", texture="ruby.png")
# ---------------------------------------------------------------------------

def Food(hunger=4, saturation=0.6, always_eat=False, fast=False):
    """Food values for Item(food=...)."""
    return {"hunger": int(hunger), "saturation": float(saturation),
            "always_eat": bool(always_eat), "fast": bool(fast)}


def _shape(value):
    """shape=[(x1, y1, z1, x2, y2, z2), ...] in sixteenths of a block (0..16): the boxes of a block's hitbox."""
    if value is None:
        return None
    if isinstance(value, (list, tuple)) and len(value) == 6 and all(isinstance(n, (int, float)) for n in value):
        value = [value]  # one box
    if not isinstance(value, (list, tuple)) or not value or len(value) > 32:
        raise ValueError("shape must be a box (x1, y1, z1, x2, y2, z2) or a list of up to 32 boxes, in 0..16")
    boxes = []
    for box in value:
        if not isinstance(box, (list, tuple)) or len(box) != 6 or not all(
                isinstance(n, (int, float)) and not isinstance(n, bool) and 0 <= n <= 16 for n in box):
            raise ValueError("each shape box is (x1, y1, z1, x2, y2, z2) with numbers from 0 to 16, got %r" % (box,))
        boxes.append([float(min(box[i], box[i + 3])) for i in range(3)] + [float(max(box[i], box[i + 3])) for i in range(3)])
    return boxes


def Block(id, name=None, texture=None, hardness=2.0, resistance=None, light=0, tool=None,
          needs_tool=False, sound=None, drops=None, extensions=(), tags=(), model=None, shape=None, **extra) -> "Content":
    """Declares a block and returns it (usable anywhere an id is expected).

    ``model="pedestal"`` uses your own mesh: assets/models/block/pedestal.json (Blockbench "Java Block/Item" export).
    ``shape=[(2, 0, 2, 14, 12, 14)]`` is the hitbox (outline and collision) as boxes in sixteenths of a block; without
    it the hitbox follows the model's boxes (a full cube without a model).

    ``extensions=[...]`` or ``.extends(...)`` adds behaviour (MineableBy, Redstone, Flammable, your own...).
    ``tags=["minecraft:mineable/pickaxe", "gems"]`` puts the block in block tags (a bare name is this project's tag)."""
    _check_id(id)
    _reject_unknown("Block", id, extra, extensions)
    definition = {}
    definition["name"] = name or _pretty(id)
    if texture is not None:
        definition["texture"] = _texture(texture)
    definition["hardness"] = float(hardness)
    if resistance is not None:
        definition["resistance"] = float(resistance)
    definition["light"] = int(light)
    if tool:
        definition["tool"] = tool
    if needs_tool:
        definition["needs_tool"] = True
    if sound:
        definition["sound"] = sound
    if model:
        import re
        if not isinstance(model, str) or not re.fullmatch(r"[a-z0-9_./:-]{1,120}", model):
            raise ValueError("model must be the name of a file in assets/models/block/, such as \"pedestal\"")
        definition["model"] = model[:-5] if model.endswith(".json") else model
    if shape is not None:
        definition["shape"] = _shape(shape)
    _DECLS["blocks"].append({"id": id, "definition": definition})
    block = Content("%s:%s" % (_namespace(), id), "block", id, definition["name"], definition)
    _content_tags(tags, "block", str(block))
    if drops is not None:
        _block_drops(id, drops)
    return block.extends(*extensions)


def _block_drops(block_id, drops):
    """drops=ruby | [ruby, (coal, 2)] | (ruby, 2, weight) | [] (nothing). Default: the block itself."""
    if isinstance(drops, (str, dict)) or (isinstance(drops, tuple) and drops and not isinstance(drops[0], tuple)):
        drops = [drops]
    entries = []
    for drop in drops:
        if isinstance(drop, tuple):
            item, count, weight = (list(drop) + [1, 1])[:3]
            entries.append(loot_entry(_ref(item), count=int(count), weight=int(weight)))
        elif isinstance(drop, str) and drop == "self":
            entries.append(loot_self())
        else:
            entries.append(loot_entry(_ref(drop)))
    loot_table(block_id, entries)


def NativeBlock(id, *, base, name=None) -> "Content":
    _check_id(id)
    base = _native_base(base)
    definition = {"name": name or _pretty(id), "native_base": base}
    _DECLS["blocks"].append({"id": id, "definition": definition})
    # Block aliases are references to the original content, not independently registered block types.
    return Content(base, "native_block", id, definition["name"], definition)


def NativeMob(id, *, base, name=None, state=None) -> "Content":
    _check_id(id)
    base = _native_base(base)
    if state is not None and not isinstance(state, dict):
        raise TypeError("NativeMob state must be a dictionary")
    definition = {"native_base": base, "state": dict(state or {})}
    if name is not None:
        definition["name"] = str(name)
    if len(json.dumps(definition["state"])) > 16384:
        raise ValueError("NativeMob initial state exceeds 16 KiB")
    _DECLS["entities"].append({"id": id, "definition": definition})
    return Content("%s:%s" % (_namespace(), id), "native_entity", id, name or _pretty(id), definition)


def _native_base(base):
    import re
    base = str(base)
    if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", base):
        raise ValueError("Native base must be a complete registered ID, such as 'minecraft:iron_golem'")
    if base.startswith("craftpy:pc"):
        raise ValueError("A native base must be original registered content, not a CraftPy slot")
    return base


def Item(id, name=None, texture=None, stack=64, glow=False, food=None, fuel=0, effect=None, extensions=(), tags=(),
         components=None, **extra) -> "Content":
    """Declares an item and returns it.

    ``tags=["minecraft:swords", "gems"]`` puts the item in item tags (a bare name is this project's tag).

    ``extensions=[...]`` or ``.extends(...)`` adds behaviour (Sword, Durability, Armor, your own...).

    food=Food(...) or a number (hunger); fuel=burn seconds; effect="speed" or ("speed", level, seconds)
    applied on right click.
    """
    _check_id(id)
    _reject_unknown("Item", id, extra, extensions)
    definition = {}
    definition["name"] = name or _pretty(id)
    if texture is not None:
        definition["texture"] = _texture(texture)
    definition["max_stack"] = int(stack)
    if glow:
        definition["glow"] = True
    if food is not None:
        definition["food"] = Food(hunger=food) if isinstance(food, (int, float)) else dict(food)
    if fuel:
        definition["burn_time"] = int(fuel)
    if effect:
        definition["use_effect"] = _effect_spec(effect)
    if components:
        definition["components"] = _components(components)
    _DECLS["items"].append({"id": id, "definition": definition})
    item = Content("%s:%s" % (_namespace(), id), "item", id, definition["name"], definition)
    _content_tags(tags, "item", str(item))
    return item.extends(*extensions)


_MOB_MODELS = ("humanoid", "player", "pig", "cow", "chicken", "spider", "creeper", "villager", "slime")


def Spawn(biomes="#minecraft:is_overworld", weight=10, min=1, max=3):
    """Where a Mob appears by itself: Mob(..., spawns=[Spawn("#minecraft:is_forest", weight=8)])."""
    names = [biomes] if isinstance(biomes, str) else list(biomes)
    if not names or not all(isinstance(b, str) and b for b in names):
        raise ValueError("Spawn biomes: a biome id ('minecraft:plains'), a biome tag ('#minecraft:is_overworld') or a list")
    low = _num("Spawn min", min, 1, 64, whole=True)
    return {"biomes": names, "weight": _num("Spawn weight", weight, 1, 1000, whole=True), "min": low,
            "max": max_(low, _num("Spawn max", max, 1, 64, whole=True))}


max_ = max  # Spawn(max=...) shadows the builtin


def Mob(id, name=None, texture=None, model="humanoid", animation=None, health=10, speed=0.25, damage=2, armor=0,
        hostile=False, width=0.6, height=1.8, scale=1.0, follow_range=16, egg=None, spawns=(), drops=None,
        behavior=None, goals=(), animations=None, **extra) -> "Content":
    """Declares a mob and returns it.

    ``model`` is a vanilla body ('humanoid', 'player', 'pig', 'cow', 'chicken', 'spider', 'creeper', 'villager',
    'slime') drawn with ``texture``, or a GeckoLib model file ('goblin.geo.json' in assets/models/, needs GeckoLib).
    Hostile mobs attack players; passive ones wander and flee. Get one with world.spawn(mob, x, y, z),
    /craftpy summon, a spawn egg (/craftpy give <player> <project>:<id> egg) or ``spawns=[Spawn(...)]``."""
    _check_id(id)
    if extra:
        raise TypeError("Mob(%r) has no attribute %s. Valid attributes: %s"
                        % (id, ", ".join(repr(k) for k in sorted(extra)), ", ".join(_MOB_ATTRS)))
    model = str(model)
    if model not in _MOB_MODELS and not model.endswith(".geo.json"):
        raise ValueError("Mob model must be one of %s or a GeckoLib '.geo.json' file, got %r"
                         % (", ".join(_MOB_MODELS), model))
    definition = {"name": name or _pretty(id), "model": model, "base": "hostile" if hostile else "passive",
                  "health": _num("Mob health", health, 1, 1024), "speed": _num("Mob speed", speed, 0, 4),
                  "damage": _num("Mob damage", damage, 0, 1024), "armor": _num("Mob armor", armor, 0, 30),
                  "width": _num("Mob width", width, 0.1, 16), "height": _num("Mob height", height, 0.1, 16),
                  "scale": _num("Mob scale", scale, 0.05, 16), "follow_range": _num("Mob follow_range", follow_range, 1, 128)}
    if texture is not None:
        definition["texture"] = str(texture)
    if animation is not None:
        definition["animation"] = str(animation)
    if behavior is not None:
        profile = {"profile": behavior} if isinstance(behavior, str) else dict(behavior)
        if profile.get("profile") not in ("melee", "ranged", "follow", "guard"):
            raise ValueError("Mob behavior profile must be melee, ranged, follow or guard")
        if profile.get("target", "hostile") not in ("hostile", "players", "none"):
            raise ValueError("Mob behavior target must be hostile, players or none")
        for key, value in profile.items():
            if key in ("profile", "target"):
                continue
            if key not in ("speed", "range", "interval", "radius"):
                raise ValueError("Unknown behavior option: %s" % key)
            _num("Behavior " + key, value, 10 if key == "interval" else .1 if key == "speed" else 1,
                 200 if key == "interval" else 4 if key == "speed" else 64, whole=key == "interval")
        definition["behavior"] = profile
    if goals:
        goals = list(goals)
        if len(goals) > 8 or not all(isinstance(goal, dict) and isinstance(goal.get("class"), str) for goal in goals):
            raise ValueError("Mob goals need at most eight inspected class/args dictionaries")
        definition["goals"] = goals
    if animations is not None:
        animations = dict(animations)
        if not all(key in ("idle", "walk", "run", "attack", "cast", "hurt", "death")
                   and isinstance(value, str) and 0 < len(value) <= 128 for key, value in animations.items()):
            raise ValueError("Mob animations map idle/walk/run/attack/cast/hurt/death to named clips")
        definition["animations"] = animations
    if egg is not None:
        color, spots = (list(egg) + ["#c9e86b"])[:2] if isinstance(egg, (tuple, list)) else (egg, "#c9e86b")
        definition["egg_color"], definition["egg_spots"] = str(color), str(spots)
    found = [spawns] if isinstance(spawns, dict) else list(spawns or ())
    if found:
        if not all(isinstance(s, dict) and "biomes" in s for s in found):
            raise TypeError("Mob spawns: a list of Spawn(...)")
        definition["spawns"] = found
    _DECLS["entities"].append({"id": id, "definition": definition})
    mob = Content("%s:%s" % (_namespace(), id), "entity", id, definition["name"], definition)
    if drops is not None:
        _block_drops(id, drops)
    return mob


_MOB_ATTRS = ("name", "texture", "model", "animation", "health", "speed", "damage", "armor", "hostile", "width",
              "height", "scale", "follow_range", "egg", "spawns", "drops", "behavior", "goals", "animations")


def Effect(id, name=None, icon=None, color="#8899ff", category="neutral", attributes=(), **extra) -> "Content":
    """Declares a potion effect and returns it: player.effect(rage, seconds=20), Item(effect=rage).

    ``attributes=[Attributes("minecraft:generic.attack_damage", 2)]`` change stats while it is active (the amount
    is per level). ``@on.effect_tick(effect=rage)`` runs once a second on whoever has it."""
    _check_id(id)
    if extra:
        raise TypeError("Effect(%r) has no attribute %s. Valid attributes: name, icon, color, category, attributes"
                        % (id, ", ".join(repr(k) for k in sorted(extra))))
    _choice("Effect category", category, ("beneficial", "harmful", "neutral"))
    found = [attributes] if isinstance(attributes, Attributes) else list(attributes or ())
    if not all(isinstance(a, Attributes) for a in found):
        raise TypeError("Effect attributes: a list of Attributes(...)")
    definition = {"name": name or _pretty(id), "color": str(color), "category": category,
                  "attributes": [{"attribute": a.attribute, "amount": a.amount, "operation": a.operation} for a in found]}
    if icon is not None:
        definition["icon"] = str(icon)
    _DECLS["effects"].append({"id": id, "definition": definition})
    return Content("%s:%s" % (_namespace(), id), "effect", id, definition["name"], definition)


def Fluid(id, name=None, color="#3fa7ff", density=1000, viscosity=1000, temperature=300, light=0, effects=(),
          still=None, flowing=None, bucket_texture=None, **extra) -> "Content":
    """Declares a liquid and returns it. ``Fluid("sap", ...).bucket`` is its bucket item.

    A real NeoForge fluid: it flows, you swim in it, buckets fill and empty it, tanks and pipes of other mods hold it.
    ``density`` (kg/m3; water 1000, lava 3000, negative floats up like gas) and ``viscosity`` (water 1000, lava 6000:
    higher flows and moves you slower) change how it behaves; ``temperature`` is in kelvin (water 300, lava 1300) and
    ``light`` is the light it gives (0..15). ``color`` tints the water textures; ``still`` / ``flowing`` name your own
    textures (assets/textures/) instead, ``bucket_texture`` the bucket's.
    ``effects=[("poison", 5), (rage, 8, 2)]`` are applied while an entity is in it: (effect, seconds, level).
    Use it in a FluidTank: ``FluidTank(16000, allowed=[sap])``."""
    _check_id(id)
    if extra:
        raise TypeError("Fluid(%r) has no attribute %s. Valid attributes: name, color, density, viscosity, temperature, "
                        "light, effects, still, flowing, bucket_texture" % (id, ", ".join(repr(k) for k in sorted(extra))))
    listed = list(effects or ())
    if len(listed) > 8:
        raise ValueError("Fluid effects: at most 8")
    specs = []
    for entry in listed:
        if isinstance(entry, (tuple, list)) and not isinstance(entry, Content):
            parts = list(entry)  # (effect, seconds, level)
            name_, seconds_, level_ = parts[0], parts[1] if len(parts) > 1 else 5, parts[2] if len(parts) > 2 else 1
        else:
            name_, seconds_, level_ = entry, 5, 1
        specs.append(_effect_spec((name_, level_, seconds_)))
    definition = {"name": name or _pretty(id), "color": str(color),
                  "density": _num("Fluid density", density, -20000, 20000, whole=True),
                  "viscosity": _num("Fluid viscosity", viscosity, 0, 20000, whole=True),
                  "temperature": _num("Fluid temperature", temperature, 0, 5000, whole=True),
                  "light": _num("Fluid light", light, 0, 15, whole=True),
                  "effects": specs}
    if still is not None:
        definition["still"] = _texture(still)
    if flowing is not None:
        definition["flowing"] = _texture(flowing)
    if bucket_texture is not None:
        definition["bucket"] = _texture(bucket_texture)
    _DECLS["fluids"].append({"id": id, "definition": definition})
    fluid = Content("%s:%s" % (_namespace(), id), "fluid", id, definition["name"], definition)
    fluid.bucket = Content("%s:%s_bucket" % (_namespace(), id), "item", id + "_bucket", definition["name"] + " Bucket", {})
    return fluid


def _ref_structure(name):
    """'ruins/house' is this project's; 'mod:path' stays."""
    name = str(name).strip()
    return name if ":" in name else "%s:%s" % (_namespace(), name)


_STEPS = ("raw_generation", "lakes", "local_modifications", "underground_structures", "surface_structures",
          "strongholds", "underground_ores", "underground_decoration", "fluid_springs", "vegetal_decoration",
          "top_layer_modification")
_TERRAIN = ("none", "beard_thin", "beard_box", "bury", "encapsulate")


def Pool(id, pieces, fallback=None, projection="rigid", processors="minecraft:empty"):
    """A jigsaw template pool: the pieces a jigsaw block named with this pool can grow into.

    pieces: ['ruins/hall', ('ruins/tower', 3), (None, 1)] - templates of data/structure/ with weights; None is an
    empty piece (a branch may stop). Jigsaw blocks in the .nbt files name pools like '<project>:<id>'."""
    _check_id(id)
    _choice("Pool projection", projection, ("rigid", "terrain_matching"))
    found = [pieces] if isinstance(pieces, (str, tuple)) else list(pieces)
    if not found:
        raise ValueError("Pool(%r) needs at least one piece" % id)
    elements = []
    for piece in found:
        name, weight = (piece if isinstance(piece, tuple) else (piece, 1))
        weight = _num("Pool weight", weight, 1, 150, whole=True)
        if name is None:
            element = {"element_type": "minecraft:empty_pool_element"}
        else:
            element = {"element_type": "minecraft:single_pool_element", "location": _ref_structure(name),
                       "processors": str(processors), "projection": projection}
        elements.append({"weight": weight, "element": element})
    raw_json("worldgen/template_pool/%s" % id, {"fallback": _ref_structure(fallback) if fallback else "minecraft:empty",
                                                "elements": elements})
    return Content("%s:%s" % (_namespace(), id), "pool", id, id, {})


def Structure(id, start, biomes="#minecraft:is_overworld", size=7, max_distance=80, spacing=32, separation=8,
              step="surface_structures", terrain="beard_thin", height=0, on_surface=True, start_jigsaw=None):
    """A jigsaw structure that generates in new chunks, like villages: it starts with a piece of the `start` Pool and
    grows `size` jigsaw steps (at most 20) within `max_distance` blocks (at most 128).

    One every `spacing` chunks on average, at least `separation` chunks apart. Minecraft reads world generation only
    when a world opens: a new or changed Structure appears after reopening the world, in chunks not generated yet.
    Try it at once with /place structure <project>:<id> (after reopening) or world.place_jigsaw(start, ...)."""
    _check_id(id)
    _choice("Structure step", step, _STEPS)
    _choice("Structure terrain", terrain, _TERRAIN)
    spacing = _num("Structure spacing", spacing, 2, 4096, whole=True)
    separation = _num("Structure separation", separation, 0, 4095, whole=True)
    if separation >= spacing:
        raise ValueError("Structure separation (%d) must be smaller than spacing (%d)" % (separation, spacing))
    names = [biomes] if isinstance(biomes, str) else list(biomes)
    structure = {"type": "minecraft:jigsaw", "biomes": names[0] if len(names) == 1 else names, "step": step,
                 "spawn_overrides": {}, "terrain_adaptation": terrain, "start_pool": _ref_structure(start),
                 "size": _num("Structure size", size, 0, 20, whole=True),
                 "start_height": {"absolute": _num("Structure height", height, -2048, 2048, whole=True)},
                 "max_distance_from_center": _num("Structure max_distance", max_distance, 1, 128, whole=True),
                 "use_expansion_hack": False}
    if on_surface:
        structure["project_start_to_heightmap"] = "WORLD_SURFACE_WG"
    if start_jigsaw:
        structure["start_jigsaw_name"] = str(start_jigsaw)
    full = "%s:%s" % (_namespace(), id)
    raw_json("worldgen/structure/%s" % id, structure)
    salt = sum(ord(c) * 31 ** i for i, c in enumerate(full)) % 2147483647  # the same structure, the same places
    raw_json("worldgen/structure_set/%s" % id, {"structures": [{"structure": full, "weight": 1}],
                                                "placement": {"type": "minecraft:random_spread", "spacing": spacing,
                                                              "separation": separation, "salt": salt}})
    return Content(full, "structure", id, id, {})


def _content_tags(tags, kind, full_id):
    """Item(..., tags=[...]) / Block(..., tags=[...]): one tag entry per tag; the pack merges entries of the same tag."""
    for name in ([tags] if isinstance(tags, str) else list(tags or ())):
        name = str(name).strip().lstrip("#")
        if not name:
            continue
        if not all(c.isalnum() or c in "_:./-" for c in name) or name.count(":") > 1:
            raise ValueError("tags: %r is not a tag id (like 'minecraft:swords' or 'gems')" % name)
        _DECLS["tags"].append({"id": name.lower(), "values": [full_id], "replace": False, "type": kind})


def _effect_spec(effect):
    """"id:amplifier:seconds" for the host. Always three parts, so a namespaced id ("irons_spellbooks:evasion") is never
    read as an id plus an amplifier; a plain name lasts 5 seconds at level 1."""
    if isinstance(effect, (tuple, list)):
        name, level, seconds = (list(effect) + [1, 10])[:3]
        return "%s:%d:%d" % (_ref_effect(name), max(0, int(level) - 1), int(seconds))
    return "%s:0:5" % _ref_effect(effect)


def _ref_effect(name):
    name = str(name).strip()
    if ":" in name:
        return name
    return "%s:%s" % (_namespace(), name) if _declared(name, ("effects",)) else "minecraft:" + name



# ---------------------------------------------------------------------------
# Extensions: behaviour you attach to an item or block.
#
#   sword = Item("ruby_sword", texture="ruby_sword.png").extends(Sword(tier="diamond"), Durability(1561))
#
#   @extension
#   class Vampiric(Extension):
#       def __init__(self, heal=2): self.heal = heal
#       def on_hit(self, event): event.attacker.heal(self.heal)
# ---------------------------------------------------------------------------

_HOOKS = {
    "item": {"on_use": "item_use", "on_hit": "item_hit", "on_mine": "item_mine", "on_hold_tick": "item_hold_tick"},
    "block": {"on_interact": "block_interact", "on_break": "block_break", "on_place": "block_place",
              "on_tick": "block_tick"},
}
_ALL_HOOKS = {name for hooks in _HOOKS.values() for name in hooks}

# tier: (mining level, mining speed, durability, damage bonus, enchantability)
_TIERS = {"wood": (0, 2.0, 59, 0.0, 15), "stone": (1, 4.0, 131, 1.0, 5), "iron": (2, 6.0, 250, 2.0, 14),
          "gold": (0, 12.0, 32, 0.0, 22), "diamond": (3, 8.0, 1561, 3.0, 10), "netherite": (4, 9.0, 2031, 4.0, 15)}
# kind: (base damage, attacks per second)
_TOOL_KINDS = {"sword": (3.0, 1.6), "axe": (5.0, 1.0), "pickaxe": (1.0, 1.2), "shovel": (1.5, 1.0), "hoe": (0.0, 2.0)}
_SLOTS = ("head", "chest", "legs", "feet")
_MINEABLE_TOOLS = ("pickaxe", "axe", "shovel", "hoe")
_PISTON_REACTIONS = ("normal", "destroy", "block", "ignore", "push_only")
_BLOCK_SOUNDS = ("stone", "wood", "grass", "glass", "gravel", "sand", "wool", "metal", "anvil", "snow")
_SMELTERS = ("furnace", "blast_furnace", "smoker", "campfire")
_COLORS = ("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray",
           "blue", "green", "aqua", "red", "light_purple", "yellow", "white")
_ITEM_ATTRS = ("name", "texture", "stack", "glow", "food", "fuel", "effect", "tags")
_BLOCK_ATTRS = ("name", "texture", "hardness", "resistance", "light", "tool", "needs_tool", "sound", "drops", "tags",
                "model", "shape")


def _reject_unknown(what, ident, extra, extensions):
    if extra:
        valid = _ITEM_ATTRS if what == "Item" else _BLOCK_ATTRS
        raise TypeError("%s(%r) has no attribute %s. Valid attributes: %s, extensions. Behaviour such as tools, "
                        "armor or redstone is added with .extends(...)."
                        % (what, ident, ", ".join(repr(k) for k in sorted(extra)), ", ".join(valid)))


def _num(name, value, low, high, whole=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError("%s must be a number, got %r" % (name, value))
    if whole and value != int(value):
        raise ValueError("%s must be a whole number, got %r" % (name, value))
    if not low <= value <= high:
        raise ValueError("%s must be between %s and %s, got %r" % (name, low, high, value))
    return int(value) if whole else float(value)


def _choice(name, value, options):
    if value not in options:
        raise ValueError("%s must be one of %s, got %r" % (name, ", ".join(repr(o) for o in options), value))
    return value


class Extension:
    """Base class of every extension - the built-in ones (Sword, Durability, Redstone...) and your own.

    Write your own with ``@extension``; methods named like a hook run for the item/block it is attached to:

    * item:  ``on_use(event)``, ``on_hit(event)``, ``on_mine(event)``, ``on_hold_tick(event)``
    * block: ``on_interact(event)``, ``on_break(event)``, ``on_place(event)``, ``on_tick(event)``

    ``self`` is your extension object, so constructor arguments are its settings. Override
    ``configure(self, target)`` to change the item/block itself (``target.set("glow", True)``).
    """
    applies_to = ("item", "block")

    def apply(self, target):
        for hook in dir(self):
            if hook.startswith("on_") and hook not in _HOOKS[target.kind] and hook not in ("on_load", "on_unload"):
                raise TypeError("%s.%s is not a hook of a %s. Hooks: %s"
                                % (type(self).__name__, hook, target.kind, ", ".join(_HOOKS[target.kind])))
        for hook, event in _HOOKS[target.kind].items():
            fn = getattr(self, hook, None)
            if callable(fn):
                _DECLS["handlers"].append({"event": event, "every": None, "priority": "normal"})
                _HANDLERS.setdefault(event, []).append((fn, {target.kind: str(target)}))
        self.configure(target)

    def configure(self, target):
        """Change the target here. Nothing to do by default."""


# name of an @extension class -> (owner that declared it, the class). Completion offers them, and every project can use
# them by name (`from craftpy import *` brings them in), so a module can add extensions for other projects.
_USER_EXTENSIONS = {}


def extension(cls):
    """Marks your class as an extension: ``@extension class Vampiric(Extension): ...``."""
    if not isinstance(cls, type):
        raise TypeError("@extension goes on a class")
    if not issubclass(cls, Extension):
        cls = type(cls.__name__, (cls, Extension), {})
    _USER_EXTENSIONS[cls.__name__] = (_OWNER[0], cls)
    for name in dir(cls):
        if name.startswith("on_") and name not in _ALL_HOOKS and name not in ("on_load", "on_unload"):
            raise TypeError("%s.%s is not a hook. Hooks: %s" % (cls.__name__, name, ", ".join(sorted(_ALL_HOOKS))))
    return cls


class _Data(Extension):
    """A built-in extension: validated settings that the game applies itself."""
    type = ""

    def data(self):
        return {}

    def configure(self, target):
        target.add_ext(dict(self.data(), type=self.type))


class Tool(_Data):
    """Makes an item mine blocks and hit harder, like a vanilla tool.

    kind: "pickaxe", "axe", "shovel", "hoe" or "sword". tier: "wood", "stone", "iron", "gold", "diamond" or
    "netherite" (sets mining level, speed, durability and damage defaults). speed: mining speed, default from the
    tier. damage: damage per hit as shown in the tooltip (a diamond sword is 7). attack_speed: hits per second."""
    applies_to = ("item",)
    type = "tool"

    def __init__(self, kind, tier="iron", speed=None, damage=None, attack_speed=None):
        self.kind = _choice("Tool kind", kind, tuple(_TOOL_KINDS))
        self.tier = _choice("Tool tier", tier, tuple(_TIERS))
        level, tier_speed, _uses, bonus, _ench = _TIERS[self.tier]
        base, hits = _TOOL_KINDS[self.kind]
        self.level = level
        self.speed = _num("Tool speed", tier_speed if speed is None else speed, 0.1, 100.0)
        self.damage = _num("Tool damage", 1.0 + base + bonus if damage is None else damage, 0.0, 1000.0)
        self.attack_speed = _num("Tool attack_speed", hits if attack_speed is None else attack_speed, 0.1, 100.0)

    def data(self):
        return {"kind": self.kind, "level": self.level, "speed": self.speed, "damage": self.damage,
                "attack_speed": self.attack_speed}

    def configure(self, target):
        super().configure(target)
        _, _, uses, _, enchant = _TIERS[self.tier]
        if target._definition.get("max_stack") == 64:
            target.set("max_stack", 1)
        if not target.has_ext("durability"):
            target.add_ext({"type": "durability", "uses": uses})
        if not target.has_ext("enchantable"):
            target.add_ext({"type": "enchantable", "value": enchant})


class Sword(Tool):
    """A sword: ``Sword(tier="diamond")``. See Tool for tier, speed, damage and attack_speed."""

    def __init__(self, tier="iron", speed=None, damage=None, attack_speed=None):
        super().__init__("sword", tier, speed, damage, attack_speed)


class Pickaxe(Tool):
    """A pickaxe: ``Pickaxe(tier="iron")``. See Tool for tier, speed, damage and attack_speed."""

    def __init__(self, tier="iron", speed=None, damage=None, attack_speed=None):
        super().__init__("pickaxe", tier, speed, damage, attack_speed)


class Axe(Tool):
    """An axe: ``Axe(tier="iron")``. See Tool for tier, speed, damage and attack_speed."""

    def __init__(self, tier="iron", speed=None, damage=None, attack_speed=None):
        super().__init__("axe", tier, speed, damage, attack_speed)


class Shovel(Tool):
    """A shovel: ``Shovel(tier="iron")``. See Tool for tier, speed, damage and attack_speed."""

    def __init__(self, tier="iron", speed=None, damage=None, attack_speed=None):
        super().__init__("shovel", tier, speed, damage, attack_speed)


class Hoe(Tool):
    """A hoe: ``Hoe(tier="iron")``. See Tool for tier, speed, damage and attack_speed."""

    def __init__(self, tier="iron", speed=None, damage=None, attack_speed=None):
        super().__init__("hoe", tier, speed, damage, attack_speed)


class Durability(_Data):
    """Gives an item a durability bar: it loses 1 per block mined or entity hit and breaks at 0.

    uses: how many uses (1 to 32767). repair_with: item that repairs it in an anvil (optional)."""
    applies_to = ("item",)
    type = "durability"

    def __init__(self, uses=250, repair_with=None):
        self.uses = _num("Durability uses", uses, 1, 32767, whole=True)
        self.repair_with = repair_with

    def configure(self, target):
        entry = {"type": self.type, "uses": self.uses}
        if self.repair_with is not None:
            entry["repair_with"] = _ref(self.repair_with)
        target.add_ext(entry)
        if target._definition.get("max_stack") == 64:
            target.set("max_stack", 1)


class Weapon(_Data):
    """Sets attack values on any item without making it a tool.

    damage: damage per hit as shown in the tooltip (1 is a fist). attack_speed: hits per second.
    knockback: extra knockback (0 to 5)."""
    applies_to = ("item",)
    type = "weapon"

    def __init__(self, damage=4.0, attack_speed=1.6, knockback=0.0):
        self.damage = _num("Weapon damage", damage, 0.0, 1000.0)
        self.attack_speed = _num("Weapon attack_speed", attack_speed, 0.1, 100.0)
        self.knockback = _num("Weapon knockback", knockback, 0.0, 5.0)

    def data(self):
        return {"damage": self.damage, "attack_speed": self.attack_speed, "knockback": self.knockback}


class Fuel(Extension):
    """Lets a furnace burn the item. seconds: burn time in seconds (1 to 100000); 200 smelts 20 items."""
    applies_to = ("item",)

    def __init__(self, seconds):
        self.seconds = _num("Fuel seconds", seconds, 1, 100000, whole=True)

    def configure(self, target):
        target.set("burn_time", self.seconds)


class Armor(_Data):
    """Makes an item wearable in an armor slot and adds armor values.

    slot: "head", "chest", "legs" or "feet". defense: armor points (0 to 30). toughness: 0 to 20.
    knockback_resistance: 0 to 1. (No worn model is drawn; the item is worn invisibly.)"""
    applies_to = ("item",)
    type = "armor"

    def __init__(self, slot="chest", defense=3, toughness=0.0, knockback_resistance=0.0):
        self.slot = _choice("Armor slot", slot, _SLOTS)
        self.defense = _num("Armor defense", defense, 0, 30)
        self.toughness = _num("Armor toughness", toughness, 0, 20)
        self.knockback_resistance = _num("Armor knockback_resistance", knockback_resistance, 0, 1)

    def data(self):
        return {"slot": self.slot, "defense": self.defense, "toughness": self.toughness,
                "knockback_resistance": self.knockback_resistance}

    def configure(self, target):
        super().configure(target)
        if target._definition.get("max_stack") == 64:
            target.set("max_stack", 1)


class Enchantable(_Data):
    """How good the enchantments from an enchanting table are. value: 0 (none) to 50 (gold armor is 25)."""
    applies_to = ("item",)
    type = "enchantable"

    def __init__(self, value=15):
        self.value = _num("Enchantable value", value, 0, 50, whole=True)

    def data(self):
        return {"value": self.value}


class Cooldown(_Data):
    """Right click can only be used again after this many seconds (0.05 to 3600), shown as a bar over the item."""
    applies_to = ("item",)
    type = "cooldown"

    def __init__(self, seconds=1.0):
        self.seconds = _num("Cooldown seconds", seconds, 0.05, 3600.0)

    def data(self):
        return {"seconds": self.seconds}


class Tooltip(_Data):
    """Extra lines in the item tooltip. lines: up to 10 texts of at most 120 characters.
    color: one of the 16 Minecraft colors ("gray", "gold", "aqua", "red"...)."""
    applies_to = ("item",)
    type = "tooltip"

    def __init__(self, *lines, color="gray"):
        if not 1 <= len(lines) <= 10 or any(not isinstance(l, str) or len(l) > 120 for l in lines):
            raise ValueError("Tooltip takes 1 to 10 texts of at most 120 characters")
        self.lines = list(lines)
        self.color = _choice("Tooltip color", color, _COLORS)

    def data(self):
        return {"lines": self.lines, "color": self.color}


class MineableBy(Extension):
    """Which tool mines a block. tool: "pickaxe", "axe", "shovel" or "hoe". tier: lowest tool tier that works
    ("wood" to "netherite", only checked for pickaxe-style mining; None = any). required: True (default) means
    nothing drops without the right tool, like stone and ores."""
    applies_to = ("block",)

    def __init__(self, tool="pickaxe", tier=None, required=True):
        self.tool = _choice("MineableBy tool", tool, _MINEABLE_TOOLS)
        self.tier = None if tier is None else _choice("MineableBy tier", tier, tuple(_TIERS))
        self.required = bool(required)

    def configure(self, target):
        target.set("tool", self.tool)
        target.set("needs_tool", self.required)
        target.add_ext({"type": "mineable", "level": 0 if self.tier is None else _TIERS[self.tier][0]})


class Drops(Extension):
    """What a block drops when mined: ``Drops(ruby, (coal, 2), "self")``. Each drop is an item, a
    ``(item, count)`` or ``(item, count, weight)`` tuple, or "self"; no drops at all means nothing drops."""
    applies_to = ("block",)

    def __init__(self, *drops):
        self.drops = list(drops)

    def configure(self, target):
        _DECLS["loot_tables"][:] = [t for t in _DECLS["loot_tables"] if t.get("id") != target.local_id]
        _block_drops(target.local_id, self.drops)


class Redstone(_Data):
    """Makes a block emit a redstone signal to its neighbours. power: 0 to 15."""
    applies_to = ("block",)
    type = "redstone"

    def __init__(self, power=15):
        self.power = _num("Redstone power", power, 0, 15, whole=True)

    def data(self):
        return {"power": self.power}


class Flammable(_Data):
    """Lets fire burn the block. chance: how easily it catches fire (0 to 300, wood is 5, leaves 30).
    spread: how fast fire spreads over it (0 to 300, wood 20, leaves 60)."""
    applies_to = ("block",)
    type = "flammable"

    def __init__(self, chance=5, spread=20):
        self.chance = _num("Flammable chance", chance, 0, 300, whole=True)
        self.spread = _num("Flammable spread", spread, 0, 300, whole=True)

    def data(self):
        return {"chance": self.chance, "spread": self.spread}


class Friction(_Data):
    """How slippery the block is to walk on. value: 0.1 to 1.0 (normal 0.6, ice 0.98)."""
    applies_to = ("block",)
    type = "friction"

    def __init__(self, value=0.98):
        self.value = _num("Friction value", value, 0.1, 1.0)

    def data(self):
        return {"value": self.value}


class Piston(_Data):
    """What pistons do to the block. reaction: "normal" (pushed), "destroy" (broken), "block" (cannot move),
    "ignore" (passes through) or "push_only" (pushed but not pulled)."""
    applies_to = ("block",)
    type = "piston"

    def __init__(self, reaction="normal"):
        self.reaction = _choice("Piston reaction", reaction, _PISTON_REACTIONS)

    def data(self):
        return {"reaction": self.reaction}


class _TypeBase(Extension):
    """Class form: ``class RubySword(ItemType): id = "ruby_sword"; extensions = [Sword("diamond")]``.
    Methods named like hooks (on_use...) work directly; the class can be used wherever an id is expected."""
    kind = ""
    attrs = ()
    content = None

    def __init_subclass__(cls, **kw):
        super().__init_subclass__(**kw)
        ident = cls.__dict__.get("id")
        if ident is None:
            return
        fields = {}
        for key, value in cls.__dict__.items():
            if key.startswith("_") or key in ("id", "extensions") or callable(value) \
                    or isinstance(value, (staticmethod, classmethod, property)):
                continue
            if key not in cls.attrs:
                raise TypeError("%s: unknown attribute %r. Valid: id, %s, extensions"
                                % (cls.__name__, key, ", ".join(cls.attrs)))
            fields[key] = value
        build = Item if cls.kind == "item" else Block
        cls.content = build(ident, **fields)
        cls.content.extends(cls(), *cls.__dict__.get("extensions", ()))


class ItemType(_TypeBase):
    """Declare an item as a class (see Item for the attributes)."""
    kind = "item"
    attrs = _ITEM_ATTRS
    applies_to = ("item",)


class BlockType(_TypeBase):
    """Declare a block as a class (see Block for the attributes)."""
    kind = "block"
    attrs = _BLOCK_ATTRS
    applies_to = ("block",)


def _components(value):
    """Data components every stack of the item gets (any mod's: Apotheosis affixes, Curios modifiers...): {id: value}."""
    import re
    if not isinstance(value, dict) or len(value) > 32:
        raise ValueError("components must be a dict of at most 32 {'mod:component': value}")
    out = {}
    for key, data in value.items():
        if not isinstance(key, str) or not re.fullmatch(r"!?[a-z0-9_.-]+:[a-z0-9_./-]+", key):
            raise ValueError("component %r: write it as 'mod:name' (a leading ! removes a component)" % (key,))
        out[key] = data
    if len(json.dumps(out)) > 32768:
        raise ValueError("components are larger than 32 KB")
    return out


class Attributes(_Data):
    """Adds attribute modifiers to an item while it is held or worn: any attribute of the game or of a mod.

    attribute: id such as "minecraft:generic.max_health" or "irons_spellbooks:fire_spell_power" (the IDE completes them).
    amount: the number to add. operation: "add" (default), "multiply_base" (+ amount x base value) or "multiply_total"
    (x (1 + amount) at the end). slot: "mainhand", "offhand", "head", "chest", "legs", "feet", "hand", "armor", "body",
    "any", or a Curios slot: "curios:ring", "curios:necklace"... ("curios" = any curio slot). Use several
    ``Attributes(...)`` for several modifiers."""
    applies_to = ("item",)
    type = "attributes"
    _OPERATIONS = {"add": "add_value", "add_value": "add_value", "multiply_base": "add_multiplied_base",
                   "add_multiplied_base": "add_multiplied_base", "multiply_total": "add_multiplied_total",
                   "add_multiplied_total": "add_multiplied_total"}
    _SLOTS = ("any", "mainhand", "offhand", "hand", "head", "chest", "legs", "feet", "armor", "body")

    def __init__(self, attribute, amount, operation="add", slot="mainhand"):
        if not isinstance(attribute, str) or not attribute:
            raise ValueError("Attributes needs the attribute id, e.g. Attributes('minecraft:generic.max_health', 4)")
        self.attribute = attribute if ":" in attribute else "minecraft:" + attribute
        self.amount = _num("Attributes amount", amount, -1000000.0, 1000000.0)
        self.operation = self._OPERATIONS[_choice("Attributes operation", operation, tuple(self._OPERATIONS))]
        import re
        if isinstance(slot, str) and re.fullmatch(r"curios(:[a-z0-9_]+)?", slot):
            self.slot = slot  # worn in a Curios slot (needs the Curios mod)
        else:
            self.slot = _choice("Attributes slot", slot, self._SLOTS + ("curios", "curios:ring"))

    def data(self):
        return {"attribute": self.attribute, "amount": self.amount, "operation": self.operation, "slot": self.slot}


class Kinetic(_Data):
    """A native CraftPy machine with an optional Create rotation adapter.

    Roles: generator supplies speed/capacity, shaft transmits, processor consumes stress and runs
    craftpy:kinetic_processing recipes. Stress capacity/impact are per RPM. Axis affects new placements;
    The machinery wrench rotates existing machines. Inventory and processing stay native, including on reload.
    rotating_model is a baked block model resource ID, aligned along Y before the axis transform.
    """
    applies_to = ("block",)
    type = "kinetic"

    def __init__(self, role="shaft", *, axis="y", speed=64, capacity=32, impact=None,
                 rotating_model="craftpy:block/machine_rotor"):
        self.role = _choice("Kinetic role", role, ("generator", "shaft", "processor"))
        self.axis = _choice("Kinetic axis", axis, ("x", "y", "z"))
        self.speed = _num("Kinetic speed", speed, -256, 256)
        self.capacity = _num("Kinetic capacity", capacity, 0, 65536)
        self.impact = _num("Kinetic impact", (8 if role == "processor" else 0) if impact is None else impact, 0, 65536)
        import re
        if not isinstance(rotating_model, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", rotating_model):
            raise ValueError("Kinetic rotating_model needs a model resource ID")
        self.rotating_model = rotating_model

    def data(self):
        return dict(role=self.role, axis=self.axis, speed=self.speed, capacity=self.capacity,
                    impact=self.impact, rotating_model=self.rotating_model)


class EnergyStorage(_Data):
    """Native NeoForge energy storage in FE. No implicit rotation-to-energy conversion."""
    applies_to = ("block",)
    type = "energy_storage"

    def __init__(self, capacity, max_receive=None, max_extract=None):
        self.capacity = _integer("EnergyStorage capacity", capacity, 1, 1_000_000_000)
        self.max_receive = _integer("EnergyStorage max_receive", capacity if max_receive is None else max_receive, 0, 1_000_000_000)
        self.max_extract = _integer("EnergyStorage max_extract", capacity if max_extract is None else max_extract, 0, 1_000_000_000)

    def data(self):
        return dict(capacity=self.capacity, max_receive=self.max_receive, max_extract=self.max_extract)


class FluidTank(_Data):
    """Native NeoForge tank in mB; allowed is an optional list of fluid resource IDs."""
    applies_to = ("block",)
    type = "fluid_tank"

    def __init__(self, capacity, allowed=None):
        self.capacity = _integer("FluidTank capacity", capacity, 1, 1_000_000)
        if allowed is not None and (not isinstance(allowed, (list, tuple)) or len(allowed) > 64):
            raise ValueError("FluidTank allowed must be a list of at most 64 fluid IDs")
        self.allowed = None if allowed is None else [_resource_id("FluidTank allowed", value) for value in allowed]

    def data(self):
        return dict(capacity=self.capacity, allowed=self.allowed)


class Inventory(_Data):
    """The item slots of a machine block, in named groups: ``Inventory(input=1, fuel=1, output=2, storage=9)``.

    A group's name is its role. ``input``: hands and hoppers put items in (a processing machine reads its recipe from the
    first one). ``fuel``: items go in from the sides only. ``output``: items only come out (by hand, or by hoppers from the
    bottom and the sides). Any other name is ``storage``: both ways, from every side. At most 54 slots.

    ``accepts={"fuel": ["minecraft:coal", "#minecraft:logs"]}`` limits what a group takes (item ids and ``#tags``).
    ``max_stack={"input": 16}`` limits the stack size in a group. ``sides={"output": ["down", "north"]}`` replaces the
    sides hoppers and pipes may use for a group. The items are saved with the block and drop when it is broken. Python reads
    them in ``world.machine(...)["inventory"]`` and changes them with ``world.machine_slot``; the window
    (``Screen``) shows them, and shift-click moves items between your inventory and the first slots that take them."""
    applies_to = ("block",)
    type = "inventory"
    _OPTIONS = ("accepts", "max_stack", "sides")

    def __init__(self, *, accepts=None, max_stack=None, sides=None, **groups):
        import re
        if not groups:
            raise ValueError("Inventory needs at least one group, for example Inventory(input=1, output=1)")
        total = 0
        self.groups = []
        for name, count in groups.items():
            if not re.fullmatch(r"[a-z][a-z0-9_]{0,24}", name):
                raise ValueError("Inventory group names are lowercase words such as input, fuel, output, storage: %r" % name)
            count = _integer("Inventory %s" % name, count, 1, 54)
            total += count
            self.groups.append({"name": name, "count": count})
        if total > 54:
            raise ValueError("Inventory has %d slots; a block can have at most 54" % total)
        by_name = {g["name"]: g for g in self.groups}
        for option, values in (("accepts", accepts), ("max_stack", max_stack), ("sides", sides)):
            for group_name, value in (values or {}).items():
                if group_name not in by_name:
                    raise ValueError("Inventory %s names the group %r, which Inventory(...) does not have (groups: %s)"
                                     % (option, group_name, ", ".join(by_name)))
                if option == "accepts":
                    if isinstance(value, str):
                        value = [value]
                    by_name[group_name]["accepts"] = [str(_ref(v)) for v in value]
                elif option == "max_stack":
                    by_name[group_name]["max_stack"] = _integer("Inventory max_stack", value, 1, 64)
                else:
                    value = [value] if isinstance(value, str) else list(value)
                    for face in value:
                        _choice("Inventory side", face, ("up", "down", "north", "south", "east", "west"))
                    by_name[group_name]["sides"] = value

    def data(self):
        return dict(groups=self.groups)


class Screen(_Data):
    """A Minecraft-style window for a machine block: right click opens it (sneak-click places a block instead).

    It shows the block's two item slots (the input takes a filled bucket or tank item and empties it into the block's
    FluidTank, the empty container comes out in the second slot), a fluid gauge when the block has a FluidTank, an energy
    bar on the right when it has EnergyStorage, and your inventory. ``title`` is the text at the top.

    ``layout="my_window"`` uses a window you drew in the UI maker (ui/my_window.json with "Legacy (Minecraft look)" ticked):
    panels, slots, the player inventory, bars, arrows, flames, labels and icons at the positions you gave them."""
    applies_to = ("block",)
    type = "screen"

    def __init__(self, title=None, *, energy=True, fluid=True, slots=True, layout=None):
        if title is not None and (not isinstance(title, str) or len(title) > 64):
            raise ValueError("Screen title must be text of at most 64 characters")
        self.title = title
        self.energy = bool(energy)
        self.fluid = bool(fluid)
        self.slots = bool(slots)
        self.layout = None
        if layout is not None:
            tree = _UI_FILES.get((_OWNER[0], str(layout)))
            if tree is None:
                raise ValueError("Screen layout %r: there is no ui/%s.json in this project" % (layout, layout))
            if not tree.get("legacy"):
                raise ValueError("Screen layout %r is not a Legacy (Minecraft look) window: tick that box in the UI maker" % layout)
            self.layout = {"size": tree.get("size") or [176, 166], "root": tree["root"]}
            if len(json.dumps(self.layout)) > 24000:
                raise ValueError("Screen layout %r is too large (at most 64 pieces)" % layout)

    def data(self):
        out = dict(title=self.title, energy=self.energy, fluid=self.fluid, slots=self.slots)
        if self.layout is not None:
            out["layout"] = self.layout
        return out


def _integer(label, value, low, high):
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise ValueError(f"{label} must be an integer from {low} to {high}")
    return value


def _resource_id(label, value):
    import re
    if not isinstance(value, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", value):
        raise ValueError(label + " needs a namespaced resource ID")
    return value


_EXTENSION_NAMES = {
    "Extension": Extension, "extension": extension, "ItemType": ItemType, "BlockType": BlockType,
    "Tool": Tool, "Sword": Sword, "Pickaxe": Pickaxe, "Axe": Axe, "Shovel": Shovel, "Hoe": Hoe,
    "Durability": Durability, "Weapon": Weapon, "Fuel": Fuel, "Armor": Armor, "Enchantable": Enchantable,
    "Cooldown": Cooldown, "Tooltip": Tooltip, "MineableBy": MineableBy, "Drops": Drops, "Redstone": Redstone,
    "Flammable": Flammable, "Friction": Friction, "Piston": Piston, "Attributes": Attributes, "Kinetic": Kinetic, "EnergyStorage": EnergyStorage, "FluidTank": FluidTank, "Screen": Screen, "Inventory": Inventory,
}


# ---------------------------------------------------------------------------
# Content: the original decorator style (still fully supported)
#
#   @block("ruby_ore", name="Ruby Ore", hardness=4)
#   class RubyOre: pass
# ---------------------------------------------------------------------------

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


def raw_json(path, data, namespace=None):
    """Escape hatch: write a raw datapack JSON file (flagged version-specific).

    It goes to data/<your project>/<path>; with namespace="minecraft" (or any other) to data/<namespace>/<path>,
    for example a tag that another mod reads."""
    entry = {"id": path, "definition": data}
    if namespace:
        entry["namespace"] = str(namespace)
    _DECLS["raw"].append(entry)


def tag(ident, values, replace=False, type="item"):
    _DECLS["tags"].append({"id": ident, "values": [_ref(v) for v in values],
                           "replace": replace, "type": type})


# -- recipes -----------------------------------------------------------------

def _recipe(kind, **payload):
    _DECLS["recipes"].append(dict(payload, type=kind))


def shaped(result, *rows, count=1, **keys):
    """shaped(sword, " R ", " R ", " S ", R=ruby, S="stick")"""
    if len(rows) == 1 and isinstance(rows[0], (list, tuple)):
        rows = tuple(rows[0])
    if not rows:
        raise ValueError("shaped() needs at least one pattern row, e.g. shaped(result, 'RR', R=ruby)")
    used = {ch for row in rows for ch in row if ch != " "}
    missing = used - set(keys)
    if missing:
        raise ValueError("shaped(): no ingredient given for %s" % ", ".join(sorted(missing)))
    _recipe("shaped", result=_ref(result), count=int(count), pattern=list(rows),
            key={k: _ref(v) for k, v in keys.items()}, category="misc",
            id="%s_shaped" % _ref(result).split(":")[-1])


def shapeless(result, *ingredients, count=1):
    """shapeless(ruby, "diamond", "redstone")"""
    if len(ingredients) == 1 and isinstance(ingredients[0], (list, tuple)):
        ingredients = tuple(ingredients[0])
    _recipe("shapeless", result=_ref(result), count=int(count),
            ingredients=[_ref(i) for i in ingredients], category="misc",
            id="%s_shapeless" % _ref(result).split(":")[-1])


def smelt(ingredient, result, xp=0.1, seconds=10, using="furnace"):
    """smelt(ruby_ore, ruby) - using: "furnace", "blast_furnace", "smoker" or "campfire"."""
    kinds = dict(zip(_SMELTERS, ("smelting", "blasting", "smoking", "campfire")))
    if using not in kinds:
        raise ValueError("smelt(using=...) must be one of %s" % ", ".join(kinds))
    _recipe(kinds[using], result=_ref(result), ingredient=_ref(ingredient), xp=float(xp),
            cook_time=max(1, int(seconds * 20)),
            id="%s_from_%s" % (_ref(result).split(":")[-1], using))


def stonecut(ingredient, result, count=1):
    _recipe("stonecutting", result=_ref(result), ingredient=_ref(ingredient), count=int(count),
            id="%s_stonecutting" % _ref(result).split(":")[-1])


def recipe(type, id=None, **fields):
    """recipe("create:mixing", ingredients=[...], results=[...]) - a recipe of another mod's type.

    The fields are that mod's own recipe JSON; the IDE completes the type and lists its fields. Your own items and blocks
    can be named like everywhere else. The recipe only loads when the mod is installed (it carries a mod_loaded condition)."""
    if not isinstance(type, str) or ":" not in type:
        raise ValueError("recipe() needs a 'mod:type' such as 'create:mixing'; use shaped, shapeless, smelt or stonecut for vanilla recipes")
    payload = dict(fields, type=type)
    if id:
        payload["id"] = str(id)
    _DECLS["recipes"].append(payload)


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
    return dict(kwargs, item=_ref(item) if isinstance(item, Content) else item, count=count, weight=weight)


# -- events ------------------------------------------------------------------

_FILTER_KEYS = ("block", "item", "entity", "effect")
_EVENT_LAST = {}      # (owner, event, function, entity/player UUID) -> last native tick, bounded


_EVENT_NAMES = ("block_break", "block_place", "block_interact", "block_tick", "item_use", "item_hit", "item_mine",
                "item_hold_tick", "item_craft", "item_pickup", "player_join", "player_leave", "player_respawn",
                "player_death", "entity_death", "entity_hurt", "entity_tick", "chat", "effect_tick", "machine_speed", "machine_state")


class _On:
    """``@on("block_break")``, ``@on("block_break", block=ruby_ore)`` or ``@on.block_break(block=ruby_ore)``."""

    def __call__(self, event, every=None, priority="normal", throttle_ticks=0, **filters):
        throttle_ticks = _num("Event throttle_ticks", throttle_ticks, 0, 12000, whole=True)
        unknown = set(filters) - set(_FILTER_KEYS)
        if unknown:
            raise TypeError("@on(%r): unknown filter %s (use %s)"
                            % (event, ", ".join(sorted(unknown)), ", ".join(_FILTER_KEYS)))
        wanted = {key: _ref(value) for key, value in filters.items()}

        def deco(fn):
            periods = dict(getattr(fn, "_craftpy_throttle_ticks", {}))
            periods[(event, tuple(sorted(wanted.items())))] = throttle_ticks
            fn._craftpy_throttle_ticks = periods
            _DECLS["handlers"].append({"event": event, "every": every, "priority": priority, "filters": wanted,
                                        "throttle_ticks": throttle_ticks})
            _HANDLERS.setdefault(event, []).append((fn, wanted))
            return fn
        return deco

    def __dir__(self):
        return list(_EVENT_NAMES) + list(_CLIENT_EVENT_NAMES)  # the IDE completes `on.` from this

    def __getattr__(self, event):
        if event.startswith("_"):
            raise AttributeError(event)

        def factory(fn=None, **filters):
            if callable(fn):  # used bare: @on.player_join
                return self(event)(fn)
            return self(event, **filters)
        return factory


on = _On()


def _matches(ctx, wanted):
    for key, value in wanted.items():
        actual = getattr(ctx, key, None)
        actual_id = actual.get("id") if isinstance(actual, dict) else getattr(actual, "id", None)
        if key == "entity" and actual is not None:
            actual_id = getattr(actual, "type", None)
        if actual_id != value:
            return False
    return True


def command(name, **params):
    """Registers ``/<project>:<name>``. Arguments come from the function signature:

        @command("heal")
        def heal(ctx, target: Player, amount: int): ...

    Supported annotations: int, float, bool, str, Player. Explicit ``**params`` also work.
    """
    def deco(fn):
        declared = dict(params)
        converters = {}
        if not declared:
            signature = inspect.signature(fn)
            for index, (pname, parameter) in enumerate(signature.parameters.items()):
                if index == 0:
                    continue  # ctx
                annotation = parameter.annotation
                declared[pname] = {int: "int", float: "float", bool: "bool", Player: "player"}.get(annotation, "str")
                if annotation is Player:
                    converters[pname] = Player.named
        _DECLS["commands"].append({"name": name, "params": declared})
        uses_signature = not params and len(inspect.signature(fn).parameters) > 1

        def handler(ctx):
            if not uses_signature:
                return fn(ctx)
            raw = getattr(ctx, "args", None)
            args = dict(raw) if isinstance(raw, dict) else dict(getattr(raw, "__dict__", {}) or {})
            for key, convert in converters.items():
                if key in args:
                    args[key] = convert(args[key])
            return fn(ctx, **args)
        _HANDLERS.setdefault("command_" + name, []).append((handler, {}))
        return fn
    return deco


def block_logic(ident):
    def deco(cls):
        # Block behaviour lives in @on handlers; kept for source compatibility.
        return cls
    return deco


def every(ticks=None, seconds=None):
    """``@every(seconds=5)`` / ``@every(100)`` - runs the function on a repeating timer."""
    if callable(ticks):
        raise TypeError("use @every(seconds=...) or @every(ticks), with parentheses")
    interval = int(seconds * 20) if seconds is not None else int(ticks or 20)

    def deco(fn):
        _schedule(max(1, interval), fn, repeat=True)
        return fn
    return deco


def on_load(fn):
    _HANDLERS.setdefault("load", []).append((fn, {}))
    return fn


def on_unload(fn):
    _HANDLERS.setdefault("unload", []).append((fn, {}))
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


def after(seconds, fn=None):
    """``after(3, fn)`` runs fn in 3 seconds; ``@after(3)`` works as a decorator too."""
    if fn is not None:
        return run_later(max(1, int(seconds * 20)), fn)
    return lambda f: (run_later(max(1, int(seconds * 20)), f), f)[1]


def _schedule(ticks, fn, repeat):
    task = Task(int(ticks), fn, repeat)
    _SCHEDULE.append({"task": task, "next": _TICK[0] + max(1, int(ticks)), "owner": _OWNER[0]})
    return task


def _on_tick(params):
    _TICK[0] = int(params.get("tick", _TICK[0] + 1))
    log = []
    for entry in list(_SCHEDULE):
        task = entry["task"]
        if task.cancelled:
            if entry in _SCHEDULE:
                _SCHEDULE.remove(entry)
            continue
        if _TICK[0] >= entry["next"]:
            _use_owner(entry["owner"])
            _LOG_TARGET[0] = log
            started = time.perf_counter()
            failed = False
            try:
                _call_flexible(task.fn, _TickContext(_TICK[0]))
            except Exception as exc:  # noqa: BLE001
                failed = True
                log.append("[%s] timer error: %s: %s" % (entry["owner"], type(exc).__name__, exc))
            finally:
                _LOG_TARGET[0] = None
                _profile_add(entry["owner"], "timer:" + getattr(task.fn, "__name__", "?"),
                             time.perf_counter() - started, failed)
            if task.repeat and not task.cancelled:
                entry["next"] = _TICK[0] + max(1, task.interval)
            elif entry in _SCHEDULE:
                _SCHEDULE.remove(entry)
    if _client_mode() and any(state["handlers"].get("client_tick") for state in _OWNERS.values()):
        ctx = _EventContext(log)
        ctx.event = "client_tick"
        ctx.tick = _TICK[0]
        _LOG_TARGET[0] = log
        try:
            _dispatch_event("client_tick", ctx, log, [])
        finally:
            _LOG_TARGET[0] = None
    return {"log": log, "scheduled": len(_SCHEDULE)}


# ---------------------------------------------------------------------------
# Debugger. Breakpoints never pause: the worker is single-threaded and the game waits for it, so stopping would freeze
# the server. A line with a breakpoint records the call stack and the variables instead (a "snapshot"), and the IDE's
# Debug tool window shows them. Breakpoints are per owner (project) and file; lines are 1-based here.
# ---------------------------------------------------------------------------

_BREAKPOINTS = {}          # (owner, file) -> set of line numbers
_DEBUG_HITS = []           # newest last, at most _DEBUG_MAX
_DEBUG_COUNT = {}          # (owner, file, line) -> hits recorded since the last clear
_DEBUG_SEQ = [0]
_DEBUG_MAX = 100
_DEBUG_PER_LINE = 20


def _debug_check(frame):
    lines = _BREAKPOINTS.get((_OWNER[0], frame.f_code.co_filename))
    if lines and frame.f_lineno in lines:
        _debug_record(frame)


def _debug_tracer(frame, event, arg):
    if _INTERRUPT:
        raise KeyboardInterrupt("Interrupted by user")
    if event == "call":
        # only frames of files with breakpoints get line events; everything else runs at full speed
        return _debug_tracer if (_OWNER[0], frame.f_code.co_filename) in _BREAKPOINTS else None
    if event == "line":
        _debug_check(frame)
    return _debug_tracer


def _debug_value(value):
    import reprlib
    shower = reprlib.Repr()
    shower.maxstring = 120
    shower.maxother = 120
    shower.maxlist = shower.maxdict = shower.maxset = shower.maxtuple = 8
    shower.maxlevel = 3
    try:
        return shower.repr(value)
    except Exception as exc:  # noqa: BLE001  a __repr__ that fails must not break the user's code
        return "<%s: repr failed: %s>" % (type(value).__name__, exc)


def _debug_record(frame):
    import types as _types
    key = (_OWNER[0], frame.f_code.co_filename, frame.f_lineno)
    count = _DEBUG_COUNT.get(key, 0)
    if count >= _DEBUG_PER_LINE:
        return
    _DEBUG_COUNT[key] = count + 1
    stack = []
    walk = frame
    while walk is not None and len(stack) < 30:
        name = walk.f_code.co_filename
        if name.startswith("code/") or name.endswith(".py") and "craftpy_runtime" not in name:
            stack.append({"file": name, "line": walk.f_lineno - 1, "function": walk.f_code.co_name})
        walk = walk.f_back
    variables = []
    for name, value in list(frame.f_locals.items()):
        if name.startswith("__") or isinstance(value, (_types.ModuleType, _types.FunctionType, type, _types.BuiltinFunctionType)):
            continue
        variables.append([name, type(value).__name__, _debug_value(value)])
        if len(variables) >= 40:
            break
    _DEBUG_SEQ[0] += 1
    _DEBUG_HITS.append({"seq": _DEBUG_SEQ[0], "time": time.strftime("%H:%M:%S"), "owner": _OWNER[0],
                        "file": frame.f_code.co_filename, "line": frame.f_lineno - 1, "function": frame.f_code.co_name,
                        "hit": count + 1, "stack": stack, "variables": variables})
    del _DEBUG_HITS[:-_DEBUG_MAX]


def _debug(params):
    """IDE requests: ``set`` the breakpoints of a project ({file: [0-based lines]}), ``hits`` after a seq, ``clear``."""
    owner = params.get("project") or "__main__"
    cmd = params.get("cmd", "hits")
    if cmd in ("set", "sync"):
        for key in [k for k in _BREAKPOINTS if k[0] == owner]:
            del _BREAKPOINTS[key]
        for path, lines in (params.get("breakpoints") or {}).items():
            wanted = {int(line) + 1 for line in lines}
            if wanted:
                _BREAKPOINTS[(owner, path)] = wanted
    if cmd == "clear":
        _DEBUG_HITS[:] = [h for h in _DEBUG_HITS if h["owner"] != owner]
        for key in [k for k in _DEBUG_COUNT if k[0] == owner]:
            del _DEBUG_COUNT[key]
    since = int(params.get("since", 0) or 0)
    hits = [h for h in _DEBUG_HITS if h["owner"] == owner and h["seq"] > since]
    count = sum(len(lines) for (o, _), lines in _BREAKPOINTS.items() if o == owner)
    return {"hits": hits, "last": _DEBUG_SEQ[0], "breakpoints": count}


def _call_flexible(fn, ctx):
    if _BREAKPOINTS and sys.gettrace() is None:
        sys.settrace(_debug_tracer)
        try:
            return _call_flexible_now(fn, ctx)
        finally:
            sys.settrace(None)
    return _call_flexible_now(fn, ctx)


def _call_flexible_now(fn, ctx):
    """Calls fn(ctx), or fn() when the function takes no arguments."""
    try:
        wants_arg = len(inspect.signature(fn).parameters) > 0
    except (TypeError, ValueError):
        wants_arg = True
    return fn(ctx) if wants_arg else fn()


class _TickContext:
    """What an @every timer receives."""

    tick: int
    world: "_World"

    def __init__(self, tick):
        self.tick = tick
        self.world = world


# Storage. The host saves the values with the world (lifecycle/StorageData); _STORAGE caches them for this worker.
def storage_get(key, default=None, scope="world"):
    value = _STORAGE.get((scope, key))
    if value is None:
        value = (_try_host("storage.get", {"key": key, "scope": scope}, default=None) or {}).get("value")
    return default if value is None else value


def storage_set(key, value, scope="world"):
    _STORAGE[(scope, key)] = value
    _try_host("storage.set", {"key": key, "value": value, "scope": scope}, default=None)
    return value


def storage_clear(key=None, scope="world"):
    if key is None:
        for cached in [k for k in _STORAGE if k[0] == scope]:
            del _STORAGE[cached]
        _try_host("storage.clear", {"scope": scope}, default=None)
    else:
        _STORAGE.pop((scope, key), None)
        _try_host("storage.clear", {"key": key, "scope": scope}, default=None)


# ---------------------------------------------------------------------------
# World / player / entity facade (host backed)
# ---------------------------------------------------------------------------

_OVERWORLD = "minecraft:overworld"


class Pos(tuple):
    """An (x, y, z) position. ``pos.x``, ``pos + (0, 1, 0)``, ``pos.up(2)``."""

    def __new__(cls, x, y, z):
        return tuple.__new__(cls, (x, y, z))

    x: float = property(lambda self: self[0])
    y: float = property(lambda self: self[1])
    z: float = property(lambda self: self[2])

    def __add__(self, other) -> "Pos":
        return Pos(self[0] + other[0], self[1] + other[1], self[2] + other[2])

    def up(self, n=1) -> "Pos":
        return Pos(self[0], self[1] + n, self[2])

    def down(self, n=1) -> "Pos":
        return Pos(self[0], self[1] - n, self[2])

    def block(self) -> "Pos":
        """Integer block coordinates."""
        return Pos(math.floor(self[0]), math.floor(self[1]), math.floor(self[2]))

    def __repr__(self):
        return "Pos(%s, %s, %s)" % self


def _xyz(x, y=None, z=None):
    if y is None and z is None and isinstance(x, (tuple, list)):
        return x[0], x[1], x[2]
    if y is None or z is None:
        raise TypeError("give a position as x, y, z or as a Pos / (x, y, z) tuple")
    return x, y, z


def _ok(result):
    """Host results are {"ok": bool, ...}; raise a readable error when a call failed."""
    if isinstance(result, dict) and result.get("ok") is False:
        raise RuntimeError(result.get("error") or result.get("reason") or "host call failed")
    return result


class _World:
    """``world[x, y, z]`` reads a block id, ``world[x, y, z] = "stone"`` sets one."""

    def __getitem__(self, pos):
        x, y, z = _xyz(pos)
        return (self.get_block(x, y, z) or {}).get("block")

    def __setitem__(self, pos, block):
        x, y, z = _xyz(pos)
        self.set_block(x, y, z, block)

    def get_block(self, x, y=None, z=None, dimension=_OVERWORLD):
        x, y, z = _xyz(x, y, z)
        return _host_call("world.get_block", {"x": x, "y": y, "z": z, "dimension": dimension})

    def machine(self, x, y=None, z=None, dimension=_OVERWORLD) -> dict:
        """Snapshot of a loaded kinetic machine: speed, overstressed, progress, input/output. No chunk loading."""
        x, y, z = _xyz(x, y, z)
        return _ok(_host_call("world.machine", dict(x=x, y=y, z=z, dimension=dimension))).get("machine", {})

    def machine_slot(self, x, y=None, z=None, *, slot, item=None, count=1, dimension=_OVERWORLD) -> dict:
        """Puts a stack into a slot of a loaded machine block, replacing what is there; ``item=None`` empties it.

        ``slot`` is ``"group.number"`` of the block's ``Inventory(...)`` (``"input.0"``, ``"output.1"``; ``"input"`` is
        ``"input.0"``; a block without ``Inventory`` has ``input`` and ``output``). Python is not held to the roles (it may
        fill an output slot), but a stack never goes over the group's ``max_stack`` or 64. Returns the new snapshot."""
        x, y, z = _xyz(x, y, z)
        _integer("count", count, 0, 64)
        return _ok(_host_call("world.machine_slot", dict(x=x, y=y, z=z, slot=str(slot), item=None if item is None else _ref(item),
                                                         count=count, dimension=dimension))).get("machine", {})

    def machine_update(self, x, y=None, z=None, *, energy=0, fluid=0, dimension=_OVERWORLD) -> dict:
        """Changes what a loaded machine block stores and returns its new snapshot.

        ``energy`` is added in FE (negative removes), ``fluid`` is removed in mB (use a negative number to take fluid out;
        a positive number is not accepted: fluid goes in through buckets, pipes and ``world.transfer``). The block's own
        limits apply: energy stays between 0 and its capacity, and no more fluid leaves than it holds. This is how a
        generator turns fuel into energy: read ``world.machine``, then call this with the conversion."""
        x, y, z = _xyz(x, y, z)
        _integer("energy", energy, -1_000_000_000, 1_000_000_000)
        _integer("fluid", fluid, -1_000_000, 0)
        return _ok(_host_call("world.machine_update", dict(x=x, y=y, z=z, energy=energy, fluid=fluid,
                                                            dimension=dimension))).get("machine", {})

    def resources(self, x, y=None, z=None, dimension=_OVERWORLD, *, side=None) -> dict:
        """Bounded native NeoForge item/FE/fluid snapshot from a loaded block face."""
        x, y, z = _xyz(x, y, z)
        return _ok(_host_call("world.resources", dict(x=x, y=y, z=z, dimension=dimension, side=side)))

    def transfer(self, source, target, kind="item", amount=1, *, source_side=None, target_side=None,
                 slot=0, simulate=False, dimension=_OVERWORLD) -> int:
        """Move items (max 64), fluid mB or energy FE through native NeoForge capabilities.

        simulate=True reports the possible transfer without changing either endpoint. No chunks are loaded.
        """
        _choice("Transfer kind", kind, ("item", "fluid", "energy"))
        _integer("Transfer amount", amount, 1, 1_000_000)
        _integer("Transfer slot", slot, 0, 1000000)
        for face in (source_side, target_side):
            if face is not None:
                _choice("Capability face", face, ("up", "down", "north", "south", "east", "west"))
        return _ok(_host_call("world.transfer", dict(source=list(_xyz(source)), target=list(_xyz(target)),
                   kind=kind, amount=amount, source_side=source_side, target_side=target_side,
                   slot=slot, simulate=bool(simulate), dimension=dimension))).get("moved", 0)

    def set_block(self, x, y, z=None, block=None, dimension=_OVERWORLD, replace=True):
        if block is None and z is None and isinstance(x, (tuple, list)):
            # set_block(pos, "stone")
            block, (x, y, z) = y, _xyz(x)
        if block is None:
            raise TypeError("set_block(x, y, z, block) or set_block(pos, block)")
        return _ok(_host_call("world.set_block", {"x": x, "y": y, "z": z, "block": _ref(block),
                                                  "dimension": dimension, "replace": replace}))

    def fill(self, start, end, block, dimension=_OVERWORLD):
        """Fills the box between two corners (max 32768 blocks)."""
        (x1, y1, z1), (x2, y2, z2) = _xyz(start), _xyz(end)
        return _ok(_host_call("world.fill", {"x1": x1, "y1": y1, "z1": z1, "x2": x2, "y2": y2, "z2": z2,
                                             "block": _ref(block), "dimension": dimension})).get("count", 0)

    def blocks_in_box(self, x, y, z, radius=0, dimension=_OVERWORLD):
        return _host_call("world.blocks_in_box", {"x": x, "y": y, "z": z, "radius": radius,
                                                  "dimension": dimension})

    def get_block_entities(self, x, y, z, radius=0, dimension=_OVERWORLD):
        # Legacy alias kept for source compatibility.
        return self.blocks_in_box(x, y, z, radius=radius, dimension=dimension)

    def spawn(self, entity, x, y=None, z=None, dimension=_OVERWORLD, *, owner=None) -> "Entity":
        """Spawns an entity ("zombie", "minecraft:pig") and returns it."""
        x, y, z = _xyz(x, y, z)
        params = {"entity": _ref(entity), "x": x, "y": y, "z": z, "dimension": dimension}
        if owner is not None:
            params["player_owner"] = _player_name(owner)
        result = _ok(_host_call("world.spawn", params))
        uuid = result.get("uuid")
        return Entity(result.get("entity") or {"uuid": uuid, "type": _ref(entity), "x": x, "y": y, "z": z}) if uuid else result

    def place_structure(self, structure, x, y=None, z=None, rotation=0, mirror=False, entities=True,
                        dimension=_OVERWORLD):
        """Places a structure template now: 'ruins/house' is data/structure/ruins/house.nbt of this project (after
        Run); 'minecraft:village/plains/houses/plains_small_house_1' a template of the game."""
        x, y, z = _xyz(x, y, z)
        return _ok(_host_call("world.place_structure", {"structure": _ref_structure(structure), "x": x, "y": y, "z": z,
                                                        "rotation": int(rotation), "mirror": bool(mirror),
                                                        "entities": bool(entities), "dimension": dimension}))

    def place_jigsaw(self, pool, x, y=None, z=None, target=None, depth=7, dimension=_OVERWORLD):
        """Generates a jigsaw structure from a Pool now, starting at the jigsaw block named `target` of its first piece
        (the "Name" field of a jigsaw block, such as 'minecraft:building_entrance').
        Pools are world generation: they exist after the world was opened with the project loaded."""
        if not target:
            raise ValueError("place_jigsaw needs target=: the name of a jigsaw block in the first piece")
        x, y, z = _xyz(x, y, z)
        return _ok(_host_call("world.place_jigsaw", {"pool": _ref_structure(pool), "target": str(target), "depth": int(depth),
                                                     "x": x, "y": y, "z": z, "dimension": dimension}))

    def explode(self, x, y=None, z=None, power=3, fire=False, break_blocks=True, dimension=_OVERWORLD):
        x, y, z = _xyz(x, y, z)
        return _ok(_host_call("world.explode", {"x": x, "y": y, "z": z, "power": power, "fire": fire,
                                                "break_blocks": break_blocks, "dimension": dimension}))

    def lightning(self, x, y=None, z=None, dimension=_OVERWORLD):
        x, y, z = _xyz(x, y, z)
        return _ok(_host_call("world.lightning", {"x": x, "y": y, "z": z, "dimension": dimension}))

    def drop(self, item, x, y=None, z=None, count=1, dimension=_OVERWORLD):
        """Drops an item stack on the ground."""
        x, y, z = _xyz(x, y, z)
        return _ok(_host_call("world.drop_item", {"item": _ref(item), "count": count, "x": x, "y": y, "z": z,
                                                  "dimension": dimension}))

    def play_sound(self, sound, x, y=None, z=None, volume=1.0, pitch=1.0, dimension=_OVERWORLD):
        x, y, z = _xyz(x, y, z)
        return _host_call("world.play_sound", {"sound": sound, "x": x, "y": y, "z": z,
                                               "volume": volume, "pitch": pitch,
                                               "dimension": dimension})

    def particle(self, particle, x, y=None, z=None, count=1, dimension=_OVERWORLD):
        x, y, z = _xyz(x, y, z)
        return _host_call("world.particle", {"particle": particle, "x": x, "y": y, "z": z,
                                             "count": count, "dimension": dimension})

    def info(self, x, y=None, z=None, dimension=_OVERWORLD):
        """Biome, highest_y, light, is_day, raining, thundering and time at a position."""
        x, y, z = _xyz(x, y, z)
        return _ok(_host_call("world.info", {"x": x, "y": y, "z": z, "dimension": dimension}))

    def biome(self, x, y=None, z=None, dimension=_OVERWORLD):
        return self.info(x, y, z, dimension).get("biome")

    def highest_y(self, x, z, dimension=_OVERWORLD):
        return self.info(x, 64, z, dimension).get("highest_y")

    def is_day(self, dimension=_OVERWORLD):
        return bool(self.info(0, 64, 0, dimension).get("is_day"))

    def time(self, dimension=_OVERWORLD):
        return _host_call("world.get_time", {"dimension": dimension})

    def set_time(self, ticks, dimension=_OVERWORLD):
        presets = {"day": 1000, "noon": 6000, "sunset": 12000, "night": 13000, "midnight": 18000, "sunrise": 23000}
        ticks = presets.get(ticks, ticks)
        return _host_call("world.set_time", {"time": int(ticks), "dimension": dimension})

    def weather(self, weather="clear", duration=6000, dimension=_OVERWORLD):
        return _host_call("world.set_weather", {"weather": weather, "duration": duration,
                                                "dimension": dimension})

    def entities_in_box(self, x, y, z, radius=8, dimension=_OVERWORLD):
        return _host_call("world.entities_in_box", {"x": x, "y": y, "z": z, "radius": radius,
                                                    "dimension": dimension})

    def entities(self, x, y=None, z=None, radius=8, type=None, dimension=_OVERWORLD) -> "list[Entity]":
        """Entities near a position, as Entity objects (optionally only one type)."""
        x, y, z = _xyz(x, y, z)
        found = (self.entities_in_box(x, y, z, radius=radius, dimension=dimension) or {}).get("entities", [])
        wanted = _ref(type) if type else None
        return [Entity(e) for e in found if wanted is None or e.get("type") == wanted]


class _GameRef:
    """Block or item data of an event (``event.block``, ``event.item``, ``player.held_item``). Missing data reads as None."""

    id: str
    name: str
    count: int


class _Thing:
    """Shared attribute access for Player/Entity snapshots: missing data reads as None."""

    name: str
    uuid: str
    x: float
    y: float
    z: float
    dimension: str
    health: float

    def __init__(self, data):
        for key, value in (data or {}).items():
            object.__setattr__(self, key, _wrap_data(value))

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        return None

    @property
    def pos(self) -> "Pos":
        return Pos(self.x or 0, self.y or 0, self.z or 0)

    @property
    def java(self):
        """The live Java object (ServerPlayer / Entity) through the Java bridge: ``player.java.getFoodData()``."""
        if self.uuid is None:
            raise JavaError("this snapshot has no uuid")
        return _java_call("entity", uuid=self.uuid)


class Player(_Thing):
    """A player snapshot with actions. Data: name, uuid, x, y, z, pos, dimension, health,
    max_health, food, level, gamemode, held_item."""

    max_health: float
    food: int
    level: int
    gamemode: str
    held_item: "_GameRef"

    @staticmethod
    def named(name) -> "Player":
        """The online player with this name, or None."""
        info = (_try_host("players.get", {"name": str(name)}, default={}) or {}).get("player")
        return Player(info) if info else None

    def _call(self, method, **params):
        params["player"] = self.uuid or self.name
        return _ok(_host_call(method, params))

    def __repr__(self):
        return "Player(%r)" % self.name

    def say(self, *message):
        """Sends a chat message to this player only."""
        return self._call("player.send_message", message=" ".join(str(m) for m in message))

    send_message = say

    def give(self, item, count=1):
        return self._call("player.give", item=_ref(item), count=int(count))

    def take(self, item, count=1):
        """Removes items from the inventory; returns how many were taken."""
        return self._call("player.take_item", item=_ref(item), count=int(count)).get("taken", 0)

    def count(self, item):
        return self._call("player.count_item", item=_ref(item)).get("count", 0)

    def has(self, item, count=1):
        return self.count(item) >= count

    def inventory(self):
        return [_wrap_data(entry) for entry in self._call("player.inventory").get("items", [])]

    def teleport(self, x, y=None, z=None, dimension=None, *, safe=False):
        x, y, z = _xyz(x, y, z)
        return self._call("player.teleport", x=x, y=y, z=z, dimension=dimension or self.dimension or _OVERWORLD, safe=bool(safe))

    def heal(self, amount=None):
        return self._call("player.heal", **({} if amount is None else {"amount": amount}))

    def set_health(self, health):
        return self._call("player.set_health", health=health)

    def feed(self, food=20, saturation=1.0):
        return self._call("player.feed", food=food, saturation=saturation)

    def effect(self, name, seconds=10, level=1, particles=True):
        """player.effect("speed", seconds=30, level=2)"""
        return self._call("player.effect", effect=_ref_effect(name), seconds=seconds, level=level, particles=particles)

    def clear_effects(self):
        return self._call("player.clear_effects")

    def set_gamemode(self, mode):
        return self._call("player.gamemode", mode=mode)

    def add_xp(self, levels=0, points=0):
        return self._call("player.add_xp", levels=levels, points=points)

    def title(self, text, subtitle=None, seconds=3):
        params = {"title": str(text), "stay": int(seconds * 20)}
        if subtitle is not None:
            params["subtitle"] = str(subtitle)
        return self._call("player.title", **params)

    def actionbar(self, text):
        return self._call("player.actionbar", message=str(text))

    def play_sound(self, sound, volume=1.0, pitch=1.0):
        return self._call("player.sound", sound=sound, volume=volume, pitch=pitch)

    def set_fire(self, seconds=3):
        return self._call("player.set_fire", seconds=seconds)

    def kill(self):
        return self._call("player.kill")

    def refresh(self) -> "Player":
        """A fresh snapshot of this player (position, health...)."""
        return Player.named(self.name)


class Entity(_Thing):
    """An entity snapshot: type, uuid, name, x, y, z, pos, health."""

    type: str
    native_type: str

    def __repr__(self):
        return "Entity(%r)" % (self.type,)

    def kill(self):
        return _ok(_host_call("entity.kill", {"uuid": self.uuid}))

    def teleport(self, x, y=None, z=None):
        x, y, z = _xyz(x, y, z)
        return _ok(_host_call("entity.teleport", {"uuid": self.uuid, "x": x, "y": y, "z": z}))

    def set_name(self, name):
        return _ok(_host_call("entity.set_name", {"uuid": self.uuid, "name": str(name)}))

    def refresh(self) -> "Entity":
        info = (_try_host("entity.get", {"uuid": self.uuid}, default={}) or {}).get("entity")
        return Entity(info) if info else None

    def state(self, key=None, default=None):
        value = _ok(_host_call("entity.state", {"uuid": self.uuid, "key": key or ""})).get("value")
        return default if value is None else value

    def set_state(self, key, value):
        if not isinstance(key, str) or not key or len(key) > 128:
            raise ValueError("Entity state needs a key (1-128 characters)")
        _ok(_host_call("entity.state", {"uuid": self.uuid, "key": key, "value": value, "set": True}))
        return value

    def set_owner(self, player=None):
        return _ok(_host_call("entity.owner", {"uuid": self.uuid, "player": _player_name(player) if player is not None else ""}))

    def animate(self, name):
        if name not in ("attack", "cast", "hurt", "death"):
            raise ValueError("Triggered animation must be attack, cast, hurt or death")
        return _ok(_host_call("entity.animate", {"uuid": self.uuid, "animation": name}))


class _Players:
    def all(self):
        """Names of every online player."""
        result = _try_host("players.all", {}, default={}) or {}
        return result.get("players", []) if isinstance(result, dict) else result

    def online(self) -> "list[Player]":
        """Every online player as a Player object."""
        return [p for p in (Player.named(name) for name in self.all()) if p is not None]

    def get(self, name):
        return _host_call("players.get", {"name": name})

    def send_message(self, name, message):
        return _host_call("player.send_message", {"player": name, "message": str(message)})

    def teleport(self, name, x, y, z, dimension=_OVERWORLD):
        return _host_call("player.teleport", {"player": name, "x": x, "y": y, "z": z,
                                              "dimension": dimension})

    def give(self, name, item, count=1):
        return _host_call("player.give", {"player": name, "item": _ref(item), "count": count})


class _Server:
    def features(self) -> dict:
        """Live installed mods and verified adapter capabilities; never cached catalog discovery."""
        return _ok(_host_call("server.features", {}))

    def info(self) -> dict:
        """Native player counts, persistent game_time ticks and average server tick milliseconds."""
        return _ok(_host_call("server.info", {}))

    def broadcast(self, message):
        return _host_call("server.broadcast", {"message": str(message)})

    def run_command(self, command):
        result = _host_call("server.command", {"command": str(command)}) or {}
        return "\n".join(result.get("output") or [])

    command = run_command

    def mods(self):
        """The ids of the loaded mods ("create", "jei", ...). Needs no permission."""
        return list((_host_call("server.mods", {}) or {}).get("mods") or [])


    def log(self, message, level="info"):
        _try_host("server.log", {"message": str(message), "level": level}, default=None)


class _Storage:
    """``storage["key"] = value`` / ``storage.get("key", default)``."""

    def get(self, key, default=None, scope="world"):
        return storage_get(key, default, scope)

    def set(self, key, value, scope="world"):
        return storage_set(key, value, scope)

    def clear(self, key=None, scope="world"):
        return storage_clear(key, scope)

    def __getitem__(self, key):
        return storage_get(key)

    def __setitem__(self, key, value):
        storage_set(key, value)


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


def player(name) -> "Player":
    """The online player with this name, or None."""
    return Player.named(name)


def say(*message):
    """Broadcasts a chat message to everyone."""
    return server.broadcast(" ".join(str(m) for m in message))


def run(command_text):
    """Runs a Minecraft command as the server, e.g. run("time set day"); returns what the command answered."""
    return server.run_command(command_text)



# ---------------------------------------------------------------------------
# UI: server-described widget tree (rendered by the Java client)
# ---------------------------------------------------------------------------

# Widget callbacks. A function becomes a token for the window being rendered; a string is the name of an @action.
# The client only ever sends these strings back, and only tokens/names of the open window are accepted.
_UI_ACTIONS = ("on_click", "on_change", "on_select", "on_submit", "on_end", "on_loop", "on_error")
_UI_BUILD = [None]    # {"tokens": {token: fn}, "named": set()} while a window renders
_UI_OPEN = {}         # (player name, window id) -> {"inst", "tokens", "named", "owner"}
_UI_NAMED = {}        # (owner, name) -> fn, from @action
_UI_FILES = {}        # (owner, window id) -> tree from ui/*.json
_UI_DATA = {}         # (owner, window id) -> viewer-context display data provider


def _ui_action(value):
    build = _UI_BUILD[0]
    if value is None or build is None:
        return None if value is None else (value if isinstance(value, str) else None)
    if callable(value):
        token = "t%d" % len(build["tokens"])
        build["tokens"][token] = value
        return token
    build["named"].add(str(value))
    return str(value)


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
        for key in _UI_ACTIONS:
            data[key] = _ui_action(data.get(key))
            if data[key] is None:
                del data[key]
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


def Video(source="", **p):
    return Widget("video", source=source, **p)


def Audio(source="", **p):
    return Widget("audio", source=source, **p)


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


def Canvas(*children, **p):
    w = Widget("canvas", **p)
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
    """Passed to ``build(ctx)``: the player the window is drawn for (None outside ui.open) and the window."""

    player: "Player"

    def __init__(self, window):
        self.window = window
        self.player = getattr(window, "_viewer", None)
        self.log = lambda message: _try_host("server.log", {"message": str(message)}, default=None)

    def close(self, event=None):
        """``on_click=ctx.close``: closes the window of the player who clicked."""
        if event is not None and hasattr(event, "close"):
            event.close()


def window(ident, title=None, size=None, resizable=False):
    """``@window("id", title="...", size=(w, h))`` on a Window subclass (build() returns the widgets)."""
    def deco(cls):
        cls.id = ident
        if title is not None:
            cls.title = title
        if size is not None:
            cls.size = size
        cls.resizable = resizable
        _WINDOWS[(_OWNER[0], ident)] = cls
        return cls
    return deco


def action(name):
    """``@action("save")``: a function that buttons of ui/*.json windows (or ``on_click="save"``) can call."""
    def deco(fn):
        _UI_NAMED[(_OWNER[0], name)] = fn
        return fn
    return deco


# ---------------------------------------------------------------------------
# Python windows into the UI maker: a @window class whose build() returns plain widgets becomes ui/<id>.json. The code
# is only read (ast), never run, so this works on any project, even one that does not run.
# ---------------------------------------------------------------------------
_WIDGET_CALLS = {  # function -> (json type, its positional parameters; None for a box whose arguments are children)
    "Label": ("label", ["text"]), "Button": ("button", ["text", "on_click"]), "TextInput": ("text_input", ["value"]),
    "TextArea": ("text_area", ["value"]), "Checkbox": ("checkbox", ["label", "checked"]),
    "Slider": ("slider", ["value", "min", "max"]), "Dropdown": ("dropdown", ["options", "selected"]),
    "ListView": ("list", ["items"]), "ProgressBar": ("progress", ["value"]), "Image": ("image", ["source"]),
    "ItemIcon": ("item_icon", ["item"]), "Video": ("video", ["source"]), "Audio": ("audio", ["source"]),
    "VBox": ("vbox", None), "HBox": ("hbox", None), "Grid": ("grid", None),
    "TabView": ("tabs", None), "ScrollView": ("scroll", None), "Canvas": ("canvas", None),
}


class _NotAWidget(Exception):
    pass


def _call_name(node):
    import ast
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    return ""


def _window_classes(sources):
    """(file, class node, window id, decorator call) for every ``@window("id", ...)`` class of the sources."""
    import ast
    found = []
    for path in sorted(sources):
        try:
            tree = ast.parse(sources[path], path)
        except SyntaxError:
            continue
        for node in ast.walk(tree):
            if not isinstance(node, ast.ClassDef):
                continue
            for deco in node.decorator_list:
                if (isinstance(deco, ast.Call) and _call_name(deco.func) == "window" and deco.args
                        and isinstance(deco.args[0], ast.Constant) and isinstance(deco.args[0].value, str)):
                    found.append((path, node, deco.args[0].value, deco))
    return found


def _code_windows(sources):
    out = []
    for path, cls, ident, deco in _window_classes(sources):
        title = next((k.value.value for k in deco.keywords if k.arg == "title" and hasattr(k.value, "value")
                      and isinstance(k.value.value, str)), ident)
        out.append({"id": ident, "title": title, "file": path, "line": cls.lineno, "class": cls.name})
    return out


def _window_to_json(sources, ident):
    """The window ``ident`` of the sources as UI maker JSON, plus notes on what could not come along."""
    import ast
    for path, cls, found, deco in _window_classes(sources):
        if found != ident:
            continue
        window = {"id": ident, "title": ident, "size": [320, 200]}
        notes = []
        for kw in deco.keywords:
            if kw.arg == "title" and isinstance(kw.value, ast.Constant) and isinstance(kw.value.value, str):
                window["title"] = kw.value.value
            elif kw.arg == "size" and isinstance(kw.value, (ast.Tuple, ast.List)) and len(kw.value.elts) == 2 \
                    and all(isinstance(e, ast.Constant) and isinstance(e.value, int) for e in kw.value.elts):
                window["size"] = [e.value for e in kw.value.elts]
        build = next((f for f in cls.body if isinstance(f, ast.FunctionDef) and f.name == "build"), None)
        if build is None:
            return {"error": "%s (%s line %d) has no build(): nothing to convert" % (cls.name, path, cls.lineno)}
        returns = [s for s in build.body if isinstance(s, ast.Return) and s.value is not None]
        if not returns:
            return {"error": "build() of %s does not return the widgets directly at its top level (%s line %d): the UI "
                             "maker can only take a window whose layout is written out as VBox(...), HBox(...) ..."
                             % (cls.name, path, build.lineno)}
        docstring = lambda s: isinstance(s, ast.Expr) and isinstance(s.value, ast.Constant)  # noqa: E731
        if any(not isinstance(s, ast.Return) and not docstring(s) for s in build.body):
            notes.append("build() also runs other code (variables, loops, if); only the returned layout came along")
        try:
            window["root"] = _widget_json(returns[-1].value, path, notes)
        except _NotAWidget as exc:
            return {"error": str(exc)}
        if window["root"]["type"] not in ("vbox", "hbox", "grid", "tabs", "scroll", "canvas"):
            window["root"] = {"type": "vbox", "children": [window["root"]]}
        actions = sorted({n.split("'")[1] for n in notes if n.startswith("action '")})
        if actions:
            notes = [n for n in notes if not n.startswith("action '")]
            notes.append("buttons now call actions by name: add @action(\"name\") functions for %s (Copy Python in the"
                         " UI maker writes them)" % ", ".join(actions))
        return {"window": window, "notes": notes}
    return {"error": "No @window(\"%s\") class in the project's code" % ident}


def _widget_json(node, path, notes):
    import ast
    if not isinstance(node, ast.Call) or _call_name(node.func) not in _WIDGET_CALLS:
        raise _NotAWidget("%s line %d: %s is not a widget written out (VBox(...), Label(...) ...), so the UI maker cannot"
                          " draw it" % (path, getattr(node, "lineno", 0), _source_hint(node)))
    kind, params = _WIDGET_CALLS[_call_name(node.func)]
    out = {"type": kind}
    if params is None:
        children = []
        for arg in node.args:
            if isinstance(arg, ast.Starred):
                notes.append("%s line %d: *%s builds widgets in Python; left out" % (path, arg.lineno, _source_hint(arg.value)))
                continue
            try:
                children.append(_widget_json(arg, path, notes))
            except _NotAWidget as exc:
                notes.append(str(exc) + "; left out")
        out["children"] = children
    else:
        for name, arg in zip(params, node.args):
            _put_value(out, name, arg, path, notes)
    for kw in node.keywords:
        if kw.arg is None:
            notes.append("%s line %d: **%s left out" % (path, kw.value.lineno, _source_hint(kw.value)))
        else:
            _put_value(out, kw.arg, kw.value, path, notes)
    return out


def _put_value(out, key, node, path, notes):
    import ast
    if key in _UI_ACTIONS:
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            out[key] = node.value
        elif isinstance(node, ast.Attribute):
            out[key] = node.attr  # self.save / ctx.close -> "save" / "close"
            if node.attr != "close":
                notes.append("action '%s'" % node.attr)
        elif isinstance(node, ast.Name):
            out[key] = node.id
            notes.append("action '%s'" % node.id)
        else:
            notes.append("%s line %d: %s=%s is Python code (a lambda?): set the action name in the designer"
                         % (path, node.lineno, key, _source_hint(node)))
        return
    try:
        out[key] = _literal(node)
    except ValueError:
        if isinstance(node, ast.JoinedStr):
            out[key] = "".join(v.value if isinstance(v, ast.Constant) else "{%s}" % _source_hint(v.value)
                               for v in node.values)
            notes.append("%s line %d: %s is an f-string; kept as plain text %r" % (path, node.lineno, key, out[key]))
        else:
            notes.append("%s line %d: %s=%s is computed in Python; left out, set it in the designer"
                         % (path, node.lineno, key, _source_hint(node)))


def _literal(node):
    import ast
    value = ast.literal_eval(node)  # numbers, text, lists, tuples, dicts, True/False/None only
    return list(value) if isinstance(value, tuple) else value


def _source_hint(node):
    import ast
    try:
        text = ast.unparse(node)
    except Exception:  # noqa: BLE001
        return type(node).__name__
    return text if len(text) <= 40 else text[:37] + "..."


class _FileWindow(Window):
    """A window drawn in the UI editor (ui/<id>.json): the tree is data, the buttons name @actions."""

    def __init__(self, tree):
        self._tree = tree
        self.id = tree.get("id", "unnamed")

    def to_dict(self):
        tree = json.loads(json.dumps(self._tree))
        build = _UI_BUILD[0]
        if build is not None:
            _ui_collect(tree.get("root"), build)
        return tree


def _ui_collect(node, build):
    if isinstance(node, dict):
        for key in _UI_ACTIONS:
            if isinstance(node.get(key), str) and node[key]:
                build["named"].add(node[key])
        for child in node.get("children") or []:
            _ui_collect(child, build)


# What a running window can set on a picture, clip or sound; the client acts on a value when it changes.
_UI_MEDIA = {"playing": "bool", "position": "number", "speed": "number", "opacity": "number", "restart": "number"}
_UI_SOUND = {"volume": "number", "muted": "bool"}


def _ui_binding_fields(kind):
    fields = {"visible": "bool", "enabled": "bool"}
    for prop, expected in {
        "label": {"text": "text"}, "button": {"text": "text"},
        "text_input": {"value": "text", "hint": "text"}, "text_area": {"value": "text", "hint": "text"},
        "checkbox": {"label": "text", "checked": "bool"},
        "slider": {"value": "number", "min": "number", "max": "number"},
        "progress": {"value": "number", "max": "number"},
        "dropdown": {"options": "list", "selected": "index"},
        "list": {"items": "list", "selected": "index"},
        "image": {"source": "text", **_UI_MEDIA}, "item_icon": {"item": "text"},
        "video": {"source": "text", "audio": "text", **_UI_MEDIA, **_UI_SOUND},
        "audio": {"source": "text", **_UI_MEDIA, **_UI_SOUND},
    }.get(kind, {}).items():
        fields[prop] = expected
    return fields


def _ui_binding_value(value, expected):
    if expected == "text":
        return isinstance(value, str) and len(value) <= 8192
    if expected == "bool":
        return type(value) is bool
    if expected == "number":
        return type(value) in (int, float) and abs(value) <= 1e9 and math.isfinite(value)
    if expected == "index":
        return type(value) is int and -1 <= value <= 1000000
    return isinstance(value, list) and len(value) <= 1024 and all(isinstance(v, str) and len(v) <= 8192 for v in value)


def _ui_bind(node, data, path):
    if not isinstance(node, dict):
        return
    bindings = node.get("bind", {})
    if not isinstance(bindings, dict):
        raise TypeError("%s.bind must be an object" % path)
    fields = _ui_binding_fields(node.get("type"))
    for prop, key in bindings.items():
        where = "%s.bind.%s" % (path, prop)
        if prop not in fields:
            raise ValueError("%s is not a bindable property" % where)
        if not isinstance(key, str) or not key or len(key) > 128:
            raise ValueError("%s needs a data dictionary key (1-128 characters)" % where)
        if key not in data:
            raise KeyError("%s: missing data key %r" % (where, key))
        value = data[key]
        if not _ui_binding_value(value, fields[prop]):
            raise TypeError("%s: data key %r requires %s" % (where, key, fields[prop]))
        node[prop] = list(value) if isinstance(value, list) else value
    for index, child in enumerate(node.get("children") or []):
        _ui_bind(child, data, "%s.children[%d]" % (path, index))


def _ui_render(entry):
    """Builds the tree of an open window and records which callbacks it may call."""
    _UI_BUILD[0] = {"tokens": {}, "named": set()}
    entry["inst"]._viewer = entry.get("player")
    try:
        tree = entry["inst"].to_dict()
        provider = _UI_DATA.get((entry["owner"], entry["inst"].id))
        data = _call_flexible(provider, _UiContext(entry.get("player"), entry["inst"].id, None, {})) if provider else {}
        if not isinstance(data, dict):
            raise TypeError("ui.data(%r) must return a dictionary" % entry["inst"].id)
        _ui_bind(tree.get("root"), data, entry["inst"].id)
        tree["owner"] = entry["owner"]  # media/... sources of the window name files of this module
        entry["tokens"] = _UI_BUILD[0]["tokens"]
        entry["named"] = _UI_BUILD[0]["named"]
    finally:
        _UI_BUILD[0] = None
    return tree


class _Ui:
    def data(self, window_id):
        if not isinstance(window_id, str) or not window_id or len(window_id) > 128:
            raise ValueError("ui.data needs a window ID (1-128 characters)")
        def register(fn):
            if not callable(fn):
                raise TypeError("ui.data needs a function")
            _UI_DATA[(_OWNER[0], window_id)] = fn
            return fn
        return register

    def open(self, player, window_id):
        owner = _OWNER[0]
        name = _player_name(player)
        cls = _WINDOWS.get((owner, window_id))
        if cls is not None:
            inst = cls()
        elif (owner, window_id) in _UI_FILES:
            inst = _FileWindow(_UI_FILES[(owner, window_id)])
        else:
            raise KeyError("unknown window: %s (windows: %s)" % (window_id, ", ".join(self.windows()) or "none"))
        viewer = player if isinstance(player, Player) else (Player.named(name) or Player({"name": name}))
        entry = {"inst": inst, "tokens": {}, "named": set(), "owner": owner, "player": viewer}
        rendered = _ui_render(entry)
        _UI_OPEN[(name, window_id)] = entry
        return _host_call("ui.open", {"player": name, "window": rendered})

    def close(self, player, window_id=None):
        name = _player_name(player)
        for key in [k for k in _UI_OPEN if k[0] == name and (window_id is None or k[1] == window_id)]:
            del _UI_OPEN[key]
        return _host_call("ui.close", {"player": name, "id": window_id or ""})

    def windows(self):
        owner = _OWNER[0]
        return sorted({wid for (own, wid) in _WINDOWS if own == owner} | {wid for (own, wid) in _UI_FILES if own == owner})

    def refresh(self, player, window_id):
        name = _player_name(player)
        entry = _UI_OPEN.get((name, window_id))
        if entry is None or entry["owner"] != _OWNER[0]:
            return False  # the player closed it meanwhile
        _host_call("ui.open", {"player": name, "window": _ui_render(entry)})
        return True


ui = _Ui()


class _UiContext:
    """Passed to widget callbacks: who clicked, the new value and every input value of the window."""

    player: "Player"
    window: str
    values: dict
    closed: bool

    def __init__(self, player, window_id, value, values):
        self.player = player
        self.window = window_id
        self.value = value
        self.values = values or {}
        self.closed = False

    def close(self):
        self.closed = True
        ui.close(self.player, self.window)

    def open(self, window_id):
        ui.open(self.player, window_id)


def _ui_event(data):
    """Runs the callback a player triggered in an open window, then re-sends the window."""
    log = []
    _LOG_TARGET[0] = log
    started = time.perf_counter()
    ran = 0
    try:
        who = data.get("player") or {}
        name = who.get("name", "")
        window_id = data.get("window", "")
        token = data.get("token", "")
        entry = _UI_OPEN.get((name, window_id))
        if entry is None:
            return {"log": log, "handlers": 0, "ms": 0, "scheduled": len(_SCHEDULE)}
        player = Player(who)
        entry["player"] = player
        ctx = _UiContext(player, window_id, data.get("value"), data.get("values"))
        if token == "__closed__":
            _UI_OPEN.pop((name, window_id), None)
            on_close = getattr(entry["inst"], "on_close", None)
            fn = on_close if callable(on_close) else None
        elif token in entry["tokens"]:
            fn = entry["tokens"][token]
        elif token in entry["named"]:
            fn = _UI_NAMED.get((entry["owner"], token))
            if fn is None and token == "close":
                fn = lambda c: c.close()  # noqa: E731 - built-in action
            if fn is None:
                log.append("[%s] window %s: no @action named %r" % (entry["owner"], window_id, token))
        else:
            fn = None
        if fn is not None:
            _use_owner(entry["owner"])
            ran = 1
            failed = False
            try:
                _call_flexible(fn, ctx)
            except Exception as exc:  # noqa: BLE001
                failed = True
                tb = traceback.extract_tb(sys.exc_info()[2])
                where = (" (line %d)" % tb[-1].lineno) if tb else ""
                log.append("[%s] ERROR in window %s callback %s%s: %s: %s"
                           % (entry["owner"], window_id, getattr(fn, "__name__", "?"), where,
                              type(exc).__name__, exc))
            finally:
                _profile_add(entry["owner"], "window:%s" % window_id, time.perf_counter() - started, failed)
            if token != "__closed__" and (name, window_id) in _UI_OPEN and not ctx.closed:
                try:
                    _host_call("ui.open", {"player": name, "window": _ui_render(entry)})
                except Exception as exc:  # noqa: BLE001
                    log.append("[%s] window %s could not be redrawn: %s" % (entry["owner"], window_id, exc))
    finally:
        _LOG_TARGET[0] = None
    return {"log": log, "handlers": ran, "ms": round((time.perf_counter() - started) * 1000, 3),
            "scheduled": len(_SCHEDULE)}


def _read_ui_dir(base_dir, owner):
    """Loads <base_dir>/ui/*.json (windows drawn in the UI editor) for ``owner``."""
    import os
    for key in [k for k in _UI_FILES if k[0] == owner]:
        del _UI_FILES[key]
    folder = os.path.join(base_dir or "", "ui")
    if not base_dir or not os.path.isdir(folder):
        return
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".json"):
            continue
        try:
            with open(os.path.join(folder, name), "r", encoding="utf-8") as handle:
                tree = json.load(handle)
        except (OSError, ValueError) as exc:
            print("ui/%s could not be read: %s" % (name, exc), file=sys.stderr)
            continue
        if isinstance(tree, dict) and isinstance(tree.get("root"), dict):
            tree.setdefault("id", name[:-5])
            _UI_FILES[(owner, tree["id"])] = tree


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

    debug: bool
    project: str
    global_state: dict
    world: "_World"

    def __init__(self, log, debug=False):
        self.debug = debug
        self._log = log
        self.project = None
        self.global_state = {}
        self.world = world

    def log(self, message):
        self._log.append(str(message))

    def info(self, message):
        self._log.append(str(message))

    def warn(self, message):
        self._log.append("WARN: %s" % message)

    def error(self, message):
        self._log.append("ERROR: %s" % message)

    def broadcast(self, message):
        return server.broadcast(message)


class _EventContext(_Context):
    """What an @on handler receives. Event data are attributes:

    event.player (Player), event.block / event.item (.id, .name), event.entity (Entity),
    event.pos (Pos), event.message (chat), event.killer / event.attacker ...
    """

    event: str
    player: "Player"
    killer: "Player"
    attacker_player: "Player"
    entity: "Entity"
    attacker: "Entity"
    block: "_GameRef"
    item: "_GameRef"
    message: str
    damage: float
    cause: str
    hand: str
    dimension: str
    state: dict
    machine: dict

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        return None

    @property
    def pos(self) -> "Pos":
        x = self.__dict__.get("x")
        if x is None:
            return self.player.pos if self.player is not None else None
        return Pos(x, self.__dict__.get("y"), self.__dict__.get("z"))

    def cancel(self):
        """Stops the game from doing what this event is about (breaking the block, using the item, the hit).
        Only events that wait for Python can be cancelled: block_break, item_use, item_hit, item_mine."""
        self.__dict__["_cancelled"] = True


_PLAYER_KEYS = ("player", "killer", "attacker_player")
_ENTITY_KEYS = ("entity", "attacker")


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


# ---------------------------------------------------------------------------
# API versions. MAJOR.MINOR. A minor version only adds: every project of the same major keeps working. A major version
# may rename or remove: the old names keep working (as deprecated aliases, with a note in the console) for at least
# one more major version. project.json / module.json "apiVersion" says which version a project was written for.
# Every change goes in API_CHANGES (newest first); gen_api_doc.py turns it into the "API versions" part of api.md.
# ---------------------------------------------------------------------------

API_VERSION = "2.11"

API_CHANGES = [
    {"version": "2.11", "date": "2026-10-23",
     "added": ["`Fluid(id, ...)`: custom liquids that are real NeoForge fluids (flowing, swimmable, buckets, tanks and pipes of any mod). "
               "`density`, `viscosity`, `temperature`, `light`, `color` or own `still` / `flowing` textures, `effects` while an entity is in it, "
               "`bucket_texture`; `fluid.bucket` is the bucket item. Eight liquid slots by default (`fluids=` in craftpy-slots.properties).",
               "`Block(model=, shape=)`: your own mesh from assets/models/block/ and a hitbox of boxes in sixteenths of a block; without `shape` "
               "the hitbox follows the model's boxes when the project runs from its folder.",
               "`Screen(title=)` extension: a Minecraft-style window for machine blocks (two item slots, liquid gauge, energy bar on the right). "
               "A bucket in the first slot is emptied into the block's `FluidTank`.",
               "`world.machine_update(x, y, z, energy=, fluid=)`: add or remove energy and remove liquid of a loaded machine block.",
               "`Inventory(input=1, fuel=1, output=2, storage=9, accepts=, max_stack=, sides=)`: named item-slot groups for a machine block (up to 54 slots) with "
               "furnace-like automation by hoppers and pipes; `world.machine(...)[\"inventory\"]` reads them and `world.machine_slot(...)` sets a slot. "
               "Legacy windows get *Item slot* (`group.number`) and *Slot grid* pieces.",
               "Minecraft-style windows: tick `legacy` in the UI maker's window properties, draw panels, item slots, the player inventory, "
               "energy bars, liquid gauges, arrows, flames, labels and icons, and name the window with `Screen(layout=\"my_window\")`."]},
    {"version": "2.10", "date": "2026-10-21",
     "added": ["Hub apps: `\"apps\": [{\"id\", \"name\", \"description\", \"window\", \"icon\", \"category\", \"order\"}]` and `\"icon\"` in project.json / module.json put windows of the module into the Hub (Apps tab). "
               "Client modules open them on this computer, server modules on the server; only enabled and loaded modules are listed.",
               "`Image` shows files of the module under `assets/media/` (PNG, JPEG, BMP, animated GIF) with `fit`, `crop`, `opacity`, `z`, playback options and an optional control bar.",
               "`Video` (Motion JPEG in an AVI, sound from a separate `audio=` file) and `Audio` (ogg, wav, mp3, flac, aiff, au): `autoplay`, `loop`, `playing`, `speed`, `position`, `volume`, `muted`, `restart`, `controls`.",
               "Media widgets report to `on_end`, `on_loop` and `on_error`, and their position and state arrive in `ctx.values[id]`. `playing`, `position`, `speed`, `volume`, `muted`, `opacity` and `restart` can be bound with `@ui.data`."]},
    {"version": "2.9", "date": "2026-10-19",
     "added": ["`keys.bind(combo, id=..., name=..., category=...)`: stable, remappable module actions on client and server. "
               "Assignments survive reloads and restarts. Server callbacks receive the authenticated `ctx.player`.",
               "`@ui.data(window_id)`: viewer-specific display dictionaries for designer JSON `bind` properties. "
               "Supports text, numbers, lists, selection, visibility and enabled state; keys never execute expressions.",
               "`NativeMob` and `NativeBlock` reuse original registered mod content. Native variants keep their AI/renderers. "
               "Custom mobs support native behavior profiles, inspected permission-gated goals, named GeckoLib clips, "
               "owner-scoped entity state and throttled `entity_tick` callbacks.",
               "`Kinetic` adds generator, shaft and two-slot processing blocks using independent native rotation with an optional Create adapter. "
               "Separate startup slots retain existing IDs. `world.machine` and throttled `machine_speed`/`machine_state` events expose snapshots. "
               "Recipes use `craftpy:kinetic_processing` with ingredient, result, duration and minimum_speed.",
               "`EnergyStorage`, `FluidTank`, `world.resources` and `world.transfer` expose native NeoForge capability storage and automation."]},
    {"version": "2.8", "date": "2026-10-18",
     "added": ["`Canvas(...)`: a window area where each widget sits at `x`, `y` with its own `width` and `height` (the "
               "UI maker drags and resizes them). `width=` and `height=` also work on any widget in `VBox`, `HBox` and "
               "`Grid`."]},
    {"version": "2.7", "date": "2026-10-17",
     "added": ["`media` (client side): play OGG, WAV, AIFF, MP3 and FLAC files of `pyforge/shared/<folder>` and music "
               "sound events; pause, seek, volume, state; `nearby=True` lets players around you hear it. A module reads "
               "a shared folder only when it declares `\"permissions\": {\"shared\": [\"music\"]}`.",
               "`Pool(...)` and `Structure(...)`: jigsaw structures from the `.nbt` files of `data/structure/`, generated "
               "in new chunks after reopening the world; `world.place_structure(...)` and `world.place_jigsaw(...)`.",
               "A project's `data/` folder goes into the data pack (structures, recipes, tags, worldgen...)."]},
    {"version": "2.6", "date": "2026-10-17",
     "added": ["`Mob(...)`: mobs in the game, with a vanilla body or a GeckoLib model, stats, hostile or passive AI, "
               "drops, a spawn egg (`/craftpy give <player> <project>:<id> egg`), `/craftpy summon` and natural "
               "spawns (`Spawn(...)`). `world.spawn` takes them.",
               "`Effect(...)`: potion effects with name, icon, color, category and attribute modifiers per level; "
               "`player.effect(rage)` and `Item(effect=...)` take them; `@on.effect_tick(effect=rage)` runs once a "
               "second on whoever has it.",
               "Entities in events and `world.entities` report Python mobs as `<project>:<id>`."]},
    {"version": "2.5", "date": "2026-10-17",
     "added": ["`ctx.player` in a window's `build(self, ctx)`: the player the window is drawn for "
               "(before: missing, so `ui.open(event.player, ...)` from an event failed)."]},
    {"version": "2.4", "date": "2026-10-16",
     "added": ["`http.get` / `http.post`: web requests through the host, to sites the module declares, the player "
               "approves and the server allows; the answer comes to a callback. API keys stay in the server config.",
               "`files`: text files in the project's own folder (pyforge/data/<project>/) and read-only shared folders.",
               "`\"permissions\"` in project.json / module.json; modules show a forced warning and need approval."]},
    {"version": "2.3", "date": "2026-10-16",
     "added": ["Client-side modules and projects (`side: client`): Python that runs on the player's own computer, in its own "
               "sandbox, and never touches the server: `hud` (text, bars, item icons on the screen), `keys.bind(...)`, "
               "`client` (the local player, what you look at, local chat, sounds, toasts), client events "
               "(`client_tick`, `chat_received`, `screen_open`, `client_join`, `client_leave`) and local windows.",
               "The checks while you type know the client events."]},
    {"version": "2.2", "date": "2026-10-16",
     "changed": ["`run(\"...\")` and `server.run_command(...)` return what the command answered, as text "
                 "(before: a status object)."],
     "added": ["The IDE console runs `/commands` (vanilla, other mods', your project's own) and shows their answer.",
               "`ui.refresh(player, window)` redraws an open window from a timer or an event."]},
    {"version": "2.1", "date": "2026-10-16",
     "added": ["Tests: `code/test_*.py` files with `test...` functions, run with `Ctrl+Shift+F10`.",
               "`Item(..., tags=[...])` and `Block(..., tags=[...])`.",
               "`API_VERSION`: the version of the API your code runs on."],
     "changed": ["`storage` is saved with the world (before, it was lost when the server stopped).",
                 "`storage.clear()` without a key clears only its `scope` (before, it cleared every scope)."]},
    {"version": "2.0",
     "added": ["Content as objects: `Block()`, `Item()`, `Food()`.",
               "Short recipes: `shaped()`, `shapeless()`, `smelt()`, `stonecut()`, `recipe()` for any mod's type.",
               "Events with filters (`@on.block_break(block=...)`), `@every`, `after()`, `@command` with typed arguments.",
               "Extensions (`Sword`, `Tool`, `Armor`, `Fuel`, ...) and your own with `@extension`.",
               "`player()`, `world`, `Player` / `Entity` / `Pos` objects, windows, the Java bridge (`java`)."],
     "deprecated": ["`@block` / `@item` decorators, `recipe_*()` functions, `tex()` / `snd()` / `mdl()`: they still work; "
                    "the console says what replaces them."]},
    {"version": "1.0",
     "added": ["The first API: `@block` / `@item` decorators, `recipe_*()` functions, `loot_table()`, timers in ticks."]},
]

# old name: (the version that replaced it, what to write instead). Calling one still works and adds a console note.
_DEPRECATED = {
    "block": ("2.0", 'Block("id", ...)'),
    "item": ("2.0", 'Item("id", ...)'),
    "recipe_shaped": ("2.0", 'shaped(result, " R ", " R ", " S ", R=ingredient, S="stick")'),
    "recipe_shapeless": ("2.0", "shapeless(result, ingredient, ...)"),
    "recipe_smelting": ("2.0", "smelt(ingredient, result, xp=..., seconds=...)"),
    "recipe_blasting": ("2.0", 'smelt(ingredient, result, using="blast_furnace")'),
    "recipe_smoking": ("2.0", 'smelt(ingredient, result, using="smoker")'),
    "recipe_campfire": ("2.0", 'smelt(ingredient, result, using="campfire")'),
    "recipe_stonecutting": ("2.0", "stonecut(ingredient, result, count=...)"),
    "tex": ("2.0", 'the file name as text, like texture="ruby.png"'),
    "snd": ("2.0", "the sound id as text"),
    "mdl": ("2.0", "the model name as text"),
}
_DEPRECATION_HITS = set()


def _api_tuple(text):
    """"2" -> (2, 0), "2.1" -> (2, 1); anything else -> (0, 0)."""
    parts = str(text or "").strip().split(".")
    try:
        return int(parts[0]), int(parts[1]) if len(parts) > 1 and parts[1] else 0
    except ValueError:
        return 0, 0


def _deprecated(name, fn):
    import functools

    @functools.wraps(fn)
    def call(*args, **kwargs):
        _DEPRECATION_HITS.add(name)
        return fn(*args, **kwargs)
    return call


def _api_notes(folder, meta_file):
    """Console notes after a run: a project written for a newer API than this one, old names that were used."""
    import os
    notes = []
    declared = None
    try:
        with open(os.path.join(folder or "", meta_file), encoding="utf-8") as handle:
            declared = json.load(handle).get("apiVersion")
    except (OSError, ValueError, AttributeError):
        pass
    if declared and _api_tuple(declared) > _api_tuple(API_VERSION):
        notes.append("[api] This project was written for Python API %s, this CraftPy has %s: names added after %s "
                     "are missing. Update CraftPy." % (declared, API_VERSION, API_VERSION))
    for name in sorted(_DEPRECATION_HITS):
        since, use = _DEPRECATED[name]
        notes.append("[api] %s() is the old API (replaced in %s). It still works; the new way: %s" % (name, since, use))
    return notes


# ---------------------------------------------------------------------------
# Client side. A module or project with side "client" runs in a second worker on the player's computer (CRAFTPY_MODE=client).
# It can draw on the screen, listen to keys and client events and open windows, and it can read what the client knows;
# it cannot change the world or reach the server. In the server's worker these names exist but say so when used.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Web and data folder. Python never opens a connection or a file itself: it asks the host, which checks what the
# project / module declared ("permissions" in project.json / module.json), what the player approved and what the
# server owner allows (config/craftpy-access.toml). A web request answers later: the callback gets a Response.
# ---------------------------------------------------------------------------

_HTTP_WAITING = {}   # request id -> (owner, callback)
_HTTP_NEXT = [1]


class Response:
    """What a web request got back: ok, status, text, headers, error; json() parses the text."""

    def __init__(self, data):
        self.ok = bool(data.get("ok"))
        self.status = int(data.get("status") or 0)
        self.text = data.get("text") or ""
        self.headers = data.get("headers") or {}
        self.error = data.get("error") or ""

    def json(self):
        return json.loads(self.text)

    def __repr__(self):
        return "Response(%d)" % self.status if self.ok else "Response(error=%r)" % self.error


class _Http:
    """``http.get(url, on_done)``: the answer comes to ``on_done(response)`` a moment later, the game never waits."""

    def request(self, method, url, on_done=None, headers=None, body=None, json_body=None, key=None, timeout=10):
        if json_body is not None:
            body = json.dumps(json_body)
            headers = dict(headers or {}, **{"Content-Type": "application/json"})
        ident = _HTTP_NEXT[0]
        _HTTP_NEXT[0] += 1
        params = {"owner": _OWNER[0], "id": ident, "method": method, "url": str(url), "headers": dict(headers or {}),
                  "body": None if body is None else str(body), "key": key, "timeout": int(timeout)}
        result = _host_call("http.request", params)
        if isinstance(result, dict) and result.get("ok") is False:
            raise PermissionError(result.get("error") or "web request refused")
        if on_done is not None:
            _HTTP_WAITING[ident] = (_OWNER[0], on_done)
        return ident

    def get(self, url, on_done=None, headers=None, key=None, timeout=10):
        return self.request("GET", url, on_done, headers=headers, key=key, timeout=timeout)

    def post(self, url, on_done=None, body=None, json=None, headers=None, key=None, timeout=10):
        return self.request("POST", url, on_done, headers=headers, body=body, json_body=json, key=key, timeout=timeout)


http = _Http()


def _http_response(data):
    log = []
    entry = _HTTP_WAITING.pop(data.get("id"), None)
    if entry is None:
        return {"log": log, "handlers": 0, "scheduled": len(_SCHEDULE)}
    owner, fn = entry
    _use_owner(owner)
    _LOG_TARGET[0] = log
    try:
        _call_flexible(fn, Response(data))
    except Exception as exc:  # noqa: BLE001
        log.append("[%s] http callback: %s: %s" % (owner, type(exc).__name__, exc))
    finally:
        _LOG_TARGET[0] = None
    return {"log": log, "handlers": 1, "scheduled": len(_SCHEDULE)}


class _Files:
    """Text files in this project's own folder (pyforge/data/<project>/), or read-only in a folder the server shares."""

    def _call(self, method, **params):
        result = _host_call(method, dict(params, owner=_OWNER[0]))
        if isinstance(result, dict) and result.get("ok") is False:
            if result.get("missing"):
                raise FileNotFoundError(result.get("error"))
            raise PermissionError(result.get("error") or "files refused")
        return result

    def read(self, name, folder=None, default=None):
        try:
            return self._call("files.read", name=str(name), folder=folder or "")["text"]
        except FileNotFoundError:
            if default is not None:
                return default
            raise

    def write(self, name, text):
        self._call("files.write", name=str(name), text=str(text))

    def delete(self, name):
        return self._call("files.delete", name=str(name))["deleted"]

    def exists(self, name, folder=None):
        return self._call("files.exists", name=str(name), folder=folder or "")["exists"]

    def list(self, folder=None):
        return self._call("files.list", folder=folder or "")["names"]

    def read_json(self, name, default=None):
        text = self.read(name, default="" if default is not None else None)
        return default if text == "" else json.loads(text)

    def write_json(self, name, value):
        self.write(name, json.dumps(value, indent=2))


files = _Files()


class _Media:
    """Client-side code: plays audio on this computer. Files come from pyforge/shared/<folder>/ (OGG, WAV, AIFF, MP3,
    FLAC), which the module must declare: "permissions": {"shared": ["music"]}. Sound events (music discs, music of
    resource packs and mods) need nothing. A track keeps playing after its window closes; one track at a time."""

    def _call(self, method, **params):
        result = _host_call(method, dict(params, owner=_OWNER[0]))
        if isinstance(result, dict) and result.get("ok") is False:
            raise PermissionError(result.get("error") or "media refused")
        return result

    def files(self, folder="music"):
        """Names of the audio files in pyforge/shared/<folder> (and its subfolders), sorted. Call again to refresh."""
        return self._call("media.files", folder=str(folder))["files"]

    def play(self, file, folder="music", seconds=0, volume=1.0, nearby=False):
        """Plays a file of the folder from `seconds`; nearby=True lets players around you hear it too."""
        self._call("media.play", file=str(file), folder=str(folder), seconds=float(seconds), volume=float(volume),
                   share=bool(nearby))

    def play_sound(self, sound, volume=1.0):
        """Plays a sound event such as 'minecraft:music_disc.cat' or a music pack's sound."""
        self._call("media.play_sound", sound=str(sound), volume=float(volume))

    def sounds(self):
        """Music sound events of the game, resource packs and mods (ids with 'music' or 'record')."""
        return self._call("media.sounds")["sounds"]

    def pause(self):
        self._call("media.pause")

    def resume(self):
        self._call("media.resume")

    def stop(self):
        self._call("media.stop")

    def seek(self, seconds):
        """Jumps to a position (files only)."""
        self._call("media.seek", seconds=float(seconds))

    def volume(self, volume):
        """0.0 to 1.0, on top of the game's Jukebox/Note Blocks slider."""
        self._call("media.volume", volume=float(volume))

    def state(self) -> dict:
        """{'state': 'playing'|'paused'|'stopped'|'ended', 'name', 'kind': 'file'|'sound', 'position', 'duration'
        (seconds, -1 unknown), 'volume', 'shared', 'error'}."""
        return dict(self._call("media.state")["state"])


media = _Media()

_MODE = [None]
_CLIENT_EVENT_NAMES = ("client_tick", "chat_received", "screen_open", "client_join", "client_leave")
_ALL_EVENT_NAMES = _EVENT_NAMES + _CLIENT_EVENT_NAMES
_HUD_ANCHORS = ("top_left", "top_center", "top_right", "center_left", "center", "center_right",
                "bottom_left", "bottom_center", "bottom_right")
_HUD_LAST = {}   # (owner, id) -> the last element sent, so an unchanged element costs nothing
_KEYS = {}       # (owner, stable action ID) -> (function, description)
_KEY_NAMES = frozenset("abcdefghijklmnopqrstuvwxyz0123456789") | frozenset("f%d" % n for n in range(1, 13)) | frozenset({
    "space", "enter", "tab", "up", "down", "left", "right", "home", "end", "pageup", "pagedown", "insert", "delete",
    "backspace", "minus", "equals", "comma", "period", "slash", "semicolon", "grave", "bracket_left", "bracket_right"})


def _client_mode():
    if _MODE[0] is None:
        import os
        _MODE[0] = os.environ.get("CRAFTPY_MODE", "server")
    return _MODE[0] == "client"


def _require_client(what):
    if not _client_mode():
        raise RuntimeError("%s only works in client-side code: use a project or module whose side is 'client' "
                           "(the server has no screen, keys or HUD)" % what)


def _normalize_combo(text):
    """'Ctrl+K' -> 'ctrl+k'; modifiers in a fixed order. Raises ValueError for a key that does not exist."""
    parts = [p.strip().lower() for p in str(text).split("+") if p.strip()]
    mods = [m for m in ("ctrl", "shift", "alt") if m in parts]
    keys = [p for p in parts if p not in ("ctrl", "shift", "alt")]
    if len(keys) != 1 or keys[0] not in _KEY_NAMES:
        raise ValueError("keys.bind(%r): use one key such as 'r', 'f6', 'space', with ctrl / shift / alt in front: "
                         "'ctrl+shift+k'" % (text,))
    return "+".join(mods + keys)


def _hud_color(color):
    import re
    if color in _COLORS or (isinstance(color, str) and re.fullmatch(r"#[0-9a-fA-F]{6}", color)):
        return color
    raise ValueError("HUD color %r: one of %s or '#rrggbb'" % (color, ", ".join(_COLORS)))


def _hud_number(name, value, low, high):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value != value or not low <= value <= high:
        raise ValueError("hud %s must be a number from %s to %s" % (name, low, high))
    return value


class _Hud:
    """Text, bars and item icons on the player's screen. ``hud.text("fps", "Hello", anchor="top_right")``."""

    def _put(self, element_id, kind, fields):
        import re
        _require_client("hud")
        if not isinstance(element_id, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", element_id):
            raise ValueError("hud id must be 1 to 64 letters, digits, _ . -")
        if fields.get("anchor") not in _HUD_ANCHORS:
            raise ValueError("hud anchor %r: one of %s" % (fields.get("anchor"), ", ".join(_HUD_ANCHORS)))
        fields["x"] = _hud_number("x", fields["x"], -4000, 4000)
        fields["y"] = _hud_number("y", fields["y"], -4000, 4000)
        fields["scale"] = _hud_number("scale", fields["scale"], 0.25, 8)
        owner = _OWNER[0]
        element = dict(fields, owner=owner, id=element_id, kind=kind)
        if _HUD_LAST.get((owner, element_id)) == element:
            return
        _HUD_LAST[(owner, element_id)] = element
        _ok(_host_call("hud.set", element))

    def text(self, id, text, anchor="top_left", x=0, y=0, color="white", scale=1.0, shadow=True):
        if len(str(text)) > 256:
            raise ValueError("hud.text: at most 256 characters")
        self._put(id, "text", {"text": str(text), "anchor": anchor, "x": x, "y": y, "color": _hud_color(color),
                               "scale": scale, "shadow": bool(shadow)})

    def bar(self, id, value, max=100, anchor="top_left", x=0, y=0, width=80, height=6, color="green",
            background="dark_gray", label=None):
        self._put(id, "bar", {"value": _hud_number("value", value, -1e9, 1e9), "max": _hud_number("max", max, 1e-9, 1e9),
                              "width": _hud_number("width", width, 1, 600), "height": _hud_number("height", height, 1, 60),
                              "anchor": anchor, "x": x, "y": y, "color": _hud_color(color),
                              "background": _hud_color(background), "scale": 1.0,
                              "label": None if label is None else str(label)[:64]})

    def item(self, id, item, anchor="top_left", x=0, y=0, count=None, scale=1.0):
        self._put(id, "item", {"item": str(item), "anchor": anchor, "x": x, "y": y, "scale": scale,
                               "count": None if count is None else int(_hud_number("count", count, 0, 999))})

    def remove(self, id):
        _require_client("hud")
        _HUD_LAST.pop((_OWNER[0], id), None)
        _ok(_host_call("hud.remove", {"owner": _OWNER[0], "id": str(id)}))

    def clear(self):
        _require_client("hud")
        for key in [k for k in _HUD_LAST if k[0] == _OWNER[0]]:
            del _HUD_LAST[key]
        _ok(_host_call("hud.clear", {"owner": _OWNER[0]}))


hud = _Hud()


class _Keys:
    """Your own key bindings: ``@keys.bind("ctrl+k")`` or ``keys.bind("f6", fn)``. They work while no screen is open."""

    def bind(self, combo, fn=None, *, id=None, name=None, category="CraftPy"):
        import re
        combo = _normalize_combo(combo)
        # Legacy bindings retain their combo as an implicit ID; explicit IDs survive default-key changes too.
        action = combo if id is None else id
        if not isinstance(action, str) or not re.fullmatch(r"[a-zA-Z0-9_.:+-]{1,128}", action):
            raise ValueError("keys.bind id must be 1-128 letters, digits, _, ., :, + or -")
        display = name if name is not None else action
        if not isinstance(display, str) or not display.strip() or len(display) > 128 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in display):
            raise ValueError("keys.bind name must be 1-128 characters")
        if not isinstance(category, str) or not category.strip() or len(category) > 80 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in category):
            raise ValueError("keys.bind category must be 1-80 characters")

        def register(function):
            if not callable(function):
                raise TypeError("keys.bind needs a function")
            if (_OWNER[0], action) not in _KEYS and sum(o == _OWNER[0] for o, _ in _KEYS) >= 32:
                raise ValueError("At most 32 key actions per owner")
            _KEYS[(_OWNER[0], action)] = (function, {"id": action, "name": display, "category": category, "combo": combo})
            return function
        return register(fn) if callable(fn) else register



keys = _Keys()


def _app_open(data):
    """A Hub app was opened: open the window it declares for that player, as the module that declared it."""
    log = []
    owner = data.get("owner", "")
    window_id = data.get("window", "")
    _LOG_TARGET[0] = log
    try:
        _use_owner(owner)
        ui.open(Player(data.get("player") or {}), window_id)
    except Exception as exc:  # noqa: BLE001
        log.append("[%s] app window %r could not open: %s: %s" % (owner, window_id, type(exc).__name__, exc))
    finally:
        _LOG_TARGET[0] = None
    return {"log": log, "handlers": 1, "scheduled": len(_SCHEDULE)}


def _key_event(data):
    """A key the player pressed: runs the function bound to it (by the owner that bound it)."""
    action = data.get("action", data.get("combo", ""))
    log = []
    ran = 0
    _LOG_TARGET[0] = log
    try:
        for (owner, bound), (fn, description) in list(_KEYS.items()):
            if bound != action or (data.get("owner") is not None and data["owner"] != owner):
                continue
            _use_owner(owner)
            ran += 1
            try:
                ctx = _TickContext(_TICK[0])
                ctx.action = action
                if isinstance(data.get("player"), dict):
                    ctx.player = Player(data["player"])
                _call_flexible(fn, ctx)
            except Exception as exc:  # noqa: BLE001
                log.append("[%s] key %s: %s: %s" % (owner, action, type(exc).__name__, exc))
    finally:
        _LOG_TARGET[0] = None
    return {"log": log, "handlers": ran, "scheduled": len(_SCHEDULE)}


class _ClientPlayer:
    """The local player as the client knows it (read only): name, health, pos, held_item..."""

    def __init__(self, data):
        self.name = data.get("name", "")
        self.uuid = data.get("uuid", "")
        self.health = data.get("health", 0.0)
        self.max_health = data.get("max_health", 20.0)
        self.food = data.get("food", 20)
        self.level = data.get("level", 0)
        self.x, self.y, self.z = data.get("x", 0.0), data.get("y", 0.0), data.get("z", 0.0)
        self.dimension = data.get("dimension", "")
        self.gamemode = data.get("gamemode", "")
        self.held_item = data.get("held_item", "minecraft:air")
        self.held_count = data.get("held_count", 0)
        self.on_ground = data.get("on_ground", True)

    @property
    def pos(self):
        return Pos(self.x, self.y, self.z)

    def __repr__(self):
        return "ClientPlayer(%r)" % self.name


class _Client:
    """What the client can do: read the local player, show things only this player sees, open local windows."""

    def player(self):
        _require_client("client")
        return _ClientPlayer((_ok(_host_call("client.player", {})) or {}).get("player") or {})

    def looking_at(self):
        _require_client("client")
        return (_ok(_host_call("client.looking_at", {})) or {}).get("target")

    def fps(self):
        _require_client("client")
        return (_ok(_host_call("client.fps", {})) or {}).get("fps", 0)

    def chat(self, text):
        _require_client("client")
        _ok(_host_call("client.chat", {"text": str(text)[:512]}))

    def sound(self, sound, volume=1.0, pitch=1.0):
        _require_client("client")
        _ok(_host_call("client.sound", {"sound": str(sound), "volume": float(volume), "pitch": float(pitch)}))

    def toast(self, title, message=""):
        _require_client("client")
        _ok(_host_call("client.toast", {"title": str(title)[:64], "message": str(message)[:128]}))

    def open_window(self, window_id):
        _require_client("client")
        return ui.open(self.player().name, window_id)

    def close_window(self, window_id=None):
        _require_client("client")
        return ui.close(self.player().name, window_id)


client = _Client()


_CLIENT_NAMES = frozenset({
    "on", "every", "after", "run_later", "run_every", "run_next_tick", "scheduler", "storage", "Pos", "has",
    "window", "Window", "ui", "action", "Label", "Button", "TextInput", "TextArea", "Checkbox", "Slider", "Dropdown",
    "ListView", "ProgressBar", "Image", "ItemIcon", "Video", "Audio", "VBox", "HBox", "Grid", "TabView", "ScrollView", "Canvas",
    "hud", "keys", "client", "API_VERSION", "http", "files", "Response", "media",
})


def _api_names():
    names = _server_api_names()
    if _client_mode():
        return {k: v for k, v in names.items() if k in _CLIENT_NAMES}
    return names


def _server_api_names():
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
        "advancement": advancement, "raw_json": raw_json, "recipe": recipe,
        "window": window, "Window": Window, "ui": ui, "cp": cp, "action": action,
        "world": world, "players": players, "server": server,
        "hud": hud, "keys": keys, "client": client, "http": http, "files": files, "Response": Response, "media": media,
        "storage": storage, "scheduler": scheduler,
        "run_later": run_later, "run_every": run_every, "run_next_tick": run_next_tick,
        "Label": Label, "Button": Button, "TextInput": TextInput, "TextArea": TextArea,
        "Checkbox": Checkbox, "Slider": Slider, "Dropdown": Dropdown, "ListView": ListView,
        "ProgressBar": ProgressBar, "Image": Image, "ItemIcon": ItemIcon, "Video": Video, "Audio": Audio,
        "VBox": VBox, "HBox": HBox, "Grid": Grid, "TabView": TabView, "ScrollView": ScrollView, "Canvas": Canvas,
        "has": has, "core": core, "requires_restart": requires_restart,
        # simple API
        "Block": Block, "NativeBlock": NativeBlock, "NativeMob": NativeMob, "Item": Item, "Food": Food, "Content": Content, "Mob": Mob, "Spawn": Spawn, "Effect": Effect, "Fluid": Fluid,
        "Pool": Pool, "Structure": Structure,
        "shaped": shaped, "shapeless": shapeless, "smelt": smelt, "stonecut": stonecut,
        "after": after, "Pos": Pos, "Player": Player, "Entity": Entity,
        "player": player, "say": say, "run": run, "java": java, "JavaError": JavaError,
    }
    for name, (since, use) in _DEPRECATED.items():
        names[name] = _deprecated(name, names[name])
    names["API_VERSION"] = API_VERSION
    names.update(_EXTENSION_NAMES)
    for name, (owner, cls) in list(_USER_EXTENSIONS.items()):
        names.setdefault(name, cls)  # the built-in API always wins over a same-named extension
    return names


# ---------------------------------------------------------------------------
# Console tools: helpers that only exist in the console (not in project code).
# They act on the project the console belongs to and on the player who typed.
# ---------------------------------------------------------------------------
_CONSOLE_PLAYER = [None]
_CONSOLE_LOG = [None]  # the output list of the console line being run


class _Me:
    """You, the player typing in the console: ``me.give("diamond")``, ``me.pos``. Always fresh data."""

    def _now(self):
        name = _CONSOLE_PLAYER[0]
        found = Player.named(name) if name else None
        if found is None:
            raise RuntimeError("me is the player typing in the console; there is none here (the IDE outside the game?)")
        return found

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        return getattr(self._now(), name)

    def __repr__(self):
        return "me (%s)" % (_CONSOLE_PLAYER[0] or "nobody")


me = _Me()


def _console_state():
    return _OWNERS.get(_OWNER[0]) or {"decls": _new_decls(), "handlers": {}}


def _content_ids(kind):
    return ["%s:%s" % (_namespace(), d["id"]) for d in _console_state()["decls"][kind + "s"]]


def tools():
    """Lists the console tools with what they do."""
    lines = []
    for fn in (here, content, give_all, showcase, fire, timers, stop_timers, slowest, bench, near, docs):
        doc = (fn.__doc__ or "").strip().splitlines()
        lines.append("  %-14s %s" % (fn.__name__ + "()", doc[0] if doc else ""))
    lines.append("  %-14s %s" % ("me", "you: me.give('diamond'), me.heal(), me.teleport(0, 80, 0)"))
    return "\n".join(["Console tools (only here, not in project code):"] + lines)


def here():
    """Where you are: position, dimension, biome, the block under you, time of day."""
    p = me._now()
    x, y, z = int(p.x // 1), int(p.y // 1), int(p.z // 1)
    under = world.get_block(x, y - 1, z, dimension=p.dimension)
    return ("%s at %d %d %d in %s\n  biome %s, standing on %s, %s, health %s/%s"
            % (p.name, x, y, z, p.dimension, world.biome(x, y, z, dimension=p.dimension),
               getattr(under, "id", under), "day" if world.is_day(p.dimension) else "night", p.health, p.max_health))


def content():
    """Everything this project declares (after the last Run): blocks, items, mobs, recipes, handlers..."""
    decls = _console_state()["decls"]
    lines = []
    for key in ("blocks", "items", "entities", "effects", "fluids", "enchantments", "recipes", "loot_tables",
                "commands", "sounds", "advancements"):
        rows = decls.get(key) or []
        if rows:
            names = [str(r.get("id", r.get("name", "?"))) for r in rows if isinstance(r, dict)]
            shown = ", ".join(names[:12]) + (" ..." if len(names) > 12 else "")
            lines.append("  %-13s %3d  %s" % (key, len(rows), shown))
    handlers = _console_state()["handlers"]
    if handlers:
        lines.append("  %-13s %3d  %s" % ("handlers", sum(len(v) for v in handlers.values()), ", ".join(sorted(handlers))))
    if not lines:
        return "This project declares nothing yet: press Run first."
    return "\n".join(["%s declares:" % _OWNER[0]] + lines)


def give_all(count=1):
    """Gives you one (or count) of every item and block of this project, for testing."""
    p = me._now()
    ids = _content_ids("item") + _content_ids("block")
    for ident in ids:
        p.give(ident, count)
    return "Gave %d thing(s) x%d to %s" % (len(ids), count, p.name) if ids else "No items or blocks: press Run first."


def showcase(gap=1):
    """Places every block of this project in a row 3 blocks south of you, going east (replaces what is there)."""
    p = me._now()
    blocks = _content_ids("block")
    if not blocks:
        return "No blocks: press Run first."
    x, y, z = int(p.x // 1), int(p.y // 1), int(p.z // 1) + 3
    for i, ident in enumerate(blocks):
        world.set_block(x + i * (gap + 1), y, z, ident, dimension=p.dimension)
    return "Placed %d block(s) from %d %d %d going east" % (len(blocks), x, y, z)


def fire(event, **data):
    """Runs this project's @on(event) handlers now with test data: fire("block_break", block="minecraft:stone").
    The player is you and the position is yours unless you pass player= / x= y= z=."""
    handlers = _console_state()["handlers"].get(event, [])
    if not handlers:
        known = ", ".join(sorted(_console_state()["handlers"])) or "none"
        return "No handler for %r in this project (it has: %s)" % (event, known)
    log = _CONSOLE_LOG[0] if _CONSOLE_LOG[0] is not None else []
    ctx = _EventContext(log)
    ctx.event = event
    try:
        p = me._now()
        ctx.player = p
        for axis in ("x", "y", "z"):
            setattr(ctx, axis, int(getattr(p, axis) // 1))
        ctx.dimension = p.dimension
    except RuntimeError:
        pass
    for key, value in data.items():
        if key in ("block", "item") and isinstance(value, str):
            value = {"id": value if ":" in value else "minecraft:" + value, "name": value}
        if key in _PLAYER_KEYS and isinstance(value, str):
            value = Player.named(value)
        setattr(ctx, key, value if isinstance(value, (Player, Entity)) else _wrap_data(value))
    results = []
    previous = _LOG_TARGET[0]
    _LOG_TARGET[0] = log  # the handlers' print() lands in the console
    try:
        for fn, wanted in list(handlers):
            if wanted and not _matches(ctx, wanted):
                log.append("(skipped %s: its filter %s does not match)" % (getattr(fn, "__name__", "?"), wanted))
                continue
            results.append(_call_flexible(fn, ctx))
    finally:
        _LOG_TARGET[0] = previous
    cancelled = " - it cancelled the event" if ctx.__dict__.get("_cancelled") else ""
    return "Ran %d %s handler(s)%s" % (len(results), event, cancelled)


def timers():
    """This project's timers: what runs, how often and in how many ticks."""
    rows = [e for e in _SCHEDULE if e["owner"] == _OWNER[0] and not e["task"].cancelled]
    if not rows:
        return "No timers running in this project."
    lines = ["  %-24s %s, next in %d tick(s)" % (getattr(e["task"].fn, "__name__", "?"),
                                                  "every %d ticks" % e["task"].interval if e["task"].repeat else "once",
                                                  max(0, e["next"] - _TICK[0])) for e in rows]
    return "\n".join(["%d timer(s):" % len(rows)] + lines)


def stop_timers():
    """Cancels every timer of this project (Run starts them again)."""
    rows = [e for e in _SCHEDULE if e["owner"] == _OWNER[0] and not e["task"].cancelled]
    for e in rows:
        e["task"].cancel()
    return "Stopped %d timer(s)" % len(rows)


def slowest(n=10):
    """The handlers and timers of this project that took the most time so far."""
    rows = [r for r in _profile_report()["rows"] if r["owner"] == _OWNER[0]][:n]
    if not rows:
        return "Nothing ran yet."
    lines = ["  %-32s %6d calls  avg %8.3f ms  max %8.3f ms%s"
             % (r["name"], r["calls"], r["avg_ms"], r["max_ms"], "  %d errors" % r["errors"] if r["errors"] else "")
             for r in rows]
    return "\n".join(lines)


def bench(fn, times=1000):
    """Times a function: bench(lambda: my_math(5), 10000) -> average time per call."""
    started = time.perf_counter()
    for _ in range(int(times)):
        fn()
    total = time.perf_counter() - started
    return "%d calls: %.3f ms total, %.4f ms per call" % (times, total * 1000, total * 1000 / max(1, times))


def near(radius=16):
    """Mobs and other entities around you, counted by type."""
    p = me._now()
    counts = {}
    for e in world.entities(p.x, p.y, p.z, radius=radius, dimension=p.dimension):
        kind = getattr(e, "type", None) or "?"
        counts[kind] = counts.get(kind, 0) + 1
    if not counts:
        return "Nothing within %d blocks." % radius
    return "\n".join("  %3d  %s" % (n, kind) for kind, n in sorted(counts.items(), key=lambda kv: -kv[1]))


def docs(thing=None):
    """What a name of the API does: docs(Block), docs(world.spawn); docs() lists every name."""
    if thing is None:
        names = sorted(n for n in _api_names() if not n.startswith("_"))
        return "API names (docs(name) for one):\n  " + ", ".join(names)
    try:
        signature = str(inspect.signature(thing))
    except (TypeError, ValueError):
        signature = ""
    name = getattr(thing, "__name__", type(thing).__name__)
    return "%s%s\n%s" % (name, signature, inspect.getdoc(thing) or "(no description)")


_CONSOLE_NAMES = {"me": me, "tools": tools, "here": here, "content": content, "give_all": give_all,
                  "showcase": showcase, "fire": fire, "timers": timers, "stop_timers": stop_timers,
                  "slowest": slowest, "bench": bench, "near": near, "docs": docs}


def _get_session(session_id):
    if session_id not in _REPL_SESSIONS:
        log = []
        ns = {
            "__name__": "__repl_%s__" % session_id,
            "__builtins__": restricted_builtins(log),
        }
        ns.update(_api_names())
        ns.update(_CONSOLE_NAMES)
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
    _use_owner(params.get("project") or "__console__")
    _CONSOLE_PLAYER[0] = params.get("player")
    session = _get_session(session_id)
    ns = session["ns"]
    log = session["log"]

    del log[:]
    _CONSOLE_LOG[0] = log

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
            _check_user_code(tree, "<console>")
            exec(compile(tree, "<console>", "exec"), ns, ns)
        if last is not None:
            _check_user_code(last, "<console>")
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
    return {"output": list(log), "value": value, "error": error, "session": session_id,
            "scheduled": len(_SCHEDULE)}


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

    with open(main_file, "r", encoding="utf-8") as f:
        code = f.read()

    # Modules are loaded into their own namespace and are NOT reset on run_main
    # They behave like permanent additions to the API/registry
    owner = "module:%s" % module_id
    library = bool(params.get("library"))
    snapshot = _snapshot_owner(owner)
    try:
        _drop_owner(owner, host=not library)
        _read_ui_dir(base_dir, owner)
        result = _run_code(code, main_file, "module_%s" % module_id, persistent=True, owner=owner,
                           sources=_read_sources(base_dir))
    except BaseException:
        _restore_owner(owner, snapshot)
        raise
    result["log"].extend(_api_notes(base_dir, "module.json"))
    if library:
        # a dependency loaded into the worker of the project (or module) that imports it: only its names are kept.
        # Its handlers, timers and content belong to its own worker, so nothing it registers here may run twice.
        export = _MODULE_EXPORTS.get(module_id)
        _drop_owner(owner, host=False)
        if export is not None:
            _MODULE_EXPORTS[module_id] = export
        _use_owner("__main__")
        return {"log": result["log"], "scheduled": len(_SCHEDULE)}
    _PREVIOUS[owner] = snapshot
    return result


# ---------------------------------------------------------------------------
# Java bridge. Python holds handles to Java objects (Minecraft, NeoForge, other mods) and the host reads fields,
# calls methods, builds objects and subscribes to events by name. See `java` in the API; the rules about what
# is reachable live in the host (config javaBridge.mode).
# ---------------------------------------------------------------------------

class JavaError(RuntimeError):
    """A Java call failed (unknown class, no such method, an exception inside Java, or the bridge is off)."""


_JAVA_RELEASE = []
_FN_TABLE = {}
_FN_NEXT = [1]


def _register_fn(fn):
    ident = _FN_NEXT[0]
    _FN_NEXT[0] += 1
    _FN_TABLE[ident] = (_OWNER[0], fn)
    return ident


def _java_call(method, raw=False, **params):
    if _JAVA_RELEASE:
        params["release"] = list(_JAVA_RELEASE)
        del _JAVA_RELEASE[:]
    result = _host_call("java." + method, params)
    if not isinstance(result, dict) or not result.get("ok"):
        message = result.get("error") if isinstance(result, dict) else None
        raise JavaError(message or "The Java bridge is not available")
    value = result.get("value")
    return value if raw else _jdec(value)


def _jenc(value):
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, JavaObject):
        return {"$h": value._h}
    if isinstance(value, JavaClass):
        return {"$c": value._n}
    if isinstance(value, (list, tuple)):
        return [_jenc(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _jenc(item) for key, item in value.items()}
    if callable(value):
        return {"$fn": _register_fn(value)}
    raise TypeError("Cannot pass a %s to Java" % type(value).__name__)


def _jdec(value, owned=True):
    if isinstance(value, dict):
        if "$h" in value:
            return JavaObject(value["$h"], value.get("$t", ""), value.get("$s", ""), owned)
        if "$c" in value:
            return JavaClass(value["$c"])
        return {key: _jdec(item, owned) for key, item in value.items()}
    if isinstance(value, list):
        return [_jdec(item, owned) for item in value]
    return value


class JavaObject:
    """A live Java object. Fields and methods are used by their Java names: ``player.getHealth()``,
    ``block_state.getBlock()``. Lists, sets and maps can be iterated; ``str(obj)`` is Java's toString()."""
    __slots__ = ("_h", "_t", "_s", "_owned")

    def __init__(self, handle, type_name, text, owned=True):
        object.__setattr__(self, "_h", handle)
        object.__setattr__(self, "_t", type_name)
        object.__setattr__(self, "_s", text)
        object.__setattr__(self, "_owned", owned)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return _java_member(self, name)

    def __setattr__(self, name, value):
        _java_call("set", target=_jenc(self), name=name, value=_jenc(value))

    def __str__(self):
        return self._s

    def __repr__(self):
        return "<java %s %s>" % (self._t.rsplit(".", 1)[-1], self._s)

    def __eq__(self, other):
        return isinstance(other, JavaObject) and other._h == self._h

    def __hash__(self):
        return hash(self._h)

    def __iter__(self):
        return iter(_java_call("items", target=_jenc(self)))

    def __del__(self):
        if self._owned:
            _JAVA_RELEASE.append(self._h)


class JavaClass:
    """A Java class: ``Blocks = java.cls("net.minecraft.world.level.block.Blocks"); Blocks.STONE`` reads a static
    field, ``Blocks.SomeStatic(...)`` calls a static method, and calling the class itself builds an object."""
    __slots__ = ("_n",)

    def __init__(self, name):
        object.__setattr__(self, "_n", name)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return _java_member(self, name)

    def __setattr__(self, name, value):
        _java_call("set", target=_jenc(self), name=name, value=_jenc(value))

    def __call__(self, *args):
        return _java_call("new", name=self._n, args=[_jenc(a) for a in args])

    def __repr__(self):
        return "<java class %s>" % self._n

    def __eq__(self, other):
        return isinstance(other, JavaClass) and other._n == self._n

    def __hash__(self):
        return hash(self._n)


class _JavaMethod:
    __slots__ = ("_target", "_name")

    def __init__(self, target, name):
        self._target = target
        self._name = name

    def __call__(self, *args):
        return _java_call("invoke", target=_jenc(self._target), name=self._name, args=[_jenc(a) for a in args])

    def __repr__(self):
        return "<java method %s>" % self._name


def _java_member(target, name):
    raw = _java_call("get", raw=True, target=_jenc(target), name=name)
    if isinstance(raw, dict) and "$m" in raw:
        return _JavaMethod(target, name)
    return _jdec(raw)


class _JavaApi:
    """Reach any Java class that is loaded - Minecraft, NeoForge, or another mod - without waiting for CraftPy
    to wrap it. Names are the Mojang-mapped names used in the game's own code."""

    def available(self):
        """True when the host allows the bridge (config javaBridge.mode is not OFF)."""
        try:
            _java_call("mods")
            return True
        except JavaError:
            return False

    def cls(self, name):
        """The class with this full name: ``java.cls("net.minecraft.world.level.block.Blocks").STONE``."""
        _java_call("class", name=name)  # fails early for unknown or forbidden classes
        return JavaClass(name)

    def new(self, name, *args):
        """Builds an object: ``java.new("net.minecraft.core.BlockPos", 1, 64, 2)``."""
        return _java_call("new", name=name, args=[_jenc(a) for a in args])

    def on(self, event_class, priority="normal", receive_cancelled=False):
        """Handles any NeoForge event by class: ``@java.on("net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent")``.
        The handler gets the event object and may call setCanceled(True) or change it."""
        name = event_class._n if isinstance(event_class, JavaClass) else str(event_class)

        def deco(fn):
            _java_call("listen", name=name, priority=priority, receive_cancelled=receive_cancelled)
            _DECLS["handlers"].append({"event": "java:" + name, "every": None, "priority": priority})
            _HANDLERS.setdefault("java:" + name, []).append((fn, {}))
            return fn
        return deco

    def items(self, obj):
        """A Java list, set, map (as key/value pairs), array or stream as a Python list."""
        return _java_call("items", target=_jenc(obj))

    def members(self, obj):
        """Public fields and methods of a Java object or class: ``java.members(Blocks)``."""
        return _java_call("members", target=_jenc(obj))

    def mods(self):
        """The mods that are loaded: a list of {id, name, version}."""
        return _java_call("mods")

    def server(self):
        """The MinecraftServer."""
        return _java_call("server")

    def level(self, dimension="minecraft:overworld"):
        """A ServerLevel by dimension id."""
        return _java_call("level", dimension=dimension)

    def entity(self, uuid):
        """The live entity or player with this uuid (a player's uuid works too)."""
        return _java_call("entity", uuid=str(uuid))


java = _JavaApi()


# ---------------------------------------------------------------------------
# Multi-file projects. code/blocks/ruby.py is `import blocks.ruby`, code/util.py is `import util`,
# code/pkg/__init__.py is the package `pkg`. Files come from the host (or the module folder) as text.
# ---------------------------------------------------------------------------

def _module_name(path):
    """'code/blocks/ruby.py' -> ('blocks.ruby', False); 'code/pkg/__init__.py' -> ('pkg', True); None for main."""
    rel = path.replace("\\", "/")
    if rel.startswith("code/"):
        rel = rel[5:]
    if not rel.endswith(".py") or rel == "main.py":
        return None
    rel = rel[:-3]
    package = rel.endswith("/__init__")
    if package:
        rel = rel[: -len("/__init__")]
    name = rel.replace("/", ".")
    if not name or any(not part.isidentifier() for part in name.split(".")):
        return None
    return name, package


class ProjectFinder:
    """Imports the project's own files inside the sandbox; everything else falls through to the normal finders."""

    def __init__(self, sources, log):
        self.modules = {}
        self.log = log
        for path, code in (sources or {}).items():
            named = _module_name(path)
            if named is not None:
                self.modules[named[0]] = (path, code, named[1])

    def names(self):
        return list(self.modules)

    def find_spec(self, fullname, path=None, target=None):
        entry = self.modules.get(fullname)
        if entry is None:
            return None
        import importlib.machinery
        return importlib.machinery.ModuleSpec(fullname, self, origin=entry[0], is_package=entry[2])

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        path, code, package = self.modules[module.__name__]
        if package:
            module.__path__ = []
        module.__dict__["__builtins__"] = restricted_builtins(self.log)
        module.__dict__["__file__"] = path
        exec(_compile_user(code, path), module.__dict__)


# ---------------------------------------------------------------------------
# Using other modules: `from modules.jukebox import songs`, `import modules.jukebox as jb`.
# A loaded module leaves its main.py names and its files behind as `modules.<id>`; a project (or another module)
# imports them instead of copying code. The module must be installed and enabled; list it under "dependencies"
# in project.json so it loads first and the built module says it needs it.
# ---------------------------------------------------------------------------
_MODULE_EXPORTS = {}   # module id -> a module object: main.py's public names, plus its own files as attributes


def _export_module(module_id, globals_, finder):
    import types as _types
    export = _types.ModuleType("modules." + module_id)
    export.__dict__.update({k: v for k, v in globals_.items() if not k.startswith("_")})
    for name in finder.names():
        top = name.split(".")[0]
        if top in sys.modules and top not in export.__dict__:
            export.__dict__[top] = sys.modules[top]
    export.__path__ = []
    _MODULE_EXPORTS[module_id] = export


class ModulesFinder:
    """Resolves `modules`, `modules.<id>` and `modules.<id>.<file>` to what installed modules exported."""

    def find_spec(self, fullname, path=None, target=None):
        parts = fullname.split(".")
        if parts[0] != "modules":
            return None
        if len(parts) >= 2 and parts[1] not in _MODULE_EXPORTS:
            raise ImportError("Module '%s' is not installed or not enabled (Modules page). List it under "
                              "\"dependencies\" in project.json so it loads before this code." % parts[1])
        if len(parts) == 3 and not hasattr(_MODULE_EXPORTS[parts[1]], parts[2]):
            raise ImportError("Module '%s' has no file or name '%s'" % (parts[1], parts[2]))
        if len(parts) > 3:
            return None
        import importlib.machinery
        return importlib.machinery.ModuleSpec(fullname, self, is_package=len(parts) <= 2)

    def create_module(self, spec):
        parts = spec.name.split(".")
        if len(parts) == 1:
            import types as _types
            root = _types.ModuleType("modules")
            root.__path__ = []
            return root
        export = _MODULE_EXPORTS[parts[1]]
        return export if len(parts) == 2 else getattr(export, parts[2])

    def exec_module(self, module):
        pass  # already executed when its module loaded


def _forget_module_imports():
    for name in [n for n in sys.modules if n == "modules" or n.startswith("modules.")]:
        sys.modules.pop(name, None)


def _read_sources(base_dir):
    """All code/**/*.py of a folder on disk (used by modules): relative path -> text."""
    import os
    out = {}
    root = os.path.join(base_dir, "code")
    for folder, _dirs, names in os.walk(root):
        for name in names:
            if name.endswith(".py"):
                full = os.path.join(folder, name)
                rel = "code/" + os.path.relpath(full, root).replace(os.sep, "/")
                with open(full, "r", encoding="utf-8") as handle:
                    out[rel] = handle.read()
    return out


_PREVIOUS = {}


def _snapshot_owner(owner):
    """Everything ``owner`` registered, so a failed re-run can put it back (handlers keep working)."""
    return {"state": _OWNERS.get(owner),
            "schedule": [e for e in _SCHEDULE if e["owner"] == owner],
            "ui_open": {k: v for k, v in _UI_OPEN.items() if v["owner"] == owner},
            "ui_named": {k: v for k, v in _UI_NAMED.items() if k[0] == owner},
            "ui_files": {k: v for k, v in _UI_FILES.items() if k[0] == owner},
            "ui_data": {k: v for k, v in _UI_DATA.items() if k[0] == owner},
            "windows": {k: v for k, v in _WINDOWS.items() if k[0] == owner},
            "keys": {k: v for k, v in _KEYS.items() if k[0] == owner},
            "event_last": {k: v for k, v in _EVENT_LAST.items() if k[0] == owner},
            "profile": {k: v for k, v in _PROFILE.items() if k[0] == owner},
            "fns": {k: v for k, v in _FN_TABLE.items() if v[0] == owner}}


def _restore_owner(owner, snap):
    _drop_owner(owner)
    if snap["state"] is not None:
        _OWNERS[owner] = snap["state"]
    _SCHEDULE.extend(snap["schedule"])
    _UI_OPEN.update(snap["ui_open"])
    _UI_NAMED.update(snap["ui_named"])
    _UI_FILES.update(snap["ui_files"])
    _UI_DATA.update(snap["ui_data"])
    _WINDOWS.update(snap["windows"])
    _KEYS.update(snap["keys"])
    _EVENT_LAST.update(snap["event_last"])
    _PROFILE.update(snap["profile"])
    _FN_TABLE.update(snap["fns"])
    if snap["state"] is not None:
        _use_owner(owner)


def _run_code(code, filename, module_name, debug=False, persistent=False, owner="__main__", sources=None, after=None):
    _use_owner(owner)
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
        if _BREAKPOINTS and event == "line":
            _debug_check(frame)
        if debug and event == "line" and frame.f_code.co_filename == filename:
            if len(steps) < 4000:
                steps.append(frame.f_lineno)
        return _combined_tracer

    sys.modules["craftpy"] = sandbox_module
    _DEPRECATION_HITS.clear()
    compiled = _compile_user(code, filename)
    finder = ProjectFinder(sources, log)
    modules_finder = ModulesFinder()
    sys.meta_path.insert(0, modules_finder)
    sys.meta_path.insert(0, finder)
    try:
        sys.settrace(_combined_tracer)
        exec(compiled, globals_, globals_)
        if after is not None:  # the test runner: calls the file's test functions while its imports still resolve
            after(globals_, log)
        if not persistent:
            main_fn = globals_.get("main")
            if callable(main_fn):
                main_fn(_Context(log, debug))
        if owner.startswith("module:"):
            _export_module(owner[len("module:"):], globals_, finder)
    except KeyboardInterrupt as exc:
        log.append("STOPPED: %s" % exc)
    finally:
        sys.settrace(None)
        sys.modules.pop("craftpy", None)
        _forget_module_imports()  # a reloaded module must be imported fresh next time
        if modules_finder in sys.meta_path:
            sys.meta_path.remove(modules_finder)
        if finder in sys.meta_path:
            sys.meta_path.remove(finder)
        for name in finder.names():  # the next run must execute the files again
            sys.modules.pop(name, None)
    if debug:
        names = sorted(k for k in globals_ if not k.startswith("_"))
        log.append("[debug] traced %d line(s): %s" % (len(steps), steps[:80]))
        log.append("[debug] top-level names: %s" % (", ".join(names) if names else "(none)"))
        log.append("[debug] declarations: %d block(s), %d item(s), %d effect(s), "
                   "%d recipe(s), %d loot table(s), %d tag(s)"
                   % (len(_DECLS["blocks"]), len(_DECLS["items"]), len(_DECLS["effects"]),
                      len(_DECLS["recipes"]), len(_DECLS["loot_tables"]), len(_DECLS["tags"])))
    return {"declarations": _DECLS, "log": log, "scheduled": len(_SCHEDULE)}


def _is_test_file(path):
    name = path.rsplit("/", 1)[-1]
    return path.startswith("code/") and name.startswith("test_") and name.endswith(".py")


def _run_tests(params):
    """Runs every ``test*`` function of the project's ``code/test_*.py`` files (or of ``file`` only), each on its own:
    a failing test does not stop the others. The files run as a throwaway owner, so what they declare or schedule is
    gone afterwards and the loaded project is not touched."""
    project = params.get("project") or "__main__"
    sources = params.get("sources") or {}
    only = params.get("file") or ""
    owner = "test:" + project
    previous = _OWNER[0]
    files = sorted(path for path in sources if _is_test_file(path) and (not only or path == only))
    results = []
    output = []

    def where(tb, path):
        line = 0
        for frame in traceback.extract_tb(tb):
            if frame.filename in sources:
                path, line = frame.filename, frame.lineno
        return path, line

    def run_file(path):
        def call_tests(namespace, log):
            tests = [fn for name, fn in namespace.items()
                     if name.startswith("test") and inspect.isfunction(fn) and fn.__code__.co_filename == path]
            tests.sort(key=lambda fn: fn.__code__.co_firstlineno)
            for fn in tests:
                started = time.perf_counter()
                row = {"file": path, "name": fn.__name__, "line": fn.__code__.co_firstlineno - 1}
                try:
                    fn()
                    row["ok"] = True
                except KeyboardInterrupt:
                    raise
                except Exception as exc:  # noqa: BLE001
                    file_name, line = where(sys.exc_info()[2], path)
                    text = str(exc)
                    if isinstance(exc, AssertionError) and not text:
                        source = sources.get(file_name, "").split("\n")
                        text = source[line - 1].strip() if 0 < line <= len(source) else "assert failed"
                    row.update(ok=False, file=file_name, line=max(0, line - 1),
                               message=text if isinstance(exc, AssertionError) else "%s: %s" % (type(exc).__name__, text))
                row["ms"] = round((time.perf_counter() - started) * 1000, 2)
                results.append(row)

        _drop_owner(owner)
        try:
            result = _run_code(sources[path], path, "__craftpy_test__", persistent=True, owner=owner,
                               sources=sources, after=call_tests)
            output.extend(result.get("log", []))
        except Exception as exc:  # noqa: BLE001  the file itself did not run (syntax error, failed import)
            file_name, line = where(sys.exc_info()[2], path)
            if isinstance(exc, SyntaxError) and exc.lineno:
                file_name, line = exc.filename if exc.filename in sources else path, exc.lineno
            results.append({"file": file_name, "name": "(file)", "line": max(0, line - 1), "ok": False, "ms": 0,
                            "message": "%s: %s" % (type(exc).__name__, exc)})

    try:
        for path in files:
            run_file(path)
            if _INTERRUPT:
                break
    finally:
        _drop_owner(owner)
        _use_owner(previous)
    return {"results": results, "files": files, "log": output[-200:]}


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
                "implementation": "CPython", "hello": "craftpy", "api": API_VERSION}
    if method == "interrupt":
        _INTERRUPT = True
        return {"ok": True}
    if method == "capabilities":
        return {"capabilities": sorted(_CAPABILITIES)}
    if method == "validate":
        _INTERRUPT = False
        return _validate(params)
    if method in ("complete", "hover", "signature"):
        _ASSIST_SOURCES.clear()
        _ASSIST_SOURCES.update(params.get("sources") or {})
        try:
            if method == "complete":
                return _complete(params)
            if method == "hover":
                return _hover(params)
            return _signature_help(params)
        finally:
            _ASSIST_SOURCES.clear()
    if method == "window_list":
        return {"windows": _code_windows(params.get("sources") or {})}
    if method == "window_to_json":
        return _window_to_json(params.get("sources") or {}, params.get("window", ""))
    if method == "definition":
        return _definition(params)
    if method == "stub":
        return _stub(params)
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
    if method == "profile":
        return _profile_report()
    if method == "profile_reset":
        _PROFILE.clear()
        return {"ok": True}
    if method == "unload_owner":
        _drop_owner(params.get("owner", ""))
        return {"ok": True, "scheduled": len(_SCHEDULE)}
    if method == "call_fn":
        entry = _FN_TABLE.get(params.get("id"))
        if entry is None:
            return {"error": "that Python function was unloaded"}
        owner, fn = entry
        _use_owner(owner)
        try:
            return {"value": _jenc(fn(*[_jdec(a) for a in params.get("args", [])]))}
        except Exception as exc:  # noqa: BLE001
            return {"error": "%s: %s" % (type(exc).__name__, exc)}
    if method == "revert_owner":
        owner = params.get("owner", "")
        snapshot = _PREVIOUS.pop(owner, None)
        if snapshot is None:
            _drop_owner(owner)
        else:
            _restore_owner(owner, snapshot)
        return {"ok": True, "scheduled": len(_SCHEDULE)}
    if method == "debug":
        return _debug(params)
    if method == "run_tests":
        _INTERRUPT = False
        return _run_tests(params)
    if method == "run_main":
        code = params.get("code", "")
        name = params.get("file", "main.py")
        debug = bool(params.get("debug", False))
        owner = params.get("project") or "__main__"
        sources = params.get("sources") or {}
        # Transaction: the previous version stays registered until the new one ran without an error.
        snapshot = _snapshot_owner(owner)
        try:
            _drop_owner(owner)
            _read_ui_dir(params.get("dir"), owner)
            result = _run_code(code, name, "__craftpy_main__", debug=debug, owner=owner, sources=sources)
            result["log"].extend(_api_notes(params.get("dir"), "project.json"))
            _PREVIOUS[owner] = snapshot  # kept until the host confirms it could apply this run (revert_owner)
            return result
        except Exception as exc:  # noqa: BLE001
            _restore_owner(owner, snapshot)
            file_name, line = name, 0
            known = set(sources) | {name}
            for frame in reversed(traceback.extract_tb(sys.exc_info()[2])):
                if frame.filename in known:
                    file_name, line = frame.filename, frame.lineno
                    break
            if isinstance(exc, SyntaxError) and exc.lineno:  # also what the sandbox check refuses
                file_name, line = (exc.filename if exc.filename in known else name), exc.lineno
            return {"error": True, "message": str(exc), "file": file_name,
                    "line": max(0, line - 1),
                    "problems": [{"file": file_name, "line": max(0, line - 1),
                                  "column": 0, "message": "%s: %s" % (type(exc).__name__, exc),
                                  "severity": "error"}]}
    raise ValueError("Unknown method: %s" % method)


def reset():
    """Forgets every project and module (used by tests / a full worker reset)."""
    _OWNERS.clear()
    _KEYS.clear()
    _EVENT_LAST.clear()
    _WINDOWS.clear()
    _UI_DATA.clear()
    _UI_FILES.clear()
    _UI_NAMED.clear()
    _UI_OPEN.clear()
    _PROFILE.clear()
    _SCHEDULE[:] = []
    _TICK[0] = 0
    _use_owner("__main__")


def _trigger_java_event(event_name, ctx_data):
    """A NeoForge event the script subscribed to with @java.on: the handler receives the event object itself."""
    log = []
    results = []
    event = _jdec(ctx_data.get("java"), owned=False)  # the host releases this handle after the handlers ran
    _LOG_TARGET[0] = log
    started = time.perf_counter()
    try:
        ran = _dispatch_event(event_name, event, log, results)
    finally:
        _LOG_TARGET[0] = None
    return {"log": log, "results": [], "scheduled": len(_SCHEDULE), "handlers": ran,
            "ms": round((time.perf_counter() - started) * 1000, 3)}


def _trigger_event(params):
    event_name = params.get("event")
    ctx_data = params.get("context", {}) or {}
    if event_name == "ui_event":
        return _ui_event(ctx_data)
    if event_name == "app_open":
        return _app_open(ctx_data)
    if event_name == "key":
        return _key_event(ctx_data)
    if event_name == "http_response":
        return _http_response(ctx_data)
    if event_name.startswith("java:"):
        return _trigger_java_event(event_name, ctx_data)
    log = []
    ctx = _EventContext(log)
    ctx.event = event_name
    for key, value in ctx_data.items():
        if key in _PLAYER_KEYS and isinstance(value, dict):
            setattr(ctx, key, Player(value))
        elif key in _ENTITY_KEYS and isinstance(value, dict):
            setattr(ctx, key, Entity(value))
        else:
            setattr(ctx, key, _wrap_data(value))

    results = []
    _LOG_TARGET[0] = log
    started = time.perf_counter()
    ran = 0
    try:
        ran = _dispatch_event(event_name, ctx, log, results)
    finally:
        _LOG_TARGET[0] = None

    response = {"log": log, "results": [r for r in results if isinstance(r, (str, int, float, bool, type(None)))],
                "scheduled": len(_SCHEDULE), "handlers": ran,
                "ms": round((time.perf_counter() - started) * 1000, 3)}
    if ctx.__dict__.get("_cancelled"):
        response["cancel"] = True
    if "damage" in ctx.__dict__ and ctx.__dict__["damage"] != ctx_data.get("damage"):
        response["damage"] = float(ctx.__dict__["damage"])  # item_hit: the handler changed the damage
    if "state" in ctx.__dict__:
        response["state"] = _unwrap_data(ctx.state)
    if "global_state" in ctx.__dict__:
        response["global_state"] = _unwrap_data(ctx.global_state)
    return response


def _dispatch_event(event_name, ctx, log, results):
    """Runs every matching handler; returns how many ran."""
    ran = 0
    for owner, state in list(_OWNERS.items()):
        for fn, wanted in list(state["handlers"].get(event_name, [])):
            if wanted and not _matches(ctx, wanted):
                continue
            period = getattr(fn, "_craftpy_throttle_ticks", {}).get((event_name, tuple(sorted(wanted.items()))), 0)
            if period:
                subject = getattr(ctx, "entity", getattr(ctx, "player", None))
                key = (owner, event_name, fn, getattr(subject, "uuid", "global"))
                now = getattr(ctx, "tick", _TICK[0])
                last = _EVENT_LAST.get(key)
                if last is not None and 0 <= now - last < period:
                    continue
                if len(_EVENT_LAST) >= 4096:
                    _EVENT_LAST.pop(next(iter(_EVENT_LAST)))
                _EVENT_LAST[key] = now
            _use_owner(owner)
            ran += 1
            started = time.perf_counter()
            failed = False
            try:
                results.append(_call_flexible(fn, ctx))
            except Exception as exc:  # noqa: BLE001
                failed = True
                tb = traceback.extract_tb(sys.exc_info()[2])
                where = (" (line %d)" % tb[-1].lineno) if tb else ""
                log.append("[%s] ERROR in %s handler %s%s: %s: %s"
                           % (owner, event_name, getattr(fn, "__name__", "?"), where, type(exc).__name__, exc))
                traceback.print_exc(file=sys.stderr)
            finally:
                _profile_add(owner, "%s:%s" % (event_name, getattr(fn, "__name__", "?")),
                             time.perf_counter() - started, failed)
    return ran

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
                if name.startswith("__"):
                    raise AttributeError(name)
                return self.__dict__.get(name)

            def get(self, name, default=None):
                return self.__dict__.get(name, default)

            def __getitem__(self, name):
                return self.__dict__[name]

            def __repr__(self):
                return "DataObj(%r)" % self.__dict__
        obj = DataObj()
        for k, v in data.items():
            setattr(obj, k, _wrap_data(v))
        return obj
    if isinstance(data, list):
        return [_wrap_data(x) for x in data]
    return data


_KEYWORDS = ("False None True and as assert async await break class continue def del elif else except finally for "
             "from global if import in is lambda nonlocal not or pass raise return try while with yield").split()


def _describe(name, obj):
    """(kind, signature, first doc paragraph) of an API object, for completion and hover."""
    import inspect
    kind = "class" if inspect.isclass(obj) else "fn" if callable(obj) else "field"
    sig = ""
    if kind != "field":
        try:
            sig = str(inspect.signature(obj))
        except (TypeError, ValueError):
            sig = "(...)"
    doc = inspect.getdoc(obj) if kind != "field" or not isinstance(obj, (int, float, str, bool)) else ""
    return kind, sig, (doc or "").strip()


def _resolve_chain(chain, extra=None):
    """Looks `a.b.c` up in the API namespace without calling anything. None if unknown."""
    names = _api_names()
    if extra:
        names.update(extra)
    parts = chain.split(".")
    if parts[0] not in names:
        return None
    obj = names[parts[0]]
    for part in parts[1:]:
        if part.startswith("_"):
            return None
        try:
            obj = getattr(obj, part)
        except Exception:  # noqa: BLE001
            return None
    return obj


def _local_defs(code):
    """name -> (kind, signature, doc) for defs, classes and assignments of the file."""
    import ast
    out = {}
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return out
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            args = [a.arg for a in node.args.args]
            out[node.name] = ("fn", "(" + ", ".join(args) + ")", ast.get_docstring(node) or "")
        elif isinstance(node, ast.ClassDef):
            out[node.name] = ("class", "", ast.get_docstring(node) or "")
        elif isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
            out.setdefault(node.id, ("field", "", ""))
        elif isinstance(node, ast.arg):
            out.setdefault(node.arg, ("field", "", ""))
    return out


def _first_line(doc):
    return doc.strip().split("\n")[0] if doc else ""


# ---------------------------------------------------------------------------
# Static inference for the editor: what `x.` / `Item("a").extends(` / `event.player.` is, without running anything.
# Values are API objects, _Inst (an instance of a class), or the _Local* wrappers for defs and classes of the edited file.
# ---------------------------------------------------------------------------

class _Inst:
    """An inferred instance of `cls`; `elem` is the element type of a list."""

    def __init__(self, cls, elem=None):
        self.cls = cls
        self.elem = elem


class _Method:
    """A function looked up on an instance: called without its first argument."""

    def __init__(self, fn):
        self.fn = fn


class _LocalFn:
    def __init__(self, node, scope, owner=None):
        self.node, self.scope, self.owner = node, scope, owner


class _LocalClass:
    def __init__(self, node, scope):
        self.node, self.scope = node, scope


class _LocalInst:
    def __init__(self, node, scope):
        self.node, self.scope = node, scope


class _Scope:
    def __init__(self, parent=None):
        self.parent = parent
        self.bindings = {}

    def bind(self, name, line, kind, value):
        self.bindings.setdefault(name, []).append((line, kind, value, self))

    def lookup(self, name, line):
        entries = self.bindings.get(name)
        if entries:
            before = [e for e in entries if e[0] < line]
            return (before or entries)[-1]
        return self.parent.lookup(name, line) if self.parent else None


_MISSING = object()
_MAX_DEPTH = 14


def _mask(text):
    """Same length as text, with comments and string contents blanked (quotes kept), for bracket matching."""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "#":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if c in "\"'":
            quote = text[i:i + 3] if text[i:i + 3] in ('"""', "'''") else c
            j = i + len(quote)
            while j < n and not text.startswith(quote, j):
                if text[j] == "\\":
                    out[j] = " "
                    j += 1
                elif text[j] == "\n" and len(quote) == 1:
                    break
                if j < n:
                    out[j] = " " if text[j] != "\n" else "\n"
                j += 1
            i = j + len(quote)
            continue
        i += 1
    return "".join(out)


def _match_back(masked, close):
    """Index of the bracket opening the one at `close`, or -1."""
    depth = 0
    for i in range(close, -1, -1):
        c = masked[i]
        if c in ")]}":
            depth += 1
        elif c in "([{":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _receiver(masked, end):
    """Start of the expression ending at `end` (a name, attribute chain, call or literal), or -1."""
    i = end
    while i > 0:
        c = masked[i - 1]
        if c in ")]":
            j = _match_back(masked, i - 1)
            if j < 0:
                return -1
            i = j
            if i > 0 and (masked[i - 1].isalnum() or masked[i - 1] in "_)]\"'"):
                continue  # a call or subscript of what comes before
            break
        if c.isalnum() or c == "_":
            while i > 0 and (masked[i - 1].isalnum() or masked[i - 1] == "_"):
                i -= 1
            if i > 0 and masked[i - 1] == ".":
                i -= 1
                continue
            break
        if c in "\"'":
            j = masked.rfind(c, 0, i - 1)
            return j
        break
    return i if i < end else -1


def _open_call(masked):
    """(start, end) of the callee of the innermost call still open at the end of `masked`; None inside a list/dict."""
    depth = 0
    for i in range(len(masked) - 1, -1, -1):
        c = masked[i]
        if c in ")]}":
            depth += 1
        elif c in "([{":
            if depth == 0:
                if c != "(":
                    return None
                end = i
                while end > 0 and masked[end - 1] == " ":
                    end -= 1
                start = _receiver(masked, end)
                return (start, end) if start >= 0 else None
            depth -= 1
    return None


def _parse_lenient(code, line_idx):
    """The module AST of code being typed: the cursor line (or what follows it) is replaced until it parses."""
    import ast
    try:
        return ast.parse(code)
    except SyntaxError:
        pass
    lines = code.split("\n")
    current = lines[line_idx] if 0 <= line_idx < len(lines) else ""
    indent = current[:len(current) - len(current.lstrip())]
    attempts = [lines[:line_idx] + [indent + "pass"] + lines[line_idx + 1:], lines[:line_idx] + [indent + "pass"]]
    for back in range(1, 25):
        cut = line_idx - back
        if cut < 0:
            break
        prev = lines[cut]
        prev_indent = prev[:len(prev) - len(prev.lstrip())]
        # one `pass` per dropped line, so a def around the cursor still reaches the cursor line
        attempts.append(lines[:cut] + [prev_indent + "pass"] * (line_idx - cut + 1))
    for attempt in attempts:
        try:
            return ast.parse("\n".join(attempt))
        except SyntaxError:
            continue
    return None


def _collect(body, scope):
    """Binds the names a block assigns (not looking into nested defs and classes)."""
    import ast
    for node in body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            scope.bind(node.name, node.lineno, "def", node)
        elif isinstance(node, ast.ClassDef):
            scope.bind(node.name, node.lineno, "class", node)
        elif isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name):
                    scope.bind(target.id, node.lineno, "expr", node.value)
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            scope.bind(node.target.id, node.lineno, "ann", node.annotation)
        elif isinstance(node, (ast.For, ast.AsyncFor)) and isinstance(node.target, ast.Name):
            scope.bind(node.target.id, node.lineno, "iter", node.iter)
        elif isinstance(node, ast.ImportFrom) and node.module and node.module != "craftpy" and not node.level:
            for alias in node.names:
                if alias.name != "*":
                    scope.bind(alias.asname or alias.name, node.lineno, "from", (node.module, alias.name))
        elif isinstance(node, ast.Import):
            for alias in node.names:
                if alias.asname:
                    scope.bind(alias.asname, node.lineno, "module", alias.name)
                else:
                    scope.bind(alias.name.split(".")[0], node.lineno, "module", alias.name.split(".")[0])
        elif isinstance(node, (ast.With, ast.AsyncWith)):
            for item in node.items:
                if isinstance(item.optional_vars, ast.Name):
                    scope.bind(item.optional_vars.id, node.lineno, "expr", item.context_expr)
        for field in ("body", "orelse", "finalbody"):
            inner = getattr(node, field, None)
            if isinstance(inner, list) and not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                _collect(inner, scope)
        for handler in getattr(node, "handlers", []) or []:
            if handler.name:
                scope.bind(handler.name, handler.lineno, "exc", handler.type)
            _collect(handler.body, scope)


_CONTEXT_DECORATORS = {"on": "_EventContext", "command": "_EventContext", "every": "_TickContext",
                       "action": "_UiContext"}


def _decorator_root(node):
    import ast
    while isinstance(node, (ast.Call, ast.Attribute)):
        node = node.func if isinstance(node, ast.Call) else node.value
    return node.id if isinstance(node, ast.Name) else None


def _function_scope(fn, parent, owner=None, top_level=False):
    """Scope of a def: its parameters (typed by annotation, decorator or position) and its assignments."""
    import ast
    scope = _Scope(parent)
    positional = list(fn.args.posonlyargs) + list(fn.args.args)
    decorators = {_decorator_root(d) for d in fn.decorator_list}
    defaults = list(fn.args.defaults)
    first_default = len(positional) - len(defaults)
    for index, arg in enumerate(positional):
        kind, value = "param", None
        if arg.annotation is not None:
            kind, value = "ann", arg.annotation
        elif index == 0 and owner is not None and "staticmethod" not in decorators:
            kind, value = "value", _LocalClass(owner, parent) if "classmethod" in decorators else _LocalInst(owner, parent)
        elif index == 0:
            context = next((_CONTEXT_DECORATORS[d] for d in decorators if d in _CONTEXT_DECORATORS), None)
            if context is None and top_level and fn.name == "main":
                context = "_Context"
            if context is not None:
                kind, value = "value", _Inst(globals()[context])
        if kind == "param" and index >= first_default:
            kind, value = "expr", defaults[index - first_default]
        # an untyped parameter is still bound (to nothing), so it hides an API name of the same spelling
        scope.bind(arg.arg, 0, "value" if kind == "param" else kind, value)
    for arg, default in zip(fn.args.kwonlyargs, fn.args.kw_defaults):
        if arg.annotation is not None:
            scope.bind(arg.arg, 0, "ann", arg.annotation)
        else:
            scope.bind(arg.arg, 0, "expr" if default is not None else "value", default)
    if fn.args.vararg:
        scope.bind(fn.args.vararg.arg, 0, "value", _Inst(tuple))
    if fn.args.kwarg:
        scope.bind(fn.args.kwarg.arg, 0, "value", _Inst(dict))
    _collect(fn.body, scope)
    return scope


def _scope_for(tree, line):
    """The scope at a 1-based line: module names, then every def around the line (innermost last)."""
    import ast
    scope = _Scope()
    if tree is None:
        return scope
    _collect(tree.body, scope)
    body, owner, top = tree.body, None, True
    while True:
        found = None
        for node in body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) \
                    and node.lineno <= line <= (getattr(node, "end_lineno", None) or node.lineno):
                found = node
                break
        if found is None:
            return scope
        if isinstance(found, ast.ClassDef):
            owner, body = found, found.body  # class-level names are not visible inside its methods
            continue
        scope = _function_scope(found, scope, owner, top)
        body, owner, top = found.body, None, False


def _type_value(annotation):
    """An annotation (a class or its name, `list[Name]`) as an inferred value."""
    import builtins
    import re
    if isinstance(annotation, str):
        m = re.fullmatch(r"\s*(?:list|List)\[\s*([A-Za-z_]\w*)\s*\]\s*", annotation)
        if m:
            return _Inst(list, _type_value(m.group(1)))
        annotation = globals().get(annotation.strip(), getattr(builtins, annotation.strip(), None))
    return _Inst(annotation) if isinstance(annotation, type) else None


def _annotation_node(node, scope, line, depth):
    import ast
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        try:
            return _annotation_node(ast.parse(node.value, mode="eval").body, scope, line, depth + 1)
        except SyntaxError:
            return None
    if isinstance(node, ast.Subscript):
        base = _infer(node.value, scope, line, depth + 1)
        if base is list:
            inner = node.slice.value if isinstance(node.slice, getattr(ast, "Index", ())) else node.slice
            return _Inst(list, _annotation_node(inner, scope, line, depth + 1))
        return _call(base, depth + 1)
    return _call(_infer(node, scope, line, depth + 1), depth + 1)


def _returns(fn):
    try:
        annotation = inspect.signature(fn).return_annotation
    except (TypeError, ValueError):
        return None
    return None if annotation is inspect.Signature.empty else _type_value(annotation)


def _own_annotations(klass):
    """Annotations a class declares itself (Python 3.14 builds them lazily, so __dict__ alone is not enough)."""
    if not isinstance(klass, type):
        return {}
    try:
        return inspect.get_annotations(klass)
    except Exception:  # noqa: BLE001
        return klass.__dict__.get("__annotations__", {}) or {}


def _class_annotation(cls, name):
    for klass in getattr(cls, "__mro__", ()):
        annotations = _own_annotations(klass)
        if name in annotations:
            return annotations[name]
    return _MISSING


def _infer(node, scope, line, depth=0):
    import ast
    if node is None or depth > _MAX_DEPTH:
        return None
    if isinstance(node, ast.Name):
        binding = scope.lookup(node.id, line) if scope else None
        if binding is not None:
            return _binding_value(binding, depth + 1)
        names = _api_names()
        if node.id in names:
            return names[node.id]
        return restricted_builtins([]).get(node.id)
    if isinstance(node, ast.Attribute):
        return _member(_infer(node.value, scope, line, depth + 1), node.attr, depth + 1)
    if isinstance(node, ast.Call):
        return _call(_infer(node.func, scope, line, depth + 1), depth + 1)
    if isinstance(node, ast.Constant):
        return None if node.value is None or node.value is Ellipsis else _Inst(type(node.value))
    if isinstance(node, ast.JoinedStr):
        return _Inst(str)
    if isinstance(node, (ast.List, ast.ListComp)):
        first = node.elts[0] if isinstance(node, ast.List) and node.elts else getattr(node, "elt", None)
        return _Inst(list, _infer(first, scope, line, depth + 1))
    if isinstance(node, (ast.Dict, ast.DictComp)):
        return _Inst(dict)
    if isinstance(node, ast.Tuple):
        return _Inst(tuple)
    if isinstance(node, (ast.Set, ast.SetComp)):
        return _Inst(set)
    if isinstance(node, ast.Subscript):
        base = _infer(node.value, scope, line, depth + 1)
        return base.elem if isinstance(base, _Inst) else None
    if isinstance(node, ast.BinOp):
        return _infer(node.left, scope, line, depth + 1)
    return None


def _binding_value(binding, depth):
    line, kind, value, scope = binding
    if kind == "value":
        return value
    if kind == "def":
        return _LocalFn(value, scope)
    if kind == "class":
        return _LocalClass(value, scope)
    if kind == "expr":
        return _infer(value, scope, getattr(value, "lineno", line) or line, depth)
    if kind == "ann":
        return _annotation_node(value, scope, getattr(value, "lineno", line) or line, depth)
    if kind == "exc":
        return _call(_infer(value, scope, line, depth), depth) if value is not None else _Inst(Exception)
    if kind == "iter":
        iterable = _infer(value, scope, line, depth)
        return iterable.elem if isinstance(iterable, _Inst) else None
    if kind == "module":
        return _LocalModule(value) if _module_scope(value) is not None else None
    if kind == "from":
        module, name = value
        return _module_member(module, name, depth)
    return None


# Other files of the project, for completion and hover across imports: path -> text, set for the duration of a request.
_ASSIST_SOURCES = {}
_MODULE_SCOPES = {}  # (module, text hash) -> scope


class _LocalModule:
    """A file of the project, imported by name (`import items`)."""

    def __init__(self, name):
        self.name = name


def _module_scope(module):
    """The module-level scope of a project file (code/<module>.py or a package __init__), or None when there is none."""
    import ast
    base = "code/" + module.replace(".", "/")
    text = _ASSIST_SOURCES.get(base + ".py")
    if text is None:
        text = _ASSIST_SOURCES.get(base + "/__init__.py")
    if text is None:
        return None
    key = (module, hash(text))
    scope = _MODULE_SCOPES.get(key)
    if scope is None:
        try:
            tree = ast.parse(text)
        except SyntaxError:
            return None
        scope = _Scope()
        _collect(tree.body, scope)
        if len(_MODULE_SCOPES) > 64:
            _MODULE_SCOPES.clear()
        _MODULE_SCOPES[key] = scope
    return scope


def _module_member(module, name, depth=0):
    if depth > _MAX_DEPTH:
        return None
    scope = _module_scope(module)
    if scope is None:
        return None
    binding = scope.lookup(name, 10 ** 9)
    return _binding_value(binding, depth + 1) if binding is not None else None


def _call(value, depth=0):
    """What calling an inferred value returns."""
    if value is None or depth > _MAX_DEPTH:
        return None
    if isinstance(value, type):
        return _Inst(value)
    if isinstance(value, _LocalClass):
        return _LocalInst(value.node, value.scope)
    if isinstance(value, _LocalFn):
        fn = value.node
        scope = _function_scope(fn, value.scope, value.owner)
        if fn.returns is not None:
            return _annotation_node(fn.returns, scope, fn.lineno, depth + 1)
        import ast
        for node in ast.walk(fn):
            if isinstance(node, ast.Return) and node.value is not None:
                return _infer(node.value, scope, node.lineno, depth + 1)
        return None
    if isinstance(value, _Method):
        return _returns(value.fn)
    if isinstance(value, (_Inst, _LocalInst)):
        return None
    return _returns(value) if callable(value) else None


def _local_class_members(node, scope, depth=0):
    """name -> (binding kind, value node or def, scope) of a class of the file, its own members before its bases'."""
    import ast
    members = {}
    for stmt in node.body:
        if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef)):
            members.setdefault(stmt.name, ("def", stmt))
            for sub in ast.walk(stmt):
                if isinstance(sub, ast.Assign):
                    for target in sub.targets:
                        if isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name) \
                                and target.value.id == "self":
                            members.setdefault(target.attr, ("self", (stmt, sub.value)))
        elif isinstance(stmt, ast.Assign):
            for target in stmt.targets:
                if isinstance(target, ast.Name):
                    members.setdefault(target.id, ("expr", stmt.value))
        elif isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
            members.setdefault(stmt.target.id, ("ann", stmt.annotation))
    return members


def _local_bases(node, scope, depth):
    out = []
    for base in node.bases:
        value = _infer(base, scope, node.lineno, depth + 1)
        if isinstance(value, (type, _LocalClass)):
            out.append(value)
    return out


def _member(value, name, depth=0):
    """value.name as an inferred value."""
    if value is None or depth > _MAX_DEPTH or name.startswith("_"):
        return None
    if isinstance(value, _LocalModule):
        return _module_member(value.name, name, depth + 1)
    if isinstance(value, (_LocalInst, _LocalClass)):
        entry = _local_class_members(value.node, value.scope).get(name)
        if entry is not None:
            kind, data = entry
            if kind == "def":
                return _LocalFn(data, value.scope, value.node)
            if kind == "self":
                method, rhs = data
                scope = _function_scope(method, value.scope, value.node)
                return _infer(rhs, scope, rhs.lineno, depth + 1)
            if kind == "ann":
                return _annotation_node(data, value.scope, data.lineno, depth + 1)
            return _infer(data, value.scope, data.lineno, depth + 1)
        for base in _local_bases(value.node, value.scope, depth):
            found = _member(_Inst(base) if isinstance(base, type) and isinstance(value, _LocalInst)
                            else _LocalInst(base.node, base.scope) if isinstance(base, _LocalClass) else base,
                            name, depth + 1)
            if found is not None:
                return found
        return None
    if isinstance(value, _Inst):
        cls = value.cls
        annotation = _class_annotation(cls, name)
        if annotation is not _MISSING:
            return _type_value(annotation)
        attr = inspect.getattr_static(cls, name, _MISSING)
        if attr is _MISSING:
            return None
        if isinstance(attr, property):
            return _returns(attr.fget) if attr.fget else None
        if isinstance(attr, (staticmethod, classmethod)):
            return attr.__func__
        if inspect.isfunction(attr):
            return _Method(attr)
        return attr
    if isinstance(value, (_Method, _LocalFn)):
        return None
    attr = inspect.getattr_static(value, name, _MISSING)
    if attr is _MISSING:
        # `on.block_break` and other names a class makes up in __getattr__ (only where that has no side effects)
        return getattr(value, name, None) if isinstance(value, _On) else None
    if isinstance(attr, property):
        return _returns(attr.fget) if attr.fget else None
    if isinstance(attr, (staticmethod, classmethod)):
        return attr.__func__
    if inspect.isfunction(attr) and not isinstance(value, type) and not inspect.ismodule(value):
        return _Method(attr)
    return attr


def _signature(fn, skip_first=False):
    try:
        sig = inspect.signature(fn)
    except (TypeError, ValueError):
        return "(...)"
    if skip_first:
        sig = sig.replace(parameters=list(sig.parameters.values())[1:])
    return str(sig)


def _type_name(annotation):
    if isinstance(annotation, str):
        return annotation.strip("_")
    return getattr(annotation, "__name__", str(annotation)).strip("_")


def _members(value, depth=0):
    """name -> (kind, signature, doc) of what `value.` offers."""
    import ast
    out = {}
    if value is None or depth > 4:
        return out
    if isinstance(value, _LocalModule):
        scope = _module_scope(value.name)
        for name, entries in (scope.bindings.items() if scope else []):
            if name.startswith("_"):
                continue
            kind = entries[-1][1]
            node = entries[-1][2]
            if kind == "def":
                out[name] = ("fn", "(" + ", ".join(a.arg for a in node.args.args) + ")", _docstring(node))
            elif kind == "class":
                out[name] = ("class", "", _docstring(node))
            else:
                out[name] = ("field", "", "")
        return out
    if isinstance(value, (_LocalInst, _LocalClass)):
        for name, (kind, data) in _local_class_members(value.node, value.scope).items():
            if name.startswith("_"):
                continue
            if kind == "def":
                args = [a.arg for a in data.args.args]
                if args and isinstance(value, _LocalInst) and "staticmethod" not in {_decorator_root(d) for d in data.decorator_list}:
                    args = args[1:]
                out[name] = ("fn", "(" + ", ".join(args) + ")", ast.get_docstring(data) or "")
            else:
                out[name] = ("field", "", "")
        for base in _local_bases(value.node, value.scope, depth):
            inherited = _members(_Inst(base) if isinstance(base, type) and isinstance(value, _LocalInst)
                                 else _LocalInst(base.node, base.scope) if isinstance(base, _LocalClass) else base, depth + 1)
            for name, row in inherited.items():
                out.setdefault(name, row)
        return out
    if isinstance(value, (_Method, _LocalFn)):
        return out
    instance = isinstance(value, _Inst)
    target = value.cls if instance else value
    names = set(dir(target))
    if instance:
        for klass in getattr(target, "__mro__", ()):
            names.update(_own_annotations(klass))
    for name in names:
        if name.startswith("_"):
            continue
        annotation = _class_annotation(target, name) if instance else _MISSING
        if annotation is not _MISSING:
            out[name] = ("field", ": " + _type_name(annotation), "")
            continue
        attr = inspect.getattr_static(target, name, _MISSING)
        static = attr is not _MISSING
        if not static:
            try:
                attr = getattr(target, name)  # made up by __getattr__/__dir__ (`on.<event>`)
            except Exception:  # noqa: BLE001
                continue
        if isinstance(attr, property):
            returned = inspect.signature(attr.fget).return_annotation if attr.fget else inspect.Signature.empty
            out[name] = ("field", "" if returned is inspect.Signature.empty else ": " + _type_name(returned),
                         (inspect.getdoc(attr) or "").strip())
        elif isinstance(attr, (staticmethod, classmethod)):
            out[name] = ("fn", _signature(attr.__func__, isinstance(attr, classmethod)), (inspect.getdoc(attr.__func__) or "").strip())
        elif inspect.isclass(attr):
            out[name] = ("class", _signature(attr), (inspect.getdoc(attr) or "").strip())
        elif callable(attr):
            bound = static and (inspect.isfunction(attr) or inspect.ismethoddescriptor(attr)) and (instance or not (isinstance(target, type) or inspect.ismodule(target)))
            out[name] = ("fn", _signature(attr, bound), (inspect.getdoc(attr) or "").strip())
        else:
            out[name] = ("field", "", "")
    return out


def _param_names(value):
    """Keyword names a call of `value` accepts."""
    import ast
    if isinstance(value, _LocalFn):
        args = [a.arg for a in value.node.args.args + value.node.args.kwonlyargs]
        return args[1:] if value.owner is not None and args else args
    if isinstance(value, _LocalClass):
        init = _local_class_members(value.node, value.scope).get("__init__")
        return [a.arg for a in init[1].args.args[1:]] if init and init[0] == "def" else []
    fn, skip = (value.fn, True) if isinstance(value, _Method) else (value, False)
    if fn is None or isinstance(fn, (_Inst, _LocalInst)) or not callable(fn):
        return []
    try:
        parameters = list(inspect.signature(fn).parameters.values())
    except (TypeError, ValueError):
        return []
    if skip:
        parameters = parameters[1:]
    return [p.name for p in parameters
            if p.kind in (p.POSITIONAL_OR_KEYWORD, p.KEYWORD_ONLY) and not p.name.startswith("_")]


def _cursor(params):
    """(code, 0-based line, text of the code before the cursor)."""
    code = params.get("code", "")
    lines = code.split("\n")
    line_idx = params.get("line", 0)
    col = params.get("column", 0)
    if not 0 <= line_idx < len(lines):
        return code, line_idx, None
    return code, line_idx, "\n".join(lines[:line_idx] + [lines[line_idx][:col]])


def _infer_span(code, line_idx, masked, start, end):
    import ast
    expr = masked[start:end].strip()
    if not expr or expr.replace("_", "").isdigit():
        return None
    try:
        node = ast.parse("(" + expr + ")", mode="eval").body
    except SyntaxError:
        return None
    tree = _parse_lenient(code, line_idx)
    return _infer(node, _scope_for(tree, line_idx + 1), line_idx + 1)


def _string_choices(callee, param):
    """The fixed values a string argument accepts (tier="diamond", slot="mainhand"...), or None."""
    name = getattr(callee.fn if isinstance(callee, _Method) else callee, "__name__", "")
    if param == "slot":
        return Attributes._SLOTS if name == "Attributes" else _SLOTS if name == "Armor" else None
    if param == "operation" and name == "Attributes":
        return tuple(Attributes._OPERATIONS)
    if param == "tool":
        return _MINEABLE_TOOLS + ("sword",) if name == "Block" else _MINEABLE_TOOLS if name == "MineableBy" else None
    if param == "sound" and name == "Block":
        return _BLOCK_SOUNDS
    if param == "color" and name == "Tooltip":
        return _COLORS
    return {"tier": tuple(_TIERS), "kind": tuple(_TOOL_KINDS), "reaction": _PISTON_REACTIONS,
            "using": _SMELTERS}.get(param)


def _open_string(text):
    """Where the content of the string the text ends inside starts, or -1 (outside strings, also in comments)."""
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "#":
            j = text.find("\n", i)
            if j < 0:
                return -1
            i = j
            continue
        if c in "\"'":
            quote = text[i:i + 3] if text[i:i + 3] in ('"""', "'''") else c
            j = start = i + len(quote)
            while j < n and not text.startswith(quote, j):
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "\n" and len(quote) == 1:
                    break
                j += 1
            if j >= n:
                return start
            i = j + len(quote) if text.startswith(quote, j) else j
            continue
        i += 1
    return -1


def _call_argument(masked, span):
    """(positional index, keyword or None) of the argument being typed in the call whose callee is at span."""
    import re
    open_at = masked.find("(", span[1])
    depth, index, segment = 0, 0, open_at + 1
    for i in range(open_at + 1, len(masked)):
        ch = masked[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            index += 1
            segment = i + 1
    keyword = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", masked[segment:])
    return index, keyword.group(1) if keyword else None


def _param_at(callee, index, keyword):
    """The parameter name an argument fills: its keyword, or the positional parameter at index."""
    if keyword:
        return keyword
    fn, skip = (callee.fn, True) if isinstance(callee, _Method) else (callee, False)
    try:
        parameters = [p for p in inspect.signature(fn).parameters.values()
                      if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
    except (TypeError, ValueError):
        return None
    if skip:
        parameters = parameters[1:]
    return parameters[index].name if index < len(parameters) else None


def _complete_in_string(code, line_idx, before, start):
    """Inside a string: the fixed values the argument accepts (ids of mods are answered by the host before this)."""
    typed = before[start:]
    masked = _mask(before[:start])
    span = _open_call(masked.rstrip("\"'rbuf"))
    if span is None:
        return []
    callee = _infer_span(code, line_idx, masked, span[0], span[1])
    if callee is None:
        return []
    index, keyword = _call_argument(masked.rstrip("\"'rbuf"), span)
    choices = _string_choices(callee, _param_at(callee, index, keyword)) or ()
    return [{"n": value, "k": "id", "s": "", "d": "accepted value"} for value in choices
            if value.startswith(typed) and value != typed]


def _extension_classes():
    """name -> class of every extension that can be added: the built-in ones, the project's and the modules' own."""
    out = {name: value for name, value in _EXTENSION_NAMES.items()
           if isinstance(value, type) and issubclass(value, Extension)
           and value not in (Extension, ItemType, BlockType, _Data, _TypeBase)}
    for name, (owner, cls) in _USER_EXTENSIONS.items():
        out.setdefault(name, cls)
    return out


def _content_kind(node, scope, line, depth=0):
    """'item' or 'block' when the expression is (or is assigned from) Item(...) / Block(...), else None."""
    import ast
    if node is None or depth > 6:
        return None
    if isinstance(node, ast.Call):
        func = node.func
        if isinstance(func, ast.Name) and func.id in ("Item", "Block"):
            return func.id.lower()
        if isinstance(func, ast.Attribute):
            return _content_kind(func.value, scope, line, depth + 1)
        return None
    if isinstance(node, ast.Name) and scope is not None:
        binding = scope.lookup(node.id, line)
        if binding is not None and binding[1] == "expr":
            return _content_kind(binding[2], binding[3], binding[0], depth + 1)
    return None


def _extension_target(code, line_idx, masked):
    """Inside extends(...) or extensions=[...] of Item/Block: 'item', 'block' or 'any'; None anywhere else."""
    import ast
    import re
    depth = 0
    for i in range(len(masked) - 1, -1, -1):
        ch = masked[i]
        if ch in ")]}":
            depth += 1
        elif ch in "([{":
            if depth > 0:
                depth -= 1
                continue
            if ch == "(":
                span = _open_call(masked[:i + 1])
                text = masked[span[0]:span[1]].strip() if span else ""
                if not text.endswith(".extends"):
                    return None
                receiver = text[:-len(".extends")]
            elif ch == "[":
                if not re.search(r"extensions\s*=\s*$", masked[:i]):
                    return None
                span = _open_call(masked[:i])
                if span is None:
                    return None
                name = masked[span[0]:span[1]].strip()
                return name.lower() if name in ("Item", "Block") else "any"
            else:
                return None
            tree = _parse_lenient(code, line_idx)
            try:
                node = ast.parse("(" + receiver + ")", mode="eval").body
            except SyntaxError:
                return "any"
            return _content_kind(node, _scope_for(tree, line_idx + 1), line_idx + 1) or "any"
    return None


def _complete(params):
    import re
    code, line_idx, before = _cursor(params)
    if before is None:
        return {"prefix": "", "items": [], "suggestions": []}
    string_start = _open_string(before)
    if string_start >= 0:
        items = _complete_in_string(code, line_idx, before, string_start)
        return {"prefix": before[string_start:], "items": items, "suggestions": [i["n"] for i in items]}
    if before and _mask(before)[-1] != before[-1]:
        return {"prefix": "", "items": [], "suggestions": []}  # in a comment
    word = re.search(r'[A-Za-z_]\w*$', before)
    prefix = word.group(0) if word else ""
    masked = _mask(before[:len(before) - len(prefix)])
    items = []
    arguments = []
    stripped = masked.rstrip(" ")
    if stripped.endswith(".") and not stripped[:-1].rstrip().endswith("."):
        end = len(stripped) - 1
        start = _receiver(masked, end)
        target = _infer_span(code, line_idx, masked, start, end) if start >= 0 else None
        for name, (kind, sig, doc) in _members(target).items():
            rank = _rank(name, prefix)
            if rank is not None:
                items.append({"n": name, "k": kind, "s": sig, "d": _first_line(doc), "r": rank})
    else:
        target = _extension_target(code, line_idx, masked)
        if target is not None:  # extends( / extensions=[: the extensions that fit, before everything else
            for name, cls in sorted(_extension_classes().items()):
                fits = target == "any" or target in getattr(cls, "applies_to", ("item", "block"))
                if fits and _rank(name, prefix) is not None:
                    owner = _USER_EXTENSIONS.get(name, (None,))[0]
                    about = _first_line(inspect.getdoc(cls) or "")
                    arguments.append({"n": name, "k": "class", "s": _signature(cls),
                                      "d": ("from " + owner + ": " if owner else "") + about})
        if stripped.endswith(("(", ",")):
            span = _open_call(masked)
            if span is not None:
                callee = _infer_span(code, line_idx, masked, span[0], span[1])
                label = masked[span[0]:span[1]].strip()
                for name in _param_names(callee):
                    if name.startswith(prefix) and name != prefix:
                        arguments.append({"n": name + "=", "k": "kw", "s": "", "d": "argument of " + label})
        seen = set()

        def add(name, kind, sig, doc):
            rank = _rank(name, prefix)
            if rank is not None and name != prefix and name not in seen:
                seen.add(name)
                items.append({"n": name, "k": kind, "s": sig, "d": _first_line(doc), "r": rank})

        tree = _parse_lenient(code, line_idx)
        scope = _scope_for(tree, line_idx + 1)
        while scope is not None:
            for name, entries in scope.bindings.items():
                kind = entries[-1][1]
                if kind == "def":
                    node = entries[-1][2]
                    add(name, "fn", "(" + ", ".join(a.arg for a in node.args.args) + ")", _docstring(node))
                elif kind == "class":
                    add(name, "class", "", _docstring(entries[-1][2]))
                else:
                    add(name, "field", "", "")
            scope = scope.parent
        for name, (kind, sig, doc) in _local_defs(code).items():
            add(name, kind, sig, doc)
        for name, obj in _api_names().items():
            kind, sig, doc = _describe(name, obj)
            add(name, kind, sig, doc)
        for name in sorted(_USER_EXTENSIONS):
            add(name, "class", "", "an extension of this project")
        for name in _KEYWORDS:
            add(name, "kw", "", "")
        for name, obj in restricted_builtins([]).items():
            if not name.startswith("_"):
                kind, sig, doc = _describe(name, obj)
                add(name, kind, sig, doc)
    items.sort(key=lambda i: (i["k"] == "kw", i.pop("r", 0), i["n"].lower()))
    items = (arguments + items)[:_COMPLETION_LIMIT]
    return {"prefix": prefix, "items": items, "suggestions": [i["n"] for i in items]}


_COMPLETION_LIMIT = 5000  # the popup draws only the rows in view


def _signature_help(params):
    """Signature help: the call the cursor is in, with the argument being typed marked by >>...<<."""
    import re
    code, line_idx, before = _cursor(params)
    if not before:
        return {}
    masked = _mask(before)
    if masked[-1] != before[-1]:
        return {}  # in a string or a comment
    span = _open_call(masked)
    if span is None:
        return {}
    callee = _infer_span(code, line_idx, masked, span[0], span[1])
    if callee is None:
        return {}
    label = masked[span[0]:span[1]].strip()
    if isinstance(callee, (_LocalFn, _LocalClass)):
        node = callee.node
        if isinstance(callee, _LocalClass):
            init = _local_class_members(node, callee.scope).get("__init__")
            node = init[1] if init and init[0] == "def" else None
        names = [a.arg for a in node.args.args] if node is not None else []
        if names and (isinstance(callee, _LocalClass) or callee.owner is not None):
            names = names[1:]
        parts, doc = names, _docstring(callee.node)
    else:
        fn, skip = (callee.fn, True) if isinstance(callee, _Method) else (callee, False)
        try:
            parameters = list(inspect.signature(fn).parameters.values())
        except (TypeError, ValueError):
            return {}
        if skip:
            parameters = parameters[1:]
        parts = [str(p) for p in parameters]
        doc = _first_line(inspect.getdoc(fn) or "")
    open_at = masked.find("(", span[1])
    depth, index, segment = 0, 0, open_at + 1
    for i in range(open_at + 1, len(masked)):
        ch = masked[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            index += 1
            segment = i + 1
    keyword = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", masked[segment:])
    names = [part.split("=")[0].split(":")[0].strip().lstrip("*") for part in parts]
    active = names.index(keyword.group(1)) if keyword and keyword.group(1) in names else index
    if active >= len(parts) and any(part.startswith("*") and not part.startswith("**") for part in parts):
        active = next(i for i, part in enumerate(parts) if part.startswith("*"))
    shown = [(">>" + part + "<<") if i == active else part for i, part in enumerate(parts)]
    return {"title": label + "(" + ", ".join(shown) + ")", "kind": "fn", "doc": doc or ""}


def _rank(name, prefix):
    """How well a completion matches what was typed: 0 prefix, 1 prefix ignoring case, 2 word initials
    ("gb" -> get_block, "pD" -> playDead), None when it does not match."""
    import re
    if not prefix or name.startswith(prefix):
        return 0
    if name.lower().startswith(prefix.lower()):
        return 1
    if len(prefix) < 2 or prefix.startswith("_"):
        return None  # "_x" asks for private names, which are never offered
    parts =[part.lower() for part in re.split(r"_+|(?<=[a-z0-9])(?=[A-Z])", name) if part]
    wanted = prefix.lower().replace("_", "")

    def match(rest, index):
        if not rest:
            return True
        for j in range(index, len(parts)):
            part = parts[j]
            for k in range(min(len(rest), len(part)), 0, -1):
                if part.startswith(rest[:k]) and match(rest[k:], j + 1):
                    return True
        return False

    return 2 if wanted and match(wanted, 0) and parts and parts[0].startswith(wanted[0]) else None


def _docstring(node):
    import ast
    try:
        return ast.get_docstring(node) or ""
    except TypeError:
        return ""


def _hover(params):
    """Documentation of the name under the cursor, for the editor's hover and quick doc."""
    code = params.get("code", "")
    lines = code.split("\n")
    line_idx = params.get("line", 0)
    col = params.get("column", 0)
    if not 0 <= line_idx < len(lines):
        return {}
    line = lines[line_idx]
    end = col
    while end < len(line) and (line[end].isalnum() or line[end] == "_"):
        end += 1
    start = col
    while start > 0 and (line[start - 1].isalnum() or line[start - 1] == "_"):
        start -= 1
    word = line[start:end]
    if not word or word[0].isdigit():
        return {}
    before = "\n".join(lines[:line_idx] + [line[:start]])
    masked = _mask(before)
    if masked.endswith("."):
        receiver = _receiver(masked, len(masked) - 1)
        base = _infer_span(code, line_idx, masked, receiver, len(masked) - 1) if receiver >= 0 else None
        row = _members(base).get(word)
        if row is not None:
            kind, sig, doc = row
            label = masked[receiver:len(masked) - 1].strip() + "." + word
            return {"title": label + sig, "kind": kind, "doc": doc}
        return {}
    local = _local_defs(code)
    tree = _parse_lenient(code, line_idx)
    scope = _scope_for(tree, line_idx + 1)
    binding = scope.lookup(word, line_idx + 2)
    if binding is not None and binding[1] not in ("def", "class"):
        value = _binding_value(binding, 0)
        if isinstance(value, (_Inst, _LocalInst)):
            cls = value.cls if isinstance(value, _Inst) else None
            name = _type_name(cls) if cls is not None else value.node.name
            return {"title": word + ": " + name, "kind": "field",
                    "doc": (inspect.getdoc(cls) or "") if cls is not None else _docstring(value.node)}
    if word in _api_names():
        obj = _api_names()[word]
        kind, sig, doc = _describe(word, obj)
        return {"title": word + sig, "kind": kind, "doc": doc}
    if word in local:
        kind, sig, doc = local[word]
        return {"title": ("def " if kind == "fn" else "class " if kind == "class" else "") + word + sig,
                "kind": kind, "doc": doc}
    if word in _KEYWORDS:
        return {"title": word, "kind": "kw", "doc": "Python keyword"}
    builtin = restricted_builtins([]).get(word)
    if builtin is not None:
        kind, sig, doc = _describe(word, builtin)
        return {"title": word + sig, "kind": kind, "doc": doc}
    return {}


_STUB_CACHE = None
STUB_PATH = "library/craftpy_api.py"


def _api_stub():
    """Readable Python source of the whole craftpy API, built from the live objects: what Ctrl+click on an API
    name opens. Returns (text, index) where index maps "world.set_block" style names to 0-based lines."""
    global _STUB_CACHE
    if _STUB_CACHE is not None:
        return _STUB_CACHE
    import inspect
    lines = ['"""CraftPy API - generated from the running API, read only. Every name here is available in main.py',
             'without an import."""', ""]
    index = {}

    def signature(obj, skip_self=False):
        try:
            sig = inspect.signature(obj)
        except (TypeError, ValueError):
            return "(...)"
        params = list(sig.parameters.values())
        if skip_self and params and params[0].name in ("self", "cls"):
            params = params[1:]
        return str(sig.replace(parameters=params))

    def emit_doc(obj, indent):
        doc = inspect.getdoc(obj) if not isinstance(obj, (int, float, str, bool, tuple, list, dict)) else ""
        doc = (doc or "").strip()
        if doc:
            body = doc.split("\n")
            if len(body) == 1:
                lines.append(indent + '"""' + body[0] + '"""')
            else:
                lines.append(indent + '"""' + body[0])
                lines.extend((indent + line).rstrip() for line in body[1:])
                lines.append(indent + '"""')
        return bool(doc)

    def emit_member(prefix, name, obj, indent):
        key = prefix + "." + name if prefix else name
        index[key] = len(lines)
        if inspect.isclass(obj):
            lines.append(indent + "class " + name + ":")
            if not emit_doc(obj, indent + "    "):
                pass
            members = 0
            for member in sorted(dir(obj)):
                if member.startswith("_"):
                    continue
                try:
                    value = getattr(obj, member)
                except Exception:  # noqa: BLE001
                    continue
                emit_member(key, member, value, indent + "    ")
                members += 1
            if not members:
                lines.append(indent + "    ...")
        elif callable(obj):
            lines.append(indent + "def " + name + signature(obj, True) + ":")
            if not emit_doc(obj, indent + "    "):
                lines.append(indent + "    ...")
        elif isinstance(obj, (int, float, str, bool)):
            lines.append(indent + name + " = " + repr(obj))
        else:
            # an API object such as `world`: show its public members as its own namespace
            lines.append(indent + "class " + name + ":  # " + type(obj).__name__)
            emit_doc(type(obj), indent + "    ")
            members = 0
            for member in sorted(dir(obj)):
                if member.startswith("_"):
                    continue
                try:
                    value = getattr(obj, member)
                except Exception:  # noqa: BLE001
                    continue
                emit_member(key, member, value, indent + "    ")
                members += 1
            if not members:
                lines.append(indent + "    ...")
        lines.append("")

    for name, obj in sorted(_api_names().items(), key=lambda item: item[0].lower()):
        if name.startswith("_"):
            continue
        try:
            emit_member("", name, obj, "")
        except Exception:  # noqa: BLE001
            continue
    _STUB_CACHE = ("\n".join(lines) + "\n", index)
    return _STUB_CACHE


def _stub(params):
    text, _index = _api_stub()
    return {"path": STUB_PATH, "text": text[:350000]}


def _definition(params):
    """Where the name under the cursor is defined: this file (`local`), the API (`api`, a line of the stub), or
    unknown (`none`, the editor then searches the project)."""
    import ast
    import re
    code = params.get("code", "")
    lines = code.split("\n")
    line_idx = params.get("line", 0)
    col = params.get("column", 0)
    if not 0 <= line_idx < len(lines):
        return {"kind": "none"}
    line = lines[line_idx]
    end = min(col, len(line))
    while end < len(line) and (line[end].isalnum() or line[end] == "_"):
        end += 1
    start = min(col, len(line))
    while start > 0 and (line[start - 1].isalnum() or line[start - 1] == "_"):
        start -= 1
    word = line[start:end]
    if not word or word[0].isdigit():
        return {"kind": "none"}
    m = re.search(r'((?:[A-Za-z_]\w*\.)+)$', line[:start])
    chain = (m.group(1) if m else "") + word
    _text, index = _api_stub()
    if chain == "craftpy":
        return {"kind": "api", "path": STUB_PATH, "line": 0, "symbol": chain}
    if chain.startswith("craftpy."):  # `import craftpy` / `craftpy.world.x`: the API names live at the top of the stub
        chain = chain[len("craftpy."):]
    if _resolve_chain(chain) is not None:
        key = chain
        while key and key not in index:
            key = key.rsplit(".", 1)[0] if "." in key else ""
        if key:
            return {"kind": "api", "path": STUB_PATH, "line": index[key], "symbol": chain}
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return {"kind": "none", "symbol": word}
    best = None
    # also methods and attributes (`self.helper()`, `obj.method`): the definition of the last name in this file
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and node.name == word:
            best = node.lineno - 1
            break
    if best is None and chain == word:
        for node in ast.walk(tree):
            if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store) and node.id == word:
                best = node.lineno - 1
                break
    if best is not None:
        return {"kind": "local", "line": best, "symbol": word}
    return {"kind": "none", "symbol": word}


def _lint(source, filename):
    """Warnings a linter would give: undefined names, unused imports, redefined top-level functions."""
    import ast
    import builtins as py_builtins
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []
    bound = set(dir(py_builtins)) | set(_api_names()) | {"__name__", "__file__", "__doc__"}
    star = False
    imported = {}
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            bound.add(node.name)
        elif isinstance(node, ast.arg):
            bound.add(node.arg)
        elif isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Del)):
            bound.add(node.id)
        elif isinstance(node, ast.ExceptHandler) and node.name:
            bound.add(node.name)
        elif isinstance(node, (ast.Global, ast.Nonlocal)):
            bound.update(node.names)
        elif isinstance(node, ast.Import):
            for alias in node.names:
                name = (alias.asname or alias.name).split(".")[0]
                bound.add(name)
                imported.setdefault(name, node)
        elif isinstance(node, ast.ImportFrom):
            for alias in node.names:
                if alias.name == "*":
                    star = star or node.module != "craftpy"
                else:
                    name = alias.asname or alias.name
                    bound.add(name)
                    if node.module != "craftpy":
                        imported.setdefault(name, node)
        elif isinstance(node, ast.MatchAs) and node.name:
            bound.add(node.name)
        elif isinstance(node, ast.MatchStar) and node.name:
            bound.add(node.name)
    used = {n.id for n in ast.walk(tree) if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Load)}
    out = []

    def warn(node, message):
        out.append({"file": filename, "line": max(0, node.lineno - 1), "column": node.col_offset + 1,
                    "message": message, "severity": "warning"})

    if not star:
        reported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load) and node.id not in bound \
                    and node.id not in reported:
                reported.add(node.id)
                warn(node, "Undefined name '%s'" % node.id)
    for name, node in imported.items():
        # "import blocks" of a project file runs it, which registers its content: that is the point, not a leftover
        side_effect = isinstance(node, ast.Import) and name not in sys.stdlib_module_names
        if name not in used and not name.startswith("_") and not side_effect:
            warn(node, "'%s' is imported but never used" % name)
    seen = {}
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and not node.decorator_list:
            if node.name in seen:
                warn(node, "'%s' is already defined on line %d" % (node.name, seen[node.name]))
            seen[node.name] = node.lineno
    _lint_more(tree, warn, lambda node, message: out.append(
        {"file": filename, "line": max(0, node.lineno - 1), "column": node.col_offset + 1,
         "message": message, "severity": "error"}))
    out.sort(key=lambda p: (p["line"], p["column"]))
    return out[:60]


def _lint_more(tree, warn, error):
    """The checks of ruff / pyflakes that catch real mistakes in mod code, plus CraftPy's own (sandbox, event names).
    Pure ast: nothing to install, runs on every save."""
    import ast
    import difflib

    def suggest(name, choices):
        close = difflib.get_close_matches(name, choices, n=1)
        return " - did you mean '%s'?" % close[0] if close else ""

    # the "02d" of f"{x:02d}" is an f-string of its own without placeholders: not a mistake
    specs = {id(n.format_spec) for n in ast.walk(tree) if isinstance(n, ast.FormattedValue) and n.format_spec}
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and not _attribute_allowed(node.attr):
            error(node, _attribute_error(node.attr) + ": this fails when the code runs")
        # the sandbox refuses these when the code runs; say so while typing
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            names = [a.name for a in node.names] if isinstance(node, ast.Import) else [node.module or ""]
            for name in names:
                top = name.split(".")[0]
                if top in _BLOCKED:
                    error(node, "'%s' is blocked in CraftPy's sandbox (no files, network or OS access): this import"
                                " fails when the code runs" % name)
        # @on("player_jion") / @on.player_jion never runs
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            for deco in node.decorator_list:
                target = deco.func if isinstance(deco, ast.Call) else deco
                event = None
                if isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name) and target.value.id == "on":
                    event = target.attr
                elif isinstance(deco, ast.Call) and isinstance(target, ast.Name) and target.id == "on" and deco.args \
                        and isinstance(deco.args[0], ast.Constant) and isinstance(deco.args[0].value, str):
                    event = deco.args[0].value
                if event is not None and event not in _ALL_EVENT_NAMES:
                    warn(deco, "No event '%s': this handler never runs%s" % (event, suggest(event, _ALL_EVENT_NAMES)))
            # mutable default arguments are shared between calls
            for default in list(node.args.defaults) + [d for d in node.args.kw_defaults if d is not None]:
                if isinstance(default, (ast.List, ast.Dict, ast.Set)) or (
                        isinstance(default, ast.Call) and isinstance(default.func, ast.Name)
                        and default.func.id in ("list", "dict", "set") and not default.args):
                    warn(default, "A list/dict/set default is shared by every call: use None and create it inside")
            _lint_unused_locals(node, warn)
        if isinstance(node, ast.ExceptHandler) and node.type is None:
            warn(node, "'except:' also catches the stop button (KeyboardInterrupt): use 'except Exception:'")
        if isinstance(node, ast.Compare):
            for op, right in zip(node.ops, node.comparators):
                if isinstance(op, (ast.Eq, ast.NotEq)) and isinstance(right, ast.Constant) and right.value is None:
                    warn(node, "Compare with None using 'is None' / 'is not None'")
                if isinstance(op, (ast.Is, ast.IsNot)) and isinstance(right, ast.Constant) \
                        and isinstance(right.value, (str, bytes, int, float)) and not isinstance(right.value, bool):
                    warn(node, "'is' checks identity, not value: use '==' with %r" % (right.value,))
        if isinstance(node, ast.Dict):
            keys = {}
            for key in node.keys:
                if isinstance(key, ast.Constant) and isinstance(key.value, (str, int, float, bool)):
                    if key.value in keys:
                        warn(key, "Key %r appears twice in this dict: the first value is lost" % (key.value,))
                    keys[key.value] = True
        if isinstance(node, ast.JoinedStr) and id(node) not in specs                 and not any(isinstance(v, ast.FormattedValue) for v in node.values):
            warn(node, "f-string without {placeholders}: the f does nothing")
        for field in ("body", "orelse", "finalbody"):
            block = getattr(node, field, None)
            if isinstance(block, list):
                for index, stmt in enumerate(block[:-1]):
                    if isinstance(stmt, (ast.Return, ast.Raise, ast.Continue, ast.Break)):
                        warn(block[index + 1], "This code never runs (it comes after %s)"
                             % type(stmt).__name__.lower())
                        break


def _lint_unused_locals(function, warn):
    """A local assigned with ``name = ...`` and never read anywhere in the function (ruff F841)."""
    import ast
    assigned = {}
    loaded = set()
    declared = set()
    for node in ast.walk(function):
        if node is not function and isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.ClassDef)):
            # names read by nested code count as read (closures); their own assignments are theirs
            for inner in ast.walk(node):
                if isinstance(inner, ast.Name) and isinstance(inner.ctx, ast.Load):
                    loaded.add(inner.id)
        if isinstance(node, (ast.Global, ast.Nonlocal)):
            declared.update(node.names)
        elif isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load):
            loaded.add(node.id)
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in ("locals", "vars"):
            return
    for stmt in ast.walk(function):
        if isinstance(stmt, (ast.Assign, ast.AnnAssign)):
            targets = stmt.targets if isinstance(stmt, ast.Assign) else [stmt.target]
            for target in targets:
                if isinstance(target, ast.Name):
                    assigned.setdefault(target.id, target)
    for name, node in assigned.items():
        if name not in loaded and name not in declared and not name.startswith("_"):
            warn(node, "'%s' is assigned but never used" % name)


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
        else:
            problems.extend(_lint(source, filename))
            problems.extend(_side_problems(source, filename, params.get("side") or "both"))
    return {"problems": problems}


# names that only exist on the player's computer (a client project), and what a client project cannot use
_CLIENT_ONLY_NAMES = frozenset({"hud", "client"})


def _side_problems(source, filename, side):
    """Warns about API names of the other side: a client project cannot add blocks or run world code, a server
    project has no HUD. The side is the project's (chosen when it was made, Tools > Project side)."""
    import ast
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []
    server_names = set(_server_api_names()) - _CLIENT_NAMES
    # a name the file binds itself (a variable, parameter, function or import) is not the API's
    own = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Del)):
            own.add(node.id)
        elif isinstance(node, ast.arg):
            own.add(node.arg)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            own.add(node.name)
        elif isinstance(node, (ast.Import, ast.ImportFrom)):
            own.update((alias.asname or alias.name).split(".")[0] for alias in node.names if alias.name != "*")
    out = []
    seen = set(own)
    for node in ast.walk(tree):
        if not isinstance(node, ast.Name) or not isinstance(node.ctx, ast.Load) or node.id in seen:
            continue
        if side == "client" and node.id in server_names:
            message = ("'%s' runs on the server, but this is a client-side project (it runs on each player's computer)."
                       " Use hud, keys, windows and client, or switch the side in Tools > Project side." % node.id)
        elif side != "client" and node.id in _CLIENT_ONLY_NAMES:
            message = ("'%s' only works in a client-side project (it runs on the player's computer)."
                       " Switch the side in Tools > Project side." % node.id)
        else:
            continue
        seen.add(node.id)
        out.append({"file": filename, "line": node.lineno - 1, "column": node.col_offset,
                    "message": message, "severity": "warning"})
    return out


# ---------------------------------------------------------------------------
# Documentation of the public API. Every function, class and method below carries its meaning and the
# meaning of every parameter; it becomes the docstring (editor hover, Ctrl+Q, the generated library file) and
# the reference in api.md. A test fails when a parameter of the API is missing here.
# ---------------------------------------------------------------------------

def _load_docs():
    ID = "Lowercase id with letters, digits and _ (for example 'ruby_ore'). The game id becomes '<project>:<id>'."
    POS3 = {"x": "X coordinate (or a Pos / (x, y, z) tuple when y and z are left out).",
            "y": "Y coordinate (height).", "z": "Z coordinate."}
    DIM = "Dimension id, default 'minecraft:overworld' (also 'minecraft:the_nether', 'minecraft:the_end')."

    return {
        # ---------------------------------------------------------------- content
        "Item": ("Declares an item and returns it; the result can be used anywhere an item id is expected.", {
            "id": ID, "name": "Display name; default is the id in title case.",
            "texture": "PNG file name in assets/textures/ ('ruby.png'), a vanilla texture ('minecraft:item/diamond') or tex(...).",
            "stack": "Largest stack, 1 to 64 (default 64; tools and armor set 1 themselves).",
            "glow": "True draws the enchantment shimmer.",
            "food": "A number (hunger points) or Food(...). Makes the item edible.",
            "fuel": "Furnace burn time in seconds (0 = not a fuel). Same as .extends(Fuel(seconds)).",
            "effect": "Right click applies an effect: 'speed' or (name, level, seconds), level 1 = normal.",
            "extensions": "List of extensions (Sword(...), Durability(...), your own); same as .extends(...).",
            "tags": "Item tags to join: ['minecraft:swords', 'gems'] (a bare name is this project's tag).",
            "extra": "Not accepted: any other attribute raises an error that lists the valid ones.",
            "components": "Data components every stack gets, from any mod: {'apotheosis:affixes': {...}}. A listed component is filled in when a stack does not have it yet; later changes (a reforge) are kept."}, "The item (a string 'project:id' with .extends(...))."),
        "Block": ("Declares a block and returns it; usable anywhere a block or item id is expected.", {
            "id": ID, "name": "Display name; default is the id in title case.",
            "texture": "PNG file name in assets/textures/, a vanilla texture ('minecraft:block/stone') or tex(...).",
            "hardness": "Mining time factor (stone 1.5, obsidian 50); -1 makes it unbreakable. Default 2.0.",
            "resistance": "Explosion resistance; default 6.0 (stone).",
            "light": "Light emitted, 0 to 15.",
            "tool": "Tool that mines it fastest: 'pickaxe', 'axe', 'shovel', 'hoe' or 'sword'. See MineableBy.",
            "needs_tool": "True: only drops anything when mined with the right tool (like ores).",
            "sound": "Sound type: 'stone', 'wood', 'grass', 'glass', 'gravel', 'sand', 'wool', 'metal', 'anvil' or 'snow'.",
            "drops": "What it drops: an item, a list, (item, count) / (item, count, weight), 'self' or [] for nothing. Default: the block itself.",
            "extensions": "List of extensions (MineableBy(...), Redstone(...), your own); same as .extends(...).",
            "tags": "Block tags to join: ['minecraft:mineable/pickaxe'] (a bare name is this project's tag).",
            "model": "Your own mesh: the name of a file in assets/models/block/ (a Blockbench 'Java Block/Item' export), such as 'pedestal'.",
            "shape": "Hitbox (outline and collision): one box (x1, y1, z1, x2, y2, z2) or a list of up to 32, in sixteenths of a block (0..16). Default: the boxes of the model, or a full cube without one.",
            "extra": "Not accepted: any other attribute raises an error that lists the valid ones.",
        }, "The block."),
        "NativeBlock": ("References an original installed block/item, retaining its native machine behavior and renderer. "
                        "This is an alias, not a new registered block type; it cannot have independent extensions.",
                        {"id": ID, "base": "Complete registered block ID, such as 'create:shaft'.",
                         "name": "Name of the Python alias; the original block keeps its original display name."}, "A native block reference."),
        "NativeMob": ("Defines a variant that spawns the original registered entity type with its own AI and renderer. "
                      "Existing entities retain their original type when base changes.",
                      {"id": ID, "base": "Complete installed living entity ID, such as 'irons_spellbooks:necromancer'.",
                       "name": "Optional custom entity name; omit to keep the native name.",
                       "state": "Initial dictionary in this content owner's persistent entity state (at most 16 KiB)."}, "The native variant reference."),
        "Mob": ("Declares a mob and returns it: world.spawn(mob, x, y, z), /craftpy summon, a spawn egg (/craftpy give <player> <project>:<id> egg) or natural spawns.", {
            "id": ID, "name": "Display name; default is the id in title case.",
            "texture": "PNG in assets/textures/ ('goblin.png') or a vanilla one ('minecraft:entity/pig/pig'); default: the model's vanilla skin.",
            "model": "Body: 'humanoid', 'player', 'pig', 'cow', 'chicken', 'spider', 'creeper', 'villager', 'slime', or a GeckoLib model 'goblin.geo.json' (assets/models/, needs GeckoLib; without it the mob is humanoid).",
            "animation": "GeckoLib animation file (assets/animations/); default '<model name>.animation.json'. Animations ending in 'walk' and 'idle' play by themselves.",
            "health": "Maximum health (a heart is 2).", "speed": "Movement speed (zombie 0.23, pig 0.25).",
            "damage": "Damage of a hit (hostile mobs).", "armor": "Armor points, 0 to 30.",
            "hostile": "True: attacks players, spawns in the dark, gone in peaceful. False: wanders and flees when hurt.",
            "width": "Hit box width in blocks.", "height": "Hit box height in blocks.",
            "scale": "Draws the model bigger or smaller (the hit box is width x height).",
            "follow_range": "How far it notices players, in blocks.",
            "egg": "Spawn egg colors: '#3d8f5a' or ('#3d8f5a', '#c9e86b') for base and spots.",
            "spawns": "Natural spawns: [Spawn('#minecraft:is_forest', weight=8)]. They start the next time the world is opened.",
            "drops": "What it drops: an item, a list, (item, count) / (item, count, weight).",
            "behavior": "Profile name or dictionary: profile='melee'|'ranged'|'follow'|'guard', optional target='hostile'|'players'|'none', speed=0.1-4, range/radius=1-64, interval=10-200 ticks. Follow uses world.spawn(owner=player) or entity.set_owner(player).",
            "goals": "At most eight inspected public native Goal constructors: dictionaries with class, args, priority (0-20), optional target=True and parameters (exact Java type names). Requires approved java permission and allowed bridge packages. Incompatible entity classes or complex arguments need an adapter.",
            "animations": "Map idle/walk/run/attack/cast/hurt/death to GeckoLib clip names. Triggered events synchronize from the server; entity.animate('cast') triggers one explicitly. Death follows Minecraft's normal removal timing.",
            "extra": "Not accepted: any other attribute raises an error that lists the valid ones.",
        }, "The mob (a string 'project:id')."),
        "Pool": ("A jigsaw template pool: pieces (.nbt files of data/structure/) a jigsaw block can grow into.", {
            "id": ID, "pieces": "['ruins/hall', ('ruins/tower', 3), (None, 1)]: templates with weights; None is an empty piece.",
            "fallback": "Pool used when no piece fits (default none).",
            "projection": "'rigid' (as built) or 'terrain_matching' (follows the ground, for paths).",
            "processors": "Processor list id applied to the pieces (default none).",
        }, "The pool (a string 'project:id')."),
        "Structure": ("A jigsaw structure generated in new chunks, like villages (after reopening the world).", {
            "id": ID, "start": "The Pool of the first piece.",
            "biomes": "Biome, biome tag ('#minecraft:is_forest') or a list.",
            "size": "How many jigsaw steps it grows, 0 to 20.", "max_distance": "How far pieces may reach, 1 to 128 blocks.",
            "spacing": "About one every this many chunks.", "separation": "At least this many chunks apart (smaller than spacing).",
            "step": "Generation step, default 'surface_structures'.",
            "terrain": "'beard_thin' (ground fits around it), 'beard_box', 'bury', 'encapsulate' or 'none'.",
            "height": "Start height; with on_surface it is added to the surface.",
            "on_surface": "True: starts on the surface.", "start_jigsaw": "Name of the jigsaw block of the first piece to start from.",
        }, "The structure (a string 'project:id')."),
        "world.place_structure": ("Places a structure template now (a project's data/structure/<name>.nbt after Run, or the game's).", {
            **POS3, "structure": "'ruins/house' (this project) or 'mod:path'.", "rotation": "0, 90, 180 or 270 degrees.",
            "mirror": "True mirrors it.", "entities": "False leaves out the entities saved in it.", "dimension": DIM}, ""),
        "world.place_jigsaw": ("Generates a jigsaw structure from a Pool now (pools exist after the world was opened with the project).", {
            **POS3, "pool": "The Pool.", "target": "Name of the jigsaw block of the first piece to start from.",
            "depth": "Jigsaw steps, 1 to 20.", "dimension": DIM}, ""),
        "Spawn": ("Where a Mob appears by itself (for Mob(spawns=[...])).", {
            "biomes": "A biome ('minecraft:plains'), a biome tag ('#minecraft:is_overworld') or a list of them.",
            "weight": "How often, next to other mobs (zombie 100, pig 10).",
            "min": "Smallest group.", "max": "Largest group.",
        }, "A dict for spawns=."),
        "Effect": ("Declares a potion effect and returns it: player.effect(rage), Item(effect=rage), @on.effect_tick(effect=rage).", {
            "id": ID, "name": "Display name; default is the id in title case.",
            "icon": "18x18 PNG in assets/textures/ ('rage.png'); default: CraftPy's flask.",
            "color": "Particle color, '#rrggbb'.",
            "category": "'beneficial', 'harmful' or 'neutral' (how the game shows it).",
            "attributes": "Stat changes while active: [Attributes('minecraft:generic.attack_damage', 2)]; the amount is per level.",
            "extra": "Not accepted: any other attribute raises an error that lists the valid ones.",
        }, "The effect (a string 'project:id')."),
        "Fluid": ("Declares a liquid (a real NeoForge fluid with a bucket: Fluid(...).bucket). Flows, can be swum in, fills tanks and pipes.", {
            "id": ID, "name": "Display name; default is the id in title case.",
            "color": "Tint of the water textures, '#rrggbb'.",
            "density": "kg/m3: water 1000, lava 3000; negative rises like a gas.",
            "viscosity": "Water 1000, lava 6000: higher slows flowing and swimming.",
            "temperature": "Kelvin: water 300, lava 1300.",
            "light": "Light given off, 0 to 15.",
            "effects": "Applied while an entity is in it: [('poison', 5), (rage, 8, 2)] as (effect, seconds, level).",
            "still": "Own texture of the still liquid (assets/textures/), instead of tinted water.",
            "flowing": "Own texture of the flowing liquid.",
            "bucket_texture": "Texture of the bucket item; default: the water bucket.",
            "extra": "Not accepted: any other attribute raises an error that lists the valid ones.",
        }, "The fluid; fluid.bucket is the bucket item."),
        "Food": ("Food values for Item(food=...).", {
            "hunger": "Hunger points restored (each is half a drumstick).",
            "saturation": "Saturation modifier; 0.6 is bread, 1.2 cooked meat.",
            "always_eat": "True lets players eat it with a full hunger bar.",
            "fast": "True eats in half the time (like dried kelp).",
        }, "A dict to pass as food=."),
        "tag": ("Declares a tag, a named group of items or blocks.", {
            "ident": "Tag id without namespace ('ruby_gems' becomes <project>:ruby_gems).",
            "values": "Items/blocks or '#other_tag' entries to include.",
            "replace": "True drops the entries of a tag with the same id from other packs.",
            "type": "'item' (default), 'block', 'entity_type', 'fluid' or 'game_event'.",
        }, ""),
        "lang": ("Adds a translation: lang('itemGroup.mypack', 'My Pack').", {
            "key": "Translation key.", "value": "English text."}, ""),
        "loot_table": ("Declares a loot table (what chests, blocks or mobs drop).", {
            "ident": "Table id; a block's own id replaces what that block drops.",
            "entries": "List of loot_entry(...) or 'self'.",
            "rolls": "How many entries are picked (default 1).",
        }, ""),
        "loot_entry": ("One possible drop of a loot table.", {
            "item": "Item to drop.", "count": "Stack size dropped (default 1).",
            "weight": "Relative chance against the other entries (default 1).",
        }, "A dict for loot_table."),
        "loot_self": ("Marks a loot entry that drops the block itself.", {}, ""),
        "sound": ("Declares a sound event (sounds.json).", {
            "event": "Sound event id ('ruby_chime').", "sounds": "(keyword) list of OGG names in assets/sounds/.",
            "subtitle": "(keyword) caption text.", "replace": "(keyword) True replaces earlier definitions."}, ""),
        "particle": ("Declares a particle definition.", {"ident": "Particle id.", "textures": "(keyword) list of texture ids."}, ""),
        "advancement": ("Declares an advancement; keywords are the fields of the advancement JSON.", {"ident": "Advancement id."}, ""),
        "raw_json": ("Writes any data pack JSON file as is (escape hatch; version specific).", {
            "path": "Path inside the data pack, for example 'tags/item/foo.json'.", "data": "A dict or list to write as JSON.",
            "namespace": "Write to data/<namespace>/ instead of your project's own folder (for example 'minecraft')."}, ""),
        "tex": ("Reference to a texture by name.", {"ref": "Texture name or id."}, "A texture reference."),
        "snd": ("Reference to a sound by name.", {"ref": "Sound event id."}, "A sound reference."),
        "mdl": ("Reference to a model by name.", {"ref": "Model id."}, "A model reference."),
        # ---------------------------------------------------------------- recipes
        "shaped": ("Shaped crafting recipe: shaped(sword, ' R ', ' R ', ' S ', R=ruby, S='stick').", {
            "result": "Item crafted.", "rows": "1 to 3 strings of up to 3 characters; a space is an empty slot.",
            "count": "How many items the recipe makes.", "keys": "One keyword per letter: the ingredient it stands for."}, ""),
        "shapeless": ("Shapeless crafting recipe: shapeless(ruby, 'diamond', 'redstone').", {
            "result": "Item crafted.", "ingredients": "Up to 9 ingredients, order does not matter.",
            "count": "How many items the recipe makes."}, ""),
        "smelt": ("Furnace-style recipe: smelt(ruby_ore, ruby).", {
            "ingredient": "Item that is cooked.", "result": "Item that comes out.",
            "xp": "Experience given (default 0.1).", "seconds": "Cooking time in seconds (default 10).",
            "using": "'furnace' (default), 'blast_furnace', 'smoker' or 'campfire'."}, ""),
        "stonecut": ("Stonecutter recipe.", {"ingredient": "Item put in.", "result": "Item produced.",
                                             "count": "Items produced per input."}, ""),
        "recipe": ("A recipe of another mod's type: recipe('create:mixing', ingredients=[...], results=[...]).", {
            "type": "Recipe type id 'mod:type' (the IDE lists the types of the installed mods).",
            "id": "File name of the recipe (optional).",
            "fields": "The mod's own recipe fields as keywords; the IDE shows the ones it knows."}, ""),
        "recipe_shaped": ("Low-level shaped recipe with the raw pattern.", {
            "result": "Result item id or dict.", "pattern": "List of up to 3 strings.", "keys": "Dict letter -> ingredient.",
            "category": "Recipe book category: 'building', 'redstone', 'equipment' or 'misc'."}, ""),
        "recipe_shapeless": ("Low-level shapeless recipe.", {
            "result": "Result item id or dict.", "ingredients": "List of ingredients.", "category": "Recipe book category."}, ""),
        "recipe_smelting": ("Furnace recipe.", {"result": "Result.", "ingredient": "Input.", "xp": "Experience given.",
                                                "cook_time": "Ticks to cook (200 = 10 s)."}, ""),
        "recipe_blasting": ("Blast furnace recipe.", {"result": "Result.", "ingredient": "Input.", "xp": "Experience given.",
                                                      "cook_time": "Ticks to cook (100 = 5 s)."}, ""),
        "recipe_smoking": ("Smoker recipe.", {"result": "Result.", "ingredient": "Input.", "xp": "Experience given.",
                                              "cook_time": "Ticks to cook (100 = 5 s)."}, ""),
        "recipe_campfire": ("Campfire recipe.", {"result": "Result.", "ingredient": "Input.",
                                                 "cook_time": "Ticks to cook (600 = 30 s)."}, ""),
        "recipe_stonecutting": ("Stonecutter recipe.", {"result": "Result.", "ingredient": "Input.", "count": "Items produced."}, ""),
        "recipe_smithing": ("Smithing table recipe.", {"result": "Result.", "template": "Smithing template item.",
                                                       "base": "Item that is upgraded.", "addition": "Material added."}, ""),
        # ---------------------------------------------------------------- events, commands, timers
        "on": ("Runs a function when something happens: @on('block_break'), @on.block_break(block=ruby_ore).", {
            "event": "Event name: block_break, block_place, block_interact, block_tick, item_use, item_hit, item_mine, "
                     "item_hold_tick, item_craft, item_pickup, player_join, player_leave, player_respawn, player_death, "
                     "entity_death, entity_hurt, entity_tick, chat, or a command_<name> event. entity_tick is for CraftPy-owned entities, at most once a second, capped at 64 snapshots per server tick.",
            "throttle_ticks": "Minimum native ticks between calls for each entity/player (0-12000). Filtering happens first.",
            "every": "Run at most every this many ticks (optional).", "priority": "'normal' (default); reserved for ordering.",
            "filters": "block=, item= or entity= : only for that block/item/entity (ids or Block/Item objects)."}, ""),
        "every": ("Runs a function again and again: @every(seconds=5) or @every(100).", {
            "ticks": "Interval in ticks (20 = one second).", "seconds": "Interval in seconds (wins over ticks)."}, ""),
        "after": ("Runs a function once after a delay: after(3, fn) or @after(3).", {
            "seconds": "Delay in seconds.", "fn": "Function to run (omit to use as a decorator)."}, "A Task that can be cancelled."),
        "run_later": ("Runs a function after a number of ticks.", {"ticks": "Delay in ticks.", "fn": "Function to run."}, "A Task."),
        "run_every": ("Runs a function every N ticks until cancelled.", {"ticks": "Interval in ticks.",
                                                                         "fn": "Function; receives the tick count."}, "A Task (task.cancel() stops it)."),
        "run_next_tick": ("Runs a function on the next game tick.", {"fn": "Function to run."}, "A Task."),
        "command": ("Registers /<project>:<name>. Arguments come from the function signature (int, float, bool, str, Player).", {
            "name": "Command name.", "params": "Explicit argument types instead of annotations (optional)."}, ""),
        "on_load": ("Runs the decorated function when the project or module is loaded.", {"fn": "The function."}, ""),
        "on_unload": ("Runs the decorated function when the project or module is unloaded.", {"fn": "The function."}, ""),
        "action": ("Names a function that buttons of ui/*.json windows can call: @action('save').", {"name": "Action name used in on_click."}, ""),
        "window": ("Registers a Window subclass: @window('counter', title='Counter', size=(260, 170)).", {
            "ident": "Window id used by ui.open.", "title": "Title bar text.", "size": "(width, height) in pixels.",
            "resizable": "True lets the player resize it."}, ""),
        "requires_restart": ("Declares that loading this module needs a full game restart.", {"flag": "True (default) or False."}, ""),
        "core": ("Marks this module as 'core': it may need a full game restart.", {}, ""),
        "has": ("True when the game supports a capability: has('ui.window').", {"capability": "Capability name."}, ""),
        # ---------------------------------------------------------------- server / chat
        "say": ("Broadcasts a chat message to everyone.", {"message": "Texts; non-text values are converted and joined with spaces."}, ""),
        "run": ("Runs a Minecraft command as the server: run('time set day').", {"command_text": "Command without the leading slash."}, "what the command answered, as text (lines joined with a newline)"),
        "player": ("The online player with this name, or None.", {"name": "Player name."}, "A Player or None."),
        # ---------------------------------------------------------------- Pos
        "Pos": ("An (x, y, z) position; supports pos + (0, 1, 0), pos.up(2).", {"x": "X.", "y": "Y.", "z": "Z."}, ""),
        "Pos.up": ("The position n blocks higher.", {"n": "Blocks (default 1)."}, "A Pos."),
        "Pos.down": ("The position n blocks lower.", {"n": "Blocks (default 1)."}, "A Pos."),
        "Pos.block": ("Integer block coordinates.", {}, "A Pos with whole numbers."),
        # ---------------------------------------------------------------- Player
        "Player.say": ("Sends a chat message to this player only.", {"message": "Texts, joined with spaces."}, ""),
        "Player.send_message": ("Same as say.", {"message": "Texts, joined with spaces."}, ""),
        "Player.actionbar": ("Shows text above the hotbar.", {"text": "The text."}, ""),
        "Player.title": ("Shows a big title in the middle of the screen.", {
            "text": "Title.", "subtitle": "Smaller line under it (optional).", "seconds": "How long it stays (default 3)."}, ""),
        "Player.give": ("Gives items; what does not fit is dropped.", {"item": "Item or id.", "count": "How many (default 1)."}, ""),
        "Player.take": ("Removes items from the inventory.", {"item": "Item or id.", "count": "How many (default 1)."}, "How many were taken."),
        "Player.has": ("True when the inventory holds enough of an item.", {"item": "Item or id.", "count": "At least this many."}, "bool"),
        "Player.inventory": ("The inventory contents.", {}, "A list of stacks."),
        "Player.heal": ("Restores health.", {"amount": "Health points (2 = one heart); omit for full health."}, ""),
        "Player.set_health": ("Sets health.", {"health": "Health points, 20 is full."}, ""),
        "Player.feed": ("Restores hunger.", {"food": "Hunger points (default 20 = full).", "saturation": "Saturation (default 1.0)."}, ""),
        "Player.effect": ("Applies a potion effect: player.effect('speed', seconds=30, level=2).", {
            "name": "Effect id ('speed', 'minecraft:regeneration').", "seconds": "Duration (default 10).",
            "level": "1 = normal, 2 = II ... (default 1).", "particles": "False hides the swirl."}, ""),
        "Player.clear_effects": ("Removes every effect.", {}, ""),
        "Player.add_xp": ("Gives experience.", {"levels": "Whole levels.", "points": "Experience points."}, ""),
        "Player.teleport": ("Moves the player.", {"x": "X (or a Pos).", "y": "Y.", "z": "Z.", "dimension": DIM, "safe": "Require loaded, unobstructed destination and solid ground."}, ""),
        "Player.play_sound": ("Plays a sound only this player hears.", {
            "sound": "Sound event id ('minecraft:entity.blaze.shoot').", "volume": "1.0 = normal.", "pitch": "1.0 = normal."}, ""),
        "Player.set_gamemode": ("Sets the game mode.", {"mode": "'survival', 'creative', 'adventure' or 'spectator'."}, ""),
        "Player.set_fire": ("Sets the player on fire.", {"seconds": "Burn time (default 3)."}, ""),
        "Player.kill": ("Kills the player.", {}, ""),
        "Player.refresh": ("A fresh snapshot of this player (position, health...).", {}, "A Player."),
        "Player.named": ("The online player with this name, or None.", {"name": "Player name."}, "A Player or None."),
        # ---------------------------------------------------------------- Entity
        "Entity.kill": ("Kills the entity.", {}, ""),
        "Entity.teleport": ("Moves the entity.", {"x": "X (or a Pos).", "y": "Y.", "z": "Z."}, ""),
        "Entity.set_name": ("Sets its custom name.", {"name": "The name."}, ""),
        "Entity.refresh": ("A fresh snapshot (None if it is gone).", {}, "An Entity or None."),
        "Entity.state": ("Reads persistent state scoped to the calling owner; other owners' values are not exposed.", {"key": "Dictionary key, or None for the whole dictionary.", "default": "Value returned for a missing key."}, "The stored value."),
        "Entity.set_state": ("Writes this owner's persistent state (at most 16 KiB per entity).", {"key": "Key, 1-128 characters.", "value": "JSON-compatible value."}, "The value."),
        "Entity.set_owner": ("Assigns the player followed by a custom mob. Only its content owner may assign it.", {"player": "Player/name, or None to clear."}, ""),
        "Entity.animate": ("Triggers a configured GeckoLib animation from the server. Only the content owner may trigger it.", {"name": "attack, cast, hurt or death."}, ""),
        # ---------------------------------------------------------------- players
        "players.all": ("Names of every online player.", {}, "list of str"),
        "players.online": ("Every online player.", {}, "list of Player"),
        "players.get": ("Snapshot of an online player.", {"name": "Player name."}, "A Player or None."),
        "players.give": ("Gives items to a player by name.", {"name": "Player name.", "item": "Item or id.", "count": "How many."}, ""),
        "players.send_message": ("Chat message to one player by name.", {"name": "Player name.", "message": "Text."}, ""),
        "players.teleport": ("Teleports a player by name.", {"name": "Player name.", "x": "X.", "y": "Y.", "z": "Z.", "dimension": DIM}, ""),
        # ---------------------------------------------------------------- world
        "world.get_block": ("The block id at a position.", dict(POS3, dimension=DIM), "str, for example 'minecraft:stone'"),
        "world.machine": ("Read a loaded kinetic machine without loading chunks. Snapshot includes speed, theoretical_speed, overstressed, processing, progress, recipe and input/output stacks.", dict(POS3, dimension=DIM), "dict"),
        "world.machine_slot": ("Put a stack into a slot ('group.number') of a loaded machine block, replacing what is there; item=None empties it. Returns the new snapshot.", dict(POS3, slot="'group.number' of the block's Inventory, such as 'input.0' or 'output.1'.", item="Item or id, or None to empty the slot.", count="Stack size, 0..64 (and the group's max_stack).", dimension=DIM), "dict"),
        "world.machine_update": ("Add or remove energy (FE) and remove fluid (mB, negative number) of a loaded machine block; returns its new snapshot. The block's capacity and contents limit it.", dict(POS3, energy="FE to add (negative removes).", fluid="mB to remove, 0 or negative.", dimension=DIM), "dict"),
        "world.set_block": ("Places a block: world.set_block(x, y, z, 'stone') or world.set_block(pos, 'stone').", dict(
            POS3, block="Block id or Block object.", dimension=DIM, replace="False only places into air.") , ""),
        "world.fill": ("Fills the box between two corners (at most 32768 blocks).", {
            "start": "First corner (Pos or tuple).", "end": "Opposite corner.", "block": "Block id.", "dimension": DIM}, "Blocks changed."),
        "world.blocks_in_box": ("Blocks around a position.", dict(POS3, radius="Distance in blocks in each direction.", dimension=DIM), "list"),
        "world.get_block_entities": ("Block entities around a position.", dict(POS3, radius="Distance in blocks.", dimension=DIM), "list"),
        "world.entities": ("Entities near a position.", dict(POS3, radius="Distance in blocks (default 8).",
                                                             type="Only this entity type ('minecraft:zombie').", dimension=DIM), "list of Entity"),
        "world.entities_in_box": ("Entities in a cube around a position.", dict(POS3, radius="Half the cube size.", dimension=DIM), "list"),
        "world.spawn": ("Spawns an entity ('zombie', 'minecraft:pig') or NativeMob variant.", dict(POS3, entity="Entity type id.", dimension=DIM,
                        owner="Optional player who controls this owner's custom mob (follow profile)."), "The Entity."),
        "world.drop": ("Drops an item stack on the ground.", dict(POS3, item="Item or id.", count="Stack size.", dimension=DIM), ""),
        "world.explode": ("Creates an explosion.", dict(POS3, power="Strength (TNT is 4, default 3).",
                                                        fire="True sets fires.", break_blocks="False only damages.", dimension=DIM), ""),
        "world.lightning": ("Strikes lightning.", dict(POS3, dimension=DIM), ""),
        "world.particle": ("Spawns particles.", dict(POS3, particle="Particle id ('minecraft:flame').", count="How many.", dimension=DIM), ""),
        "world.play_sound": ("Plays a sound at a position.", dict(POS3, sound="Sound event id.", volume="1.0 = normal.",
                                                                  pitch="1.0 = normal.", dimension=DIM), ""),
        "world.biome": ("Biome id at a position.", dict(POS3, dimension=DIM), "str"),
        "world.highest_y": ("Height of the highest solid block in a column.", {"x": "X.", "z": "Z.", "dimension": DIM}, "int"),
        "world.info": ("Biome, highest_y, light, is_day, raining, thundering and time at a position.", dict(POS3, dimension=DIM), "dict"),
        "world.time": ("Time of day in ticks (0 to 24000).", {"dimension": DIM}, "int"),
        "world.set_time": ("Sets the time of day.", {"ticks": "0 = sunrise, 6000 = noon, 13000 = night.", "dimension": DIM}, ""),
        "world.is_day": ("True during the day.", {"dimension": DIM}, "bool"),
        "world.weather": ("Sets the weather.", {"weather": "'clear', 'rain' or 'thunder'.", "duration": "Ticks (default 6000).", "dimension": DIM}, ""),
        # ---------------------------------------------------------------- storage, scheduler, server, ui
        "storage.get": ("Reads a saved value (saved with the world, shared by all handlers).", {"key": "Name.", "default": "Returned when missing.", "scope": "A name that keeps groups of values apart (default 'world')."}, "the value"),
        "storage.set": ("Saves a value. storage['key'] = value does the same in the default scope.", {"key": "Name.", "value": "Number, text, bool, list or dict.", "scope": "Group name (default 'world')."}, "the value"),
        "storage.clear": ("Deletes one value, or everything of the scope.", {"key": "Name; omit to clear the whole scope.", "scope": "Group name (default 'world')."}, ""),
        "scheduler.after": ("Runs fn once after a delay.", {"seconds": "Delay.", "fn": "Function."}, "A Task."),
        "scheduler.every_seconds": ("Runs fn repeatedly.", {"seconds": "Interval.", "fn": "Function."}, "A Task."),
        "scheduler.run_every": ("Runs fn every N ticks.", {"ticks": "Interval.", "fn": "Function."}, "A Task."),
        "scheduler.run_later": ("Runs fn after N ticks.", {"ticks": "Delay.", "fn": "Function."}, "A Task."),
        "scheduler.run_next_tick": ("Runs fn on the next tick.", {"fn": "Function."}, "A Task."),
        "server.broadcast": ("Chat message to everyone.", {"message": "Text."}, ""),
        "server.mods": ("The ids of the loaded mods, e.g. 'create' in server.mods(). Needs no permission.", {}, "a list of mod ids"),
        "server.run_command": ("Runs a command at permission level 2, like a command block. Needs \"permissions\": {\"commands\": true} in project.json / module.json (modules: approved on the Modules page).", {"command": "Without the leading slash."}, "what the command answered, as text"),
        "server.log": ("Writes to the server log and the IDE console.", {"message": "Text.", "level": "'info' (default), 'warn' or 'error'."}, ""),
        "ui.open": ("Opens a window for a player.", {"player": "Player or name.", "window_id": "Id of a @window class or ui/<id>.json."}, ""),
        "ui.data": ("Decorates a display data provider. It receives ctx.player and ctx.window and returns a dictionary. "
                    "Widgets bind properties to dictionary keys, for example bind={'text': 'title'}. "
                    "Called on open and refresh; named actions still validate every operation.",
                    {"window_id": "Id of the JSON or Python window."}, "A decorator; its function returns a display dictionary."),
        "ui.close": ("Closes the window.", {"player": "Player or name.", "window_id": "Which one; omit to close any."}, ""),
        "ui.windows": ("Ids of the registered windows.", {}, "list of str"),
        "ui.refresh": ("Redraws a window that is open, from a timer or an event (callbacks redraw by themselves); it keeps"
                       " its state.", {"player": "Player or name.", "window_id": "Which window."},
                       "False when the player has closed it"),
        # ---------------------------------------------------------------- widgets
        "Label": ("Text.", {"text": "The text.", "p": "style='title'|'headline'|'text', color='#rrggbb', id."}, "A widget."),
        "Button": ("A button.", {"text": "Caption.", "on_click": "An @action name or a function (called as fn(event)).",
                                 "p": "style='text' for a flat button, id."}, "A widget."),
        "TextInput": ("One-line text field.", {"value": "Initial text.", "p": "id (read it with ctx.values[id]), hint, on_change, on_submit."}, "A widget."),
        "TextArea": ("Multi-line text field.", {"value": "Initial text.", "p": "id, hint, on_change."}, "A widget."),
        "Checkbox": ("A check box.", {"label": "Caption.", "checked": "Initial state.", "p": "id, on_change."}, "A widget."),
        "Slider": ("A slider.", {"value": "Initial value.", "min": "Lowest value.", "max": "Highest value.", "p": "id, on_change."}, "A widget."),
        "Dropdown": ("A drop-down list.", {"options": "List of texts.", "selected": "Index of the chosen one.", "p": "id, on_select."}, "A widget."),
        "ListView": ("A list of texts.", {"items": "List of texts.", "p": "id, on_select."}, "A widget."),
        "ProgressBar": ("A progress bar.", {"value": "Current value.", "p": "max (default 100), id."}, "A widget."),
        "Image": ("A picture. A source such as 'media/logo.png' or 'media/loop.gif' is a file of the module (PNG, JPEG, GIF, BMP; a GIF animates) and takes the media options; any other source is a texture of the game.", {"source": "Texture id, or a file under media/ of the module (assets/media/).", "p": "id, width, height, size, fit='contain'|'cover'|'stretch', crop=[x, y, width, height] in picture pixels, opacity (0-1), z (draw order in a Canvas), "
            "autoplay, loop, speed, position (seconds), controls=True for a bar, on_end, on_loop, on_error."}, "A widget."),
        "Video": ("A video: Motion JPEG in an AVI file of the module (assets/media/clip.avi). Sound is a separate file named by audio=. Needs no controls: start it with playing=True or autoplay=True.", {"source": "A file under media/ ending in .avi.", "p": "id, width, height, audio='media/clip.ogg', autoplay (default False), loop (default False), playing, speed (0.25-4), position (seconds), volume (0-1), muted, restart, "
            "fit, crop, opacity, z, controls=True for a play/seek/speed/volume bar, on_end, on_loop, on_error."}, "A widget."),
        "Audio": ("A sound for the window: it plays through Minecraft (Records volume), has nothing to see unless controls=True. Use it for effects and background sound.", {"source": "A file under media/ (ogg, wav, mp3, flac, aiff, au).", "p": "id, autoplay, loop, playing, position (seconds), volume (0-1), muted, restart (set a new number to play again), controls=True, on_end, on_loop, on_error."}, "A widget."),
        "ItemIcon": ("The icon of an item.", {"item": "Item id.", "p": "id."}, "A widget."),
        "VBox": ("Stacks children top to bottom.", {"children": "Widgets.", "p": "spacing (pixels between), padding."}, "A widget."),
        "HBox": ("Places children side by side.", {"children": "Widgets.", "p": "spacing, padding."}, "A widget."),
        "Grid": ("Places children in a grid.", {"children": "Widgets.", "p": "columns, spacing, padding."}, "A widget."),
        "TabView": ("Tabs; each child is one tab.", {"children": "Widgets.", "p": "selected (index)."}, "A widget."),
        "ScrollView": ("A scrolling area.", {"children": "Widgets.", "p": "padding."}, "A widget."),
        "Canvas": ("Places each child freely: x and y (from the top left) and width and height, all in pixels; what the"
                   " UI maker makes when you drag and resize widgets.",
                   {"children": "Widgets with x=, y=, width=, height=.", "p": "width, height, padding."}, "A widget."),
        "Window.build": ("Returns the widgets of the window. Called again after every callback.", {"ctx": "Window context: ctx.player, ctx.values, ctx.close."}, "A widget."),
        "hud.text": ("Shows text on your screen (client-side code only). Calling it again with the same id updates it.", {
            "id": "Name of this element (letters, digits, _ . -); the same id replaces it.", "text": "The text (at most 256 characters).",
            "anchor": "Where on the screen: top_left, top_center, top_right, center_left, center, center_right, bottom_left, bottom_center, bottom_right.",
            "x": "Pixels to the right of the anchor (negative = left).", "y": "Pixels below the anchor (negative = above).",
            "color": "One of the 16 chat colors ('gold', 'aqua'...) or '#rrggbb'.", "scale": "Size, 0.25 to 8.",
            "shadow": "Draw a shadow behind the text."}, ""),
        "hud.bar": ("Shows a bar (health, mana, progress) on your screen.", {
            "id": "Name of this element.", "value": "Current value.", "max": "Value when the bar is full.",
            "anchor": "Where on the screen (see hud.text).", "x": "Pixels right of the anchor.", "y": "Pixels below the anchor.",
            "width": "Width in pixels, 1 to 600.", "height": "Height in pixels, 1 to 60.", "color": "Color of the filled part.",
            "background": "Color of the empty part.", "label": "Text drawn on the bar (at most 64 characters), or None."}, ""),
        "hud.item": ("Shows an item icon on your screen.", {
            "id": "Name of this element.", "item": "Item id such as 'minecraft:diamond' or 'welcome:gem'.",
            "anchor": "Where on the screen (see hud.text).", "x": "Pixels right of the anchor.", "y": "Pixels below the anchor.",
            "count": "A number drawn in the corner, or None.", "scale": "Size, 0.25 to 8."}, ""),
        "http.get": ("Fetches a web page or API (https only). The answer comes later to on_done(response).", {
            "url": "https:// address of a site the module declares in permissions.net and the server allows.",
            "on_done": "Function called with a Response (ok, status, text, headers, error, json()).",
            "headers": "Extra request headers (dict).", "key": "Name of an API key in the server config; the key itself stays there.",
            "timeout": "Seconds before giving up."}, "the request id"),
        "http.post": ("Sends data to a web API (https only); the answer comes to on_done(response).", {
            "url": "https:// address.", "on_done": "Function called with a Response.", "body": "Text to send.",
            "json": "A dict or list sent as JSON instead of body.", "headers": "Extra request headers (dict).",
            "key": "Name of an API key in the server config.", "timeout": "Seconds before giving up."}, "the request id"),
        "http.request": ("Any of GET, POST, PUT, DELETE; see http.get.", {"method": "GET, POST, PUT or DELETE.",
            "url": "https:// address.", "on_done": "Function called with a Response.", "headers": "Extra headers.",
            "body": "Text to send.", "json_body": "A dict or list sent as JSON.", "key": "API key name in the server config.",
            "timeout": "Seconds."}, "the request id"),
        "files.read": ("Reads a text file of this project's data folder (or of a shared folder).", {
            "name": "File name, folders with /: 'notes/day1.txt'.", "folder": "Name of a shared folder from the server config, or None for your own.",
            "default": "Returned when the file does not exist (instead of an error)."}, "the text"),
        "files.write": ("Writes a text file into this project's data folder.", {"name": "File name.", "text": "The text."}, ""),
        "files.delete": ("Deletes a file of the data folder.", {"name": "File name."}, "True when it was there"),
        "files.exists": ("True when the file exists.", {"name": "File name.", "folder": "Shared folder name or None."}, "bool"),
        "files.list": ("Names of the files of the data folder (or of a shared folder).", {"folder": "Shared folder name or None."}, "list of str"),
        "media.files": ("Client side: the audio files of pyforge/shared/<folder> (declare \"shared\": [\"music\"] in permissions). Call again to refresh.", {"folder": "Folder of pyforge/shared (default 'music')."}, "list of str"),
        "media.play": ("Client side: plays a file of the shared folder; it keeps playing when the window closes.", {
            "file": "Name from media.files().", "folder": "Folder of pyforge/shared (default 'music').",
            "seconds": "Where to start.", "volume": "0.0 to 1.0.",
            "nearby": "True: players around you hear it too (if the server allows it, up to media.maxShareMb)."}, ""),
        "media.play_sound": ("Client side: plays a sound event (music disc, music of a resource pack or mod).", {"sound": "Id such as 'minecraft:music_disc.cat'.", "volume": "0.0 to 1.0."}, ""),
        "media.sounds": ("Client side: music sound events the game, packs and mods have (ids with 'music' or 'record').", {}, "list of str"),
        "media.pause": ("Client side: pauses the track.", {}, ""),
        "media.resume": ("Client side: plays on from where it paused.", {}, ""),
        "media.stop": ("Client side: stops the track.", {}, ""),
        "media.seek": ("Client side: jumps to a position (files only).", {"seconds": "Seconds from the start."}, ""),
        "media.volume": ("Client side: sets the volume.", {"volume": "0.0 to 1.0, on top of the Jukebox/Note Blocks slider."}, ""),
        "media.state": ("Client side: what plays now.", {}, "dict: state ('playing', 'paused', 'stopped', 'ended'), name, kind, position, duration (-1 unknown), volume, shared, error"),
        "files.read_json": ("Reads a JSON file.", {"name": "File name.", "default": "Returned when the file does not exist."}, "the value"),
        "files.write_json": ("Writes a value as a JSON file.", {"name": "File name.", "value": "Dict, list, number, text..."}, ""),
        "hud.remove": ("Takes one element off the screen.", {"id": "Name of the element."}, ""),
        "hud.clear": ("Takes every element of this module off the screen.", {}, ""),
        "keys.bind": ("Runs a function when you press a key (while no screen is open). Use it as @keys.bind('ctrl+k').", {
            "combo": "One key with optional ctrl / shift / alt: 'r', 'f6', 'ctrl+shift+k'. Letters, digits, f1-f12, space, enter, tab, arrows, home, end, pageup, pagedown, insert, delete, backspace, minus, equals, comma, period, slash, semicolon, grave, bracket_left, bracket_right.",
            "fn": "The function; leave out to use as a decorator. Server callbacks get ctx.player from the server.",
            "id": "Stable action ID within this owner. Omit for the legacy combo ID. Keep this unchanged when changing the default key.",
            "name": "Display name in Controls and Module Keybinds (defaults to the ID).",
            "category": "Controls category (default CraftPy)."}, "the function"),
        "client.player": ("The local player as the client knows it (read only).", {}, "an object with name, uuid, health, max_health, food, level, x, y, z, pos, dimension, gamemode, held_item, held_count, on_ground"),
        "client.looking_at": ("The block you are looking at.", {}, "{'block': 'minecraft:stone', 'pos': [x, y, z]}, or None"),
        "client.fps": ("Frames per second right now.", {}, "number"),
        "client.chat": ("Writes a line in your own chat; nobody else sees it and the server is not told.", {"text": "The text (at most 512 characters)."}, ""),
        "client.sound": ("Plays a sound only you hear.", {"sound": "Sound event id.", "volume": "1.0 = normal.", "pitch": "1.0 = normal."}, ""),
        "client.toast": ("Shows a small pop-up in the corner of the screen.", {"title": "Title (at most 64 characters).", "message": "Second line (at most 128 characters)."}, ""),
        "client.open_window": ("Opens one of your windows (see window) on this computer.", {"window_id": "Id of a @window class or ui/<id>.json."}, ""),
        "client.close_window": ("Closes a window of yours.", {"window_id": "Which one; omit to close any."}, ""),
        "Window.state": ("A value remembered between redraws.", {"default": "Initial value."}, "A holder with .value."),
        # ---------------------------------------------------------------- extensions
        "Extension": ("Base class of every extension, built in or your own. Methods named like a hook run for the item or block it is attached to: "
                      "items on_use, on_hit, on_mine, on_hold_tick; blocks on_interact, on_break, on_place, on_tick. Override configure(self, target) to change the target "
                      "(target.set('glow', True)). Hooks receive one event; event.cancel() stops block_break, item_use, item_hit and item_mine, event.damage = x changes a hit.", {}, ""),
        "extension": ("Marks your class as an extension: @extension class Vampiric(Extension): ...", {"cls": "The class."}, "The class."),
        "ItemType": ("Class form of Item(...): attributes id, name, texture, stack, glow, food, fuel, effect and extensions are the keyword arguments; methods named like hooks run directly.", {}, ""),
        "BlockType": ("Class form of Block(...): attributes id, name, texture, hardness, resistance, light, tool, needs_tool, sound, drops and extensions are the keyword arguments.", {}, ""),
        "Tool": ("Makes an item mine blocks and hit harder like a vanilla tool.", {
            "kind": "'pickaxe', 'axe', 'shovel', 'hoe' or 'sword'.",
            "tier": "'wood', 'stone', 'iron' (default), 'gold', 'diamond' or 'netherite': mining level, speed, durability and damage defaults.",
            "speed": "Mining speed; default from the tier (wood 2, stone 4, iron 6, gold 12, diamond 8, netherite 9).",
            "damage": "Damage per hit as the tooltip shows it (a diamond sword is 7).",
            "attack_speed": "Hits per second (sword 1.6, axe 1.0, pickaxe 1.2)."}, ""),
        "Sword": ("A sword (Tool with kind 'sword').", {"tier": "Material tier.", "speed": "Mining speed.", "damage": "Damage per hit.", "attack_speed": "Hits per second."}, ""),
        "Pickaxe": ("A pickaxe.", {"tier": "Material tier.", "speed": "Mining speed.", "damage": "Damage per hit.", "attack_speed": "Hits per second."}, ""),
        "Axe": ("An axe.", {"tier": "Material tier.", "speed": "Mining speed.", "damage": "Damage per hit.", "attack_speed": "Hits per second."}, ""),
        "Shovel": ("A shovel.", {"tier": "Material tier.", "speed": "Mining speed.", "damage": "Damage per hit.", "attack_speed": "Hits per second."}, ""),
        "Hoe": ("A hoe.", {"tier": "Material tier.", "speed": "Mining speed.", "damage": "Damage per hit.", "attack_speed": "Hits per second."}, ""),
        "Durability": ("A durability bar: 1 use is lost per block mined or entity hit; the item breaks at 0.", {
            "uses": "Number of uses, 1 to 32767.", "repair_with": "Item that repairs it in an anvil (optional)."}, ""),
        "Weapon": ("Attack values for any item without making it a tool.", {
            "damage": "Damage per hit as the tooltip shows it (a fist is 1).", "attack_speed": "Hits per second.", "knockback": "Extra knockback, 0 to 5."}, ""),
        "Fuel": ("Lets a furnace burn the item.", {"seconds": "Burn time in seconds, 1 to 100000 (200 smelts 20 items)."}, ""),
        "Armor": ("Makes an item wearable in an armor slot (no worn model is drawn).", {
            "slot": "'head', 'chest', 'legs' or 'feet'.", "defense": "Armor points, 0 to 30.",
            "toughness": "Armor toughness, 0 to 20.", "knockback_resistance": "0 to 1."}, ""),
        "Enchantable": ("How good enchantments from an enchanting table are.", {"value": "0 (none) to 50; gold armor is 25."}, ""),
        "Cooldown": ("The item can only be used again after a pause, shown as a bar.", {"seconds": "0.05 to 3600."}, ""),
        "Tooltip": ("Extra lines in the item tooltip.", {"lines": "1 to 10 texts of at most 120 characters.", "color": "One of the 16 chat colors ('gray', 'gold', 'aqua'...)."}, ""),
        "MineableBy": ("Which tool mines a block.", {
            "tool": "'pickaxe', 'axe', 'shovel' or 'hoe'.", "tier": "Lowest tool tier that works ('wood' to 'netherite'; None = any).",
            "required": "True (default): nothing drops without the right tool."}, ""),
        "Drops": ("What a block drops when mined.", {"drops": "Items, (item, count) or (item, count, weight) tuples, or 'self'; none = nothing drops."}, ""),
        "Kinetic": (Kinetic.__doc__, {"role": "generator, shaft or processor.", "axis": "x, y or z.",
                    "speed": "Signed generated RPM, -256 to 256.", "capacity": "Generator stress capacity per RPM, 0 to 65536.",
                    "impact": "Stress consumed per RPM, 0 to 65536 (processor default 8).", "rotating_model": "Y-aligned baked block model ID."}, ""),
        "Redstone": ("The block emits a redstone signal.", {"power": "Signal strength 0 to 15."}, ""),
        "Flammable": ("Fire can burn the block.", {"chance": "How easily it catches fire, 0 to 300 (wood 5, leaves 30).", "spread": "How fast fire spreads over it, 0 to 300 (wood 20, leaves 60)."}, ""),
        "Friction": ("How slippery the block is.", {"value": "0.1 to 1.0 (normal 0.6, ice 0.98)."}, ""),
        "Attributes": ("Attribute modifiers on an item (any attribute of the game or of a mod): Attributes('irons_spellbooks:fire_spell_power', 0.1, slot='mainhand').", {
            "attribute": "Attribute id; the IDE completes the ones of the installed mods.", "amount": "Number added (or the factor, with a multiply operation).",
            "operation": "'add' (default), 'multiply_base' or 'multiply_total'.",
            "slot": "'mainhand' (default), 'offhand', 'head', 'chest', 'legs', 'feet', 'hand', 'armor', 'body' or 'any'."}, ""),
        "Piston": ("What pistons do to the block.", {"reaction": "'normal', 'destroy', 'block', 'ignore' or 'push_only'."}, ""),
        "Content.extends": ("Adds behaviour to an item or block; returns the same item or block so calls chain.", {"extensions": "Extension objects, or lists of them."}, "The item or block."),
        # ---------------------------------------------------------------- java bridge
        "java.available": ("True when the host allows the bridge (config javaBridge.mode is not OFF).", {}, "bool"),
        "java.cls": ("The Java class with this full name.", {"name": "Full class name, for example 'net.minecraft.world.level.block.Blocks'."}, "A JavaClass: .FIELD, .method(...), Class(...) builds an object."),
        "java.new": ("Builds a Java object.", {"name": "Full class name.", "args": "Constructor arguments."}, "A JavaObject."),
        "java.on": ("Handles any NeoForge event: @java.on('net.neoforged...LivingIncomingDamageEvent').", {
            "event_class": "Event class name or JavaClass.", "priority": "'highest', 'high', 'normal', 'low' or 'lowest'.",
            "receive_cancelled": "True also receives events other handlers cancelled."}, ""),
        "java.items": ("A Java list, set, map (as pairs), array or stream as a Python list.", {"obj": "The Java object."}, "list"),
        "java.members": ("Public fields and methods of a Java object or class.", {"obj": "The Java object or class."}, "dict"),
        "java.mods": ("The loaded mods.", {}, "list of {id, name, version}"),
        "java.server": ("The MinecraftServer.", {}, "A JavaObject."),
        "java.level": ("A ServerLevel.", {"dimension": DIM}, "A JavaObject."),
        "java.entity": ("The live entity or player with this uuid.", {"uuid": "The uuid text."}, "A JavaObject."),
    }



def _doc_text(summary, params, returns):
    lines = [summary]
    if params:
        lines += ["", "Args:"] + ["    %s: %s" % (name, text) for name, text in params.items()]
    if returns:
        lines += ["", "Returns:", "    " + returns]
    return "\n".join(lines)


_DOCS = _load_docs()
_DOCS.update({
    'EnergyStorage': (EnergyStorage.__doc__, dict(capacity='Maximum stored FE (1..1000000000).', max_receive='Maximum FE per receive call; default capacity.', max_extract='Maximum FE per extraction call; default capacity.'), ''),
    'Inventory': (Inventory.__doc__, dict(accepts='{group: [item ids or #tags]}: what a group takes.', max_stack='{group: 1..64}: stack limit of a group.', sides='{group: ["up", "down", "north", "south", "east", "west"]}: sides hoppers and pipes may use.', **{'groups': 'The slot groups as keyword arguments: input=1, fuel=1, output=2, storage=9 (names are roles; at most 54 slots).'}), ''),
    'Screen': (Screen.__doc__, dict(title='Text at the top of the window.', energy='Show the energy bar (needs EnergyStorage).', fluid='Show the fluid gauge (needs FluidTank).', slots='Show the two item slots.', layout='Name of a Legacy window drawn in the UI maker (ui/<name>.json); replaces the standard layout.'), ''),
    'FluidTank': (FluidTank.__doc__, dict(capacity='Maximum mB (1..1000000).', allowed='Optional list of up to 64 namespaced fluid IDs; None permits all.'), ''),
    'world.resources': (_World.resources.__doc__, dict(x='Block X, tuple or Pos.', y='Block Y.', z='Block Z.', dimension='Dimension resource ID.', side='Capability face or None for unsided access.'), 'dict'),
    'world.transfer': (_World.transfer.__doc__, dict(source='Three source block coordinates.', target='Three destination coordinates in the same dimension.', kind='item, fluid or energy.', amount='Requested count, mB or FE (1..1000000; items capped to 64).', source_side='Source capability face or None.', target_side='Destination capability face or None.', slot='Source item slot; default 0.', simulate='True checks without changing contents.', dimension='Dimension resource ID.'), 'int'),
    'server.features': (_Server.features.__doc__, {}, 'dict'),
    'server.info': (_Server.info.__doc__, {}, 'dict'),
})



def _apply_docs():
    names = _api_names()
    for key, (summary, params, returns) in _DOCS.items():
        owner, _, member = key.partition(".")
        obj = names.get(owner)
        if obj is None:
            continue
        target = obj
        if member:
            target = getattr(obj if inspect.isclass(obj) else type(obj), member, None)
        target = getattr(target, "__func__", target)
        try:
            target.__doc__ = _doc_text(summary, params, returns)
        except (AttributeError, TypeError):
            pass


_apply_docs()


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


def _idle():
    """True when nothing in this worker can be called again: no handler (events, commands, Java listeners, hooks),
    timer, Java callback, window, action, key or pending web reply. The host then stops a module's worker; what it
    declared (blocks, items, recipes) stays registered in the game."""
    return not (any(fns for state in _OWNERS.values() for fns in state["handlers"].values())
                or _SCHEDULE or _FN_TABLE or _WINDOWS or _UI_NAMED or _UI_DATA or _UI_OPEN or _KEYS or _HTTP_WAITING)


def _process(request):
    import os
    req_id = request.get("id")
    method = request.get("method", "")
    params = request.get("params", {}) or {}
    try:
        result = _handle(method, params)
        if isinstance(result, dict) and "scheduled" in result:
            # each owner has its own worker: the host only sends this worker the events it has handlers for
            result["events"] = sorted({name for state in _OWNERS.values() for name, fns in state["handlers"].items()
                                       if fns})
            result["idle"] = _idle()
        if isinstance(result, dict):
            # Publish only after execution/rollback; a failed run cannot advertise half-built callbacks.
            result["keys"] = [description for (owner, _), (_, description) in _KEYS.items()
                              if owner == os.environ.get("CRAFTPY_OWNER", "__main__")]
        if isinstance(result, dict) and _MODE[0] == "client":
            # the client only sends ticks while something needs them (a timer, a client_tick handler)
            result["ticking"] = bool(_SCHEDULE) or any(s["handlers"].get("client_tick") for s in _OWNERS.values())
        _write({"id": req_id, "result": result})
    except Exception as exc:  # noqa: BLE001
        _write({"id": req_id, "error": {"message": str(exc)}})


def _write(obj):
    with _IO_LOCK:
        sys.stdout.write(json.dumps(obj) + "\n")
        sys.stdout.flush()


def main():
    _harden()
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
