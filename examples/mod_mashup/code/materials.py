from craftpy import *

arcane_alloy = Item("arcane_alloy", name="Arcane Alloy", texture="arcane_alloy.png", glow=True).extends(
    Tooltip("Osmium and arcane essence, mixed hot in a Create mixer", color="dark_purple"))
arcane_dust = Item("arcane_dust", name="Arcane Dust", texture="arcane_dust.png").extends(
    Tooltip("Enriched from the alloy in a Mekanism enrichment chamber"))
arcane_plate = Item("arcane_plate", name="Arcane Plate", texture="arcane_plate.png").extends(
    Tooltip("Pressed from the alloy by a Create mechanical press"))
