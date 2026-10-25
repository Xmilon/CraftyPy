# Armory

Weapons with a character of their own: greatswords, daggers, spears and bows with 3D models, and five legends with GeckoLib
animations, animated icons and abilities of their own, all with sounds made for the module.

- Module id: `armory`
- Works on: client and server (the server runs the abilities, every player draws the models and hears the sounds)
- CraftPy Python API: 2.12 (`Item(model=, geo=, display=)`, `Bow`, `Throwable`, `Entity.effect`)
- Needs: nothing but CraftPy; asks for no permissions. GeckoLib makes the legends 3D and animated; without it they show their
  animated flat icons
- Try it: `/armory:kit <player>` (operators) gives every weapon and arrows

## The weapons

| Weapon | What it is | What it does |
| --- | --- | --- |
| Claymore | iron greatsword, 3D, held at 1.35 x | 9 damage, slow, knocks back |
| Obsidian Zweihander | huge obsidian blade, 3D, held at 1.6 x | 12 damage, slows you and what it hits |
| Frostbrand | ice greatsword, 3D | 8 damage, Slowness II on the target |
| Bone Stiletto | small bone dagger | very fast, leaves a wither wound |
| Venom Kris | wavy venom-green dagger | Poison II |
| Shadowfang | violet dagger | throw it: hold right click, let go; pick it up again |
| Hunter's Spear | iron spear | one block of reach; thrown, it goes through a mob |
| Glaive | spear with an axe blade | a block and a half of reach |
| **Damocles' Sword** | GeckoLib: hangs from a thread, sways, glows red | 14 damage; now and then the thread snaps: triple blow, you pay; costs 2 hearts while held |
| **Scipio's Spear** | GeckoLib: golden head, a plume in the wind | marks what it hits; three quick hits earn a triumph (horn, Speed, Resistance); thrown it pierces three mobs |
| **Gungnir** | GeckoLib: the head turns and crackles | thrown it flies straight through five mobs; blows slow and sometimes add a bolt |
| **Excalibur** | GeckoLib: a pulsing gem and a bright blade | twice the damage against the undead; every hit heals a heart |
| **Tyrfing** | GeckoLib: a trembling crimson blade with glowing veins | a third of every blow comes back as health |
| Warbow | heavy bow | slow draw, long range, 9 damage |
| Twinfang | bone bow | two arrows at once |
| **Artemis' Bow** | silver bow with an animated glow | needs no arrows |

## How it is made

| Part | Where | What it shows |
| --- | --- | --- |
| 3D weapons | `assets/models/item/*.json`, `Item(model="claymore")` | boxes in Blockbench's "Java Block/Item" format; every box turned 45 degrees like a tool sprite so the hand, inventory and ground transforms fit; the colours come from a small swatch texture (`*_palette.png`) |
| Inventory icons | `texture="claymore.png"` next to `model=` / `geo=`, `assets/textures/*.png` (+ `.png.mcmeta`) | 16 x 16 pictures in Minecraft's style for the inventory and hotbar, many of them animated; hands, frames and the ground show the 3D model |
| Held bigger | the `display` of those models | scaled copies of the tool's hand transforms |
| Legends | `Item("damocles", texture="damocles.png", geo="damocles", display=GEO_DISPLAY)`, `assets/models/damocles.geo.json`, `assets/animations/damocles.animation.json` | a GeckoLib model with bones, an `idle` clip that loops, `use` and `attack` clips that play on a right click and a hit, an animated texture and a `_glowmask` that shines in the dark |
| Animated icons | `assets/textures/damocles.png` + `.png.mcmeta` | eight frames: a light sweeping along the blade, a pulsing gem; shown in the inventory without GeckoLib |
| Bows | `Bow(draw_seconds=, power=, damage=, arrows=, infinite=)`, `assets/textures/warbow_pulling_0..2.png` | drawn pictures swap in while you draw, like the vanilla bow |
| Thrown weapons | `Throwable(speed=, damage=, pierce=, gravity=)` | the item flies as itself and is picked up again |
| Abilities | `@extension class Dread(Extension)` with `on_hit` / `on_hold_tick` in `code/main.py`; rules in `code/rules.py` with tests | `event.damage` changes the blow, `event.entity.effect(...)` puts an effect on the target |
| Sounds | `assets/sounds/*.ogg`, `assets/sounds.json`, `player.play_sound("armory:snap")` | sounds are named in sounds.json and played by id |

The textures, models and sounds were drawn and synthesised by a script (not shipped), so every weapon is its own.
