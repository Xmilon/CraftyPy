"""CraftPy module checker: tells whether a .cpm module is SAFE, needs REVIEW or is DANGEROUS, and why.

    check_module.py <file.cpm | folder> [...]      checks each module (a folder: every .cpm in it)
    check_module.py                                 asks for a path (for a double-clicked .exe)

It never runs a module: it only reads the zip and parses the Python. It is strict on purpose: SAFE only when it
understands everything in the module (no permissions, nothing hidden or unusual), so a harmful module is never called
safe; a harmless one may still be sent to REVIEW. The rules of the mod's sandbox come from its worker.py, so the two
always agree. Exit code: 0 all safe, 1 something to review, 2 something dangerous.
"""
import ast
import importlib.util
import json
import os
import re
import sys
import zipfile

SAFE, REVIEW, DANGEROUS = 0, 1, 2
NAMES = {SAFE: "SAFE", REVIEW: "REVIEW", DANGEROUS: "DANGEROUS"}

# What a module may contain, by folder and file type. Anything else goes to review.
ALLOWED_FILES = {
    "code": {".py"},
    "assets": {".png", ".json", ".mcmeta", ".ogg", ".lang", ".txt"},
    "data": {".json"},
    "ui": {".json"},
}
TOP_FILES = {"module.json", "readme.md", "license", "license.txt", "license.md", "pack.mcmeta"}
# Standard modules ordinary content code uses; every other import goes to review.
STD_IMPORTS = {"craftpy", "modules", "math", "random", "json", "re", "collections", "itertools", "functools", "string",
               "time", "datetime", "statistics", "dataclasses", "enum", "typing", "textwrap", "heapq", "bisect", "copy",
               "operator", "fractions", "decimal", "colorsys", "__future__"}
# Ways to rebuild or unpack hidden code; also review.
UNPACKING = {"base64", "binascii", "codecs", "zlib", "lzma", "bz2", "gzip", "zipfile", "tarfile", "io", "struct"}
RISKY_CALLS = {"eval", "exec", "compile", "vars", "globals", "locals", "open", "breakpoint", "input", "memoryview",
               "__import__", "type", "object", "super", "classmethod", "staticmethod", "property"}
NAME_CALLS = {"getattr", "setattr", "hasattr", "delattr"}
# The CraftPy API that reaches outside the game; each one needs a permission the module would have to declare.
OUTSIDE_API = {"http": "the web", "files": "files on the computer", "java": "the Java bridge (full trust)"}
MAX_UNPACKED = 200 * 1024 * 1024
MAX_RATIO = 100
LONG_TEXT = 400


def load_rules():
    """The sandbox rules of the mod: worker.py next to this file, else the one in the project."""
    here = getattr(sys, "_MEIPASS", None) or os.path.dirname(os.path.abspath(__file__))  # _MEIPASS: inside the .exe
    for path in (os.path.join(here, "worker.py"),
                 os.path.join(here, "..", "src", "main", "resources", "craftpy_runtime", "worker.py")):
        if os.path.isfile(path):
            spec = importlib.util.spec_from_file_location("craftpy_worker_rules", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)  # defines the rules only; the worker starts only as __main__
            return module
    raise SystemExit("worker.py not found: keep it next to the checker")


class Report:
    def __init__(self, name):
        self.name = name
        self.level = SAFE
        self.findings = []

    def add(self, level, where, text):
        self.level = max(self.level, level)
        self.findings.append((level, where, text))


def check(path, rules):
    report = Report(os.path.basename(path))
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as error:
        report.add(DANGEROUS, "", "not a readable .cpm (zip): %s" % error)
        return report
    with archive:
        infos = archive.infolist()
        unpacked = sum(i.file_size for i in infos)
        packed = max(1, sum(i.compress_size for i in infos))
        if unpacked > MAX_UNPACKED or unpacked / packed > MAX_RATIO:
            report.add(DANGEROUS, "", "unpacks to %d MB (%dx its size): a zip bomb?" % (unpacked >> 20, unpacked // packed))
            return report
        names = [i.filename for i in infos if not i.is_dir()]
        for name in names:
            check_entry(report, name)
        meta = {}
        if "module.json" not in names:
            report.add(REVIEW, "module.json", "missing: not a module built by CraftPy")
        else:
            try:
                meta = json.loads(archive.read("module.json").decode("utf-8"))
            except (ValueError, UnicodeDecodeError) as error:
                report.add(REVIEW, "module.json", "not valid JSON: %s" % error)
        check_permissions(report, meta.get("permissions") or {})
        own = {os.path.splitext(n.split("/")[-1])[0] for n in names if n.endswith(".py")}
        for name in names:
            if name.endswith(".py") and name.startswith("code/"):
                try:
                    source = archive.read(name).decode("utf-8")
                except UnicodeDecodeError:
                    report.add(REVIEW, name, "not UTF-8 text")
                    continue
                check_code(report, name, source, rules, own)
    return report


def check_entry(report, name):
    parts = name.split("/")
    if name.startswith("/") or "\\" in name or ":" in parts[0] or ".." in parts:
        report.add(DANGEROUS, name, "a path that leaves the module folder")
        return
    if len(parts) == 1:
        if name.lower() not in TOP_FILES:
            report.add(REVIEW, name, "an unexpected file at the top of the module")
        return
    folder, ext = parts[0], os.path.splitext(name)[1].lower()
    allowed = ALLOWED_FILES.get(folder)
    if allowed is None:
        report.add(REVIEW, name, "a folder modules don't have (%s/)" % folder)
    elif ext not in allowed:
        why = {".mcfunction": "a datapack function: it runs commands with full rights",
               ".nbt": "a structure: it may contain command blocks",
               ".fsh": "a shader", ".vsh": "a shader", ".glsl": "a shader"}.get(ext, "a %s file" % (ext or "nameless"))
        level = DANGEROUS if ext in {".exe", ".dll", ".so", ".dylib", ".jar", ".class", ".bat", ".cmd", ".sh", ".ps1",
                                     ".pyc", ".pyd", ".vbs", ".js"} else REVIEW
        report.add(level, name, why)
    if folder == "assets" and "/shaders/" in name:
        report.add(REVIEW, name, "replaces a game shader")


def check_permissions(report, permissions):
    if not isinstance(permissions, dict):
        report.add(REVIEW, "module.json", "permissions in an unknown form")
        return
    if permissions.get("java"):
        report.add(DANGEROUS, "module.json", "asks for the Java bridge: full trust, it can do anything the game can")
    if permissions.get("commands"):
        report.add(REVIEW, "module.json", "asks to run server commands (like a command block)")
    for site in permissions.get("net") or []:
        report.add(REVIEW, "module.json", "asks for web access to %s: check what it sends there" % site)
    if permissions.get("files"):
        report.add(REVIEW, "module.json", "asks for a data folder on the computer")
    for folder in permissions.get("shared") or []:
        report.add(REVIEW, "module.json", "asks to read pyforge/shared/%s" % folder)
    unknown = set(permissions) - {"net", "files", "shared", "commands", "java"}
    if unknown:
        report.add(REVIEW, "module.json", "unknown permissions: %s" % ", ".join(sorted(unknown)))


def check_code(report, name, source, rules, own):
    try:
        tree = ast.parse(source, name)
    except SyntaxError as error:
        report.add(REVIEW, "%s:%s" % (name, error.lineno), "does not parse: %s" % error.msg)
        return
    try:
        rules._check_user_code(tree, name)
    except SyntaxError as error:
        report.add(DANGEROUS, "%s:%s" % (name, error.lineno), "reaches into the interpreter: %s" % error.msg)
    for node in ast.walk(tree):
        at = "%s:%s" % (name, getattr(node, "lineno", "?"))
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            if isinstance(node, ast.ImportFrom) and node.level:
                continue  # its own files
            targets = [a.name for a in node.names] if isinstance(node, ast.Import) else [node.module or ""]
            for target in targets:
                top = target.split(".")[0]
                if top in rules._BLOCKED:
                    report.add(DANGEROUS, at, "imports %s, which the sandbox blocks (OS, files, network or internals)" % target)
                elif top in UNPACKING:
                    report.add(REVIEW, at, "imports %s: can unpack hidden code or data" % target)
                elif top not in STD_IMPORTS and top not in own:
                    report.add(REVIEW, at, "imports %s, which content code does not need" % target)
        elif isinstance(node, ast.Call):
            func = node.func
            called = func.id if isinstance(func, ast.Name) else None
            if called in RISKY_CALLS and called not in {"super", "property", "classmethod", "staticmethod"}:
                report.add(DANGEROUS if called in {"eval", "exec", "compile", "__import__"} else REVIEW, at,
                           "calls %s(), which can run or reach hidden code" % called)
            elif called in NAME_CALLS:
                text = node.args[1] if len(node.args) > 1 else None
                if not (isinstance(text, ast.Constant) and isinstance(text.value, str)):
                    report.add(REVIEW, at, "%s() with a name built at run time" % called)
                elif not rules._attribute_allowed(text.value):
                    report.add(DANGEROUS, at, "%s() on %r, which the sandbox refuses" % (called, text.value))
            if isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name):
                if func.value.id == "server" and func.attr == "run_command":
                    report.add(REVIEW, at, "runs a server command")
            if called == "run":
                report.add(REVIEW, at, "runs a command")
        elif isinstance(node, ast.Name) and node.id in OUTSIDE_API and isinstance(node.ctx, ast.Load):
            report.add(REVIEW, at, "uses %s (%s)" % (node.id, OUTSIDE_API[node.id]))
        elif isinstance(node, ast.Constant) and isinstance(node.value, (str, bytes)) and len(node.value) > LONG_TEXT:
            text = node.value if isinstance(node.value, str) else node.value.decode("latin-1")
            if re.fullmatch(r"[A-Za-z0-9+/=\s]+|[0-9a-fA-F\s]+", text) or isinstance(node.value, bytes):
                report.add(REVIEW, at, "a long encoded text (%d characters): it may hide code" % len(text))


def paths_of(arguments):
    for argument in arguments:
        argument = argument.strip().strip('"')
        if os.path.isdir(argument):
            for entry in sorted(os.listdir(argument)):
                if entry.lower().endswith(".cpm"):
                    yield os.path.join(argument, entry)
        else:
            yield argument


def main(arguments):
    asked = not arguments
    if asked:
        arguments = [input("Module (.cpm) or folder to check: ")]
    rules = load_rules()
    worst = SAFE
    for path in paths_of(arguments):
        report = check(path, rules)
        worst = max(worst, report.level)
        print("%-10s %s" % (NAMES[report.level], report.name))
        for level, where, text in sorted(report.findings, key=lambda f: -f[0]):
            print("  %-9s %s%s" % (NAMES[level], where + ": " if where else "", text))
        if report.level == SAFE:
            print("  no permissions, nothing hidden or unusual: the sandbox keeps it inside the game")
    print("\nSAFE means nothing outside the game was found. REVIEW means a person should read the lines above.")
    if asked:
        input("Press Enter to close.")
    return worst


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
