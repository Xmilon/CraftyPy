# 6. Commands. Arguments come from the annotations: int, float, bool, str, Player.
from craftpy import *
from items import gem


@command("gift")
def gift(ctx, target: Player, amount: int):
    """/{{id}}:gift <player> <amount> - give gems."""
    target.give(gem, amount)
    target.say(f"You received {amount} gem(s).")


@command("stats")
def stats(ctx):
    """/{{id}}:stats - how many gem ores were mined."""
    ctx.player.say("Gem ores mined so far:", storage.get("ores_mined", 0))


@command("menu")
def menu(ctx):
    """/{{id}}:menu - open the counter window."""
    ui.open(ctx.player, "counter")


@command("settings")
def settings(ctx):
    """/{{id}}:settings - open the window that was drawn in the UI designer (ui/settings.json)."""
    ui.open(ctx.player, "settings")


@command("selftest")
def selftest(ctx):
    """/{{id}}:selftest - checks a few API calls and tells you what passed."""
    player = ctx.player
    results = []

    def check(name, condition):
        results.append((name, bool(condition)))

    check("Pos math", Pos(1, 2, 3).up(2) == Pos(1, 4, 3))
    storage["selftest"] = 41
    storage["selftest"] += 1
    check("storage", storage["selftest"] == 42)
    check("world read", isinstance(world[int(player.x), int(player.y) - 1, int(player.z)], str))
    check("player data", player.name and player.health > 0)
    check("content ids", gem == "{{id}}:gem")
    check("windows registered", "counter" in ui.windows() and "settings" in ui.windows())
    failed = [name for name, ok in results if not ok]
    player.say(f"Self test: {len(results) - len(failed)}/{len(results)} passed")
    for name in failed:
        player.say("  FAILED:", name)
