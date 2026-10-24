from craftpy import *

# Backpack: Minecraft-style windows that belong to something other than a block.
#
#   /backpack:open            your own backpack: 9 slots that stay with you (ui/backpack.json)
#   shift + right click a villager   its pockets: 6 slots that stay with that villager (ui/pockets.json)
#
# ui.open_screen opens a Minecraft-style window drawn in the UI maker for an entity or for the player. The item slots are
# kept in that entity's data, under this project's name and the window's name, so they are saved with the world and no other
# project can reach them. The windows themselves are made of the same pieces as a machine's window.


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
