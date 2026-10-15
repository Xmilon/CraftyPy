import random

from craftpy import *
from items import ruby, ruby_sword
from blocks import ruby_ore
from surge import SURGE_EVERY, start_surge


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


@on.item_use(item=ruby_sword)
def sword_used(event):
    event.player.play_sound("minecraft:entity.blaze.shoot", pitch=1.5)


@on.entity_death
def ruby_luck(event):
    # A zombie killed with the ruby sword sometimes drops a ruby.
    if event.killer and event.entity.type == "minecraft:zombie":
        if event.killer.held_item.id == ruby_sword and random.random() < 0.25:
            world.drop(ruby, event.entity.pos)
