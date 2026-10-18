from craftpy import *
from materials import arcane_alloy, arcane_plate

PARTS = {
    "create": "mixer and press (Arcane Alloy, Arcane Plate)",
    "mekanism": "osmium and the enrichment chamber (Arcane Dust)",
    "irons_spellbooks": "arcane essence, mithril, spell power",
    "curios": "the ring slot",
}


@command("mods")
def mods(ctx):
    """/mod_mashup:mods - which of the combined mods are installed, and what each one brings."""
    installed = set(server.mods())  # mod ids; needs no permission (the Java bridge would be full trust)
    for mod, part in PARTS.items():
        mark = "?" if installed is None else ("yes" if mod in installed else "missing")
        ctx.player.say(f"{mod}: {mark} - {part}")


@command("starter")
def starter(ctx):
    """/mod_mashup:starter - a few materials to try the chain without the machines."""
    ctx.player.give(arcane_alloy, 4)
    ctx.player.give(arcane_plate, 5)
    ctx.player.give("irons_spellbooks:mithril_ingot", 2)
    ctx.player.say("Craft the Arcane Ring and the Arcane Blade on a crafting table.")
