# Ruby Rush - a small mining game that shows most of the CraftPy API.
#
# Content: ruby ore (drops rubies), rubies, a ruby block, a ruby sword and a ruby apple.
# Gameplay: every 10 ores mined server-wide starts a "Ruby Surge" with a reward.
# Try it:  /craftpy give @s ruby_rush:ruby_ore block 16
import random

from craftpy import *

# ---------------------------------------------------------------- content ---

ruby = Item("ruby", texture="ruby.png")

ruby_ore = Block("ruby_ore", texture="ruby_ore.png", hardness=3, resistance=3,
                 tool="pickaxe", needs_tool=True, sound="stone",
                 drops=(ruby, 2))                      # mining it drops 2 rubies

ruby_block = Block("ruby_block", texture="ruby_block.png", hardness=5, tool="pickaxe",
                   sound="metal", light=4)

ruby_sword = Item("ruby_sword", texture="ruby_sword.png", stack=1,
                  effect=("strength", 2, 8))           # right click: Strength II for 8 s

ruby_apple = Item("ruby_apple", texture="ruby_apple.png",
                  food=Food(hunger=6, saturation=1.2, always_eat=True))

# ---------------------------------------------------------------- recipes ---

shaped(ruby_block, "RRR", "RRR", "RRR", R=ruby)
shapeless(ruby, ruby_block, count=9)
shaped(ruby_sword, "R", "R", "S", R=ruby, S="stick")
shaped(ruby_apple, " R ", "RAR", " R ", R=ruby, A="apple")
smelt(ruby_ore, ruby, xp=1.0)

tag("ruby_gems", [ruby, "diamond"])                    # item tag ruby_rush:ruby_gems

# ----------------------------------------------------------------- events ---

SURGE_EVERY = 10


@on.player_join
def welcome(event):
    event.player.title("Ruby Rush", subtitle="Mine ruby ore to start a surge")


@on.block_break(block=ruby_ore)
def mined_ruby(event):
    mined = storage.get("ores_mined", 0) + 1
    storage["ores_mined"] = mined
    world.play_sound("minecraft:block.amethyst_cluster.break", event.pos, pitch=1.4)
    event.player.actionbar(f"Ruby ores mined: {mined}")
    if mined % SURGE_EVERY == 0:
        start_surge(event.player)


def start_surge(miner):
    say(f"Ruby Surge! {miner.name} mined ore number {storage['ores_mined']}.")
    miner.give(ruby_apple)
    for p in players.online():
        p.effect("haste", seconds=30, level=2)
    # Fireworks for 5 seconds, then calm down again.
    show = run_every(10, lambda tick: world.particle("minecraft:firework", miner.pos.up(2), count=20))
    after(5, show.cancel)


@on.item_use(item=ruby_sword)
def sword_used(event):
    event.player.play_sound("minecraft:entity.blaze.shoot", pitch=1.5)


@on.entity_death
def ruby_luck(event):
    # A zombie killed with the ruby sword sometimes drops a ruby.
    if event.killer and event.entity.type == "minecraft:zombie":
        if event.killer.held_item.id == ruby_sword and random.random() < 0.25:
            world.drop(ruby, event.entity.pos)


# --------------------------------------------------------------- commands ---

@command("surge")
def surge_command(ctx, target: Player):
    """/ruby_rush:surge <player> - start a surge right away."""
    start_surge(target)


def main(ctx):
    ctx.log("Ruby Rush loaded. Try /craftpy give @s ruby_rush:ruby_ore block 16")
