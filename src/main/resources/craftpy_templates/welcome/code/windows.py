# Layouts live in ui/: Design edits their placement; Python supplies each viewer's saved state.
from craftpy import *
from items import gem


def _key(ctx, name):
    return name + ":" + (ctx.player.uuid or ctx.player.name)


def _count(ctx):
    return max(0, min(10000, int(storage.get(_key(ctx, "counter"), 0))))


@ui.data("counter")
def counter_data(ctx):
    count = _count(ctx)
    return {"caption": f"Clicked {count} times", "count": min(count, 10), "can_claim": count >= 10}


@action("counter_more")
def more(ctx):
    storage.set(_key(ctx, "counter"), min(10000, _count(ctx) + 1))


@action("counter_less")
def less(ctx):
    storage.set(_key(ctx, "counter"), max(0, _count(ctx) - 1))


@action("counter_reward")
def reward(ctx):
    # Bindings are informational: the server reads its saved count before giving anything.
    if _count(ctx) < 10:
        ctx.player.actionbar("Click to 10 first.")
        return
    storage.set(_key(ctx, "counter"), 0)
    ctx.player.give(gem, 3)
    ctx.player.say("Reward claimed!")


@ui.data("settings")
def settings_data(ctx):
    saved = storage.get(_key(ctx, "settings"), {})
    return {"nickname": saved.get("nickname", ctx.player.name), "hints": saved.get("hints", True),
            "volume": saved.get("volume", 60)}

@action("apply_settings")
def apply_settings(ctx):
    name = str(ctx.values.get("nickname") or ctx.player.name).strip()[:48]
    try:
        volume = max(0, min(100, int(ctx.values.get("volume", 60))))
    except (ValueError, TypeError):
        volume = 60
    saved = dict(nickname=name, hints=ctx.values.get("hints") is True, volume=volume)
    storage.set(_key(ctx, "settings"), saved)
    ctx.player.say(f"Saved: nickname={name}, hints={saved['hints']}, volume={volume}")
    ctx.close()
