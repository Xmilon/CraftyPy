from craftpy import *
import conversion

# Power Cell: a generator that burns a custom liquid into energy.
#
#   - Volt Sap is a real liquid (it flows, you swim in it, buckets and tanks of any mod hold it). Its density, viscosity,
#     temperature and light are set below, and standing in it gives you effects.
#   - The Sap Generator is a block with its own mesh (assets/models/block/generator.json), its own texture and a hitbox
#     that matches the mesh. Right click it with a bucket of Volt Sap to fill it. Right click it with an empty hand to
#     open its window (ui/sap_generator.json, drawn in the UI maker with the Minecraft look): a bucket slot, the liquid
#     gauge and, on the right, the energy bar.
#   - It stores energy (FE) as a normal NeoForge energy storage that only gives energy out, so any cable that pulls
#     energy from a neighbour takes it: Mekanism's Universal Cables carry it to Mekanism's batteries. Nothing here
#     mentions Mekanism; it works because the generator speaks the game's standard energy and liquid interfaces.
#   - Turning liquid into energy is the Python below (the rules are in conversion.py).

volt_sap = Fluid(
    "volt_sap", name="Volt Sap", color="#5CE03C",
    density=1400,        # heavier than water (1000): things sink in it
    viscosity=2600,      # thicker than water: it flows and moves you slowly
    temperature=340,     # kelvin, a bit warm
    light=7,
    effects=[("glowing", 15), ("speed", 8, 2), ("poison", 3)],   # (effect, seconds, level) while you stand in it
    still="volt_sap_still.png", flowing="volt_sap_flow.png", bucket_texture="volt_sap_bucket.png",
)

# The hitbox follows the mesh, box by box (sixteenths of a block): base, tank, chimney and four cable connectors.
GENERATOR_SHAPE = [
    (0, 0, 0, 16, 3, 16),
    (2, 3, 2, 14, 13, 14),
    (6, 13, 6, 10, 16, 10),
    (0, 5, 5, 2, 9, 11), (14, 5, 5, 16, 9, 11),
    (5, 5, 0, 11, 9, 2), (5, 5, 14, 11, 9, 16),
]

generator = Block(
    "sap_generator", name="Sap Generator",
    model="generator", texture="generator.png", shape=GENERATOR_SHAPE,
    hardness=3, tool="pickaxe", needs_tool=True, sound="metal",
).extends(
    FluidTank(capacity=8000, allowed=[volt_sap]),                    # only Volt Sap goes in
    EnergyStorage(capacity=400_000, max_receive=0, max_extract=2000),  # gives energy out, takes none in
    Screen(title="Sap Generator", layout="sap_generator"),            # the Minecraft-style window drawn in ui/sap_generator.json
)


@on.machine_state(block=generator)
def burn(ctx):
    """Runs when the generator's contents change (a bucket, a cable taking energy): burn what fits."""
    machine = ctx.machine
    mb, fe = conversion.plan(machine.fluid_amount, machine.energy, machine.energy_capacity)
    if mb:
        world.machine_update(ctx.x, ctx.y, ctx.z, energy=fe, fluid=-mb, dimension=ctx.dimension)


@command("sapbucket")
def sapbucket(ctx):
    """/powercell:sapbucket - gives you a bucket of Volt Sap and a Sap Generator to try it with."""
    ctx.player.give(volt_sap.bucket, 3)
    ctx.player.give(generator)
    ctx.player.actionbar("Place the generator, right click it with a bucket, then right click it empty-handed")
