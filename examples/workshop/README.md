# Workshop

A small machine workshop that works with no other mod: a motor, a shaft, a mill, a battery and a water tank.

- Module id: `workshop`
- Works on: client and server
- CraftPy Python API: 2.9
- Asks for: no permissions

## What it adds

- **Workshop Motor**: makes rotation (speed 32, capacity 32) along the X axis.
- **Workshop Shaft**: carries rotation along X.
- **Workshop Mill**: uses rotation (impact 8) to turn cobblestone into gravel in 100 ticks. It needs a speed of at least 16.
- **Workshop Battery**: stores up to 100000 FE, at most 1000 per tick in or out.
- **Workshop Water Tank**: holds up to 16000 mB of water.

All five are crafted the same way: eight of one material around an iron ingot. The materials are copper ingot (motor),
iron ingot (shaft), stone (mill), redstone (battery) and glass (tank).

## How to use it

1. Place the motor, then shafts in a row along X, then the mill. The mill needs space for its output.
2. Put cobblestone into the mill (by hand or with a hopper or pipe) and take the gravel out.
3. The battery and the tank accept energy and fluid from other mods' machines and buckets.
4. The Machinery Wrench changes the axis of a machine.
5. `/workshop:inspect <x> <y> <z>` prints the resources and the machine state of a block.

The motor makes rotation, not electricity. Nothing here fills the battery; that would need a generator you write.

## How it is made

`code/main.py` declares the blocks with `Kinetic(...)`, `EnergyStorage(...)` and `FluidTank(...)`, the five crafting
recipes and the `craftpy:kinetic_processing` recipe. With Create installed, the same declarations run on Create's
rotation instead (see the Create Bridge project).
