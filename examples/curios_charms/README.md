# Curios Charms

Two craftable rings for the Curios ring slot. It only does something when Curios is installed.

- Module id: `curios_charms`
- Works on: client and server
- CraftPy Python API: 2.9
- Needs: the Curios mod
- Asks for: no permissions

## What it adds

- **Prospector Ring**: +1 luck while worn. Recipe: gold ingots around an emerald.
- **Vitality Ring**: +2 maximum health (one heart) while worn. Recipe: iron ingots around a diamond.
- Both items stack to 1, carry the `curios:ring` tag and show a tooltip that says where to wear them.

## How to use it

Craft a ring and put it in a Curios ring slot. Without Curios the module logs that it is unavailable and adds nothing.

## How it is made

- `code/main.py` declares the items with `Attributes(..., slot='curios:ring')`, the tooltip and the two recipes.
- `data/curios/slots` and `data/curios/entities` add the `ring` slot to players.
