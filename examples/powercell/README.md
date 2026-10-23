# Power Cell

A generator with its own mesh, texture and window that burns a custom liquid into energy. Mekanism cables carry the energy
to Mekanism batteries, and no line of this project mentions Mekanism.

- Module id: `powercell`
- Works on: client and server (the server runs the generator, every player draws the mesh and the window)
- CraftPy Python API: 2.11 (adds `Fluid`, `Screen`, `Block(model=, shape=)` and `world.machine_update`)
- Needs: nothing but CraftPy; asks for no permissions (it makes no web requests and reads no files)
- Try it with Mekanism: put a Universal Cable next to the generator and the other end on an Energy Cube, Basic Energy
  Cube or any other energy storage

## How to use it

1. Run `/powercell:sapbucket`. You get three buckets of **Volt Sap** and a **Sap Generator**.
2. Place the generator. Right click it **with a bucket**: the liquid goes into the generator and you keep the empty bucket.
   (You can also place the liquid in the world with the bucket; it flows, and standing in it gives you Glowing, Speed II
   and a little Poison.)
3. Right click the generator **with an empty hand**: its window opens. The slot on the left takes a bucket (the liquid
   is poured into the tank, the empty bucket comes out below it), the tall bar in the middle is the liquid, the bar on the
   right is the energy. Hover a bar for the numbers.
4. Every second the generator burns up to 100 mB of Volt Sap into 80 FE per mB, as long as there is room in its energy
   store. Cables pull the energy out, which makes room for more.

## How it is made

| Part | Where | What it shows |
| --- | --- | --- |
| The liquid | `Fluid("volt_sap", ...)` in `code/main.py` | density, viscosity, temperature, light, effects, own textures and bucket |
| The mesh | `assets/models/block/generator.json` | a Blockbench-style model: seven boxes (`Block(model="generator")`) |
| The texture | `assets/textures/generator.png` | the model's texture |
| The hitbox | `GENERATOR_SHAPE` in `code/main.py` | the same seven boxes, so you click and walk into what you see |
| The storages | `.extends(FluidTank(...), EnergyStorage(...))` | a tank that only takes Volt Sap, and an energy store that only gives out |
| The window | `Screen(title="Sap Generator")` | the Minecraft-style window: slots, liquid gauge, energy bar |
| The burning | `burn()` in `code/main.py` | listens to `machine_state`, asks `conversion.plan` and calls `world.machine_update` |
| The rules | `code/conversion.py`, `code/test_conversion.py` | plain functions with tests (Ctrl+Shift+F10) |

Why Mekanism works with it: the generator is a block with the game's standard energy storage (NeoForge `Capabilities.EnergyStorage`).
Mekanism's cables treat any such block as a neighbour they can take energy from or give energy to, the same way they
do for blocks of any other mod. The same is true for liquids: any pipe or tank that works with the game's fluid handler
can fill or empty the generator.

## Things you can change

- `FE_PER_MB` and `MAX_MB_PER_STEP` in `code/conversion.py` set how much energy a millibucket is worth and how fast it burns.
- `density`, `viscosity` and the effects of `Fluid(...)` change how the liquid feels.
- Swap the model for your own export from Blockbench ("Java Block/Item"), keep the texture names in it as
  `powercell:<file name without .png>`, and change `GENERATOR_SHAPE` to match its boxes (or remove `shape=` while the
  project runs from its folder: the hitbox is then read from the model).
