"""Worker API tests. Run: python src/test/python/test_worker.py (also run by WorkerPythonTest)."""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
WORKER = os.path.join(HERE, "..", "..", "main", "resources", "craftpy_runtime", "worker.py")

spec = importlib.util.spec_from_file_location("craftpy_worker", WORKER)
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)

CALLS = []


def fake_host(method, params=None, timeout=10.0):
    CALLS.append((method, params))
    if method == "players.get":
        return {"ok": True, "player": {"name": params["name"], "uuid": "u-1", "x": 1, "y": 2, "z": 3}}
    if method == "player.take_item":
        return {"ok": True, "taken": 2}
    if method == "world.get_block":
        return {"ok": True, "block": "minecraft:stone"}
    return {"ok": True}


w._host_call = fake_host
w._try_host = lambda method, params=None, timeout=10.0, default=None: fake_host(method, params)


def run_main(project, code, sources=None):
    result = w._handle("run_main", {"project": project, "code": code, "file": "code/main.py", "sources": sources or {}})
    assert not result.get("error"), result
    return result["declarations"]


SIMPLE = '''
from craftpy import *

ruby = Item("ruby", texture="ruby.png", glow=True)
ruby_ore = Block("ruby_ore", texture="ruby_ore.png", hardness=3, tool="pickaxe", drops=(ruby, 2))
sword = Item("ruby_sword", stack=1, effect=("strength", 2, 10))
apple = Item("ruby_apple", food=Food(hunger=6, saturation=1.2, always_eat=True))

shaped(sword, " R ", " R ", " S ", R=ruby, S="stick")
shapeless(ruby, "diamond", "redstone")
smelt(ruby_ore, ruby, xp=0.7)

@on.block_break(block=ruby_ore)
def mined(event):
    event.player.say("ruby!")
    event.player.give(ruby, 1)

@on("player_join")
def hello(event):
    say("welcome", event.player.name)

@every(seconds=1)
def tick():
    world[0, 64, 0] = "stone"

@command("gift")
def gift(ctx, target: Player, amount: int):
    target.give(ruby, amount)
'''


def test_simple_declarations():
    d = run_main("rr", SIMPLE)
    blocks = {b["id"]: b["definition"] for b in d["blocks"]}
    items = {i["id"]: i["definition"] for i in d["items"]}
    assert blocks["ruby_ore"]["name"] == "Ruby Ore"
    assert blocks["ruby_ore"]["texture"] == "ruby_ore.png"
    assert items["ruby"]["glow"] is True
    assert items["ruby_sword"]["max_stack"] == 1
    assert items["ruby_sword"]["use_effect"] == "minecraft:strength:1:10"
    assert items["ruby_apple"]["food"] == {"hunger": 6, "saturation": 1.2, "always_eat": True, "fast": False}

    shaped_r = [r for r in d["recipes"] if r["type"] == "shaped"][0]
    assert shaped_r["result"] == "rr:ruby_sword"
    assert shaped_r["key"] == {"R": "rr:ruby", "S": "minecraft:stick"}
    shapeless_r = [r for r in d["recipes"] if r["type"] == "shapeless"][0]
    assert shapeless_r["ingredients"] == ["minecraft:diamond", "minecraft:redstone"]
    smelt_r = [r for r in d["recipes"] if r["type"] == "smelting"][0]
    assert smelt_r["ingredient"] == "rr:ruby_ore" and smelt_r["cook_time"] == 200

    loot = d["loot_tables"][0]
    assert loot["id"] == "ruby_ore" and loot["entries"][0]["item"] == "rr:ruby" and loot["entries"][0]["count"] == 2
    assert {h["event"] for h in d["handlers"]} == {"block_break", "player_join"}
    assert d["commands"][0]["params"] == {"target": "player", "amount": "int"}


def test_event_filters_and_player_actions():
    run_main("rr", SIMPLE)
    CALLS.clear()
    ctx = {"player": {"name": "Dev", "uuid": "u-1"}, "block": {"id": "minecraft:stone"}, "x": 1, "y": 2, "z": 3}
    w._handle("trigger_event", {"event": "block_break", "context": ctx})
    assert CALLS == [], "filter must skip other blocks"
    ctx["block"] = {"id": "rr:ruby_ore"}
    result = w._handle("trigger_event", {"event": "block_break", "context": ctx})
    assert result["log"] == [], result
    methods = [m for m, _ in CALLS]
    assert methods == ["player.send_message", "player.give"], methods
    assert CALLS[1][1]["item"] == "rr:ruby"


def test_command_signature_arguments():
    run_main("rr", SIMPLE)
    CALLS.clear()
    w._handle("trigger_event", {"event": "command_gift",
                                "context": {"args": {"target": "Alex", "amount": 3}}})
    give = [p for m, p in CALLS if m == "player.give"]
    assert give and give[0]["count"] == 3 and give[0]["player"] == "u-1", CALLS


def test_timer_runs_and_owners_are_isolated():
    run_main("rr", SIMPLE)
    # A second project must not wipe rr's handlers or timers.
    run_main("other", "from craftpy import *\nBlock('thing')\n")
    assert "block_break" in w._OWNERS["rr"]["handlers"]
    CALLS.clear()
    result = w._handle("tick", {"tick": w._TICK[0] + 40})
    assert ("world.set_block", {"x": 0, "y": 64, "z": 0, "block": "minecraft:stone",
                                "dimension": "minecraft:overworld", "replace": True}) in CALLS, (result, CALLS)
    # Re-running rr replaces only rr; stopping rr removes its timer.
    run_main("rr", SIMPLE)
    assert len([e for e in w._SCHEDULE if e["owner"] == "rr"]) == 1
    w._handle("unload_owner", {"owner": "rr"})
    assert "rr" not in w._OWNERS and not [e for e in w._SCHEDULE if e["owner"] == "rr"]
    assert "other" in w._OWNERS


def test_legacy_api_still_works():
    legacy = '''
from craftpy import *

@block("old_ore", name="Old Ore", texture="minecraft:stone", hardness=4.0)
class OldOre:
    pass

recipe_shaped("old_ore", ["XX"], {"X": {"item": "minecraft:dirt"}})

@on("block_break")
def broke(ctx):
    bid = getattr(ctx.block, "id", "")
    ctx.log("broke " + bid)
'''
    d = run_main("legacy", legacy)
    assert d["blocks"][0]["definition"]["name"] == "Old Ore"
    assert d["recipes"][0]["keys"] == {"X": {"item": "minecraft:dirt"}}
    result = w._handle("trigger_event", {"event": "block_break",
                                         "context": {"block": {"id": "minecraft:dirt"}}})
    assert "broke minecraft:dirt" in result["log"], result


def test_errors_are_readable():
    result = w._handle("run_main", {"project": "bad", "code": "from craftpy import *\nBlock('Bad Id')\n",
                                    "file": "main.py"})
    assert result.get("error") and "lowercase" in result["message"], result
    result = w._handle("run_main", {"project": "bad", "code": "from craftpy import *\nshaped('x', 'AB', A='stone')\n",
                                    "file": "main.py"})
    assert result.get("error") and "B" in result["message"], result


def test_world_item_syntax():
    w._use_owner("rr")
    assert w.world[1, 2, 3] == "minecraft:stone"
    assert w.Pos(1, 2, 3).up(2) == (1, 4, 3)


def test_print_inside_handlers_reaches_the_event_log():
    run_main("pp", "from craftpy import *\n@on.chat\ndef c(event):\n    print('got', event.message)\n")
    result = w._handle("trigger_event", {"event": "chat", "context": {"message": "hi"}})
    assert result["log"] == ["got hi"], result


EXAMPLES = os.path.join(HERE, "..", "..", "..", "examples")


def test_examples_run():
    for project in ("ruby_rush",):
        path = os.path.join(EXAMPLES, project, "code", "main.py")
        code = open(path, encoding="utf-8").read()
        d = run_main(project, code, w._read_sources(os.path.join(EXAMPLES, project)))
        assert d["blocks"] or d["items"], project
        for entry in d["blocks"] + d["items"]:
            texture = entry["definition"].get("texture")
            if texture and texture.endswith(".png"):
                assert os.path.isfile(os.path.join(EXAMPLES, project, "assets", "textures", texture)), texture
    # ruby_rush handlers fire with filters and helpers.
    CALLS.clear()
    for _ in range(10):
        w._handle("trigger_event", {"event": "block_break", "context": {
            "player": {"name": "Dev", "uuid": "u-1", "x": 0, "y": 64, "z": 0},
            "block": {"id": "ruby_rush:ruby_ore"}, "x": 0, "y": 64, "z": 0}})
    methods = [m for m, _ in CALLS]
    assert "server.broadcast" in methods, methods
    result = w._handle("trigger_event", {"event": "entity_death", "context": {
        "entity": {"type": "minecraft:zombie", "x": 1, "y": 2, "z": 3},
        "killer": {"name": "Dev", "held_item": {"id": "ruby_rush:ruby_sword"}}}})
    assert result["log"] == [], result


def test_profiler_counts_handler_calls_and_errors():
    run_main("prof", """from craftpy import *
@on.chat
def slow(event):
    pass
@on.chat
def broken(event):
    1 / 0
""")
    for _ in range(3):
        result = w._handle("trigger_event", {"event": "chat", "context": {"message": "hi"}})
    assert result["handlers"] == 2 and result["ms"] >= 0, result
    rows = {r["name"]: r for r in w._handle("profile", {})["rows"]}
    assert rows["chat:slow"]["calls"] == 3 and rows["chat:slow"]["errors"] == 0, rows
    assert rows["chat:broken"]["errors"] == 3, rows
    w._handle("unload_owner", {"owner": "prof"})
    assert w._handle("profile", {})["rows"] == []
    w._handle("profile_reset", {})


WELCOME = os.path.join(HERE, "..", "..", "main", "resources", "craftpy_default", "welcome")
DEV = {"name": "Dev", "uuid": "u-1", "x": 0, "y": 64, "z": 0, "health": 20}


def opened_windows():
    return [p["window"] for m, p in CALLS if m == "ui.open"]


def find(node, **props):
    """First widget in a tree whose properties match."""
    if all(node.get(k) == v for k, v in props.items()):
        return node
    for child in node.get("children", []):
        hit = find(child, **props)
        if hit:
            return hit
    return None


def test_default_project_and_windows():
    code = open(os.path.join(WELCOME, "code", "main.py"), encoding="utf-8").read()
    result = w._handle("run_main", {"project": "welcome", "code": code, "file": "code/main.py", "dir": WELCOME,
                                    "sources": w._read_sources(WELCOME)})
    assert not result.get("error"), result
    d = result["declarations"]
    assert {b["id"] for b in d["blocks"]} == {"gem_ore", "gem_block"}
    assert {i["id"] for i in d["items"]} >= {"gem", "gem_apple", "gem_sword", "gem_wand"}
    for entry in d["blocks"] + d["items"]:
        texture = entry["definition"].get("texture")
        assert os.path.isfile(os.path.join(WELCOME, "assets", "textures", texture)), texture
    assert {c["name"] for c in d["commands"]} >= {"gift", "stats", "menu", "settings", "selftest"}

    # A Python window: open, click +1 (redraw), close.
    CALLS.clear()
    w._handle("trigger_event", {"event": "command_menu", "context": {"player": DEV, "args": {}}})
    tree = opened_windows()[-1]
    assert tree["id"] == "counter" and find(tree["root"], type="label", text="Clicked 0 times"), tree
    more = find(tree["root"], type="button", text="+1")["on_click"]
    assert isinstance(more, str) and more
    w._handle("trigger_event", {"event": "ui_event", "context": {"player": DEV, "window": "counter", "token": more}})
    assert find(opened_windows()[-1]["root"], type="label", text="Clicked 1 times"), opened_windows()[-1]
    # Tokens of other windows / other players / nonsense do nothing.
    count = len(opened_windows())
    for context in ({"player": DEV, "window": "counter", "token": "t99"},
                    {"player": dict(DEV, name="Other"), "window": "counter", "token": more},
                    {"player": DEV, "window": "settings", "token": more}):
        w._handle("trigger_event", {"event": "ui_event", "context": context})
    assert len(opened_windows()) == count
    close = find(opened_windows()[-1]["root"], type="button", text="Close")["on_click"]
    CALLS.clear()
    w._handle("trigger_event", {"event": "ui_event", "context": {"player": DEV, "window": "counter", "token": close}})
    assert [m for m, _ in CALLS] == ["ui.close"], CALLS
    assert ("Dev", "counter") not in w._UI_OPEN

    # The window drawn in the UI designer: its buttons name @actions; "close" is built in.
    CALLS.clear()
    w._handle("trigger_event", {"event": "command_settings", "context": {"player": DEV, "args": {}}})
    tree = opened_windows()[-1]
    assert find(tree["root"], type="text_input", id="nickname"), tree
    result = w._handle("trigger_event", {"event": "ui_event", "context": {
        "player": DEV, "window": "settings", "token": "apply_settings",
        "values": {"nickname": "Steve", "hints": True, "volume": 30}}})
    assert not result["log"], result
    assert ("ui.close", {"player": "Dev", "id": "settings"}) in CALLS, CALLS

    # Self test command reports through the player.
    CALLS.clear()
    w._handle("trigger_event", {"event": "command_selftest", "context": {"player": DEV, "args": {}}})
    said = " ".join(str(p) for m, p in CALLS if m == "player.send_message")
    assert "Self test:" in said and "FAILED" not in said, said


def test_window_callback_errors_are_logged():
    run_main("ew", """
from craftpy import *
@window("bad", size=(100, 100))
class Bad(Window):
    def build(self, ctx):
        return Button("x", on_click=self.boom)
    def boom(self, ctx):
        1 / 0
""")
    CALLS.clear()
    w._host_call("noop")
    w._use_owner("ew")
    w.ui.open(DEV["name"], "bad")
    token = opened_windows()[-1]["root"]["on_click"]
    result = w._handle("trigger_event", {"event": "ui_event", "context": {"player": DEV, "window": "bad", "token": token}})
    assert any("ZeroDivisionError" in line for line in result["log"]), result


TEMPLATES = os.path.join(HERE, "..", "..", "main", "resources", "craftpy_templates")


def test_project_templates_run():
    names = sorted(n for n in os.listdir(TEMPLATES) if n.endswith(".py"))
    assert len(names) == 8, names
    for name in names:
        code = open(os.path.join(TEMPLATES, name), encoding="utf-8").read().replace("{{id}}", "tpl")
        run_main("tpl_" + name[:-3], code)
    block = run_main("tpl_b", open(os.path.join(TEMPLATES, "block.py"), encoding="utf-8").read().replace("{{id}}", "tpl"))
    assert [b["id"] for b in block["blocks"]] == ["gem_ore"] and block["recipes"], block


def test_completion_and_hover():
    names = lambda r: [i["n"] for i in r["items"]]
    r = w._handle("complete", {"code": "world.set_", "line": 0, "column": 10})
    assert names(r) == ["set_block", "set_time"], names(r)
    assert r["prefix"] == "set_"
    code = "def helper(a, b):" + chr(10) + "    pass" + chr(10) + "hel"
    r = w._handle("complete", {"code": code, "line": 2, "column": 3})
    assert names(r) == ["helper"], names(r)
    assert "(a, b)" in r["items"][0]["s"]
    r = w._handle("complete", {"code": "Ite", "line": 0, "column": 3})
    assert "Item" in names(r) and "ItemIcon" in names(r)
    assert w._handle("complete", {"code": "nothing.x", "line": 0, "column": 9})["items"] == []
    assert w._handle("complete", {"code": "world._p", "line": 0, "column": 8})["items"] == []
    h = w._handle("hover", {"code": "world.set_block(1)", "line": 0, "column": 8})
    assert h["title"].startswith("world.set_block("), h
    assert "Declares an item" in w._handle("hover", {"code": "Item('a')", "line": 0, "column": 2})["doc"]
    assert w._handle("hover", {"code": "unknown_thing", "line": 0, "column": 3}) == {}
    assert w._handle("hover", {"code": "def go(x):" + chr(10) + "    pass" + chr(10) + "go(1)", "line": 2, "column": 1})["title"] == "def go(x)"


NL = chr(10)


def test_multi_file_projects_and_failed_reload():
    sources = {
        "code/items.py": NL.join(["from craftpy import *", "gem = Item('gem')", ""]),
        "code/blocks/__init__.py": "",
        "code/blocks/ore.py": NL.join(["from craftpy import *", "from items import gem",
                                        "gem_ore = Block('gem_ore', drops=(gem, 1))", ""]),
    }
    main = NL.join(["from craftpy import *", "from blocks.ore import gem_ore", "import items",
                    "@on.player_join", "def hi(event):", "    pass", ""])
    r = w._handle("run_main", {"project": "split", "code": main, "file": "code/main.py", "sources": sources})
    assert not r.get("error"), r
    ids = sorted(b["id"] for b in r["declarations"]["blocks"]) + sorted(i["id"] for i in r["declarations"]["items"])
    assert ids == ["gem_ore", "gem"], ids
    assert len(r["declarations"]["handlers"]) == 1
    # a second run executes the files again instead of reusing cached modules
    r = w._handle("run_main", {"project": "split", "code": main, "file": "code/main.py", "sources": sources})
    assert len(r["declarations"]["blocks"]) == 1, r
    # a broken module reports ITS file and line, and the previous handlers stay registered
    broken = dict(sources, **{"code/items.py": NL.join(["from craftpy import *", "", "boom = 1 / 0", ""])})
    r = w._handle("run_main", {"project": "split", "code": main, "file": "code/main.py", "sources": broken})
    assert r.get("error") and r["file"] == "code/items.py" and r["line"] == 2, r
    assert len(w._OWNERS["split"]["decls"]["handlers"]) == 1
    assert "blocks" not in sys.modules and "items" not in sys.modules
    # first-ever load that fails leaves nothing behind
    r = w._handle("run_main", {"project": "fresh", "code": "boom = 1/0", "file": "code/main.py"})
    assert r.get("error") and "fresh" not in w._OWNERS, r


def test_java_bridge_proxies():
    """The Python half of the bridge against a fake host that behaves like the real one."""
    store = {1: {"hp": 20}}
    calls = []

    def host(method, params=None, timeout=10.0):
        calls.append((method, params))
        if not method.startswith("java."):
            return {"ok": True}
        kind = method[5:]
        if kind == "class":
            return {"ok": True, "value": {"$c": params["name"]}}
        if kind == "new":
            return {"ok": True, "value": {"$h": 1, "$t": params["name"], "$s": "obj"}}
        if kind == "get":
            if params["name"] == "getHealth":
                return {"ok": True, "value": {"$m": "getHealth"}}
            if params["name"] == "OWNER":
                return {"ok": True, "value": {"$h": 2, "$t": "x.Owner", "$s": "owner"}}
            return {"ok": False, "error": "no such member"}
        if kind == "invoke":
            return {"ok": True, "value": store[params["target"]["$h"]]["hp"]}
        if kind == "items":
            return {"ok": True, "value": [1, {"$h": 2, "$t": "x.Owner", "$s": "owner"}]}
        return {"ok": True, "value": None}

    saved = w._host_call
    w._host_call = host
    try:
        obj = w.java.new("a.b.Player", 1, [1, 2, 3], {"k": True})
        assert isinstance(obj, w.JavaObject) and str(obj) == "obj"
        assert obj.getHealth() == 20
        assert obj.OWNER == w.JavaObject(2, "x.Owner", "owner")
        assert [type(i).__name__ for i in obj] == ["int", "JavaObject"]
        cls = w.java.cls("a.b.Blocks")
        assert isinstance(cls, w.JavaClass) and repr(cls) == "<java class a.b.Blocks>"
        try:
            obj.missing
            assert False, "unknown member must raise"
        except w.JavaError as exc:
            assert "no such member" in str(exc)
        new_call = [p for m, p in calls if m == "java.new"][0]
        assert new_call["args"] == [1, [1, 2, 3], {"k": True}], new_call
        # python functions travel as ids and can be called back
        assert w._jenc(lambda: 5) == {"$fn": max(w._FN_TABLE)}
        ident = w._jenc(lambda a, b: a + b)["$fn"]
        assert w._handle("call_fn", {"id": ident, "args": [2, 3]}) == {"value": 5}
        assert "error" in w._handle("call_fn", {"id": 99999, "args": []})
        # released objects are reported with the next call
        del obj
        w.java.cls("a.b.Other")
        assert any("release" in (p or {}) for m, p in calls), calls
        try:
            w._jenc(object())
            assert False
        except TypeError:
            pass
    finally:
        w._host_call = saved


def test_java_event_handlers():
    seen = []
    saved = w._host_call
    w._host_call = lambda method, params=None, timeout=10.0: {"ok": True, "value": None}
    code = NL.join(["from craftpy import *",
                    "@java.on('net.neoforged.neoforge.event.X')",
                    "def hurt(event):",
                    "    print('got', event)",
                    ""])
    try:
        d = run_main("jv", code)
        assert {"event": "java:net.neoforged.neoforge.event.X", "every": None, "priority": "normal"} in d["handlers"]
        r = w._handle("trigger_event", {"event": "java:net.neoforged.neoforge.event.X",
                                         "context": {"java": {"$h": 7, "$t": "x.X", "$s": "the event"}}})
        assert r["handlers"] == 1 and r["log"] == ["got the event"], r
    finally:
        w._host_call = saved


def test_extensions_data_and_validation():
    code = NL.join([
        "from craftpy import *",
        "sword = Item('ruby_sword', texture='s.png').extends(Sword(tier='diamond'), Durability(1561, repair_with='diamond'))",
        "pick = Item('ruby_pick', extensions=[Pickaxe('iron', speed=7)])",
        "axe = Item('cooler', glow=True).extends(Weapon(damage=3, knockback=1), Fuel(30), Cooldown(2), Tooltip('hot', color='gold'))",
        "helmet = Item('ruby_helm').extends(Armor('head', defense=3, toughness=1))",
        "ore = Block('ruby_ore', hardness=3, drops=sword).extends(MineableBy('pickaxe', tier='iron'), Drops((sword, 2), 'self'),",
        "                                                       Redstone(7), Flammable(5, 20), Friction(0.9), Piston('block'))",
        "",
    ])
    d = run_main("ext", code)
    items = {i["id"]: i["definition"] for i in d["items"]}
    ext = lambda name, kind: [e for e in items[name]["ext"] if e["type"] == kind][0]
    assert ext("ruby_sword", "tool") == {"type": "tool", "kind": "sword", "level": 3, "speed": 8.0, "damage": 7.0, "attack_speed": 1.6}
    assert ext("ruby_sword", "durability") == {"type": "durability", "uses": 1561, "repair_with": "minecraft:diamond"}
    assert items["ruby_sword"]["max_stack"] == 1 and ext("ruby_sword", "enchantable")["value"] == 10
    assert ext("ruby_pick", "tool")["speed"] == 7.0 and ext("ruby_pick", "durability")["uses"] == 250
    assert items["cooler"]["burn_time"] == 30 and items["cooler"]["glow"] is True
    assert ext("cooler", "weapon") == {"type": "weapon", "damage": 3.0, "attack_speed": 1.6, "knockback": 1.0}
    assert ext("cooler", "tooltip")["color"] == "gold" and ext("cooler", "cooldown")["seconds"] == 2.0
    assert ext("ruby_helm", "armor")["slot"] == "head"
    block = {b["id"]: b["definition"] for b in d["blocks"]}["ruby_ore"]
    assert block["tool"] == "pickaxe" and block["needs_tool"] is True
    assert [e for e in block["ext"] if e["type"] == "mineable"] == [{"type": "mineable", "level": 2}]
    assert {e["type"] for e in block["ext"]} == {"mineable", "redstone", "flammable", "friction", "piston"}
    tables = [t for t in d["loot_tables"] if t["id"] == "ruby_ore"]
    assert len(tables) == 1 and len(tables[0]["entries"]) == 2, tables
    # mistakes are explained, with the attribute named
    for bad, text in [("Item('a').extends(Sword(tier='ruby'))", "Tool tier must be one of"),
                      ("Item('a').extends(Durability(0))", "Durability uses must be between"),
                      ("Item('a').extends(Sword)", "with parentheses"),
                      ("Item('a').extends(Redstone(3))", "can only be used on block"),
                      ("Block('b').extends(Armor())", "can only be used on item"),
                      ("Item('a', rarity='epic')", "has no attribute 'rarity'"),
                      ("Item('a').extends('sword')", "takes extensions"),
                      ("Item('a').extends(Fuel(1.5))", "whole number")]:
        r = w._handle("run_main", {"project": "bad", "code": "from craftpy import *" + NL + bad, "file": "code/main.py"})
        assert r.get("error") and text in r["message"], (bad, r)


def test_custom_extensions_and_class_form():
    code = NL.join([
        "from craftpy import *",
        "@extension",
        "class Vampiric(Extension):",
        "    def __init__(self, heal=2):",
        "        self.heal = heal",
        "    def on_hit(self, event):",
        "        print('hit', self.heal)",
        "        event.cancel()",
        "    def on_use(self, event):",
        "        print('use')",
        "    def configure(self, target):",
        "        target.set('glow', True)",
        "fang = Item('fang').extends(Vampiric(heal=5))",
        "other = Item('other').extends(Vampiric())",
        "class RubyBlade(ItemType):",
        "    id = 'ruby_blade'",
        "    name = 'Ruby Blade'",
        "    stack = 1",
        "    extensions = [Sword('iron')]",
        "    def on_mine(self, event):",
        "        print('mined with blade')",
        "class RubyBlock(BlockType):",
        "    id = 'ruby_block2'",
        "    hardness = 5",
        "    extensions = [Redstone(3)]",
        "shaped(RubyBlade, 'R', R=fang)",
        "",
    ])
    d = run_main("cx", code)
    assert {i["id"]: i["definition"].get("glow") for i in d["items"]}["fang"] is True
    assert "item_hit" in [h["event"] for h in d["handlers"]]
    r = w._handle("trigger_event", {"event": "item_hit", "context": {"item": {"id": "cx:fang"}, "damage": 4}})
    assert r["log"] == ["hit 5"], r
    assert r["cancel"] is True and "damage" not in r
    r = w._handle("trigger_event", {"event": "item_hit", "context": {"item": {"id": "cx:other"}, "damage": 4}})
    assert r["log"] == ["hit 2"], r
    r = w._handle("trigger_event", {"event": "item_hit", "context": {"item": {"id": "minecraft:stick"}}})
    assert r["log"] == [] and "cancel" not in r
    r = w._handle("trigger_event", {"event": "item_mine", "context": {"item": {"id": "cx:ruby_blade"}}})
    assert r["log"] == ["mined with blade"], r
    items = {i["id"]: i["definition"] for i in d["items"]}
    assert items["ruby_blade"]["name"] == "Ruby Blade" and items["ruby_blade"]["ext"][0]["kind"] == "sword"
    assert {b["id"] for b in d["blocks"]} == {"ruby_block2"}
    assert "cx:ruby_blade" in str(d["recipes"][0]), d["recipes"]
    # a typo'd hook, and a hook that does not exist for the kind, are rejected
    for bad, text in [(["@extension", "class A(Extension):", "    def on_hitt(self, e): pass"], "is not a hook"),
                      (["@extension", "class A(Extension):", "    def on_break(self, e): pass", "Item('a').extends(A())"],
                       "is not a hook of a item")]:
        r = w._handle("run_main", {"project": "bad", "code": "from craftpy import *" + NL + NL.join(bad), "file": "code/main.py"})
        assert r.get("error") and text in r["message"], (bad, r)


def test_api_reference_is_complete_and_current():
    """Every parameter of the public API is described, and api.md carries the current reference."""
    spec = importlib.util.spec_from_file_location("gen_api_doc", os.path.join(HERE, "gen_api_doc.py"))
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    text, missing = gen.build(w)
    assert missing == [], missing
    current = gen.current_text()
    assert gen.merged(current, text) == current, "api.md is out of date: python src/test/python/gen_api_doc.py"
    covered = {key.partition(".")[0] for _, keys in gen.CHAPTERS for key in keys}
    legacy = {"block", "item", "entity", "effect", "enchantment", "potion", "block_logic", "cp", "JavaError", "Content",
              "Entity", "Player", "Window", "players", "world", "storage", "scheduler", "server", "ui", "java",
              "API_VERSION"}  # API_VERSION is in the generated "API versions" part
    uncovered = sorted(n for n in w._api_names() if not n.startswith("_") and n not in covered and n not in legacy)
    assert uncovered == [], "public names without documentation: %s" % uncovered


def test_definition_and_stub():
    stub = w._handle("stub", {})
    lines = stub["text"].split(NL)
    d = w._handle("definition", {"code": "world.set_block(1)", "line": 0, "column": 8})
    assert d["kind"] == "api" and d["path"] == stub["path"], d
    assert "def set_block(" in lines[d["line"]], lines[d["line"]]
    d = w._handle("definition", {"code": "Item('a')", "line": 0, "column": 1})
    assert d["kind"] == "api" and "Item" in lines[d["line"]], d
    code = "def helper(a):" + NL + "    pass" + NL + "helper(1)"
    assert w._handle("definition", {"code": code, "line": 2, "column": 2}) == {"kind": "local", "line": 0, "symbol": "helper"}
    assert w._handle("definition", {"code": "other_file_fn()", "line": 0, "column": 3})["kind"] == "none"
    assert w._handle("definition", {"code": "x", "line": 5, "column": 0}) == {"kind": "none"}


def test_lint_warnings():
    src = NL.join(["import json", "from craftpy import *", "def a(): pass", "def a(): pass",
                   "x = Item('i')", "print(missing_name, x)", ""])
    r = w._handle("validate", {"code": src, "file": "main.py"})
    msgs = [p["message"] for p in r["problems"]]
    assert "'json' is imported but never used" in msgs, msgs
    assert "Undefined name 'missing_name'" in msgs, msgs
    assert any("already defined" in m for m in msgs), msgs
    assert not any("Item" in m or "print" in m for m in msgs), msgs
    assert all(p["severity"] == "warning" for p in r["problems"])
    local = w._handle("validate", {"code": "import blocks" + NL + "import items" + NL, "file": "code/main.py"})
    assert local["problems"] == [], local  # importing a project file runs it: no warning
    star = w._handle("validate", {"code": "from other import *" + NL + "print(zzz)" + NL, "file": "m.py"})
    assert not any("Undefined" in p["message"] for p in star["problems"])


def test_recipes_of_other_mods_and_attribute_extensions():
    d = run_main("rr", '''
from craftpy import *

gem = Item("gem")
gem.extends(Attributes("irons_spellbooks:fire_spell_power", 0.1, operation="multiply_base", slot="offhand"))
recipe("create:mixing", id="mix", ingredients=[gem, "lava"], results=[{"id": gem}], heat_requirement="heated")
raw_json("tags/item/x.json", {"values": []}, namespace="minecraft")
''')
    mix = [r for r in d["recipes"] if r["type"] == "create:mixing"][0]
    assert mix["id"] == "mix" and mix["heat_requirement"] == "heated"
    assert mix["ingredients"] == ["rr:gem", "lava"] and mix["results"] == [{"id": "rr:gem"}]
    ext = [i for i in d["items"] if i["id"] == "gem"][0]["definition"]["ext"]
    assert ext == [{"type": "attributes", "attribute": "irons_spellbooks:fire_spell_power", "amount": 0.1,
                    "operation": "add_multiplied_base", "slot": "offhand"}], ext
    raw = [r for r in d["raw"] if r["id"] == "tags/item/x.json"][0]
    assert raw.get("namespace") == "minecraft", raw
    for bad in (lambda: w.recipe("shaped"), lambda: w.Attributes("", 1), lambda: w.Attributes("a:b", 1, slot="knee")):
        try:
            bad()
            assert False, "should have been refused"
        except ValueError:
            pass


def test_params_and_extensions_for_completion():
    assert "player_join" in dir(w.on) and "block_break" in dir(w.on)
    names = [i["n"] for i in w._handle("complete", {"code": "Item(", "line": 0, "column": 5})["items"]]
    assert "id=" in names and "texture=" in names, names
    assert not any(n.endswith("=") for n in [i["n"] for i in w._handle("complete", {"code": "nothing_here(", "line": 0, "column": 13})["items"]])
    run_main("rr", "from craftpy import *\n@extension\nclass Vampiric(Extension):\n    pass\n")
    items = w._handle("complete", {"code": "Vamp", "line": 0, "column": 4})["items"]
    assert any(i["n"] == "Vampiric" for i in items), items
    w._handle("unload_owner", {"owner": "rr"})
    items = w._handle("complete", {"code": "Vamp", "line": 0, "column": 4})["items"]
    assert not any(i["n"] == "Vampiric" for i in items), items


def test_block_tick_and_interact_context_from_a_block_entity():
    # the shape SlottedBlockEntity sends: x/y/z + dimension + block dict (+ player dict); event.pos is built from x/y/z
    run_main("bt", """
from craftpy import *
box = Block("box", texture="box.png")
seen = []

@on.block_tick(block=box)
def tick(event):
    seen.append((event.pos.x, event.pos.y, event.pos.z, event.block.id))

@on.block_interact(block=box)
def touch(event):
    seen.append(("touch", event.player.name, event.pos.y))
""")
    block = {"id": "bt:box", "name": "Box", "craftpy": True}
    ctx = {"x": 4, "y": 70, "z": -2, "dimension": "minecraft:overworld", "block": block, "block_id": "bt:box", "state": {}}
    assert w._handle("trigger_event", {"event": "block_tick", "context": ctx})["handlers"] == 1
    player = {"name": "Dev", "uuid": "00000000-0000-0000-0000-000000000000"}
    assert w._handle("trigger_event", {"event": "block_interact", "context": dict(ctx, player=player)})["handlers"] == 1
    w._handle("unload_owner", {"owner": "bt"})


def _names(code, line=None, column=None):
    lines = code.split(chr(10))
    line = len(lines) - 1 if line is None else line
    column = len(lines[line]) if column is None else column
    return [i["n"] for i in w._handle("complete", {"code": code, "line": line, "column": column})["items"]]


def test_completion_of_chained_calls_and_returned_values():
    assert "extends" in _names('Item("a").')
    assert "extends" in _names('Item("a", texture="x.png").extends(Sword()).')
    assert "local_id" in _names('gem = Item("gem")' + chr(10) + "gem.")
    assert "tier=" in _names('Item("a").extends(Sword(')
    assert "durability=" in _names('Item("a").extends(Sword(tier="iron"), Durability(') or         any(n.endswith("=") for n in _names('Item("a").extends(Durability('))
    assert "up" in _names("Pos(1, 2, 3).up(1).")
    assert "upper" in _names('"abc".')
    assert _names("x = 1.") == [] or "real" not in _names("x = 1.")


def test_completion_of_event_handlers_and_contexts():
    code = "@on.block_break" + chr(10) + "def broke(event):" + chr(10) + "    event."
    names = _names(code)
    assert "player" in names and "block" in names and "cancel" in names, names
    names = _names(code.replace("event.", "event.player."))
    assert "give" in names and "teleport" in names and "health" in names, names
    assert "id" in _names(code.replace("event.", "event.block."))
    assert "up" in _names(code.replace("event.", "event.pos."))
    assert "x" in _names(code.replace("event.", "event.player.pos."))
    assert "count=" in _names(code.replace("event.", "event.player.give(\"x\", "))
    tick = "@every(seconds=5)" + chr(10) + "def t(ctx):" + chr(10) + "    ctx."
    assert "tick" in _names(tick) and "world" in _names(tick)
    assert "set_block" in _names(tick.replace("ctx.", "ctx.world."))
    cmd = "@command(\"heal\")" + chr(10) + "def heal(ctx, target: Player):" + chr(10) + "    target."
    assert "heal" in _names(cmd)
    loop = "for p in players.online():" + chr(10) + "    p."
    assert "give" in _names(loop)
    # the line being typed does not parse; the handler around it is still found
    broken = "@on.player_join" + chr(10) + "def hi(e):" + chr(10) + "    e.player.say(" + chr(10) + "    e."
    assert "player" in _names(broken)


def test_completion_of_classes_and_functions_of_the_file():
    code = chr(10).join([
        "class Shop(Window):",
        "    def __init__(self):",
        "        self.stock = Item('a')",
        "    def price(self, item):",
        "        return 3",
        "    def show(self):",
        "        self.",
    ])
    names = _names(code)
    assert "stock" in names and "price" in names and "show" in names, names
    assert "extends" in _names(code + "stock.")
    fn = "def make():" + chr(10) + "    return Item('a')" + chr(10) + "make()."
    assert "extends" in _names(fn)
    assert "item=" in _names(code + "price(")
    assert _names("# Item(" + chr(10) + "x", 0, 7) == []  # in a comment


def test_hover_uses_inferred_types():
    code = "@on.block_break" + chr(10) + "def broke(event):" + chr(10) + "    event.player.give('x')"
    h = w._handle("hover", {"code": code, "line": 2, "column": 21})
    assert h["title"].startswith("event.player.give("), h
    h = w._handle("hover", {"code": code, "line": 2, "column": 6})
    assert h["title"] == "event: EventContext", h


def test_every_building_block_loads_as_a_file_of_a_project():
    # what the editor's "New file" does: code/<module>.py from the template, "import <module>" in main.py
    root = os.path.join(HERE, "..", "..", "main", "resources", "craftpy_blocks")
    index = [line.strip() for line in open(os.path.join(root, "index.txt"), encoding="utf-8")
             if line.strip() and not line.startswith("#")]
    assert len(index) >= 25, index
    for number, rel in enumerate(index):
        text = open(os.path.join(root, rel), encoding="utf-8").read()
        meta = dict(line[2:].strip().split(":", 1) for line in text.splitlines() if line.startswith("#:"))
        module = meta["module"].strip()
        body = "\n".join(line for line in text.splitlines() if not line.startswith("#:"))
        project = "bb%d" % number
        filled = body.replace("{{name}}", module).replace("{{id}}", project)
        main = "from craftpy import *\nimport " + module + "\n"
        result = w._handle("run_main", {"project": project, "code": main, "file": "code/main.py",
                                        "sources": {"code/" + module + ".py": filled}})
        assert not result.get("error"), (rel, result.get("error"))
        compile(filled.replace("from craftpy import *", ""), rel, "exec")  # the Insert snippet parses alone too
        w._handle("unload_owner", {"owner": project})



def test_fuzzy_completion_and_signature_help():
    names = _names("world.gb")
    assert "get_block" in names and "get_block_entities" in names, names
    assert _names("world.set_") == ["set_block", "set_time"]
    assert names.index("get_block") < len(names)
    ranked = _names("Ite")
    assert ranked.index("Item") < ranked.index("ItemIcon"), ranked
    sig = w._handle("signature", {"code": 'Item("gem", tex', "line": 0, "column": 15})
    assert sig["title"].startswith("Item(") and ">>name=None<<" in sig["title"], sig
    sig = w._handle("signature", {"code": 'Item("gem", texture=', "line": 0, "column": 20})
    assert ">>texture=None<<" in sig["title"], sig
    code = "@on.player_join" + chr(10) + "def hi(event):" + chr(10) + "    event.player.give("
    sig = w._handle("signature", {"code": code, "line": 2, "column": 22})
    assert sig["title"].startswith("event.player.give(>>item<<"), sig
    assert w._handle("signature", {"code": "x = 1", "line": 0, "column": 5}) == {}



def test_extensions_of_a_module_can_be_used_by_other_projects():
    run_main("ext_mod", "from craftpy import *\n@extension\nclass Glowing(Extension):\n    pass\n")
    decls = run_main("ext_user", "from craftpy import *\nItem('wand').extends(Glowing())\n")
    assert any(i["id"] == "wand" for i in decls["items"]), decls
    w._handle("unload_owner", {"owner": "ext_mod"})
    result = w._handle("run_main", {"project": "ext_user2", "code": "from craftpy import *\nGlowing()\n",
                                    "file": "code/main.py", "sources": {}})
    assert result.get("error"), "gone with the module that declared it"
    w._handle("unload_owner", {"owner": "ext_user"})


def test_effect_specs_keep_namespaced_ids_whole():
    assert w._effect_spec("speed") == "minecraft:speed:0:5"
    assert w._effect_spec("irons_spellbooks:evasion") == "irons_spellbooks:evasion:0:5"
    assert w._effect_spec(("strength", 2, 8)) == "minecraft:strength:1:8"
    assert w._effect_spec(("irons_spellbooks:evasion", 1, 3)) == "irons_spellbooks:evasion:0:3"


def test_completion_follows_imports_between_project_files():
    sources = {"code/items.py": "from craftpy import *" + chr(10) + "gem = Item('gem')" + chr(10) + "def helper(a):" + chr(10)
               + "    return gem" + chr(10)}
    def ask(code):
        lines = code.split(chr(10))
        reply = w._handle("complete", {"code": code, "line": len(lines) - 1, "column": len(lines[-1]), "sources": sources})
        return [i["n"] for i in reply["items"]]
    assert "extends" in ask("from items import gem" + chr(10) + "gem.")
    assert "gem" in ask("import items" + chr(10) + "items.") and "helper" in ask("import items" + chr(10) + "items.")
    assert "extends" in ask("import items" + chr(10) + "items.helper(1).")
    assert ask("from nothing import x" + chr(10) + "x.") == []
    assert w._ASSIST_SOURCES == {}, "the sources are dropped after the request"


def test_items_and_blocks_join_tags():
    d = run_main("tg", "from craftpy import *" + chr(10)
                 + "Item('blade', tags=['minecraft:swords', '#gems'])" + chr(10)
                 + "Block('ore', tags='minecraft:mineable/pickaxe')" + chr(10))
    tags = [(t["id"], t["type"], t["values"]) for t in d["tags"]]
    assert ("minecraft:swords", "item", ["tg:blade"]) in tags, tags
    assert ("gems", "item", ["tg:blade"]) in tags, tags
    assert ("minecraft:mineable/pickaxe", "block", ["tg:ore"]) in tags, tags
    result = w._handle("run_main", {"project": "tg2", "code": "from craftpy import *" + chr(10) + "Item('x', tags=['a b'])",
                                    "file": "code/main.py", "sources": {}})
    assert result.get("error"), "a tag id with a space is refused"


def test_extensions_and_fixed_values_are_offered_where_they_fit():
    names = _names('Item("blade").extends(')
    assert names[0] in ("Armor", "Attributes"), names[:5]
    assert "Sword" in names and "Durability" in names and "Redstone" not in names[:20], names[:20]
    block = _names('Block("ore").extends(')
    assert "Redstone" in block and "MineableBy" in block and "Sword" not in block[:15], block[:15]
    assigned = _names('ore = Block("ore")' + chr(10) + "ore.extends(")
    assert "Redstone" in assigned[:15], assigned[:15]
    in_list = _names('Item("x", extensions=[Du')
    assert in_list[0] == "Durability", in_list[:3]
    run_main("ext_owner", "from craftpy import *\n@extension\nclass Shiny(Extension):\n    applies_to = ('item',)\n")
    assert "Shiny" in _names('Item("x").extends(Sh')
    w._handle("unload_owner", {"owner": "ext_owner"})
    assert _names('Sword(tier="di') == ["diamond"]
    assert "mainhand" in _names('Attributes("minecraft:generic.armor", 2, slot="')
    assert "multiply_base" in _names('Attributes("minecraft:generic.armor", 2, "')
    assert "metal" in _names('Block("x", sound="')
    assert _names('Label("hel') == []
    assert _names('x = 1  # "tier') == []


def test_run_tests():
    sources = {
        "code/items.py": "from craftpy import *\nruby = Item('ruby')\ndef double(x):\n    return x * 2\n",
        "code/test_items.py": ("from items import ruby, double\n"
                               "def test_double():\n    assert double(2) == 4\n"
                               "def test_wrong():\n    assert double(2) == 5\n"
                               "def test_id():\n    assert ruby == 'tp:ruby', ruby\n"
                               "def test_boom():\n    {}['x']\n"
                               "def helper():\n    raise RuntimeError('not a test')\n"),
        "code/test_broken.py": "def test_x(:\n    pass\n",
        "code/notes.py": "def test_not_collected():\n    assert False\n",
    }
    run_main("tp", "from craftpy import *\n")
    previous = w._OWNER[0]
    result = w._handle("run_tests", {"project": "tp", "sources": sources})
    rows = {(r["file"].rsplit("/", 1)[-1], r["name"]): r for r in result["results"]}
    assert rows[("test_items.py", "test_double")]["ok"], rows
    wrong = rows[("test_items.py", "test_wrong")]
    assert not wrong["ok"] and wrong["message"] == "assert double(2) == 5" and wrong["line"] == 4, wrong
    assert rows[("test_items.py", "test_id")]["ok"], rows
    assert rows[("test_items.py", "test_boom")]["message"].startswith("KeyError"), rows
    assert ("test_items.py", "helper") not in rows
    broken = rows[("test_broken.py", "(file)")]
    assert not broken["ok"] and broken["line"] == 0 and "SyntaxError" in broken["message"], broken
    assert not any(name == "test_not_collected" for _, name in rows)
    assert "test:tp" not in w._OWNERS and w._OWNER[0] == previous, (list(w._OWNERS), w._OWNER)
    only = w._handle("run_tests", {"project": "tp", "sources": sources, "file": "code/test_broken.py"})
    assert only["files"] == ["code/test_broken.py"], only


def test_api_versions():
    assert w.API_CHANGES[0]["version"] == w.API_VERSION, "the newest API_CHANGES entry must be API_VERSION"
    versions = [w._api_tuple(c["version"]) for c in w.API_CHANGES]
    assert versions == sorted(versions, reverse=True) and len(set(versions)) == len(versions), versions
    assert w._api_tuple("2") == (2, 0) and w._api_tuple("2.1") == (2, 1) and w._api_tuple("x") == (0, 0)
    for name, (since, use) in w._DEPRECATED.items():
        assert name in w._api_names() and w._api_tuple(since) <= w._api_tuple(w.API_VERSION), name
    assert w._handle("ping", {})["api"] == w.API_VERSION

    import tempfile, json as _json
    with tempfile.TemporaryDirectory() as folder:
        with open(os.path.join(folder, "project.json"), "w") as handle:
            _json.dump({"apiVersion": "9.0"}, handle)
        old = ("from craftpy import *\n"
               "@block('ore', texture=tex('ore.png'))\nclass Ore:\n    pass\n"
               "recipe_smelting('apiv:ore', {'item': 'minecraft:stone'})\n"
               "assert API_VERSION == '%s'\n" % w.API_VERSION)
        result = w._handle("run_main", {"project": "apiv", "code": old, "file": "code/main.py", "dir": folder})
        assert not result.get("error"), result
        notes = [line for line in result["log"] if line.startswith("[api]")]
        assert any("written for Python API 9.0" in n for n in notes), notes
        assert [n.split("()")[0][6:] for n in notes[1:]] == ["block", "recipe_smelting", "tex"], notes
        assert len(result["declarations"]["blocks"]) == 1  # old code still works
        clean = w._handle("run_main", {"project": "apiv", "code": "from craftpy import *\nItem('x')\n",
                                       "file": "code/main.py"})
        assert not [line for line in clean["log"] if line.startswith("[api]")], clean["log"]


def test_lint_checks():
    code = NL.join([
        "import os",
        "from craftpy import *",
        "",
        "@on('player_jion')",
        "def hello(event, seen=[]):",
        "    unused = 1",
        "    used = 2",
        "    if event == None:",
        "        return used",
        "        say('never')",
        "    try:",
        "        pass",
        "    except:",
        "        pass",
        "    x = {'a': 1, 'a': 2}",
        "    print(f'plain', x, event is 'steve')",
        "",
        "@on.block_brake",
        "def broke(event):",
        "    def inner():",
        "        return captured",
        "    captured = 3",
        "    return inner",
        ""])
    problems = w._handle("validate", {"file": "code/main.py", "code": code})["problems"]
    text = [(p["line"] + 1, p["severity"], p["message"]) for p in problems]
    joined = NL.join("%d %s %s" % t for t in text)
    for expected in ["1 error 'os' is blocked", "4 warning No event 'player_jion'", "did you mean 'player_join'",
                     "5 warning A list/dict/set default", "6 warning 'unused' is assigned but never used",
                     "8 warning Compare with None", "10 warning This code never runs", "13 warning 'except:'",
                     "15 warning Key 'a' appears twice", "16 warning f-string without", "16 warning 'is' checks identity",
                     "18 warning No event 'block_brake'"]:
        assert expected in joined, (expected, joined)
    assert "'used' is assigned" not in joined and "'captured' is assigned" not in joined, joined
    spec = w._handle("validate", {"file": "code/a.py", "code": "x = 3" + NL + "print(f'{x:02d}')" + NL})["problems"]
    assert spec == [], spec
    clean = w._handle("validate", {"file": "code/main.py", "code": SIMPLE})["problems"]
    assert clean == [], clean


def test_debugger_snapshots():
    code = NL.join([
        "from craftpy import *",
        "total = 0",
        "",
        "@on.player_join",
        "def hello(event):",
        "    greeting = 'hi ' + event.player.name",
        "    event.player.say(greeting)",
        "",
        "for i in range(3):",
        "    total += i",
        ""])
    w._handle("debug", {"project": "dbg", "cmd": "set", "breakpoints": {"code/main.py": [6, 9]}})
    run_main("dbg", code)
    first = w._handle("debug", {"project": "dbg", "cmd": "hits"})
    loop = [h for h in first["hits"] if h["line"] == 9]
    assert len(loop) == 3 and first["breakpoints"] == 2, first
    assert ["i", "int", "2"] in loop[-1]["variables"], loop[-1]["variables"]
    ctx = {"player": {"name": "Alex", "uuid": "u-1"}}
    w._handle("trigger_event", {"event": "player_join", "context": ctx})
    after = w._handle("debug", {"project": "dbg", "cmd": "hits", "since": first["last"]})
    hit = after["hits"][0]
    assert hit["function"] == "hello" and hit["line"] == 6, hit
    assert ["greeting", "str", "'hi Alex'"] in hit["variables"], hit["variables"]
    assert hit["stack"][0]["function"] == "hello", hit["stack"]
    assert sys.gettrace() is None
    w._handle("debug", {"project": "dbg", "cmd": "set", "breakpoints": {}})
    w._handle("debug", {"project": "dbg", "cmd": "clear"})
    w._handle("trigger_event", {"event": "player_join", "context": ctx})
    assert w._handle("debug", {"project": "dbg", "cmd": "hits"})["hits"] == []


def test_ui_refresh_keeps_the_window_state():
    sent = []
    old = w._host_call
    w._host_call = lambda method, params=None, timeout=10.0: sent.append((method, params)) or {"ok": True}
    try:
        run_main("uir", NL.join([
            "from craftpy import *",
            "@window('ticker', title='Ticker')",
            "class Ticker(Window):",
            "    n = 0",
            "    def build(self, ctx):",
            "        return Label('n=%d' % self.n)",
            ""]))
        w._use_owner("uir")
        w.ui.open("Alex", "ticker")
        w._UI_OPEN[("Alex", "ticker")]["inst"].n = 5
        assert w.ui.refresh("Alex", "ticker") is True
        assert "n=5" in w.json.dumps(sent[-1][1]["window"]), sent[-1]
        w.ui.close("Alex", "ticker")
        assert w.ui.refresh("Alex", "ticker") is False
    finally:
        w._host_call = old


REFUSED_CODE = {
    "frame attribute": "def f():\n    pass\nprint(f.__globals__)\n",
    "subclass walk": "print(().__class__.__bases__[0].__subclasses__())\n",
    "code object": "def f():\n    pass\nprint(f.__code__)\n",
    "frame of a generator": "g = (i for i in range(1))\nprint(g.gi_frame.f_globals)\n",
    "builtins name": "print(__builtins__)\n",
    "getattr with a built name": "def f():\n    pass\nprint(getattr(f, '__glo' + 'bals__'))\n",
    "setattr with a built name": "class A:\n    pass\nsetattr(A, '__bases__', ())\n",
    "inspect import": "import inspect\n",
    "gc import": "import gc\n",
    "os import": "import os\n",
    "open": "open('x.txt', 'w')\n",
}


def test_sandbox_refuses_interpreter_internals_and_keeps_the_api():
    for label, code in REFUSED_CODE.items():
        result = w._handle("run_main", {"project": "sbx", "code": code, "file": "code/main.py"})
        assert result.get("error"), (label, result)
    # the editor shows the refusal while typing
    problems = w._handle("validate", {"file": "code/main.py", "code": REFUSED_CODE["frame attribute"]})["problems"]
    assert any("not available in CraftPy" in p["message"] and p["severity"] == "error" for p in problems), problems
    # what ordinary code does keeps working: classes, dunder methods, super, properties, dataclass-like patterns
    ok = NL.join([
        "from craftpy import *",
        "class Base:",
        "    def __init__(self, n):",
        "        self.n = n",
        "    def __repr__(self):",
        "        return 'Base(%d)' % self.n",
        "    def __eq__(self, other):",
        "        return self.n == other.n",
        "    def __hash__(self):",
        "        return hash(self.n)",
        "class Child(Base):",
        "    def __init__(self, n):",
        "        super().__init__(n + 1)",
        "    @property",
        "    def double(self):",
        "        return self.n * 2",
        "c = Child(1)",
        "assert repr(c) == 'Base(2)' and c.double == 4 and c == Child(1) and c.__class__.__name__ == 'Child'",
        "assert getattr(c, 'n') == 2 and hasattr(c, 'double') and not hasattr(c, 'nothing')",
        "setattr(c, 'n', 5)",
        "def named(): pass",
        "assert named.__name__ == 'named'",
        ""])
    result = w._handle("run_main", {"project": "sbx", "code": ok, "file": "code/main.py"})
    assert not result.get("error"), result


AUDIT_PROBE = NL.join([
    "import importlib.util, os, sys, tempfile",
    "spec = importlib.util.spec_from_file_location('w', %r)",
    "w = importlib.util.module_from_spec(spec); spec.loader.exec_module(w)",
    "inside = os.path.join(%r, 'inside.txt')",
    "open(inside, 'w').write('data')",
    "w._install_audit_hook([sys.prefix, sys.base_prefix, %r, os.path.dirname(inside)])",
    "def attempt(label, fn):",
    "    try:",
    "        fn(); print('ALLOWED', label)",
    "    except PermissionError:",
    "        print('refused', label)",
    "attempt('read inside', lambda: open(inside).read())",
    "attempt('read outside', lambda: open(os.path.abspath(os.sep)).read() if False else open(%r).read())",
    "attempt('write inside', lambda: open(inside, 'a').write('x'))",
    "attempt('create file', lambda: open(os.path.join(os.path.dirname(inside), 'new.txt'), 'w'))",
    "attempt('delete', lambda: os.remove(inside))",
    "attempt('start process', lambda: __import__('subprocess').run([sys.executable, '-c', '1']))",
    "attempt('network', lambda: __import__('socket').socket().connect(('127.0.0.1', 9)))",
    "attempt('list outside', lambda: os.listdir(os.path.dirname(os.path.dirname(inside))))",
    "attempt('stdlib import', lambda: __import__('colorsys'))",
    "print('worker still reads its folder', len(os.listdir(os.path.dirname(inside))) > 0)",
])


def test_audit_hook_refuses_files_processes_and_network():
    import subprocess
    import tempfile
    with tempfile.TemporaryDirectory() as folder:
        outside = os.path.join(tempfile.gettempdir(), "craftpy_audit_outside.txt")
        with open(outside, "w") as handle:
            handle.write("secret")
        try:
            code = AUDIT_PROBE % (w.__file__, folder, folder, outside)
            run = subprocess.run([sys.executable, "-I", "-c", code], capture_output=True, text=True, timeout=60)
        finally:
            os.remove(outside)
    lines = run.stdout.split(NL)
    assert run.returncode == 0, run.stderr[-800:]
    for label in ["read outside", "write inside", "create file", "delete", "start process", "network", "list outside"]:
        assert "refused " + label in lines, (label, run.stdout, run.stderr[-400:])
    assert "ALLOWED read inside" in lines and "ALLOWED stdlib import" in lines, run.stdout


MEMORY_PROBE = NL.join([
    "import importlib.util",
    "spec = importlib.util.spec_from_file_location('w', %r)",
    "w = importlib.util.module_from_spec(spec); spec.loader.exec_module(w)",
    "w._limit_memory(300)",
    "try:",
    "    block = bytearray(900 * 1024 * 1024)",
    "    print('not limited')",
    "except MemoryError:",
    "    print('limited')",
])


def test_memory_limit_stops_runaway_allocation():
    import subprocess
    run = subprocess.run([sys.executable, "-I", "-c", MEMORY_PROBE % w.__file__], capture_output=True, text=True, timeout=60)
    assert run.stdout.strip() == "limited", (run.stdout, run.stderr[-500:])


if __name__ == "__main__":
    failures = 0
    for name in sorted(n for n in dir() if n.startswith("test_")):
        try:
            w.reset()
            globals()[name]()
            print("PASS", name)
        except Exception as exc:  # noqa: BLE001
            failures += 1
            import traceback
            traceback.print_exc()
            print("FAIL", name, exc)
    print("%d failure(s)" % failures)
    sys.exit(1 if failures else 0)
