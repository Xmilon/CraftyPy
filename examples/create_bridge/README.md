# Create Bridge

Connects CraftPy machines to Create's own shafts, gears and stress. It only does something when Create is installed.

- Module id: `create_bridge`
- Works on: client and server
- CraftPy Python API: 2.9
- Needs: the Create mod, with the CraftPy Create adapter active (`rotation.create` in `server.features()`)
- Asks for: no permissions

## What it adds

- **Bridge Motor**: a generator, axis X, speed 64, capacity 32. Craft it with 8 copper ingots around a Create shaft.
- **Bridge Mill**: a processor, axis X, impact 8. Craft it with 8 iron ingots around a Create shaft.
- A processing recipe: gravel to sand in 100 ticks at rotation speed 32 or more.
- A Create pressing recipe: copper ingot to copper sheet.
- Two native aliases, `create_shaft` and `create_press`, which reuse Create's own shaft and mechanical press.

## How to use it

Place the motor and the mill on the X axis and join them with Create shafts or gears. Create's network supplies the
speed and the stress, so Create's wrench and goggles work on them. With Create absent, nothing is registered and the log
says the adapter is unavailable.

## How it is made

`code/main.py` checks `rotation.create` once and declares everything inside that check. The machines use the same
`Kinetic(...)` declarations as the Workshop project; only the active rotation backend is different.
