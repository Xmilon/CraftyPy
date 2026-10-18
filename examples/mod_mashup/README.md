# Mod Mashup

One crafting chain that needs four mods at once, plus gear and behaviour that cross between them.

| | |
|---|---|
| Module id | `mod_mashup` |
| Works on | client and server |
| CraftPy Python API | 2.2 |
| Uses content of | Create, Mekanism, Iron's Spells 'n Spellbooks, Curios |

## The chain

1. **Create mixer** on a heated basin: Mekanism osmium ingot + 2 Iron's arcane essence + 100 mB lava = **Arcane Alloy**
2. **Mekanism enrichment chamber**: 1 alloy = 2 **Arcane Dust** (a blast furnace turns dust back into alloy)
3. **Create mechanical press**: alloy = **Arcane Plate**
4. **Crafting table**: plates + Iron's mithril ingot = **Arcane Ring** (Curios ring slot, spell power and mana) and
   **Arcane Blade** (diamond-tier sword with fire spell power, repaired with dust)

Mining Mekanism osmium ore with the blade sometimes pulls out Iron's arcane essence.

## Commands

- `/mod_mashup:mods`: which of the four mods are installed and what each brings
- `/mod_mashup:starter`: materials to try the crafting step without the machines

A recipe of a mod that is not installed is skipped (CraftPy adds a `mod_loaded` condition), so the project still loads.
