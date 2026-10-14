from craftpy import *

@block(
    "ruby_ore",
    name="Ruby Ore",
    texture="minecraft:deepslate_gold_ore",
    hardness=4.0,
    resistance=12.0,
    light=2,
    tool="pickaxe",
    needs_tool=True,
    sound="stone",
)
class RubyOre:
    pass


@item(
    "ruby",
    name="Ruby",
    texture="minecraft:diamond",
    max_stack=64,
    glow=True,
)
class Ruby:
    pass


@item(
    "ruby_sword",
    name="Ruby Sword",
    texture="minecraft:diamond_sword",
    max_stack=1,
    glow=True,
    use_effect="strength:1:6",
)
class RubySword:
    pass


recipe_shaped(
    "ruby_sword",
    [" R ", " R ", " S "],
    {"R": {"item": "ruby_rush:ruby"}, "S": {"item": "minecraft:stick"}},
)

recipe_shapeless(
    "ruby_rush:ruby",
    [{"item": "minecraft:diamond"}, {"item": "minecraft:redstone"}, {"item": "minecraft:glowstone_dust"}],
)

recipe_smelting("ruby_rush:ruby", {"item": "ruby_rush:ruby_ore"}, xp=0.5, cook_time=200)

loot_table(
    "ruby_ore",
    [
        loot_entry("ruby_rush:ruby", count=2, weight=6),
        loot_entry("ruby_rush:ruby", count=1, weight=3),
        loot_entry("minecraft:diamond", count=1, weight=1),
        loot_self(),
    ],
    rolls=1,
)

tag("ruby_gems", ["ruby_rush:ruby", "minecraft:diamond"], type="item", replace=False)

lang("block.ruby_rush.ruby_ore", "Ruby Ore")
lang("item.ruby_rush.ruby", "Ruby")
lang("item.ruby_rush.ruby_sword", "Ruby Sword")

sound("ruby_rush.ore_burst", category="blocks", sounds=[{"name": "minecraft:block.amethyst_block.break"}])
particle("ruby_rush.spark", type="minecraft:dust", color=[1.0, 0.2, 0.2], size=0.25)

advancement(
    "ruby_rush/get_ruby",
    display={
        "title": {"text": "Ruby Rush"},
        "description": {"text": "Mine Ruby Ore and claim the gems"},
        "icon": {"item": "ruby_rush:ruby"},
        "frame": "task",
        "show_toast": True,
        "announce_to_chat": True,
    },
    parent="minecraft:story/iron_tools",
    criteria={
        "have_ruby": {
            "trigger": "minecraft:inventory_changed",
            "conditions": {"items": [{"items": ["ruby_rush:ruby"]}]},
        }
    },
    rewards={"experience": 20},
)


THRESHOLD = 5


@on("player_join")
def welcome(ctx):
    try:
        players.send_message(ctx.player.name, "§cRuby Rush§r: Mine ruby_ore to trigger surges!")
    except Exception:
        pass


@on("block_break")
def on_mine(ctx):
    try:
        blk = getattr(ctx, "block", None)
        bid = getattr(blk, "id", "") if blk else ""
        if bid and bid.endswith("ruby_ore"):
            g = getattr(ctx, "global_state", {}) or {}
            count = getattr(g, "ore_mined", 0) or 0
            count += 1
            g.ore_mined = count
            ctx.global_state = g

            x, y, z = getattr(ctx, "x", 0), getattr(ctx, "y", 0), getattr(ctx, "z", 0)
            dim = getattr(ctx, "dimension", "minecraft:overworld")

            try:
                world.play_sound("minecraft:block.amethyst_cluster.place", x, y, z, 0.6, 1.2, dim)
            except Exception:
                pass
            try:
                world.particle("ruby_rush.spark", x, y, z, 4, dim)
            except Exception:
                pass

            if count % THRESHOLD == 0:
                try:
                    server.broadcast(f"§4Ruby Surge!§r {count} ores mined so far.")
                except Exception:
                    pass
                try:
                    world.spawn("minecraft:firework_rocket", x + 0.5, y + 1, z + 0.5, dim)
                except Exception:
                    pass
    except Exception:
        pass


@on("block_interact")
def on_interact(ctx):
    try:
        if hasattr(ctx, "player") and getattr(ctx, "player", None):
            pass
    except Exception:
        pass


@on("item_use")
def on_item_use(ctx):
    try:
        iid = getattr(ctx, "item_id", "")
        if iid and iid.endswith("ruby_sword"):
            pname = getattr(getattr(ctx, "player", None), "name", None)
            if pname:
                players.send_message(pname, "Ruby power unleashed!")
    except Exception:
        pass


def main(ctx):
    ctx.log("ruby_rush: loaded ruby_ore + ruby + ruby_sword, events + loot + recipes + advancement")
    ctx.log("ruby_rush: try /craftpy give <player> ruby_rush:ruby_ore block | ruby item | ruby_sword item")
