# 4. Events. Handlers run right after the game event. Filters (block=, item=, entity=) narrow them down.
import random

from craftpy import *
from items import gem, gem_apple, gem_sword, gem_wand
from blocks import gem_ore, gem_block
from rewards import apple_due


@on.player_join
def greet(event):
    event.player.title("Welcome to CraftPy", subtitle="Open the project in the editor and change me!")
    event.player.say("Try /{{id}}:menu", event.player.name, "- a window made in Python.")


@on.block_break(block=gem_ore)
def mined_gem(event):
    mined = storage.get("ores_mined", 0) + 1            # storage is shared by all handlers and saved with the world
    storage["ores_mined"] = mined
    event.player.actionbar(f"Gem ores mined: {mined}")
    if apple_due(mined):                                # rewards.py, checked by test_rewards.py
        event.player.give(gem_apple)
        say(f"{event.player.name} found a gem apple after {mined} ores!")


@on.block_place(block=gem_block)
def placed_block(event):
    world.particle("minecraft:happy_villager", event.pos.up(1), count=12)


@on.item_use(item=gem_wand)
def wand(event):
    event.player.effect("speed", seconds=10, level=2)
    event.player.play_sound("minecraft:entity.illusioner.cast_spell")
    ui.open(event.player, "counter")                    # right click the wand: open the counter window


@on.chat
def chat(event):
    if event.message.strip().lower() == "!gems":
        event.player.say("You carry", event.player.count(gem), "gem(s).")


@on.entity_death
def luck(event):
    # A zombie killed with the sword sometimes drops a gem.
    if event.killer and event.entity.type == "minecraft:zombie":
        if event.killer.held_item.id == gem_sword and random.random() < 0.25:
            world.drop(gem, event.entity.pos)
