from craftpy import *

import rules
from display import PACK_DISPLAY

# Backpack: things that carry their contents with them.
#
#   Traveler's Satchel    27 slots, a tank, Sort and Eat buttons. A leather 3D model.
#   Adventurer's Pack     54 slots, two tanks, 40 000 FE, Distill / Sort / Eat. A 3D model with a bedroll and a glowing vial.
#   Enderweave Pack       81 slots, four tanks, 200 000 FE. A GeckoLib model: a floating eye, runes that glow in the dark.
#
#   Right click with one: its window opens, kept inside the item. Shift + right click a block sets it down as itself (a right
#   click opens it there, with the same contents; hit it, or shift + right click with an empty hand, takes it back with
#   everything in it). The window's buttons: Distill turns Starbrine into energy, Sort merges and orders the slots, Eat feeds
#   you from the food inside. Slots next to the tanks and the energy bar fill and empty them with buckets and batteries of any mod.
#   Starbrine is a glowing liquid of the night sky: Night Vision and Slow Falling while you swim in it.
#   /backpack:open     your own pocket backpack (9 slots that stay with you)
#   shift + right click a villager     its pockets (6 slots that stay with that villager)
#
# ui.open_screen opens a Minecraft-style window drawn in the UI maker. Its slots, energy and liquids are kept in the item
# (hand=), in a placed pack (entity= of the pack on the floor: the same item, so the same contents) or in an entity's data.
# Sounds (assets/sounds, named in assets/sounds.json) are made for the module.


def hear(player, name, volume=1.0, pitch=1.0):
    player.play_sound("backpack:" + name, volume, pitch)


# ---- Starbrine --------------------------------------------------------------------------------------------------------

starbrine = Fluid("starbrine", name="Starbrine", color="#7C5CFF",
                  density=1100, viscosity=1400, temperature=260,   # a little heavier and thicker than water, and cold
                  light=12,                                        # it glows like a lantern
                  effects=[("night_vision", 15), ("slow_falling", 3)],
                  still="starbrine_still.png", flowing="starbrine_flow.png", bucket_texture="starbrine_bucket.png",
                  tags=["c:starbrine"], bucket_glint=True)      # the bucket shimmers like an enchanted item
shapeless(starbrine.bucket, "bucket", "prismarine_crystals", "amethyst_shard", "glowstone_dust")

# ---- the three packs ----------------------------------------------------------------------------------------------------

adventure_pack = Item("adventure_pack", name="Adventurer's Pack", model="backpack", texture="adventure_pack.png", stack=1).extends(
    Placeable(width=0.7, height=0.8),
    Tooltip("54 slots, two tanks, 40 000 FE", "Right click: open. Shift + right click a block: set it down."))
shaped(adventure_pack, "SLS", "LCL", "LWL", S="string", L="leather", C="chest", W="#minecraft:wool")

satchel = Item("satchel", name="Traveler's Satchel", model="satchel", texture="satchel.png", stack=1).extends(
    Placeable(width=0.6, height=0.6),
    Tooltip("27 slots and a tank", "Right click: open. Shift + right click a block: set it down."))
shaped(satchel, "SLS", "L L", "LLL", S="string", L="leather")

enderweave = Item("enderweave", name="Enderweave Pack", texture="enderweave.png", geo="enderweave", display=PACK_DISPLAY, stack=1).extends(
    Placeable(width=0.7, height=0.9),
    Tooltip("81 slots, four tanks, 200 000 FE", "Its eye floats; its runes glow in the dark."))
shaped(enderweave, "EOE", "OAO", "ECE", E="ender_eye", O="obsidian", A=adventure_pack, C="ender_chest")

PACKS = {
    adventure_pack: ("adventure_pack", dict(inventory=Inventory(storage=54, fluid_in=1, fluid_out=1, energy_in=1, energy_out=1),
                                            energy=40_000, tanks=2, tank_capacity=8000)),
    satchel: ("satchel", dict(inventory=Inventory(storage=27, fluid_in=1, fluid_out=1), tanks=1, tank_capacity=4000)),
    enderweave: ("enderweave", dict(inventory=Inventory(storage=81, fluid_in=1, fluid_out=1, energy_in=1, energy_out=1),
                                    energy=200_000, tanks=4, tank_capacity=16_000)),
}


def wire(item, layout, spec):
    """Right click with it, and right click it where it stands, open the same window."""

    @on.item_use(item=item)
    def held(ctx):
        hear(ctx.player, "pack_open", 0.8)
        ui.open_screen(ctx.player, layout, hand="off" if ctx.hand == "OFF_HAND" else "main", values={"status": " "}, **spec)

    @on.entity_interact(entity=item)
    def placed(ctx):
        if ctx.sneaking:        # shift + right click with an empty hand picks it up
            return
        hear(ctx.player, "pack_open", 0.8)
        ui.open_screen(ctx.player, layout, entity=ctx.entity, values={"status": " "}, **spec)
        ctx.cancel()


for pack, (layout, spec) in PACKS.items():
    wire(pack, layout, spec)

# ---- the buttons --------------------------------------------------------------------------------------------------------


def say(player, text):
    ui.screen_set(player, {"status": text})


@on.screen_button(button="distill")
def distill(ctx):
    """Distill: 100 mB of Starbrine from any tank becomes 4000 FE, if the store has room."""
    window = ui.screen_update(ctx.player)
    room = window["energy_capacity"] - window["energy"]
    for index, tank in enumerate(window["tanks"]):
        if tank["fluid"] == str(starbrine) and tank["amount"] >= rules.DISTILL_MB:
            if not rules.fits(room):
                say(ctx.player, "Store full")
                return
            ui.screen_update(ctx.player, energy=rules.DISTILL_FE, fluid=-rules.DISTILL_MB, tank=index)
            hear(ctx.player, "pack_distill", 0.9)
            say(ctx.player, "+%d FE" % rules.DISTILL_FE)
            return
    say(ctx.player, "No Starbrine" if window["energy_capacity"] else "No energy store")


@on.screen_button(button="sort")
def sort(ctx):
    ui.screen_sort(ctx.player, "storage")
    hear(ctx.player, "pack_sort", 0.8)
    say(ctx.player, "Sorted")


@on.screen_button(button="eat")
def eat(ctx):
    """Eat: the food inside that fits your hunger best is eaten for you."""
    if ctx.player.food >= 20:
        say(ctx.player, "Not hungry")
        return
    slots = ui.screen_slots(ctx.player, "storage")
    choice = rules.best_food(slots, 20 - ctx.player.food)
    if choice is None:
        say(ctx.player, "No food")
        return
    ui.screen_take(ctx.player, "storage", choice, 1)
    entry = slots[choice]
    ctx.player.feed(entry["food"], entry.get("saturation", 0.6))
    ctx.player.play_sound("minecraft:entity.generic.eat", 0.8, 1.0)
    say(ctx.player, "Ate " + entry["name"][:14])


# ---- the old pocket backpack and the villagers' pockets ------------------------------------------------------------


@command("open", level=0)
def open_backpack(ctx):
    """/backpack:open - your own pocket backpack"""
    opened = storage.get("opened:" + ctx.player.uuid, 0) + 1
    storage.set("opened:" + ctx.player.uuid, opened)
    hear(ctx.player, "pack_open", 0.8)
    ui.open_screen(ctx.player, "backpack", title=ctx.player.name + "'s backpack",
                   inventory=Inventory(storage=9),        # the slots; entity= is left out, so they belong to the player
                   values={"opened": opened})             # the label and the bar of the window read this


@on.entity_interact
def pockets(ctx):
    """Shift + right click a villager opens its pockets; a normal click still trades."""
    if ctx.sneaking and ctx.entity.native_type == "minecraft:villager":
        ui.open_screen(ctx.player, "pockets", title=ctx.entity.name + "'s pockets", entity=ctx.entity,
                       inventory=Inventory(pockets=6))
        ctx.cancel()


@command("kit", level=2)
def kit(ctx, target: Player):
    """/backpack:kit <player> - operators: every pack, a bucket of Starbrine, food to try Eat with."""
    for pack in PACKS:
        target.give(pack)
    target.give(starbrine.bucket)
    target.give("cooked_beef", 8)
    target.give("lava_bucket")
