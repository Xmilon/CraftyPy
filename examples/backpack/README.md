# Backpack

Packs that carry their contents with them: the Traveler's Satchel, the Adventurer's Pack and the Enderweave Pack, with
tanks, energy, buttons and custom sounds, set down in the world as 3D items. Also a pocket backpack and the pockets of villagers.

- Module id: `backpack`
- Works on: client and server (the server keeps the contents, every player draws the models and hears the sounds)
- CraftPy Python API: 2.12 (`Placeable`, `Item(model=, geo=)`, `ui.screen_sort`, `ui.screen_slots`, `ui.screen_take`)
- Needs: nothing but CraftPy; asks for no permissions. GeckoLib makes the Enderweave Pack 3D and animated
- Try it: `/backpack:kit <player>` (operators) gives every pack, a bucket of Starbrine, lava and food

## The packs

| Pack | Slots | Tanks | Energy | Model |
| --- | --- | --- | --- | --- |
| Traveler's Satchel | 27 | 1 x 4000 mB | none | leather satchel with a buckle and a rolled map |
| Adventurer's Pack | 54 | 2 x 8000 mB | 40 000 FE | a pack with a bedroll, buckles and a glowing vial |
| Enderweave Pack | 81 (9 x 9) | 4 x 16 000 mB | 200 000 FE | GeckoLib: a floating eye, runes that glow in the dark |

## How to use it

1. Right click with a pack: its window opens, kept inside the item. Whatever is in it goes wherever the item goes.
2. **Shift + right click a block** sets the pack down as itself. Right click it there for the same window; hit it, or shift +
   right click it with an empty hand, to pick it up with everything inside.
3. The slots beside the tanks and the energy bar fill and empty them with buckets, tank items and batteries of any mod.
4. **Distill** turns 100 mB of Starbrine from any tank into 4000 FE. **Sort** merges equal stacks and puts them in order.
   **Eat** feeds you from the food inside (the meal that fits your hunger best).
5. **Starbrine** (a bucket, prismarine crystals, an amethyst shard and glowstone dust) glows, and gives Night Vision and Slow
   Falling while you swim in it. Its bucket is iron with an amethyst band, brimming over with starlight that glows, twinkles
   and drips (an animated texture), and it shimmers like an enchanted item (`Fluid(bucket_glint=True)`).
6. `/backpack:open` opens your own pocket backpack of 9 slots; shift + right click a villager opens its pockets.

## How it is made

| Part | Where | What it shows |
| --- | --- | --- |
| Models | `assets/models/item/backpack.json`, `satchel.json` | 3D item models (`Item(model=)`): boxes with a texture or a swatch palette, own `display` transforms |
| Inventory icons | `texture="adventure_pack.png"` next to `model=` / `geo=` | 16 x 16 pictures in Minecraft's style (the vial and the runes pulse); the hand and the floor show the 3D models |
| GeckoLib pack | `Item("enderweave", texture=, geo="enderweave")`, `assets/models/enderweave.geo.json`, `animations/enderweave.animation.json` | bones, an `idle` clip (the eye floats and turns), an animated texture and a glow mask |
| Set down | `Placeable(width=, height=)` | the item becomes an entity that is its stack |
| Windows | `ui/satchel.json`, `adventure_pack.json`, `enderweave.json` | Minecraft-style windows made in the window maker: grids up to 9 x 9, tank gauges bound to `fluid:N`, `fluid_in` / `fluid_out` / `energy_in` / `energy_out` slots, buttons |
| Opening | `@on.item_use(item=)` with `ui.open_screen(..., hand=)`; `@on.entity_interact(entity=)` with `entity=ctx.entity` | the same contents in the hand and on the floor |
| Buttons | `@on.screen_button(button="distill")` with `ui.screen_update`, `ui.screen_sort`, `ui.screen_slots`, `ui.screen_take` | a button changes energy, liquids and slots of the open window |
| Sounds | `assets/sounds/*.ogg`, `assets/sounds.json` | zip up and down, a latch, sloshing liquid; `player.play_sound("backpack:pack_open")` |
| Starbrine | `Fluid("starbrine", light=12, effects=[...], still=, flowing=, bucket_texture=, tags=["c:starbrine"])` | a liquid of its own |
| Rules | `code/rules.py`, `code/test_rules.py` | which meal, how much energy: tested apart from the game |
