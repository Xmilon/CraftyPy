from craftpy import *

# Backpack: things that carry their contents with them.
#
#   The Adventurer's Pack    a leather pack with a bedroll and a glowing vial of Starbrine on its side (its own 3D model,
#                            assets/models/item/backpack.json). Right click with it: 54 slots, two liquid tanks and an
#                            energy store, all kept inside the item. Shift + right click a block: it is set down as itself
#                            and opens there with a right click; hit it (or shift + right click with an empty hand) and it
#                            comes back with everything in it.
#   Starbrine                a liquid of the night sky: it glows, and swimming in it lets you see in the dark and fall
#                            slowly. A bucket is crafted from prismarine crystals, an amethyst shard and glowstone dust.
#                            In the pack, the Distill button turns it into energy that charges your tools and batteries.
#   /backpack:open           your own pocket backpack: 9 slots that stay with you (ui/backpack.json)
#   shift + right click a villager   its pockets: 6 slots that stay with that villager (ui/pockets.json)
#
# ui.open_screen opens a Minecraft-style window drawn in the UI maker. Its slots, energy and liquids are kept in the item
# (hand=), in a placed pack (entity= of the pack on the floor: the same item, so the same contents) or in an entity's data.

starbrine = Fluid("starbrine", name="Starbrine", color="#7C5CFF",
                  density=1100, viscosity=1400, temperature=260,   # a little heavier and thicker than water, and cold
                  light=12,                                        # it glows like a lantern
                  effects=[("night_vision", 15), ("slow_falling", 3)],
                  still="starbrine_still.png", flowing="starbrine_flow.png", bucket_texture="starbrine_bucket.png",
                  tags=["c:starbrine"])
shapeless(starbrine.bucket, "bucket", "prismarine_crystals", "amethyst_shard", "glowstone_dust")

pack = Item("adventure_pack", name="Adventurer's Pack", model="backpack", stack=1).extends(
    Placeable(width=0.7, height=0.8),
    Tooltip("54 slots, two tanks, 40 000 FE", "Right click: open; shift + right click a block: set it down"),
)
shaped(pack, "SLS", "LCL", "LWL", S="string", L="leather", C="chest", W="#minecraft:wool")

DISTILL_MB = 100           # Starbrine one press of Distill uses
DISTILL_FE = 4000          # energy it gives: a bucket is 40 000 FE, a full store

PACK = dict(inventory=Inventory(storage=54, fluid_in=1, fluid_out=1, energy_in=1, energy_out=1),
            energy=40_000, tanks=2, tank_capacity=8000)


@on.item_use(item=pack)
def open_held(ctx):
    """Right click with the pack: its window, kept in the item itself."""
    ui.open_screen(ctx.player, "adventure_pack", hand="off" if ctx.hand == "OFF_HAND" else "main", **PACK)


@on.entity_interact(entity=pack)
def open_placed(ctx):
    """Right click a pack on the floor: the very same window (shift + right click with an empty hand picks it up)."""
    if ctx.sneaking:
        return
    ui.open_screen(ctx.player, "adventure_pack", entity=ctx.entity, **PACK)
    ctx.cancel()


@on.screen_button(button="distill", window="adventure_pack")
def distill(ctx):
    """Distill: 100 mB of Starbrine from either tank becomes 4000 FE, if the store has room."""
    window = ui.screen_update(ctx.player)
    room = window["energy_capacity"] - window["energy"]
    for index, tank in enumerate(window["tanks"]):
        if tank["fluid"] == str(starbrine) and tank["amount"] >= DISTILL_MB:
            if room < DISTILL_FE:
                ui.screen_set(ctx.player, {"status": "The store is full"})
                return
            window = ui.screen_update(ctx.player, energy=DISTILL_FE, fluid=-DISTILL_MB, tank=index)
            ui.screen_set(ctx.player, {"status": "+%d FE" % DISTILL_FE})
            return
    ui.screen_set(ctx.player, {"status": "Needs %d mB of Starbrine" % DISTILL_MB})


@command("open", level=0)
def open_backpack(ctx):
    """/backpack:open - your own backpack"""
    opened = storage.get("opened:" + ctx.player.uuid, 0) + 1
    storage.set("opened:" + ctx.player.uuid, opened)
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
