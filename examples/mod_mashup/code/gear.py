from craftpy import *
from materials import arcane_dust, arcane_plate

# A ring: the Curios tag lets it into the ring slot, and slot="curios:ring" makes its attributes count there.
arcane_ring = Item("arcane_ring", name="Arcane Ring", texture="arcane_ring.png", stack=1, glow=True,
                   tags=["curios:ring"]).extends(
    Attributes("irons_spellbooks:spell_power", 0.10, operation="multiply_base", slot="curios:ring"),
    Attributes("irons_spellbooks:max_mana", 50, slot="curios:ring"),
    Tooltip("Wear it in a Curios ring slot", color="aqua"),
)

# A sword that is also a fire focus for Iron's spells, repaired with arcane dust.
arcane_blade = Item("arcane_blade", name="Arcane Blade", texture="arcane_blade.png").extends(
    Sword(tier="diamond", damage=8),
    Durability(1200, repair_with=arcane_dust),
    Attributes("irons_spellbooks:fire_spell_power", 0.15, operation="multiply_base", slot="mainhand"),
)

shaped(arcane_ring, " P ", "PMP", " P ", P=arcane_plate, M="irons_spellbooks:mithril_ingot")
shaped(arcane_blade, " P ", " P ", " M ", P=arcane_plate, M="irons_spellbooks:mithril_ingot")
