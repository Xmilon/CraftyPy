# Backpack

Things that carry their contents with them: an adventurer's pack you can set down, a glowing liquid of the night sky, a
personal pocket backpack and the pockets of villagers.

- Module id: `backpack`
- Works on: client and server (the model, textures and windows need the client)
- CraftPy Python API: 2.12
- Needs: nothing but CraftPy; asks for no permissions

## How to use it

1. Craft an **Adventurer's Pack**: string, leather, a chest in the middle and wool below. Right click with it: 54 slots,
   two liquid tanks of 8000 mB and an energy store of 40 000 FE, all kept inside the item.
2. **Shift + right click a block** to set it down. Right click it there for the same contents; hit it, or shift + right
   click it with an empty hand, to pick it up with everything inside.
3. Craft a bucket of **Starbrine** from a bucket, prismarine crystals, an amethyst shard and glowstone dust. It glows, and
   swimming in it gives Night Vision and Slow Falling. Pour it into the pack (the slot under the left tank); **Distill**
   turns 100 mB of it into 4000 FE. Put a battery or any mod's energy tool in the lower right slot to charge it, a charged
   one in the upper right slot to drain it into the pack.
4. `/backpack:open` opens your own 9-slot pocket backpack; shift + right click a villager to open its pockets.

## How it is made

| Part | Where | What it shows |
| --- | --- | --- |
| The pack's look | `assets/models/item/backpack.json`, `assets/textures/backpack.png`, `Item(..., model="backpack")` | a Blockbench "Java Block/Item" model with a bedroll, buckles and a glowing vial (NeoForge `block_light` on that part) |
| Setting it down | `Placeable(width=0.7, height=0.8)` | the item becomes an entity that is its stack: data, name and all |
| Its window | `ui/adventure_pack.json` (248 x 222) | a 9 x 6 grid, two gauges bound to `fluid:0` and `fluid:1`, the energy bar, `fluid_in` / `fluid_out` / `energy_in` / `energy_out` slots, a button |
| Opening it | `@on.item_use(item=pack)` with `ui.open_screen(..., hand=)`, `@on.entity_interact(entity=pack)` with `entity=ctx.entity` | the same contents in the hand and on the floor |
| Distill | `@on.screen_button(button="distill", window="adventure_pack")`, `ui.screen_update` | a button that turns liquid into energy |
| Starbrine | `Fluid("starbrine", ..., light=12, effects=[...], still=, flowing=, bucket_texture=, tags=["c:starbrine"])` | a liquid of its own: textures, glow, effects, a tag other mods read |
